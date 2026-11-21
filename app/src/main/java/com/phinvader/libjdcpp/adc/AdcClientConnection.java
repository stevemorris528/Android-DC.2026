package com.phinvader.libjdcpp.adc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * An ADC client-to-client connection (ADC 1.0 "C" messages), plain or over TLS.
 * <pre>
 * connector (opened the connection)        acceptor
 *   CSUP ADBASE ADTIGR          ------&gt;
 *                               &lt;------   CSUP ADBASE ADTIGR
 *                               &lt;------   CINF ID&lt;acceptor CID&gt;
 *   CINF ID&lt;our CID&gt; TO&lt;token&gt;  ------&gt;
 * then the downloader sends CGET, the uploader answers CSND + raw bytes, or CSTA.
 * </pre>
 * Reads straight from the socket (no reader thread), so raw file data after CSND is read exactly, whatever its
 * first byte. Pure Java, so it can be unit-tested on the JVM.
 */
public final class AdcClientConnection implements Closeable {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_LINE = 64 * 1024;

    public final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    /** The peer's CID (its CINF ID) after the handshake. */
    public volatile String peerCid;
    /** The token from the connector's CINF TO (acceptor side), or the one we sent (connector side). */
    public volatile String token;

    /** Wraps a connected socket. {@code alreadyRead}: bytes the caller read before (e.g. to detect ADC). */
    public AdcClientConnection(Socket socket, byte[] alreadyRead) throws IOException {
        this.socket = socket;
        InputStream raw = socket.getInputStream();
        if (alreadyRead != null && alreadyRead.length > 0) {
            raw = new SequenceInputStream(new ByteArrayInputStream(alreadyRead), raw);
        }
        this.in = new BufferedInputStream(raw, 64 * 1024);
        this.out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
    }

    // ---- handshakes ----

    /** We opened the connection (after the peer's or our CTM): SUP, read SUP + INF, send our INF with the token. */
    public void handshakeAsConnector(String ourCid, String token) throws IOException {
        this.token = token;
        send(AdcCommand.of('C', "SUP").param("ADBASE").param("ADTIGR").build());
        expect("SUP");
        AdcCommand inf = expect("INF");
        peerCid = inf.named("ID");
        send(AdcCommand.of('C', "INF").named("ID", ourCid).named("TO", token).build());
    }

    /** The peer connected to us: read SUP, send SUP + our INF, read the peer's INF (ID + TO token). */
    public void handshakeAsAcceptor(String ourCid) throws IOException {
        expect("SUP");
        send(AdcCommand.of('C', "SUP").param("ADBASE").param("ADTIGR").build());
        send(AdcCommand.of('C', "INF").named("ID", ourCid).build());
        AdcCommand inf = expect("INF");
        peerCid = inf.named("ID");
        token = inf.named("TO");
    }

    private AdcCommand expect(String cmd) throws IOException {
        AdcCommand c = readCommand();
        if (c == null) throw new EOFException("Peer closed the connection during the ADC handshake");
        if (c.type == 'C' && c.cmd.equals("STA")) {
            throw new IOException("Peer refused the connection: " + c.param(1) + " (" + c.param(0) + ")");
        }
        if (c.type != 'C' || !c.cmd.equals(cmd)) {
            throw new IOException("Expected C" + cmd + " from peer, got " + c.name());
        }
        return c;
    }

    // ---- downloading ----

    /** Result of a CGET. */
    public static final class Reply {
        /** CSND: the bytes that follow. -1 when the peer answered CSTA. */
        public final long length;
        /** CSND: the start offset the peer will send from. */
        public final long start;
        /** CSTA code (e.g. "151" file not available, "153" no slots), or null. */
        public final String statusCode;
        public final String statusText;

        Reply(long start, long length, String code, String text) {
            this.start = start;
            this.length = length;
            this.statusCode = code;
            this.statusText = text;
        }

        public boolean ok() {
            return statusCode == null;
        }

        /** 151: the peer says it doesn't have (that part of) the file. */
        public boolean notAvailable() {
            return statusCode != null && (statusCode.endsWith("51") || statusCode.endsWith("52"));
        }

