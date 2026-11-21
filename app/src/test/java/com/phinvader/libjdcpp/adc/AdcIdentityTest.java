package com.phinvader.libjdcpp.adc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.phinmadvader.andcpp.util.Base32;
import com.phinmadvader.andcpp.util.TigerDigest;

import java.nio.charset.Charset;
import java.security.SecureRandom;

import org.junit.Test;

public class AdcIdentityTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }

    private static String tigerHex(String s) {
        TigerDigest t = new TigerDigest();
        t.update(s.getBytes(ASCII));
        return hex(t.digest());
    }

    /** Published Tiger (v1, 0x01 padding, as DC++ uses) test vectors. */
    @Test
    public void tigerKnownVectors() {
        assertEquals("3293AC630C13F0245F92BBB1766E16167A4E58492DDE73F3", tigerHex(""));
        assertEquals("2AAB1484E8C158F2BFB8C5FF41B57A525129131C957B5F93", tigerHex("abc"));
    }

    /** RFC 4648 Base32 vectors, without padding. */
    @Test
    public void base32KnownVectors() {
        String[][] v = {{"f", "MY"}, {"fo", "MZXQ"}, {"foo", "MZXW6"}, {"foob", "MZXW6YQ"},
                {"fooba", "MZXW6YTB"}, {"foobar", "MZXW6YTBOI"}};
        for (String[] p : v) {
            assertEquals(p[1], Base32.encode(p[0].getBytes(ASCII)));
            assertArrayEquals(p[0].getBytes(ASCII), Base32.decode(p[1]));
        }
    }

    /**
     * Base32(Tiger(0x00)) is the well-known TTH of an empty file, so this checks the exact CID calculation
     * (Tiger, then unpadded Base32) against a value every DC++ client agrees on.
     */
    @Test
    public void cidCalculationMatchesEmptyFileTth() {
        assertEquals("LWPNACQDBZRYXW3VHJVCJ64QBZNGHOHHHZWCLNQ", AdcIdentity.cidOf(new byte[] {0}));
    }

    @Test
    public void generatedIdentityIsWellFormed() {
        AdcIdentity id = AdcIdentity.generate(new SecureRandom());
        assertEquals(39, id.pid.length());
        assertEquals(39, id.cid.length());
        assertTrue(AdcIdentity.isValidId(id.pid));
        assertTrue(AdcIdentity.isValidId(id.cid));
        assertNotEquals(id.pid, id.cid);
        assertEquals(AdcIdentity.cidOf(Base32.decode(id.pid)), id.cid);
    }

    @Test
    public void fromPidIsStable() {
        AdcIdentity a = AdcIdentity.generate();
        AdcIdentity b = AdcIdentity.fromPid(a.pid);
        assertEquals(a.pid, b.pid);
        assertEquals(a.cid, b.cid);
    }

    /** Fixed PID: all-zero 24 bytes. Its CID must equal Base32(Tiger(24 zero bytes)), computed independently. */
    @Test
    public void zeroPidVector() {
        byte[] zero = new byte[24];
        String pid = Base32.encode(zero);
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", pid);
        TigerDigest t = new TigerDigest();
        t.update(zero);
        String expectedCid = Base32.encode(t.digest());
        assertEquals(expectedCid, AdcIdentity.fromPid(pid).cid);
    }

    @Test
    public void invalidIdsRejected() {
        assertFalse(AdcIdentity.isValidId(null));
        assertFalse(AdcIdentity.isValidId(""));
        assertFalse(AdcIdentity.isValidId("0123456789ABCDEF0123456789ABCDEF")); // old random-UUID "CID"
        assertFalse(AdcIdentity.isValidId("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA1")); // '1' isn't Base32
        assertFalse(AdcIdentity.isValidId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")); // lower case
        assertFalse(AdcIdentity.isValidId("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB")); // non-zero padding bits
        try {
            AdcIdentity.fromPid("not a pid");
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
