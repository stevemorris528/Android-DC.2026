package com.phinvader.libjdcpp.adc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/** Both handshake roles and a file request over a loopback socket. */
public class AdcClientConnectionTest {

    @Test
    public void identifiers() {
        assertEquals("/downloads share/Alien/a.mkv", AdcClientConnection.identifierFor("downloads share\\Alien\\a.mkv"));
        assertEquals("/x/y", AdcClientConnection.identifierFor("/x/y"));
        assertEquals("files.xml", AdcClientConnection.identifierFor("files.xml"));
        assertEquals("TTH/ABC", AdcClientConnection.identifierFor("TTH/ABC"));
    }

    @Test
    public void handshakeAndDownload() throws Exception {
        // File content that starts with '\n': must not be eaten as a line ending.
        final byte[] data = "\nhello raw world".getBytes("US-ASCII");
        final BlockingQueue<Object> uploaderSaw = new LinkedBlockingQueue<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread uploader = new Thread(() -> {
                try (Socket s = server.accept()) {
                    AdcClientConnection up = new AdcClientConnection(s, null);
                    up.handshakeAsAcceptor("UPLOADERCID");
                    uploaderSaw.add(up.peerCid + "|" + up.token);
                    // 1st request: served
                    AdcCommand get = up.readCommand();
                    uploaderSaw.add(get.toString());
                    long start = Long.parseLong(get.param(2));
                    up.sendSnd("file", get.param(1), start, data.length - start);
                    OutputStream o = up.rawOut();
                    o.write(data, (int) start, (int) (data.length - start));
                    o.flush();
                    // 2nd request: no slots; 3rd: not available
                    up.readCommand();
                    up.sendStatus("153", "No slots available");
                    up.readCommand();
                    up.sendStatus("151", "File Not Available");
                    up.readCommand(); // wait for close
                } catch (Exception e) {
                    uploaderSaw.add(e);
                }
            });
            uploader.setDaemon(true);
            uploader.start();

            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), 5000);
            s.setSoTimeout(5000);
            AdcClientConnection down = new AdcClientConnection(s, null);
            down.handshakeAsConnector("DOWNLOADERCID", "tok123");
            assertEquals("UPLOADERCID", down.peerCid);
            assertEquals("DOWNLOADERCID|tok123", uploaderSaw.poll(5, TimeUnit.SECONDS));

            AdcClientConnection.Reply r = down.request("file", "/dir/a b.txt", 0, -1);
            assertEquals("CGET file /dir/a\\sb.txt 0 -1", uploaderSaw.poll(5, TimeUnit.SECONDS));
            assertTrue(r.ok());
            assertEquals(0, r.start);
            assertEquals(data.length, r.length);
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            final long[] last = {0};
            assertEquals(data.length, down.readRaw(r.length, got, n -> last[0] = n));
            assertArrayEquals(data, got.toByteArray());
            assertEquals(data.length, last[0]);

            AdcClientConnection.Reply busy = down.request("file", "/dir/x", 0, -1);
            assertFalse(busy.ok());
            assertTrue(busy.noSlots());
            AdcClientConnection.Reply gone = down.request("file", "/dir/y", 0, -1);
            assertTrue(gone.notAvailable());
            down.close();
            Object err = uploaderSaw.poll(1, TimeUnit.SECONDS);
            if (err instanceof Throwable) throw new AssertionError((Throwable) err);
        }
    }

    @Test
    public void resumeFromOffset() throws Exception {
        final byte[] data = "0123456789".getBytes("US-ASCII");
        try (ServerSocket server = new ServerSocket(0)) {
            Thread uploader = new Thread(() -> {
                try (Socket s = server.accept()) {
                    AdcClientConnection up = new AdcClientConnection(s, null);
                    up.handshakeAsAcceptor("U");
                    AdcCommand get = up.readCommand();
                    int start = Integer.parseInt(get.param(2));
                    up.sendSnd("file", get.param(1), start, data.length - start);
                    up.rawOut().write(data, start, data.length - start);
                    up.rawOut().flush();
                    up.readCommand();
                } catch (Exception ignored) {
                    // test thread asserts
                }
            });
            uploader.setDaemon(true);
            uploader.start();
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), 5000);
            s.setSoTimeout(5000);
            AdcClientConnection down = new AdcClientConnection(s, null);
            down.handshakeAsConnector("D", "t");
            AdcClientConnection.Reply r = down.request("file", "/f", 6, -1);
            assertEquals(6, r.start);
            assertEquals(4, r.length);
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            down.readRaw(r.length, got, null);
            assertEquals("6789", got.toString("US-ASCII"));
            down.close();
        }
    }
}
