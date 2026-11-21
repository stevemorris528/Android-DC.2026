package com.phinmadvader.andcpp;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.net.Socket;

/**
 * Looks at the first byte of an incoming connection without taking it from the socket (MSG_PEEK), so a TLS
 * handshake (first byte 0x16) can still be started on the untouched socket. Android 5+ only (android.system.Os).
 */
final class SocketPeek {
    /** Returned when peeking isn't possible here (old Android, or an error): the caller reads normally. */
    static final int UNSUPPORTED = -2;
    /** Returned when no byte arrived in time or the peer closed the connection. */
    static final int NOTHING = -1;

    private SocketPeek() {}

    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    /** The first byte the peer sent (0-255) without consuming it, NOTHING, or UNSUPPORTED. */
    static int peekFirstByte(Socket s, int timeoutMs) {
        if (!supported()) return UNSUPPORTED;
        ParcelFileDescriptor pfd = null;
        try {
            pfd = ParcelFileDescriptor.fromSocket(s); // a dup of the socket's fd; closing it leaves the socket open
            StructPollfd p = new StructPollfd();
            p.fd = pfd.getFileDescriptor();
            p.events = (short) OsConstants.POLLIN;
            int ready = Os.poll(new StructPollfd[] {p}, timeoutMs);
            if (ready <= 0) return NOTHING;
            byte[] b = new byte[1];
            int n = Os.recvfrom(pfd.getFileDescriptor(), b, 0, 1, OsConstants.MSG_PEEK, null);
            return n == 1 ? (b[0] & 0xFF) : NOTHING;
        } catch (Exception e) {
            return UNSUPPORTED;
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                    // nothing to do
                }
            }
        }
    }
}
