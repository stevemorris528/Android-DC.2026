package com.phinmadvader.andcpp.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TTH {
    private static final int BLOCK_SIZE = 1024;

    public static List<byte[]> getLeaves(File file) throws IOException {
        if (!file.exists() || !file.isFile()) {
            throw new IOException("Invalid file");
        }

        List<byte[]> leaves = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[BLOCK_SIZE];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                Tiger tiger = new Tiger();
                tiger.update(new byte[]{0x00}); // Leaf node prefix
                if (bytesRead < BLOCK_SIZE) {
                    byte[] lastBlock = new byte[bytesRead];
                    System.arraycopy(buffer, 0, lastBlock, 0, bytesRead);
                    tiger.update(lastBlock);
                } else {
                    tiger.update(buffer);
                }
                leaves.add(tiger.digest());
            }
        }
        return leaves;
    }

    public static String calculateTTH(File file) throws IOException {
        List<byte[]> leaves = getLeaves(file);
        if (leaves.isEmpty()) {
            Tiger tiger = new Tiger();
            tiger.update(new byte[]{0x00});
            return Base32.encode(tiger.digest());
        }

        return Base32.encode(computeRoot(leaves));
    }

    private static byte[] computeRoot(List<byte[]> nodes) {
        if (nodes.size() == 1) {
            return nodes.get(0);
        }

        List<byte[]> nextLevel = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i += 2) {
            if (i + 1 < nodes.size()) {
                Tiger tiger = new Tiger();
                tiger.update(new byte[]{0x01}); // Internal node prefix
                tiger.update(nodes.get(i));
                tiger.update(nodes.get(i + 1));
                nextLevel.add(tiger.digest());
            } else {
                nextLevel.add(nodes.get(i));
            }
        }
        return computeRoot(nextLevel);
    }
}
