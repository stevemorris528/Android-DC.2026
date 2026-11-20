package com.phinmadvader.andcpp.pia;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.phinmadvader.andcpp.Constants;

/**
 * Automatic PIA port forwarding while the phone is connected to PIA (e.g. through the PIA app):
 *  1. gets a PIA auth token from the saved PIA username/password (renewed before it expires, ~24 h);
 *  2. finds PIA's port-forwarding API inside the tunnel (port 19999 on the VPN gateway);
 *  3. getSignature -> assigned port, then bindPort now and every 15 minutes to keep it.
 * The service is told the port (onPortBound) and listens on it; on failure onPortError with getLastError().
 * Works independently of the PIA app's own "Port Forwarding" switch.
 */
public class PIAPortForwardingManager {
    private static final String TAG = "PIAPortForwarding";
    private static final int PIA_PF_PORT = 19999;
    private static final long TOKEN_MAX_AGE_MILLIS = TimeUnit.HOURS.toMillis(20); // tokens last 24 h

    public static final boolean FEATURE_AVAILABLE = true;

    public static final String PREF_ENABLED = "pia_port_forwarding_enabled";
    public static final String PREF_USERNAME = "pia_username";
    public static final String PREF_PASSWORD = "pia_password";
    private static final String PREF_TOKEN = "pia_token";
    private static final String PREF_TOKEN_TIME = "pia_token_time";
    // Last port grant (signature + payload, valid ~2 months) and the gateway it came from.
    private static final String PREF_SIGNATURE = "pia_pf_signature";
    private static final String PREF_PAYLOAD = "pia_pf_payload";
    private static final String PREF_GATEWAY = "pia_pf_gateway";

    /** Trust-all TLS, ONLY for the in-tunnel PIA gateway (its certificate is from PIA's private CA). */
    private javax.net.ssl.SSLSocketFactory piaSocketFactory;

    private static PIAPortForwardingManager instance;
    private final Context context;
    private final SharedPreferences prefs;

    private volatile String currentPort = null;
    private volatile String signature = null;
    private volatile String payload = null;
    private volatile String gateway = null;
    private volatile String lastError = null;

    private ScheduledExecutorService executorService;
    private volatile boolean isEnabled;

    public interface PortForwardingListener {
        void onPortBound(String port);
        void onPortError();
    }

    private volatile PortForwardingListener listener;

