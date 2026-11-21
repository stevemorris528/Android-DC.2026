package com.phinvader.libjdcpp.tls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.junit.BeforeClass;
import org.junit.Test;

public class TlsTest {

    private static SelfSignedCertificate cert;

    @BeforeClass
    public static void makeCert() throws Exception {
        cert = SelfSignedCertificate.generate("TESTCID", 20, new SecureRandom());
    }

    @Test
    public void derBasics() {
        assertArrayEquals(new byte[] {0x02, 0x01, 0x02}, SelfSignedCertificate.integer(BigInteger.valueOf(2)));
        // 1.2.840.113549 (RSA) is the textbook OID encoding example.
        assertArrayEquals(new byte[] {0x06, 0x06, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D},
                SelfSignedCertificate.oid("1.2.840.113549"));
        byte[] big = SelfSignedCertificate.tlv(0x04, new byte[300]);
        assertEquals(0x82, big[1] & 0xFF); // long form, two length bytes
        assertEquals(300, ((big[2] & 0xFF) << 8) | (big[3] & 0xFF));
    }

    @Test
    public void certificateIsWellFormedAndSelfSigned() throws Exception {
        X509Certificate c = cert.certificate;
        assertEquals(3, c.getVersion());
        assertEquals("SHA256withRSA", c.getSigAlgName());
        assertTrue(c.getSubjectX500Principal().getName().contains("CN=TESTCID"));
        assertEquals(c.getSubjectX500Principal(), c.getIssuerX500Principal());
        c.verify(c.getPublicKey()); // throws if the signature is wrong
        c.checkValidity();
        assertTrue(c.getSerialNumber().signum() > 0);
    }

    @Test
    public void keyprintFormat() {
        String kp = cert.keyprint();
        assertTrue(kp.startsWith("SHA256/"));
        assertEquals(7 + 52, kp.length()); // 32 bytes -> 52 unpadded Base32 chars
        assertTrue(Keyprint.matches(kp, kp.toLowerCase()));
        assertTrue(Keyprint.matches(kp, kp.substring(7)));
        assertTrue(Keyprint.matches(kp + "====", " " + kp));
        assertFalse(Keyprint.matches(kp, "SHA256/AAAA"));
        assertFalse(Keyprint.matches(null, kp));
    }

    @Test
    public void storeAndLoadKeepsKeyprint() throws Exception {
        SelfSignedCertificate back = SelfSignedCertificate.load(cert.encodedKey(), cert.encodedCertificate());
        assertEquals(cert.keyprint(), back.keyprint());
        assertArrayEquals(cert.encodedKey(), back.encodedKey());
        assertTrue(back.keyManagers().length > 0);
    }

    @Test
    public void handshakeWithMatchingKeyprint() throws Exception {
        try (SSLServerSocket server = startEchoServer()) {
            SSLSocket s = TlsSocketFactory.connect("127.0.0.1", server.getLocalPort(), 5000, null, cert.keyprint());
            try {
                assertEquals(cert.keyprint(), TlsSocketFactory.peerKeyprint(s));
                String proto = s.getSession().getProtocol();
                assertTrue(proto, proto.equals("TLSv1.2") || proto.equals("TLSv1.3"));
                OutputStream out = s.getOutputStream();
                out.write("$Lock X|".getBytes("US-ASCII"));
                out.flush();
                InputStream in = s.getInputStream();
                byte[] b = new byte[8];
                int n = 0;
                while (n < 8) n += in.read(b, n, 8 - n);
                assertEquals("$Lock X|", new String(b, "US-ASCII"));
            } finally {
                s.close();
            }
        }
    }

    @Test
    public void handshakeWithWrongKeyprintIsRefused() throws Exception {
        try (SSLServerSocket server = startEchoServer()) {
            try {
                TlsSocketFactory.connect("127.0.0.1", server.getLocalPort(), 5000, null,
                        "SHA256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
                fail("Expected a keyprint mismatch");
            } catch (TlsSocketFactory.KeyprintMismatchException e) {
                assertEquals(cert.keyprint(), e.actual);
            }
        }
    }

    @Test
    public void noKeyprintGivenAcceptsAnyCertificate() throws Exception {
        try (SSLServerSocket server = startEchoServer()) {
            SSLSocket s = TlsSocketFactory.connect("127.0.0.1", server.getLocalPort(), 5000, null, null);
            assertEquals(cert.keyprint(), TlsSocketFactory.peerKeyprint(s));
            s.close();
        }
    }

    /** ADCS transfer shape: a plain accepted socket upgraded with acceptOver; both sides present certificates. */
    @Test
    public void acceptOverWithClientCertificate() throws Exception {
        final SelfSignedCertificate clientCert = SelfSignedCertificate.generate("CLIENTCID", 20, new java.security.SecureRandom());
        try (java.net.ServerSocket plain = new java.net.ServerSocket(0)) {
            final AtomicReference<Object> serverSaw = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try (java.net.Socket raw = plain.accept()) {
                    SSLSocket s = TlsSocketFactory.acceptOver(raw, 5000, cert.keyManagers());
                    serverSaw.set(TlsSocketFactory.peerKeyprintOrNull(s));
                    s.getOutputStream().write('C');
                    s.getOutputStream().flush();
                    s.getInputStream().read();
                } catch (Exception e) {
                    serverSaw.set(e);
                }
            });
            t.setDaemon(true);
            t.start();
            SSLSocket c = TlsSocketFactory.connect("127.0.0.1", plain.getLocalPort(), 5000,
                    clientCert.keyManagers(), cert.keyprint());
            assertEquals('C', c.getInputStream().read());
            assertEquals(cert.keyprint(), TlsSocketFactory.peerKeyprint(c));
            c.close();
            t.join(5000);
            assertEquals(clientCert.keyprint(), serverSaw.get());
        }
    }

    /** A one-connection TLS echo server presenting {@link #cert}. */
    private static SSLServerSocket startEchoServer() throws Exception {
        final SSLServerSocket server = (SSLServerSocket) TlsSocketFactory.context(cert.keyManagers())
                .getServerSocketFactory().createServerSocket(0);
        final AtomicReference<Throwable> err = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try (SSLSocket c = (SSLSocket) server.accept()) {
                InputStream in = c.getInputStream();
                OutputStream out = c.getOutputStream();
                byte[] buf = new byte[256];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (Exception e) {
                err.set(e); // a refused handshake ends up here; that's fine
            }
        });
        t.setDaemon(true);
        t.start();
        return server;
    }
}
