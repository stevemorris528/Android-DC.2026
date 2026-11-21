package com.phinvader.libjdcpp.adc;

import com.phinmadvader.andcpp.util.Base32;
import com.phinmadvader.andcpp.util.TigerDigest;

import java.security.SecureRandom;

/**
 * ADC client identity (ADC base spec, "TIGR" hash feature):
 * <ul>
 * <li><b>PID</b> (private ID): 24 random bytes, generated once and kept. Sent only to the hub, in our own
 * {@code BINF PD...} at login. Never sent to other clients: whoever knows it can claim our CID.</li>
 * <li><b>CID</b> (client ID): {@code Tiger(PID)}, public. Identifies us on every hub and in our file list.</li>
 * </ul>
 * Both are written as unpadded Base32: 24 bytes = 39 characters.
 * Pure Java (no Android classes), so it can be unit-tested on the JVM.
 */
public final class AdcIdentity {

    public static final int ID_BYTES = TigerDigest.DIGEST_LENGTH; // 24
    public static final int ID_BASE32_LENGTH = 39;

    /** Private ID, Base32. Keep secret. */
    public final String pid;
    /** Client ID = Base32(Tiger(PID)). Public. */
    public final String cid;

    private AdcIdentity(String pid, String cid) {
        this.pid = pid;
        this.cid = cid;
    }

    /** Creates a new identity with a random PID. */
    public static AdcIdentity generate(SecureRandom random) {
        byte[] pid = new byte[ID_BYTES];
        random.nextBytes(pid);
        return fromPidBytes(pid);
    }

    public static AdcIdentity generate() {
        return generate(new SecureRandom());
    }

    /** Rebuilds the identity from a stored Base32 PID. Throws IllegalArgumentException if it isn't valid. */
    public static AdcIdentity fromPid(String pidBase32) {
        if (!isValidId(pidBase32)) throw new IllegalArgumentException("Invalid ADC PID");
        return fromPidBytes(Base32.decode(pidBase32));
    }

    private static AdcIdentity fromPidBytes(byte[] pid) {
        return new AdcIdentity(Base32.encode(pid), cidOf(pid));
    }

    /** CID for a PID: Base32(Tiger(pid)). */
    public static String cidOf(byte[] pid) {
        TigerDigest tiger = new TigerDigest();
        tiger.update(pid);
        return Base32.encode(tiger.digest());
    }

    /** True for a 39-character Base32 string that decodes to exactly 24 bytes (a PID or CID). */
    public static boolean isValidId(String s) {
        if (s == null || s.length() != ID_BASE32_LENGTH) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!((ch >= 'A' && ch <= 'Z') || (ch >= '2' && ch <= '7'))) return false;
        }
        // 39 chars carry 195 bits: the last 3 must be zero, or it isn't the encoding of 24 bytes.
        return Base32.encode(Base32.decode(s)).equals(s);
    }
}
