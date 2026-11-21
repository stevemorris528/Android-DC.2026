package com.phinvader.libjdcpp.tls;

import com.phinmadvader.andcpp.util.Base32;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.Locale;

/**
 * ADC/DC++ keyprint (KEYP extension): {@code SHA256/} + unpadded Base32 of the SHA-256 of the DER
 * certificate. Hubs publish it in {@code adcs://host:port/?kp=SHA256/...}; clients publish theirs in INF KP.
 */
public final class Keyprint {
    public static final String PREFIX = "SHA256/";

    private Keyprint() {}

    public static String of(Certificate cert) {
        try {
            return of(cert.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Cannot encode certificate", e);
        }
    }

    public static String of(byte[] derCertificate) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return PREFIX + Base32.encode(sha.digest(derCertificate));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * True if two keyprints name the same certificate. Ignores case, surrounding space and Base32 '='
     * padding, and accepts a missing "SHA256/" prefix on either side.
     */
    public static boolean matches(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        return na != null && na.equals(nb);
    }

    private static String normalize(String kp) {
        if (kp == null) return null;
        String s = kp.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith(PREFIX)) s = s.substring(PREFIX.length());
        while (s.endsWith("=")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? null : s;
    }
}
