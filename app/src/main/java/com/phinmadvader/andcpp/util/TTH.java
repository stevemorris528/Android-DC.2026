package com.phinmadvader.andcpp.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiger Tree Hash (THEX) as used by DC++: 1024-byte leaves hashed as Tiger(0x00 || data),
 * internal nodes Tiger(0x01 || left || right), an unpaired node is promoted unchanged.
 * Streaming: memory use is O(log n) regardless of file size.
 */
public class TTH {
    private static final int BLOCK_SIZE = 1024;
    /** TTHL is sent at a tree level with at most this many leaves (as DC++ does). */
    private static final int MAX_TTHL_LEAVES = 512;

    /** Incrementally folds a sequence of same-level nodes into their tree root. */
    private static final class TreeBuilder {
        private final List<byte[]> hashes = new ArrayList<>();
        private final List<Integer> levels = new ArrayList<>();
        private final TigerDigest tiger;

        TreeBuilder(TigerDigest tiger) {
            this.tiger = tiger;
        }

        void add(byte[] node) {
            hashes.add(node);
            levels.add(0);
            while (levels.size() >= 2
                    && levels.get(levels.size() - 1).equals(levels.get(levels.size() - 2))) {
                byte[] right = hashes.remove(hashes.size() - 1);
                byte[] left = hashes.remove(hashes.size() - 1);
                int level = levels.remove(levels.size() - 1);
                levels.remove(levels.size() - 1);
                hashes.add(internal(tiger, left, right));
                levels.add(level + 1);
            }
        }

        boolean isEmpty() {
            return hashes.isEmpty();
        }

        byte[] root() {
            byte[] right = hashes.get(hashes.size() - 1);
            for (int i = hashes.size() - 2; i >= 0; i--) {
                right = internal(tiger, hashes.get(i), right);
            }
            return right;
        }
    }

    private static byte[] leaf(TigerDigest tiger, byte[] data, int len) {
        tiger.update((byte) 0x00);
        tiger.update(data, 0, len);
        return tiger.digest();
    }

    private static byte[] internal(TigerDigest tiger, byte[] left, byte[] right) {
        tiger.update((byte) 0x01);
        tiger.update(left);
        tiger.update(right);
        return tiger.digest();
    }

    /** Reads exactly buf.length bytes unless EOF; returns bytes read (0 at EOF). */
    private static int readBlock(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static void checkFile(File file) throws IOException {
        if (!file.exists() || !file.isFile()) {
            throw new IOException("Invalid file");
        }
    }

    /** Base32 TTH root of the file. */
    public static String calculateTTH(File file) throws IOException {
        checkFile(file);
        TigerDigest tiger = new TigerDigest();
        TreeBuilder tree = new TreeBuilder(tiger);
        byte[] buf = new byte[BLOCK_SIZE];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 256 * 1024)) {
            int n;
            while ((n = readBlock(in, buf)) > 0) {
                tree.add(leaf(tiger, buf, n));
            }
        }
        if (tree.isEmpty()) {
            return Base32.encode(leaf(tiger, buf, 0)); // empty file: hash of the empty leaf
        }
        return Base32.encode(tree.root());
    }

    /**
     * Leaf hashes for a TTHL reply: a complete tree level with at most MAX_TTHL_LEAVES nodes, each
     * covering 1024 * 2^k bytes. Their tree root equals the file's TTH.
     */
    public static List<byte[]> getLeaves(File file) throws IOException {
        checkFile(file);
        long blocks = Math.max(1, (file.length() + BLOCK_SIZE - 1) / BLOCK_SIZE);
        long blocksPerLeaf = 1;
        while ((blocks + blocksPerLeaf - 1) / blocksPerLeaf > MAX_TTHL_LEAVES) {
            blocksPerLeaf <<= 1;
        }

        TigerDigest tiger = new TigerDigest();
        List<byte[]> leaves = new ArrayList<>();
        byte[] buf = new byte[BLOCK_SIZE];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 256 * 1024)) {
            TreeBuilder segment = new TreeBuilder(tiger);
            long inSegment = 0;
            int n;
            while ((n = readBlock(in, buf)) > 0) {
                segment.add(leaf(tiger, buf, n));
                if (++inSegment == blocksPerLeaf) {
                    leaves.add(segment.root());
                    segment = new TreeBuilder(tiger);
                    inSegment = 0;
                }
            }
            if (!segment.isEmpty()) {
                leaves.add(segment.root());
            }
        }
        if (leaves.isEmpty()) {
            leaves.add(leaf(tiger, buf, 0));
        }
        return leaves;
    }
}
