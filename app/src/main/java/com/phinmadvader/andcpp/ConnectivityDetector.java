package com.phinmadvader.andcpp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out whether other DC users can connect to us (active mode):
 *  1. a public IPv4 on one of our own interfaces -> active ("direct");
 *  2. on Wi-Fi/Ethernet behind a router: ask it for a UPnP port mapping (as DC++ does). If the router maps
 *     our listening port and reports a public external address -> active ("UPnP");
 *  3. otherwise passive (mobile carrier NAT, UPnP off, port taken, double NAT, ...).
 * Router traffic uses plain sockets (no HttpURLConnection), so Android's cleartext-HTTP rules don't apply.
 * All methods do network I/O: never call them on the main thread.
 */
public class ConnectivityDetector {
    private static final String TAG = "andcpp";
    private static final String DESCRIPTION = "Android DC++";
    private static final int LEASE_SECONDS = 3600;

    public static final class Result {
        public final boolean active;
        /** Address other users should connect to (null when passive). */
        public final String externalIp;
        /** How it was decided: "public IP", "UPnP", or why we are passive. */
        public final String reason;

        Result(boolean active, String externalIp, String reason) {
            this.active = active;
            this.externalIp = externalIp;
            this.reason = reason;
        }

        public String describe() {
            if (!active) return "Passive (" + reason + ")";
            return externalIp != null ? "Active (" + reason + ", " + externalIp + ")" : "Active (" + reason + ")";
        }
    }

    private final Context context;
    // Current UPnP mapping (if any), so it can be refreshed and removed.
    private volatile String mapControlUrl;
    private volatile String mapServiceType;
    private volatile String mapLocalIp;
    private volatile int mapPort = -1;

