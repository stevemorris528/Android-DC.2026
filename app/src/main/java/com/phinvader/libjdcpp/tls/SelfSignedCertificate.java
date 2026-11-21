package com.phinvader.libjdcpp.tls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Makes a self-signed X.509 v3 certificate (RSA-2048, SHA256withRSA) with a small DER encoder, so the app
 * needs no certificate library. DC clients and hubs don't check certificates against a CA: the peer is
 * identified by the certificate's keyprint (SHA-256 of the DER), so only a well-formed cert is needed.
 * Pure Java (java.security only), so it can be unit-tested on the JVM.
 */
public final class SelfSignedCertificate {

    /** sha256WithRSAEncryption */
    private static final String OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11";
    /** commonName */
    private static final String OID_CN = "2.5.4.3";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    public final PrivateKey privateKey;
    public final X509Certificate certificate;

    private SelfSignedCertificate(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    /**
     * Generates a new RSA-2048 key pair and a self-signed certificate with subject and issuer CN={@code cn},
     * valid from yesterday (clock skew) for {@code validYears} years. Slow on old phones (RSA key generation):
     * call it off the UI thread.
     */
    public static SelfSignedCertificate generate(String cn, int validYears, SecureRandom random)
            throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, random);
        KeyPair kp = kpg.generateKeyPair();

        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - DAY_MS);
        // UTCTime only goes to 2049; stay below it.
        long maxEnd = utcTimeLimit();
        Date notAfter = new Date(Math.min(now + validYears * 365L * DAY_MS, maxEnd));

        byte[] serial = new byte[8];
        random.nextBytes(serial);
        BigInteger serialNumber = new BigInteger(1, serial).setBit(62); // positive, non-zero

        byte[] algId = seq(oid(OID_SHA256_WITH_RSA), nul());
        byte[] name = name(cn);
        byte[] tbs = seq(
                explicit(0, integer(BigInteger.valueOf(2))), // version v3
                integer(serialNumber),
                algId,
                name,                                        // issuer
                seq(utcTime(notBefore), utcTime(notAfter)),  // validity
                name,                                        // subject
                kp.getPublic().getEncoded());                // SubjectPublicKeyInfo, already DER

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] signature = sig.sign();

        byte[] der = seq(tbs, algId, bitString(signature));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        return new SelfSignedCertificate(kp.getPrivate(), cert);
    }

    /** Rebuilds a stored certificate: PKCS#8 private key + DER certificate (as {@link #encodedKey()} etc. give). */
    public static SelfSignedCertificate load(byte[] pkcs8Key, byte[] certDer) throws GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8Key));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certDer));
        return new SelfSignedCertificate(key, cert);
    }

    /** PKCS#8 DER of the private key. Secret. */
    public byte[] encodedKey() {
        return privateKey.getEncoded();
    }

    public byte[] encodedCertificate() throws GeneralSecurityException {
        return certificate.getEncoded();
    }

    /** Our keyprint, as published to hubs and peers ({@code SHA256/...}). */
    public String keyprint() {
        return Keyprint.of(certificate);
    }

    /** Key managers that present this certificate in a TLS handshake (as client or server). */
    public KeyManager[] keyManagers() throws GeneralSecurityException {
        char[] pw = "andcpp".toCharArray(); // in-memory key store only
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try {
            ks.load(null, null);
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
        ks.setKeyEntry("client", privateKey, pw, new Certificate[] {certificate});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        return kmf.getKeyManagers();
    }

    private static long utcTimeLimit() {
        java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.clear();
        c.set(2049, java.util.Calendar.DECEMBER, 31, 0, 0, 0);
        return c.getTimeInMillis();
    }

    // ---- minimal DER ----

    static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 6);
        out.write(tag);
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else {
            int n = 0;
            for (int l = len; l > 0; l >>= 8) n++;
            out.write(0x80 | n);
            for (int i = n - 1; i >= 0; i--) out.write((len >> (8 * i)) & 0xFF);
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] explicit(int tagNumber, byte[] inner) {
        return tlv(0xA0 | tagNumber, inner);
    }

    static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray()); // two's complement, minimal: what DER wants
    }

    private static byte[] nul() {
        return new byte[] {0x05, 0x00};
    }

    private static byte[] bitString(byte[] bits) {
        byte[] v = new byte[bits.length + 1]; // first byte: number of unused bits (0)
        System.arraycopy(bits, 0, v, 1, bits.length);
        return tlv(0x03, v);
    }

    static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            int n = 0;
            for (long t = v; t > 0 || n == 0; t >>= 7) n++;
            for (int k = n - 1; k >= 0; k--) {
                int b = (int) ((v >> (7 * k)) & 0x7F);
                out.write(k > 0 ? (b | 0x80) : b);
            }
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] name(String cn) {
        byte[] value = tlv(0x0C, cn.getBytes(Charset.forName("UTF-8"))); // UTF8String
        return seq(set(seq(oid(OID_CN), value)));
    }

    private static byte[] utcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(Charset.forName("US-ASCII")));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] b : parts) out.write(b, 0, b.length);
        return out.toByteArray();
    }
}