    private PIAPortForwardingManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        this.isEnabled = FEATURE_AVAILABLE && prefs.getBoolean(PREF_ENABLED, false);
        // Not started here: the service starts it while a VPN is on (ensureRunning) and stops it otherwise.
    }

    public static synchronized PIAPortForwardingManager getInstance(Context context) {
        if (instance == null) {
            instance = new PIAPortForwardingManager(context);
        }
        return instance;
    }

    public void setListener(PortForwardingListener listener) {
        this.listener = listener;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = FEATURE_AVAILABLE && enabled;
        prefs.edit().putBoolean(PREF_ENABLED, this.isEnabled).apply();
        if (!this.isEnabled) stopPortForwarding();
    }

    public boolean hasCredentials() {
        return !prefs.getString(PREF_USERNAME, "").isEmpty() && !prefs.getString(PREF_PASSWORD, "").isEmpty();
    }

    public void setCredentials(String username, String password) {
        prefs.edit().putString(PREF_USERNAME, username == null ? "" : username.trim())
                .putString(PREF_PASSWORD, password == null ? "" : password)
                .remove(PREF_TOKEN).remove(PREF_TOKEN_TIME) // new login: get a new token
                .remove(PREF_SIGNATURE).remove(PREF_PAYLOAD).remove(PREF_GATEWAY)
                .apply();
    }

    public String getUsername() {
        return prefs.getString(PREF_USERNAME, "");
    }

    /** The forwarded port, once PIA assigned and bound it; null otherwise. */
    public String getCurrentPort() {
        return currentPort;
    }

    /** Why port forwarding isn't working (for the Mode line), or null. */
    public String getLastError() {
        return lastError;
    }

    /** Starts port forwarding if enabled and not already running (call while the VPN is on). */
    public synchronized void ensureRunning() {
        if (!isEnabled) return;
        if (executorService != null && !executorService.isShutdown()) return;
        lastError = null;
        executorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PIA-PortForwarding");
            t.setDaemon(true);
            return t;
        });
        executorService.execute(this::acquireAndBind);
        // Keep the port: PIA drops it if bindPort isn't repeated within ~15 minutes.
        executorService.scheduleWithFixedDelay(this::keepAlive, 15, 15, TimeUnit.MINUTES);
    }

    /** Restart after a network change (new tunnel, maybe a new server). */
    public void triggerRebind() {
        if (isEnabled) {
            stopPortForwarding();
            ensureRunning();
        }
    }

    public synchronized void stopPortForwarding() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        currentPort = null;
        signature = null;
        payload = null;
        gateway = null;
    }

    // ---------------------------------------------------------------------------------------------

    private void acquireAndBind() {
        try {
            // A signature is valid for about two months: re-binding the saved one keeps the SAME port across app
            // restarts and short VPN drops. Only ask PIA for a new one if that no longer works.
            if (signature == null && loadSavedSignature()) {
                try {
                    if (bindPort()) {
                        lastError = null;
                        Log.i(TAG, "Re-bound saved PIA port " + currentPort);
                        notifyBound();
                        return;
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Saved PIA signature no longer works: " + e.getMessage());
                }
                clearSavedSignature();
                signature = null;
                payload = null;
                currentPort = null;
                gateway = null;
            }
            if (signature == null && !getSignature()) {
                notifyError();
                return;
            }
            if (bindPort()) {
                lastError = null;
                notifyBound();
            } else {
                notifyError();
            }
        } catch (Exception e) {
            lastError = "error: " + e.getMessage();
            Log.e(TAG, "Port forwarding failed", e);
            notifyError();
        }
    }

    /** Loads the saved port grant (if any). The gateway is re-checked, since the tunnel may have changed. */
    private boolean loadSavedSignature() {
        String sig = prefs.getString(PREF_SIGNATURE, "");
        String pay = prefs.getString(PREF_PAYLOAD, "");
        if (sig.isEmpty() || pay.isEmpty()) return false;
        try {
            JSONObject p = new JSONObject(new String(Base64.decode(pay, Base64.DEFAULT), "UTF-8"));
            String saved = prefs.getString(PREF_GATEWAY, "");
            if (!saved.isEmpty() && reachable(saved)) {
                gateway = saved;
            } else if (findGateway() == null) {
                return false;
            }
            signature = sig;
            payload = pay;
            currentPort = String.valueOf(p.getInt("port"));
            return true;
        } catch (Exception e) {
            clearSavedSignature();
            return false;
        }
    }

    private void clearSavedSignature() {
        prefs.edit().remove(PREF_SIGNATURE).remove(PREF_PAYLOAD).remove(PREF_GATEWAY).apply();
    }

    private static boolean reachable(String host) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, PIA_PF_PORT), 1500);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void keepAlive() {
        try {
            if (signature == null || !bindPort()) {
                // Signature expired or the server changed: start over (may get a new port).
                clearSavedSignature();
                signature = null;
                payload = null;
                gateway = null;
                acquireAndBind();
            }
        } catch (Exception e) {
            Log.w(TAG, "Keep-alive failed", e);
        }
    }

    private void notifyBound() {
        PortForwardingListener l = listener;
        if (l != null) {
            try { l.onPortBound(currentPort); } catch (Exception e) { Log.e(TAG, "onPortBound failed", e); }
        }
    }

    private void notifyError() {
        PortForwardingListener l = listener;
        if (l != null) {
            try { l.onPortError(); } catch (Exception e) { Log.e(TAG, "onPortError failed", e); }
        }
    }

    /** A valid PIA token: the cached one, or a new one from the saved username/password. */
    private String getToken(boolean forceNew) {
        String token = prefs.getString(PREF_TOKEN, "");
        long age = System.currentTimeMillis() - prefs.getLong(PREF_TOKEN_TIME, 0);
        if (!forceNew && !token.isEmpty() && age < TOKEN_MAX_AGE_MILLIS) return token;

        String user = prefs.getString(PREF_USERNAME, "");
        String pass = prefs.getString(PREF_PASSWORD, "");
        if (user.isEmpty() || pass.isEmpty()) {
            lastError = "set your PIA login in Settings";
            return null;
        }
        token = fetchToken(user, pass);
        if (token != null) {
            prefs.edit().putString(PREF_TOKEN, token).putLong(PREF_TOKEN_TIME, System.currentTimeMillis()).apply();
        }
        return token;
    }

    /** PIA token API (normal, fully verified HTTPS to privateinternetaccess.com). */
    private String fetchToken(String user, String pass) {
        try {
            URL url = new URL("https://www.privateinternetaccess.com/api/client/v2/token");
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String form = "username=" + URLEncoder.encode(user, "UTF-8") + "&password=" + URLEncoder.encode(pass, "UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                lastError = "PIA login rejected: check username/password in Settings";
                return null;
            }
            if (code != 200) {
                lastError = "PIA token request failed (HTTP " + code + ")";
                return null;
            }
            JSONObject json = new JSONObject(readAll(conn.getInputStream()));
            String token = json.optString("token", "");
            if (token.isEmpty()) {
                lastError = "PIA returned no token";
                return null;
            }
            Log.i(TAG, "Got a new PIA token");
            return token;
        } catch (Exception e) {
            lastError = "could not reach PIA to log in";
            Log.w(TAG, "Token request failed", e);
            return null;
        }
    }

    private boolean getSignature() throws Exception {
        String gw = findGateway();
        if (gw == null) {
            lastError = "PIA port-forwarding gateway not found (is the VPN connected to a server that allows port forwarding?)";
            return false;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = getToken(attempt > 0);
            if (token == null) return false;
            String resp = piaGet(gw, "/getSignature?token=" + URLEncoder.encode(token, "UTF-8"));
            JSONObject json = resp != null ? new JSONObject(resp) : null;
            if (json != null && "OK".equals(json.optString("status"))) {
                signature = json.getString("signature");
                payload = json.getString("payload");
                JSONObject p = new JSONObject(new String(Base64.decode(payload, Base64.DEFAULT), "UTF-8"));
                currentPort = String.valueOf(p.getInt("port"));
                Log.i(TAG, "PIA assigned port " + currentPort + " (gateway " + gw + ")");
                prefs.edit().putString(PREF_SIGNATURE, signature).putString(PREF_PAYLOAD, payload)
                        .putString(PREF_GATEWAY, gw).apply();
                return true;
            }
            Log.w(TAG, "getSignature failed: " + resp);
            // Retry once with a fresh token (the cached one may have expired).
        }
        lastError = "PIA refused port forwarding (this server may not support it)";
        return false;
    }

    private boolean bindPort() throws Exception {
        String gw = gateway != null ? gateway : findGateway();
        if (gw == null || signature == null || payload == null) return false;
        String resp = piaGet(gw, "/bindPort?payload=" + URLEncoder.encode(payload, "UTF-8")
                + "&signature=" + URLEncoder.encode(signature, "UTF-8"));
        JSONObject json = resp != null ? new JSONObject(resp) : null;
        if (json != null && "OK".equals(json.optString("status"))) {
            Log.i(TAG, "Port " + currentPort + " bound");
            return true;
        }
        lastError = "PIA bindPort failed";
        Log.w(TAG, "bindPort failed: " + resp);
        return false;
    }

    /**
     * PIA's port-forwarding API listens on 19999 at the tunnel gateway. Its address isn't published to other
     * apps, so try the likely ones (VPN routes, x.y.z.1 of our tunnel address, PIA's classic 10.0.0.1) and
     * use the first that accepts a connection.
     */
    private String findGateway() {
        if (gateway != null) return gateway;
        Set<String> candidates = new LinkedHashSet<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && android.os.Build.VERSION.SDK_INT >= 23) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp == null) continue;
                    for (RouteInfo r : lp.getRoutes()) {
                        InetAddress g = r.getGateway();
                        if (g instanceof Inet4Address && !g.isAnyLocalAddress()) candidates.add(g.getHostAddress());
                    }
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress a = la.getAddress();
                        if (!(a instanceof Inet4Address)) continue;
                        byte[] b = a.getAddress();
                        candidates.add((b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + ".1");
                        candidates.add((b[0] & 0xFF) + "." + (b[1] & 0xFF) + ".0.1");
                        candidates.add((b[0] & 0xFF) + "." + (b[1] & 0xFF) + ".128.1");
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not read VPN network: " + e.getMessage());
        }
        candidates.add("10.0.0.1");
        for (String c : candidates) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(c, PIA_PF_PORT), 1500);
                gateway = c;
                Log.i(TAG, "PIA port-forwarding API found at " + c);
                return c;
            } catch (Exception ignored) {
                // not here
            } finally {
                try { s.close(); } catch (Exception ignored) {}
            }
        }
        Log.w(TAG, "PIA port-forwarding API not found; tried " + candidates);
        return null;
    }

    /** HTTPS GET to the in-tunnel gateway; returns the body, or null on failure. */
    private String piaGet(String gw, String pathAndQuery) {
        try {
            HttpsURLConnection conn = openPiaConnection(new URL("https://" + gw + ":" + PIA_PF_PORT + pathAndQuery));
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = in != null ? readAll(in) : "";
            return code == 200 ? body : null;
        } catch (Exception e) {
            Log.w(TAG, "PIA gateway request failed: " + e.getMessage());
            return null;
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /**
     * Connection to the in-tunnel PIA gateway, accepting its certificate (issued by PIA's private CA for the
     * server name, which we don't know). Applied to this connection only; app-wide HTTPS is untouched.
     */
    private HttpsURLConnection openPiaConnection(URL url) throws Exception {
        if (piaSocketFactory == null) {
            TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[] {}; }
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
            } };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            piaSocketFactory = sc.getSocketFactory();
        }
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(piaSocketFactory);
        conn.setHostnameVerifier((hostname, session) -> true);
        return conn;
    }
}