    public ConnectivityDetector(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result detect(int port) {
        try {
            // 1. Directly on the internet?
            String publicLocal = firstPublicLocalIpv4();
            if (publicLocal != null) {
                return new Result(true, publicLocal, "public IP");
            }

            // 2. Behind a router: only try UPnP on Wi-Fi / Ethernet (mobile data is behind the carrier's NAT).
            if (!onWifiOrEthernet()) {
                return new Result(false, null, "mobile network, not reachable from outside");
            }
            if (port <= 0) {
                return new Result(false, null, "not listening for connections");
            }
            return tryUpnp(port);
        } catch (Exception e) {
            Log.w(TAG, "Connectivity detection failed", e);
            return new Result(false, null, "detection failed");
        }
    }

    /** Re-adds the current mapping before its lease runs out. Returns false if it no longer works. */
    public boolean refreshMapping() {
        try {
            if (pmpGateway != null && pmpPort > 0) {
                return natPmpMap(pmpGateway, pmpPort, LEASE_SECONDS);
            }
            if (mapControlUrl == null || mapPort <= 0) return true;
            addPortMapping(mapControlUrl, mapServiceType, mapPort, mapLocalIp, LEASE_SECONDS);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Port mapping refresh failed: " + e.getMessage());
            return false;
        }
    }

    /** Removes our port mapping from the router (on exit or before re-detecting on a new network). */
    public void removeMapping() {
        InetAddress pg = pmpGateway;
        int pp = pmpPort;
        pmpGateway = null;
        pmpPort = -1;
        if (pg != null && pp > 0) {
            try {
                natPmpMap(pg, pp, 0);
                Log.i(TAG, "NAT-PMP: removed port mapping " + pp);
            } catch (Exception e) {
                Log.w(TAG, "NAT-PMP: could not remove port mapping " + pp + ": " + e.getMessage());
            }
        }
        String url = mapControlUrl;
        int port = mapPort;
        mapControlUrl = null;
        mapPort = -1;
        if (url == null || port <= 0) return;
        try {
            soap(url, mapServiceType, "DeletePortMapping",
                    "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort>"
                            + "<NewProtocol>TCP</NewProtocol>");
            Log.i(TAG, "UPnP: removed port mapping " + port);
        } catch (Exception e) {
            Log.w(TAG, "UPnP: could not remove port mapping " + port + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------

    private Result tryUpnp(int port) {
        android.net.wifi.WifiManager.MulticastLock lock = null;
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("andcpp-upnp");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception e) {
            lock = null; // not essential: SSDP answers are unicast
        }
        try {
            InetAddress gateway = gatewayAddress();
            List<String> locations = new ArrayList<>();
            try {
                locations.addAll(discoverGateways(InetAddress.getByName("239.255.255.250"))); // standard multicast
            } catch (IOException e) {
                Log.w(TAG, "UPnP multicast discovery failed: " + e.getMessage());
            }
            if (locations.isEmpty() && gateway != null) {
                // Some routers (and some phones on Wi-Fi) drop multicast: ask the router directly...
                try {
                    locations.addAll(discoverGateways(gateway));
                } catch (IOException e) {
                    Log.w(TAG, "UPnP unicast discovery failed: " + e.getMessage());
                }
                // ...and try the description URLs common routers publish.
                if (locations.isEmpty()) locations.addAll(probeKnownDescriptionUrls(gateway));
            }
            Log.i(TAG, "UPnP: gateway " + (gateway != null ? gateway.getHostAddress() : "unknown")
                    + ", " + locations.size() + " UPnP device(s) found");
            if (locations.isEmpty()) {
                // No UPnP: many routers that don't advertise UPnP support NAT-PMP instead.
                Result pmp = gateway != null ? tryNatPmp(gateway, port) : null;
                return pmp != null ? pmp : new Result(false, null, "router has no UPnP / NAT-PMP (or it is switched off)");
            }
            String lastProblem = "router refused the port mapping";
            for (String location : locations) {
                try {
                    String description = http("GET", location, null, null);
                    String[] svc = findWanService(description, location);
                    if (svc == null) continue;
                    String controlUrl = svc[0];
                    String serviceType = svc[1];

                    String external = extract(soap(controlUrl, serviceType, "GetExternalIPAddress", ""),
                            "NewExternalIPAddress");
                    if (external == null || external.isEmpty() || isPrivateOrLoopback(external)) {
                        lastProblem = "router has no public address (double NAT)";
                        continue;
                    }
                    String localIp = localAddressTowards(new URL(controlUrl).getHost());
                    if (localIp == null) continue;

                    try {
                        addPortMapping(controlUrl, serviceType, port, localIp, LEASE_SECONDS);
                    } catch (IOException e) {
                        // Some routers only accept permanent mappings (lease 0).
                        addPortMapping(controlUrl, serviceType, port, localIp, 0);
                    }
                    mapControlUrl = controlUrl;
                    mapServiceType = serviceType;
                    mapLocalIp = localIp;
                    mapPort = port;
                    Log.i(TAG, "UPnP: mapped TCP " + external + ":" + port + " -> " + localIp + ":" + port);
                    return new Result(true, external, "UPnP");
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    Log.w(TAG, "UPnP via " + location + " failed: " + msg);
                    if (msg.contains("718")) lastProblem = "port " + port + " is already forwarded to another device";
                }
            }
            // UPnP devices answered but none would map: NAT-PMP may still work.
            Result pmp = gateway != null ? tryNatPmp(gateway, port) : null;
            return pmp != null ? pmp : new Result(false, null, lastProblem);
        } finally {
            if (lock != null) {
                try { lock.release(); } catch (Exception ignored) {}
            }
        }
    }

    private void addPortMapping(String controlUrl, String serviceType, int port, String localIp, int lease)
            throws IOException {
        soap(controlUrl, serviceType, "AddPortMapping",
                "<NewRemoteHost></NewRemoteHost>"
                        + "<NewExternalPort>" + port + "</NewExternalPort>"
                        + "<NewProtocol>TCP</NewProtocol>"
                        + "<NewInternalPort>" + port + "</NewInternalPort>"
                        + "<NewInternalClient>" + localIp + "</NewInternalClient>"
                        + "<NewEnabled>1</NewEnabled>"
                        + "<NewPortMappingDescription>" + DESCRIPTION + "</NewPortMappingDescription>"
                        + "<NewLeaseDuration>" + lease + "</NewLeaseDuration>");
    }

    // ---- NAT-PMP (RFC 6886): UDP 5351 on the router ----

    private volatile InetAddress pmpGateway;
    private volatile int pmpPort = -1;

    /** Asks the router for its public address and a TCP mapping of our port. Null if NAT-PMP isn't available. */
    private Result tryNatPmp(InetAddress gateway, int port) {
        try {
            byte[] addr = natPmp(gateway, new byte[]{0, 0}, 12);
            if (addr == null || addr[1] != (byte) 128 || u16(addr, 2) != 0) return null;
            String external = (addr[8] & 0xFF) + "." + (addr[9] & 0xFF) + "." + (addr[10] & 0xFF) + "." + (addr[11] & 0xFF);
            if (isPrivateOrLoopback(external)) {
                return new Result(false, null, "router has no public address (double NAT)");
            }
            if (!natPmpMap(gateway, port, LEASE_SECONDS)) {
                return new Result(false, null, "router refused the NAT-PMP port mapping");
            }
            pmpGateway = gateway;
            pmpPort = port;
            Log.i(TAG, "NAT-PMP: mapped TCP " + external + ":" + port);
            return new Result(true, external, "NAT-PMP");
        } catch (Exception e) {
            Log.d(TAG, "NAT-PMP not available: " + e.getMessage());
            return null;
        }
    }

    /** TCP mapping request (lifetime 0 = delete). True if the router mapped the same external port. */
    private static boolean natPmpMap(InetAddress gateway, int port, int lifetime) throws IOException {
        byte[] req = new byte[12];
        req[1] = 2; // map TCP
        req[4] = (byte) (port >> 8); req[5] = (byte) port;   // internal port
        req[6] = (byte) (port >> 8); req[7] = (byte) port;   // suggested external port
        req[8] = (byte) (lifetime >> 24); req[9] = (byte) (lifetime >> 16);
        req[10] = (byte) (lifetime >> 8); req[11] = (byte) lifetime;
        byte[] resp = natPmp(gateway, req, 16);
        if (resp == null || resp[1] != (byte) 130 || u16(resp, 2) != 0) return false;
        return lifetime == 0 || u16(resp, 10) == port; // other users must reach the port we advertise
    }

    private static byte[] natPmp(InetAddress gateway, byte[] request, int expected) throws IOException {
        DatagramSocket s = new DatagramSocket();
        try {
            s.setSoTimeout(1000);
            for (int attempt = 0; attempt < 2; attempt++) {
                s.send(new DatagramPacket(request, request.length, gateway, 5351));
                byte[] buf = new byte[32];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                    if (p.getLength() >= expected) return buf;
                } catch (SocketTimeoutException ignored) {
                }
            }
            return null;
        } finally {
            s.close();
        }
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    /** The router's address: the IPv4 default route of the active network (or the DHCP gateway), or null. */
    private InetAddress gatewayAddress() {
        // Android 5+: the routing table. (WifiManager.getDhcpInfo() returns 0.0.0.0 on recent Android versions.)
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.LinkProperties lp = cm != null ? cm.getLinkProperties(cm.getActiveNetwork()) : null;
                if (lp != null) {
                    for (android.net.RouteInfo route : lp.getRoutes()) {
                        InetAddress gw = route.getGateway();
                        if (route.isDefaultRoute() && gw instanceof Inet4Address && !gw.isAnyLocalAddress()) {
                            return gw;
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "No default route: " + e.getMessage());
            }
        }
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getDhcpInfo() != null && wm.getDhcpInfo().gateway != 0) {
                int g = wm.getDhcpInfo().gateway; // little-endian
                return InetAddress.getByAddress(new byte[]{(byte) g, (byte) (g >> 8), (byte) (g >> 16), (byte) (g >> 24)});
            }
        } catch (Exception e) {
            Log.d(TAG, "No DHCP gateway: " + e.getMessage());
        }
        return null;
    }

    /** UPnP description URLs that common routers publish (miniupnpd, Fritz!Box, Broadcom, ...). */
    private static List<String> probeKnownDescriptionUrls(InetAddress gateway) {
        String g = gateway.getHostAddress();
        String[] candidates = {
                "http://" + g + ":5000/rootDesc.xml",       // miniupnpd (OpenWrt, many ISP routers)
                "http://" + g + ":49000/igddesc.xml",       // AVM Fritz!Box
                "http://" + g + ":1900/igd.xml",
                "http://" + g + ":1900/rootDesc.xml",
                "http://" + g + ":5431/dyndev/uuid:0000e068-20a0-00e0-20a0-48a8000808e0", // Broadcom
                "http://" + g + ":2555/upnp/InternetGatewayDevice.xml",
                "http://" + g + ":80/rootDesc.xml",
                "http://" + g + ":49152/rootDesc.xml",
        };
        List<String> found = new ArrayList<>();
        for (String url : candidates) {
            try {
                String desc = http("GET", url, null, null);
                if (desc.contains("InternetGatewayDevice") || desc.contains("WANIPConnection")
                        || desc.contains("WANPPPConnection")) {
                    found.add(url);
                    break;
                }
            } catch (Exception ignored) {
                // not at this address
            }
        }
        return found;
    }

    /** SSDP M-SEARCH for Internet gateways (multicast, or unicast to the router); returns description URLs. */
    private List<String> discoverGateways(InetAddress group) throws IOException {
        Set<String> locations = new LinkedHashSet<>();
        String[] targets = {
                "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
                "urn:schemas-upnp-org:service:WANIPConnection:1",
                "urn:schemas-upnp-org:service:WANPPPConnection:1",
        };
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setSoTimeout(500);
            for (String st : targets) {
                byte[] req = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
                        + "MX: 2\r\nST: " + st + "\r\n\r\n").getBytes("US-ASCII");
                socket.send(new DatagramPacket(req, req.length, group, 1900));
            }
            long deadline = System.currentTimeMillis() + 3000;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (SocketTimeoutException e) {
                    if (!locations.isEmpty()) break;
                    continue;
                }
                String resp = new String(p.getData(), 0, p.getLength(), "US-ASCII");
                for (String line : resp.split("\r\n")) {
                    if (line.toLowerCase(Locale.US).startsWith("location:")) {
                        locations.add(line.substring(9).trim());
                    }
                }
            }
        } finally {
            socket.close();
        }
        return new ArrayList<>(locations);
    }

    /** Finds the WANIPConnection / WANPPPConnection service: {absolute controlURL, serviceType}. */
    private static String[] findWanService(String description, String location) throws IOException {
        String base = extract(description, "URLBase");
        if (base == null || base.isEmpty()) base = location;
        Matcher m = Pattern.compile("<service>(.*?)</service>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
                .matcher(description);
        while (m.find()) {
            String svc = m.group(1);
            String type = extract(svc, "serviceType");
            String control = extract(svc, "controlURL");
            if (type == null || control == null) continue;
            if (type.contains("WANIPConnection") || type.contains("WANPPPConnection")) {
                return new String[]{new URL(new URL(base), control.trim()).toString(), type.trim()};
            }
        }
        return null;
    }

    private static String soap(String controlUrl, String serviceType, String action, String args) throws IOException {
        String body = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + serviceType + "\">" + args + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        return http("POST", controlUrl, "\"" + serviceType + "#" + action + "\"", body);
    }

    /** Minimal HTTP/1.0 over a plain socket. Returns the body; throws on non-2xx (message has the status/body). */
    private static String http(String method, String url, String soapAction, String body) throws IOException {
        URL u = new URL(url); // parsing only
        int port = u.getPort() > 0 ? u.getPort() : 80;
        String path = (u.getPath() == null || u.getPath().isEmpty()) ? "/" : u.getPath();
        if (u.getQuery() != null) path += "?" + u.getQuery();
        byte[] content = body != null ? body.getBytes("UTF-8") : new byte[0];

        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(u.getHost(), port), 3000);
            s.setSoTimeout(5000);
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.0\r\n");
            req.append("Host: ").append(u.getHost()).append(':').append(port).append("\r\n");
            req.append("Connection: close\r\n");
            if (soapAction != null) {
                req.append("Content-Type: text/xml; charset=\"utf-8\"\r\n");
                req.append("SOAPAction: ").append(soapAction).append("\r\n");
            }
            if (body != null) req.append("Content-Length: ").append(content.length).append("\r\n");
            req.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes("US-ASCII"));
            out.write(content);
            out.flush();

            InputStream in = s.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 512 * 1024) break;
            }
            String resp = bos.toString("UTF-8");
            int split = resp.indexOf("\r\n\r\n");
            String head = split >= 0 ? resp.substring(0, split) : resp;
            String respBody = split >= 0 ? resp.substring(split + 4) : "";
            if (head.toLowerCase(Locale.US).contains("transfer-encoding: chunked")) {
                respBody = dechunk(respBody);
            }
            int status = 0;
            String[] first = head.split("\r\n", 2)[0].split(" ");
            if (first.length >= 2) {
                try { status = Integer.parseInt(first[1]); } catch (NumberFormatException ignored) {}
            }
            if (status < 200 || status >= 300) {
                String code = extract(respBody, "errorCode");
                throw new IOException("HTTP " + status + (code != null ? " UPnP error " + code : ""));
            }
            return respBody;
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static String dechunk(String chunked) {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (pos < chunked.length()) {
            int eol = chunked.indexOf("\r\n", pos);
            if (eol < 0) break;
            int size;
            try {
                size = Integer.parseInt(chunked.substring(pos, eol).split(";")[0].trim(), 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size == 0) break;
            int start = eol + 2;
            int end = Math.min(start + size, chunked.length());
            out.append(chunked, start, end);
            pos = end + 2;
        }
        return out.toString();
    }

    /** Text of the first <tag>...</tag> (namespace prefixes allowed), or null. */
    private static String extract(String xml, String tag) {
        if (xml == null) return null;
        Matcher m = Pattern.compile("<(?:\\w+:)?" + tag + "(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?" + tag + ">",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    /** Our local address on the route to the given host (the router), or null. */
    private static String localAddressTowards(String host) {
        DatagramSocket d = null;
        try {
            d = new DatagramSocket();
            d.connect(InetAddress.getByName(host), 1900); // no packet is sent
            InetAddress a = d.getLocalAddress();
            return (a instanceof Inet4Address && !a.isAnyLocalAddress()) ? a.getHostAddress() : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (d != null) d.close();
        }
    }

    private boolean onWifiOrEthernet() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        if (ni == null || !ni.isConnected()) return false;
        int t = ni.getType();
        if (t == ConnectivityManager.TYPE_WIFI || t == ConnectivityManager.TYPE_ETHERNET) return true;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
        }
        return false;
    }

    private static String firstPublicLocalIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !isPrivateOrLoopback(a.getHostAddress())) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not list network interfaces", e);
        }
        return null;
    }

    static boolean isPrivateOrLoopback(String ip) {
        if (ip == null || ip.isEmpty()) return true;
        String[] p = ip.split("\\.");
        if (p.length != 4) return true;
        try {
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            if (a == 0 || a == 10 || a == 127) return true;
            if (a == 169 && b == 254) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 192 && b == 168) return true;
            if (a == 100 && b >= 64 && b <= 127) return true; // carrier-grade NAT
            if (a >= 224) return true; // multicast / reserved
        } catch (NumberFormatException e) {
            return true;
        }
        return false;
    }
}
