package com.phinvader.libjdcpp.adc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * BLOM bit layout, worked out by hand from the ADC-EXT / DC++ HashBloom definition:
 * hash n = TTH bits n*h .. n*h+h-1 (bit i = bit i%8 of byte i/8, LSB first), mod m;
 * filter bit p is bit p%8 of byte p/8.
 */
public class AdcBloomTest {

    private static byte[] tth(int... firstBytes) {
        byte[] t = new byte[24];
        for (int i = 0; i < firstBytes.length; i++) t[i] = (byte) firstBytes[i];
        return t;
    }

    @Test
    public void hashPositionsWithByteAlignedSlices() {
        byte[] t = tth(0x05, 0x80);
        assertEquals(5, AdcBloom.pos(t, 0, 8, 256));   // byte 0 = 0b00000101
        assertEquals(128, AdcBloom.pos(t, 1, 8, 256)); // byte 1 = 0b10000000
        assertEquals(0, AdcBloom.pos(t, 2, 8, 256));
    }

    @Test
    public void hashSlicesCrossByteBoundaries() {
        // h = 10: hash 0 = bits 0..9 = byte0 (all 8 bits) + bits 0,1 of byte1; hash 1 = bits 10..19.
        byte[] t = tth(0xFF, 0x07, 0x30); // byte1 = 0b00000111, byte2 = 0b00110000
        assertEquals(0x3FF, AdcBloom.pos(t, 0, 10, 1 << 20)); // 8 ones + byte1 bits 0,1
        // hash 1: byte1 bits 2..7 (=0b000001 -> value 1 at i=0), byte2 bits 0..3 (=0b0000) -> 1
        assertEquals(1, AdcBloom.pos(t, 1, 10, 1 << 20));
        // mod m
        assertEquals(0x3FF % 1000, AdcBloom.pos(t, 0, 10, 1000));
    }

    @Test
    public void filterBytes() {
        byte[] f = AdcBloom.build(Collections.singletonList(tth(0x05, 0x80)), 2, 8, 256);
        assertEquals(32, f.length);
        byte[] expected = new byte[32];
        expected[0] = 0x20;  // bit 5
        expected[16] = 0x01; // bit 128
        assertEquals(Arrays.toString(expected), Arrays.toString(f));
    }

    @Test
    public void severalFilesAndBadEntries() {
        byte[] a = tth(0x01);       // hash0 = 1
        byte[] b = tth(0x00, 0x03); // hash0 = 0, hash1 = 3
        byte[] f = AdcBloom.build(Arrays.asList(a, b, new byte[5], null), 2, 8, 64);
        // a: hash0=1, hash1=0 ; b: hash0=0, hash1=3  -> bits 0,1,3
        assertEquals(0x0B, f[0] & 0xFF);
        for (int i = 1; i < f.length; i++) assertEquals(0, f[i]);
    }

    @Test
    public void emptyFilter() {
        assertEquals(0, AdcBloom.build(Collections.<byte[]>emptyList(), 2, 8, 0).length);
    }

    @Test
    public void parameterChecks() {
        assertNull(AdcBloom.checkParameters(8, 24, 8192, 1000));
        assertNotNull(AdcBloom.checkParameters(0, 24, 8192, 1000));
        assertNotNull(AdcBloom.checkParameters(9, 24, 8192, 1000));
        assertNotNull(AdcBloom.checkParameters(8, 64, 8192, 1000));   // h too big for us
        assertNotNull(AdcBloom.checkParameters(8, 25, 8192, 1000));   // k*h > 192
        assertNotNull(AdcBloom.checkParameters(2, 8, 512, 1000));     // m > 2^h
        assertNotNull(AdcBloom.checkParameters(8, 24, 12, 1000));     // not whole bytes
        assertNotNull(AdcBloom.checkParameters(8, 24, 1L << 23, 10)); // far too big for 10 files
    }
}
