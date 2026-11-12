package com.phinmadvader.andcpp.pia;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.phinmadvader.andcpp.Constants;

public class PIAPortForwardingManager {
    private static final String TAG = "PIAPortForwarding";
    private static final int PIA_PORT = 19999;

    private static PIAPortForwardingManager instance;
    private final Context context;
    private final SharedPreferences prefs;

    private String currentPort = null;
    private String signature = null;
    private String payload = null;

    private ScheduledExecutorService executorService;
    private boolean isEnabled = false;

    public interface PortForwardingListener {
        void onPortBound(String port);
        void onPortError();
    }
    
    private PortForwardingListener listener;

    private PIAPortForwardingManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        this.isEnabled = prefs.getBoolean("pia_port_forwarding_enabled", false);
        trustAllHosts(); // Necessary for self-signed 10.0.0.1 cert

        if (isEnabled) {
            startPortForwarding();
        }
    }

    public static synchronized PIAPortForwardingManager getInstance(Context context) {
        if (instance == null) {
            instance = new PIAPortForwardingManager(context);
        }
        return instance;
    }

    public void setListener(PortForwardingListener listener) {
        this.listener = listener;
        if (isEnabled && currentPort != null) {
            listener.onPortBound(currentPort);
        }
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        prefs.edit().putBoolean("pia_port_forwarding_enabled", enabled).apply();
        if (enabled) {
            startPortForwarding();
        } else {
            stopPortForwarding();
        }
    }

    public String getCurrentPort() {
        return currentPort;
    }

    public String getPIAGateway() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network network = cm.getActiveNetwork();
                if (network != null) {
                    LinkProperties linkProperties = cm.getLinkProperties(network);
                    if (linkProperties != null) {
                        for (RouteInfo route : linkProperties.getRoutes()) {
                            if ((route.isDefaultRoute() || "0.0.0.0".equals(route.getDestination().getAddress().getHostAddress())) 
                                    && route.getGateway() != null) {
                                return route.getGateway().getHostAddress();
                            }
                        }
                    }
                }
            }
        }
        return "10.0.0.1"; // fallback
    }

    public void triggerRebind() {
        if (isEnabled) {
            Log.d(TAG, "Triggering rebind...");
            stopPortForwarding();
            startPortForwarding();
        }
    }

    private void startPortForwarding() {
        if (executorService != null && !executorService.isShutdown()) {
            return;
        }
        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.submit(() -> {
            try {
                boolean success = getSignature();
                if (success) {
                    bindPort();
                    // Schedule keep-alive every 15 minutes
                    executorService.scheduleAtFixedRate(this::bindPort, 15, 15, TimeUnit.MINUTES);
                } else {
                    Log.e(TAG, "Failed to get signature. Stopping PIA port forwarding.");
                    if (listener != null) {
                        try {
                            listener.onPortError();
                        } catch (Exception ex) {
                            Log.e(TAG, "Listener error onPortError", ex);
                        }
                    }
                    stopPortForwarding();
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in port forwarding startup", e);
            }
        });
    }

    public void stopPortForwarding() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        currentPort = null;
        signature = null;
        payload = null;
    }

    private String getToken() {
        return prefs.getString("pia_token", "");
    }

    private boolean getSignature() {
        String token = getToken();
        if (token == null || token.isEmpty()) {
            Log.e(TAG, "No PIA token found. Please set it in Settings.");
            return false;
        }
        try {
            String gateway = getPIAGateway();
            URL url = new URL("https://" + gateway + ":" + PIA_PORT + "/getSignature?token=" + token);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setHostnameVerifier((hostname, session) -> true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                Log.e(TAG, "getSignature failed with code: " + responseCode);
                return false;
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }
            in.close();

            JSONObject json = new JSONObject(response.toString());
            if (!json.getString("status").equals("OK")) {
                Log.e(TAG, "getSignature returned error: " + response.toString());
                return false;
            }

            signature = json.getString("signature");
            payload = json.getString("payload");

            String decodedPayload = new String(Base64.decode(payload, Base64.DEFAULT));
            JSONObject payloadJson = new JSONObject(decodedPayload);
            currentPort = String.valueOf(payloadJson.getInt("port"));

            Log.i(TAG, "Got signature. Assigned port: " + currentPort);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Exception in getSignature", e);
            return false;
        }
    }

    private void bindPort() {
        if (signature == null || payload == null) return;
        try {
            String gateway = getPIAGateway();
            URL url = new URL("https://" + gateway + ":" + PIA_PORT + "/bindPort?signature=" + signature + "&payload=" + payload);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setHostnameVerifier((hostname, session) -> true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                Log.e(TAG, "bindPort failed with code: " + responseCode);
                return;
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }
            in.close();

            JSONObject json = new JSONObject(response.toString());
            if (!json.getString("status").equals("OK")) {
                Log.e(TAG, "bindPort returned error: " + response.toString());
                if (listener != null) {
                    try {
                        listener.onPortError();
                    } catch (Exception ex) {
                        Log.e(TAG, "Listener error onPortError", ex);
                    }
                }
            } else {
                Log.i(TAG, "Successfully bound port " + currentPort);
                if (listener != null) {
                    try {
                        listener.onPortBound(currentPort);
                    } catch (Exception ex) {
                        Log.e(TAG, "Listener error onPortBound", ex);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception in bindPort", e);
            if (listener != null) {
                try {
                    listener.onPortError();
                } catch (Exception ex) {
                    Log.e(TAG, "Listener error onPortError in catch block", ex);
                }
            }
        }
    }

    private static void trustAllHosts() {
        TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[] {}; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
        } };
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
