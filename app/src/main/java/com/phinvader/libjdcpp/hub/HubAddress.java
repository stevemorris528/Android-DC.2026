package com.phinvader.libjdcpp.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A hub address with its protocol: {@code dchub://}, {@code nmdc://}, {@code nmdcs://}, {@code adc://},
 * {@code adcs://}, or a bare {@code host[:port]} (bare = NMDC, as the app always stored hubs that way).
 * <p>
 * Saved-hub ids and SharedPreferences keys:
 * <ul>
 * <li><b>NMDC</b> keeps the old format exactly ({@code host} or {@code host:port}, no scheme), so every hub,
 * nick and password saved by earlier builds still matches without a migration. {@code dchub://} and
 * {@code nmdc://} addresses are normalised to that form.</li>
 * <li><b>Every other protocol</b> keeps its scheme ({@code adc://host:port}), so it can never be mistaken
 * for an NMDC hub on the same host.</li>
 * </ul>
 * Pure Java (no Android classes), so it can be unit-tested on the JVM.
 */
public final class HubAddress {

    public enum Protocol {
        NMDC("nmdc", "NMDC", false, true),
        NMDCS("nmdcs", "NMDCS", true, true),
        ADC("adc", "ADC", false, false),
        ADCS("adcs", "ADCS", true, false);

        public final String scheme;
        public final String label;
        public final boolean secure;
        /** NMDC hubs default to port 411; ADC has no standard port, so one must be given. */
        public final boolean hasDefaultPort;

        Protocol(String scheme, String label, boolean secure, boolean hasDefaultPort) {
            this.scheme = scheme;
            this.label = label;
            this.secure = secure;
            this.hasDefaultPort = hasDefaultPort;
        }

        public boolean isAdc() {
            return this == ADC || this == ADCS;
        }
    }

    public static final int DEFAULT_NMDC_PORT = 411;

    public final Protocol protocol;
    public final String host;
    public final int port;
    /** False when the address had no port and the default (411) was filled in. */
    public final boolean portExplicit;
    /** Hub keyprint from {@code ?kp=}, e.g. {@code SHA256/ABC...}, or null. */
    public final String kp;

    private HubAddress(Protocol protocol, String host, int port, boolean portExplicit, String kp) {
        this.protocol = protocol;
        this.host = host;
        this.port = port;
        this.portExplicit = portExplicit;
        this.kp = kp;
    }

    /** Builds an address from parts (e.g. a running session's host and port). */
    public static HubAddress of(Protocol protocol, String host, int port) {
        if (protocol == null) protocol = Protocol.NMDC;
        boolean explicit = !(protocol.hasDefaultPort && port == DEFAULT_NMDC_PORT);
        return new HubAddress(protocol, host, port, explicit, null);
    }

    /** Like {@link #parse} but returns null instead of throwing. */
    public static HubAddress tryParse(String raw) {
        try {
            return parse(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parses a hub address. Throws IllegalArgumentException with a user-readable message when the address
     * is unusable (unknown scheme, bad port, ADC address without a port, ...).
     */
    public static HubAddress parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("No hub address");
        String s = raw.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("No hub address");

        Protocol protocol = Protocol.NMDC;
        int sep = s.indexOf("://");
        if (sep >= 0) {
            String scheme = s.substring(0, sep).toLowerCase(Locale.ROOT);
            s = s.substring(sep + 3);
            switch (scheme) {
                case "dchub":
                case "nmdc":
                    protocol = Protocol.NMDC;
                    break;
                case "nmdcs":
                    protocol = Protocol.NMDCS;
                    break;
                case "adc":
                    protocol = Protocol.ADC;
                    break;
                case "adcs":
                    protocol = Protocol.ADCS;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown hub protocol \"" + scheme + "://\"");
            }
        }

        // Split off the path / query: "host:port/?kp=SHA256/XYZ" or "host:port?kp=...".
        String query = null;
        int q = s.indexOf('?');
        if (q >= 0) {
            query = s.substring(q + 1);
            s = s.substring(0, q);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash); // the path itself carries nothing we use

        String host;
        String portStr = null;
        if (s.startsWith("[")) { // [IPv6]:port
            int close = s.indexOf(']');
            if (close < 0) throw new IllegalArgumentException("Invalid hub address");
            host = s.substring(0, close + 1);
            String rest = s.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) throw new IllegalArgumentException("Invalid hub address");
                portStr = rest.substring(1);
            }
        } else {
            int colon = s.indexOf(':');
            if (colon >= 0) {
                if (s.indexOf(':', colon + 1) >= 0) throw new IllegalArgumentException("Invalid hub address");
                host = s.substring(0, colon);
                portStr = s.substring(colon + 1);
            } else {
                host = s;
            }
        }
        host = host.trim();
        if (host.isEmpty() || host.equals("[]") || host.contains(" ")) {
            throw new IllegalArgumentException("Invalid hub address");
        }

        int port;
        boolean explicit;
        if (portStr == null || portStr.trim().isEmpty()) {
            if (!protocol.hasDefaultPort) {
                throw new IllegalArgumentException(protocol.label + " hub addresses need a port, e.g. "
                        + protocol.scheme + "://" + host + ":2780");
            }
            port = DEFAULT_NMDC_PORT;
            explicit = false;
        } else {
            try {
                port = Integer.parseInt(portStr.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid port number");
            }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port number");
            explicit = true;
        }

        String kp = null;
        if (query != null) {
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase("kp")) {
                    String v = part.substring(eq + 1).trim();
                    if (!v.isEmpty()) kp = v;
                }
            }
        }
        return new HubAddress(protocol, host, port, explicit, kp);
    }

    private String hostPort() {
        return host + ":" + port;
    }

    /**
     * The string stored in the saved-hubs list. NMDC: {@code host} or {@code host:port} (old format).
     * Others: {@code scheme://host:port}, plus {@code /?kp=...} when a keyprint was given.
     */
    public String id() {
        if (protocol == Protocol.NMDC) return portExplicit ? hostPort() : host;
        return prefKey() + (kp != null ? "/?kp=" + kp : "");
    }

    /**
     * Key suffix for per-hub preferences (favorite_nicks_, favorite_passwords_, hub_name_). Same as
     * {@link #id()} but never includes the keyprint, so adding or changing a kp keeps the saved nick.
     */
    public String prefKey() {
        if (protocol == Protocol.NMDC) return id();
        return protocol.scheme + "://" + hostPort();
    }

    /**
     * Keys to try, best first, when reading a per-hub preference. NMDC also tries {@code host:port} and the
     * bare {@code host}, exactly like the old code did. Other protocols only use their own key: an NMDC
     * hub's nick or password on the same host must not leak to an ADC hub.
     */
    public List<String> prefKeys() {
        List<String> keys = new ArrayList<>();
        keys.add(prefKey());
        if (protocol == Protocol.NMDC) {
            if (!keys.contains(hostPort())) keys.add(hostPort());
            if (!keys.contains(host)) keys.add(host);
        }
        return keys;
    }

    /**
     * Keys to write when saving a per-hub preference. NMDC writes {@code host:port} and {@code host}
     * (old behaviour); other protocols only their own key.
     */
    public List<String> prefKeysToWrite() {
        List<String> keys = new ArrayList<>();
        keys.add(prefKey());
        if (protocol == Protocol.NMDC && portExplicit && !keys.contains(host)) keys.add(host);
        return keys;
    }

    /** Full URL, always with the scheme, e.g. {@code dchub://host:411} or {@code adcs://host:2781/?kp=...}. */
    public String toUrl() {
        String scheme = protocol == Protocol.NMDC ? "dchub" : protocol.scheme;
        return scheme + "://" + hostPort() + (kp != null ? "/?kp=" + kp : "");
    }

    /** True if this build can connect to this kind of hub. Grows as the ADC phases land. */
    public boolean isSupported() {
        return protocol == Protocol.NMDC || protocol == Protocol.NMDCS;
    }

    /**
     * Key of the running hub session in DCPPService: {@code host:port} for NMDC (as always), the
     * {@link #prefKey()} ({@code nmdcs://host:port}) for other protocols, so a secure and a plain session to
     * the same host:port never share a key.
     */
    public String sessionKey() {
        return protocol == Protocol.NMDC ? hostPort() : prefKey();
    }

    /** Short user-visible reason why {@link #isSupported()} is false, or null when supported. */
    public String unsupportedReason() {
        if (isSupported()) return null;
        return protocol.label + " (not yet supported)";
    }

    /** True if both addresses point at the same hub (same protocol, host and port; kp ignored). */
    public boolean sameHub(HubAddress o) {
        return o != null && o.protocol == protocol && o.port == port && o.host.equalsIgnoreCase(host);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof HubAddress)) return false;
        HubAddress o = (HubAddress) obj;
        return sameHub(o) && (kp == null ? o.kp == null : kp.equals(o.kp));
    }

    @Override
    public int hashCode() {
        return (protocol.hashCode() * 31 + host.toLowerCase(Locale.ROOT).hashCode()) * 31 + port;
    }

    @Override
    public String toString() {
        return toUrl();
    }
}
