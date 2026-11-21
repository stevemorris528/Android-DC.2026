package com.phinvader.libjdcpp.adc;

/**
 * The BLOM extension's Bloom filter of our shared files' TTHs (ADC-EXT "BLOM"), as DC++ and ADCH++ build it
 * (their HashBloom): m bits, k hash functions, hash function n = the h bits of the 192-bit TTH starting at bit
 * n*h (bit i of the TTH is bit i%8 of byte i/8, least significant first), modulo m. The filter is sent as m/8
 * bytes, bit i of the filter in bit i%8 of byte i/8.
 * Pure Java, so it can be unit-tested on the JVM.
 */
public final class AdcBloom {

    public static final int TTH_BITS = 192;

    private AdcBloom() {}

    /**
     * Null if the hub's parameters are acceptable, else why not. Same limits as DC++: 1 ≤ k ≤ 8, 1 ≤ h ≤ 63
     * (DC++ allows 64; we stay in signed range), k·h ≤ 192, 0 ≤ m ≤ 2^h, and m at most 5× the ideal size for
     * our file count (so a hub can't make us allocate a huge filter).
     */
    public static String checkParameters(int k, int h, long mBits, long fileCount) {
        if (k < 1 || k > 8) return "Unsupported k";
        if (h < 1 || h > 63) return "Unsupported h";
        if ((long) k * h > TTH_BITS) return "Unsupported k*h";
        if (mBits < 0 || mBits % 8 != 0) return "Unsupported m";
        if (mBits > (1L << h)) return "Unsupported m";
        long ideal = roundUp((long) Math.ceil(fileCount * k / Math.log(2)), 64);
        if (mBits > 5 * Math.max(ideal, 64)) return "Unsupported m";
        return null;
    }

    /** The filter for these TTHs (24 bytes each; others are skipped), as m/8 bytes. */
    public static byte[] build(Iterable<byte[]> tths, int k, int h, long mBits) {
        byte[] out = new byte[(int) (mBits / 8)];
        if (mBits == 0) return out;
        for (byte[] tth : tths) {
            if (tth == null || tth.length != TTH_BITS / 8) continue;
            for (int n = 0; n < k; n++) {
                long p = pos(tth, n, h, mBits);
                out[(int) (p / 8)] |= (byte) (1 << (p % 8));
            }
        }
        return out;
    }

    /** Bit position of hash function n for this TTH. Package-private for tests. */
    static long pos(byte[] tth, int n, int h, long mBits) {
        long x = 0;
        int start = n * h;
        for (int i = 0; i < h; i++) {
            int bit = start + i;
            if ((tth[bit / 8] & (1 << (bit % 8))) != 0) x |= 1L << i;
        }
        return x % mBits;
    }

    private static long roundUp(long v, long multiple) {
        return ((v + multiple - 1) / multiple) * multiple;
    }
}