        /** 153: all the peer's slots are taken. */
        public boolean noSlots() {
            return statusCode != null && statusCode.endsWith("53");
        }
    }

    /**
     * Sends {@code CGET type identifier start bytes} and reads the answer. On CSND the raw data follows: read it
     * with {@link #readRaw}. {@code bytes} = -1 means "to the end".
     */
    public Reply request(String type, String identifier, long start, long bytes) throws IOException {
        send(AdcCommand.of('C', "GET").param(type).param(identifier).param(String.valueOf(start))
                .param(String.valueOf(bytes)).build());
        while (true) {
            AdcCommand c = readCommand();
            if (c == null) throw new EOFException("Peer closed the connection before answering");
            if (c.type != 'C') continue;
            if (c.cmd.equals("SND")) {
                return new Reply(parseLong(c.param(2)), parseLong(c.param(3)), null, null);
            }
            if (c.cmd.equals("STA")) {
                String code = c.param(0);
                if (code != null && code.startsWith("0")) continue; // severity 0: just information
                return new Reply(-1, -1, code, c.param(1));
            }
            // anything else (e.g. a stray INF): ignore and keep waiting
        }
    }

    /** Progress callback for {@link #readRaw}. */
    public interface Progress {
        /** Total bytes of this transfer received so far. */
        void onBytes(long receivedSoFar);
    }

    /**
     * Copies exactly {@code length} raw bytes from the peer to {@code sink}. Returns the number copied: less than
     * {@code length} if the peer closed the connection early (caller keeps what it got, for resume).
     */
    public long readRaw(long length, OutputStream sink, Progress progress) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long done = 0;
        while (done < length) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, length - done));
            if (n < 0) break;
            sink.write(buf, 0, n);
            done += n;
            if (progress != null) progress.onBytes(done);
        }
        sink.flush();
        return done;
    }

    // ---- uploading (see PeerConnectionHandler's ADC mode) ----

    /** CSND type identifier start length (the raw bytes go to {@link #rawOut()} right after). */
    public void sendSnd(String type, String identifier, long start, long length) throws IOException {
        sendOrThrow(AdcCommand.of('C', "SND").param(type).param(identifier).param(String.valueOf(start))
                .param(String.valueOf(length)).build());
    }

    /** CSTA code text, e.g. ("151", "File Not Available"). */
    public void sendStatus(String code, String text) throws IOException {
        sendOrThrow(AdcCommand.of('C', "STA").param(code).param(text).build());
    }

    public OutputStream rawOut() {
        return out;
    }

    // ---- lines ----

    /** Next command, skipping keep-alive empty lines. Null when the peer closed the connection. */
    public AdcCommand readCommand() throws IOException {
        while (true) {
            String line = readLine();
            if (line == null) return null;
            if (line.isEmpty()) continue;
            try {
                return AdcCommand.parse(line);
            } catch (IllegalArgumentException e) {
                // not ADC: skip it
            }
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (true) {
            int b = in.read();
            if (b < 0) return line.size() > 0 ? new String(line.toByteArray(), UTF8) : null;
            if (b == '\n') break;
            if (line.size() < MAX_LINE) line.write(b);
        }
        String s = new String(line.toByteArray(), UTF8);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    public void send(AdcCommand c) throws IOException {
        sendOrThrow(c);
    }

    private void sendOrThrow(AdcCommand c) throws IOException {
        synchronized (out) {
            out.write((c.toString() + "\n").getBytes(UTF8));
            out.flush();
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null ? -1 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** ADC path for a file-list path ("dir\sub\file" -> "/dir/sub/file"); the file list itself is "files.xml". */
    public static String identifierFor(String remotePath) {
        if (remotePath == null) return null;
        if (remotePath.regionMatches(true, 0, "TTH/", 0, 4)) return remotePath;
        if (remotePath.equalsIgnoreCase("files.xml") || remotePath.equalsIgnoreCase("files.xml.bz2")) return remotePath;
        String p = remotePath.replace('\\', '/');
        return p.startsWith("/") ? p : "/" + p;
    }
}
