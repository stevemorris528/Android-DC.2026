package com.phinvader.libjdcpp.tls;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS sockets for secure hubs (NMDCS, ADCS) and, later, client-to-client transfers.
 * <ul>
 * <li>Only TLS 1.3 / 1.2 are enabled. Android 4.1-4.4 support TLS 1.2 but leave it switched off, so it is
 * switched on explicitly; ADCH++ refuses anything older by default. Never falls back to plain TCP.</li>
 * <li>Hubs and peers use self-signed certificates, so any certificate chain is accepted; the peer is checked
 * by its keyprint instead (see {@link Keyprint}), when one is known.</li>
 * <li>No hostname verification: it means nothing for DC hubs.</li>
 * </ul>
 */
public final class TlsSocketFactory {

    private static final String[] WANTED_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private TlsSocketFactory() {}

    /** The hub's certificate didn't match the keyprint we were given (or had stored). */
    public static final class KeyprintMismatchException extends SSLException {
        public final String expected;
        public final String actual;

        KeyprintMismatchException(String expected, String actual) {
            super("Keyprint mismatch: expected " + expected + ", got " + actual);
            this.expected = expected;
            this.actual = actual;
        }
    }

    /** Accepts every certificate; trust comes from the keyprint check instead. */
    private static final X509TrustManager ACCEPT_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    /**
     * Connects to host:port and completes a TLS handshake (both bounded by timeoutMs). Presents our own
     * certificate when {@code keyManagers} is given. If {@code expectedKeyprint} is not null, the hub's
     * certificate must match it or the socket is closed and {@link KeyprintMismatchException} thrown.
     * The returned socket has no read timeout set (the caller decides).
     */
    public static SSLSocket connect(String host, int port, int timeoutMs, KeyManager[] keyManagers,
                                    String expectedKeyprint) throws IOException {
        Socket raw = new Socket();
        SSLSocket ssl = null;
        try {
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            ssl = (SSLSocket) context(keyManagers).getSocketFactory().createSocket(raw, host, port, true);
            enableModernProtocols(ssl);
            ssl.setUseClientMode(true);
            ssl.setSoTimeout(timeoutMs);
            ssl.startHandshake();
            ssl.setSoTimeout(0);

            if (expectedKeyprint != null) {
                String actual = peerKeyprint(ssl);
                if (!Keyprint.matches(expectedKeyprint, actual)) {
                    throw new KeyprintMismatchException(expectedKeyprint, actual);
                }
            }
            return ssl;
        } catch (IOException e) {
            closeQuietly(ssl != null ? ssl : raw);
            throw e;
        } catch (RuntimeException e) {
            closeQuietly(ssl != null ? ssl : raw);
            throw e;
        }
    }

    /** The keyprint of the certificate the other side presented. */
    public static String peerKeyprint(SSLSocket socket) throws SSLPeerUnverifiedException {
        Certificate[] chain = socket.getSession().getPeerCertificates();
        if (chain == null || chain.length == 0) throw new SSLPeerUnverifiedException("No peer certificate");
        return Keyprint.of(chain[0]);
    }

    /** E.g. "TLSv1.3 / TLS_AES_128_GCM_SHA256", for logs and hub details. */
    public static String describe(SSLSocket socket) {
        return socket.getSession().getProtocol() + " / " + socket.getSession().getCipherSuite();
    }

    static SSLContext context(KeyManager[] keyManagers) throws IOException {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keyManagers, new TrustManager[] {ACCEPT_ALL}, new SecureRandom());
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS is not available: " + e.getMessage(), e);
        }
    }

    /** Enables TLS 1.3/1.2 only. Throws if the device supports neither (Android 4.0 and older). */
    static void enableModernProtocols(SSLSocket s) throws SSLException {
        List<String> supported = Arrays.asList(s.getSupportedProtocols());
        List<String> enable = new ArrayList<>();
        for (String p : WANTED_PROTOCOLS) {
            if (supported.contains(p)) enable.add(p);
        }
        if (enable.isEmpty()) {
            throw new SSLException("Secure hubs need TLS 1.2 (Android 4.1 or newer)");
        }
        s.setEnabledProtocols(enable.toArray(new String[0]));
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
