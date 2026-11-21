package com.phinmadvader.andcpp.util;

public class Base32 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int index = 0;
        int digit = 0;
        int currByte;
        int nextByte;

        for (int i = 0; i < data.length; ) {
            currByte = (data[i] >= 0) ? data[i] : (data[i] + 256);

            if (index > 3) {
                if ((i + 1) < data.length) {
                    nextByte = (data[i + 1] >= 0) ? data[i + 1] : (data[i + 1] + 256);
                } else {
                    nextByte = 0;
                }

                digit = currByte & (0xFF >> index);
                index = (index + 5) % 8;
                digit <<= index;
                digit |= nextByte >> (8 - index);
                i++;
            } else {
                digit = (currByte >> (8 - (index + 5))) & 0x1F;
                index = (index + 5) % 8;
                if (index == 0) {
                    i++;
                }
            }
            sb.append(ALPHABET.charAt(digit));
        }
        return sb.toString();
    }

    /**
     * Decodes unpadded RFC 4648 Base32 (A-Z, 2-7; lower case accepted). Other characters, such as '='
     * padding, are skipped. Leftover bits that don't make a whole byte are dropped.
     */
    public static byte[] decode(String base32) {
        byte[] bytes = new byte[base32.length() * 5 / 8];
        int buffer = 0, bits = 0, offset = 0;
        for (int i = 0; i < base32.length() && offset < bytes.length; i++) {
            char ch = Character.toUpperCase(base32.charAt(i));
            int digit;
            if (ch >= 'A' && ch <= 'Z') digit = ch - 'A';
            else if (ch >= '2' && ch <= '7') digit = ch - '2' + 26;
            else continue;
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                bytes[offset++] = (byte) (buffer >> bits);
                buffer &= (1 << bits) - 1;
            }
        }
        return offset == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, offset);
    }
}
