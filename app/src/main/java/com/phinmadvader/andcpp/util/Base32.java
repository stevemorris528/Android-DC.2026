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

    public static byte[] decode(String base32) {
        int i, index, lookup, offset, digit;
        byte[] bytes = new byte[base32.length() * 5 / 8];

        for (i = 0, index = 0, offset = 0; i < base32.length(); i++) {
            lookup = base32.charAt(i) - '2';
            if (lookup < 0 || lookup >= 30) {
                lookup = base32.charAt(i) - 'A';
                if (lookup < 0 || lookup >= 26) {
                    continue;
                }
                digit = lookup;
            } else {
                digit = lookup + 26;
            }

            if (index <= 3) {
                index = (index + 5) % 8;
                if (index == 0) {
                    bytes[offset] |= digit;
                    offset++;
                    if (offset >= bytes.length) break;
                } else {
                    bytes[offset] |= digit << (8 - index);
                }
            } else {
                index = (index + 5) % 8;
                bytes[offset] |= (digit >>> index);
                offset++;
                if (offset >= bytes.length) break;
                bytes[offset] |= digit << (8 - index);
            }
        }
        return bytes;
    }
}
