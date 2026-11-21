package com.phinmadvader.andcpp;

import android.util.Log;

import com.phinvader.libjdcpp.DCFunctions;
import com.phinvader.libjdcpp.DCMessage;
import com.phinvader.libjdcpp.DCUser;
import com.phinvader.libjdcpp.MessageHandler;
import com.phinvader.libjdcpp.adc.AdcClientConnection;
import com.phinvader.libjdcpp.adc.AdcCommand;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class PeerConnectionHandler implements Runnable {
    
    public enum ConnectionState {
        HANDSHAKING,
        CONNECTED,
        REQUESTING,
        QUEUED,
        UPLOADING,
        COMPLETED,
        FAILED,
        NAT_ERROR,
        HANDSHAKE_TIMEOUT
    }

    private final Socket peerSocket;
    private final UploadManager uploadManager;
    private final boolean isServer;
    
    private String hisNick = "Unknown";
    private String fileName = "None";
    private final AtomicLong bytesSent = new AtomicLong(0);
    private long totalSize = 0;
    private long startTime = 0;
    private final long connectionStartTime = System.currentTimeMillis();
    private volatile boolean isFinished = false;
    private volatile ConnectionState state = ConnectionState.HANDSHAKING;
    private String lastError = null;

    public interface UploadListener {
        void onUploadStarted(PeerConnectionHandler handler);
        void onUploadProgress(PeerConnectionHandler handler, long sent, long total);
        void onUploadFinished(PeerConnectionHandler handler);
        void onUploadError(PeerConnectionHandler handler, String error);
    }

    private UploadListener listener;

    // Set only for connections handshaken by the $ConnectToMe handler (we connected out).
    private MessageHandler preHandler = null;
    private String preNick = null;
    private String preLock = null;

    /** A $Get/$ADCGET that arrived during the handshake; served as the first request. */
    private DCMessage earlyRequest = null;
    /** True once this connection holds an upload slot (kept across requests on the same connection). */
    private boolean haveSlot = false;

    private static final long FIRST_REQUEST_TIMEOUT_MS = 15000;
    private static final long IDLE_REQUEST_TIMEOUT_MS = 60000;
    
    /** ADC uploads: the client-to-client connection (handshake already done), else null (NMDC). */
    private AdcClientConnection adc = null;

    public PeerConnectionHandler(Socket socket, UploadManager uploadManager) {
        this(socket, uploadManager, true);
    }

    /** An ADC peer downloading from us; the ADC handshake is done. Serves its CGETs like NMDC $ADCGETs. */
    public PeerConnectionHandler(AdcClientConnection conn, UploadManager uploadManager, String hisNick) {
        this(conn.socket, uploadManager, false);
        this.adc = conn;
        this.hisNick = hisNick != null ? hisNick : "ADC peer";
    }

    // ---- replies, NMDC or ADC ----

    /** How serveRequest() answers: NMDC ($ADCSND / $Error / $MaxedOut) or ADC (CSND / CSTA). */
    private interface Responder {
        void maxedOut() throws IOException;

        void notAvailable() throws IOException;

        void offsetError() throws IOException;

        void snd(String type, String identifier, long offset, long length) throws IOException;

        /** NMDC $Get only. */
        void fileLength(long length) throws IOException;

        /** NMDC $Get only: wait for the peer's $Send. */
        void awaitSend() throws IOException, InterruptedException;

        OutputStream out();
    }

    /** NMDC: exactly the strings this class always sent. */
    private Responder nmdcResponder(final MessageHandler handler) {
        return new Responder() {
            @Override public void maxedOut() { handler.send_msg("$MaxedOut"); }
            @Override public void notAvailable() { handler.send_msg("$Error File Not Available"); }
            @Override public void offsetError() { handler.send_msg("$Error Offset out of range"); }
            @Override public void snd(String type, String identifier, long offset, long length) {
                handler.send_msg("$ADCSND " + type + " " + DCMessage.escapeAdc(identifier) + " " + offset + " " + length);
            }
            @Override public void fileLength(long length) { handler.send_msg("$FileLength " + length); }
            @Override public void awaitSend() throws IOException, InterruptedException {
                long start = System.currentTimeMillis();
                DCMessage sendMsg = null;
                while (sendMsg == null && (System.currentTimeMillis() - start < FIRST_REQUEST_TIMEOUT_MS)) {
                    DCMessage m = handler.getNextMessage(1000, TimeUnit.MILLISECONDS);
                    if (m == null) continue;
                    if ("Send".equals(m.command)) {
                        sendMsg = m;
                        Log.d("andcpp", "Received $Send");
                    } else if ("HubQuit".equals(m.command)) {
                        throw new IOException("Peer closed connection before $Send");
                    }
                }
                if (sendMsg == null) {
                    throw new IOException("Peer did not acknowledge with $Send");
                }
            }
            @Override public OutputStream out() { return handler.os; }
        };
    }

    /** ADC: CSND, CSTA 151 (not available), 152 (part not available), 153 (no slots). */
    private Responder adcResponder(final AdcClientConnection conn) {
        return new Responder() {
            @Override public void maxedOut() throws IOException { conn.sendStatus("153", "No slots available"); }
            @Override public void notAvailable() throws IOException { conn.sendStatus("151", "File Not Available"); }
            @Override public void offsetError() throws IOException { conn.sendStatus("152", "File part not available"); }
            @Override public void snd(String type, String identifier, long offset, long length) throws IOException {
                conn.sendSnd(type, identifier, offset, length);
            }
            @Override public void fileLength(long length) { throw new IllegalStateException("not on ADC"); }
            @Override public void awaitSend() { throw new IllegalStateException("not on ADC"); }
            @Override public OutputStream out() { return conn.rawOut(); }
        };
    }

    /** A CGET as the DCMessage serveRequest() takes (the same fields an NMDC $ADCGET fills). Null if unusable. */
    static DCMessage requestFromCget(AdcCommand c) {
        if (c == null || c.type != 'C' || !"GET".equals(c.cmd) || c.params.size() < 4) return null;
        DCMessage m = new DCMessage();
        m.command = "ADCGET";
        m.adc_type = c.param(0);
        m.get_filename = c.param(1);
        long start, bytes;
        try {
            start = Long.parseLong(c.param(2));
            bytes = Long.parseLong(c.param(3));
        } catch (NumberFormatException e) {
            return null;
        }
        switch (m.adc_type) {
            case "file":
                m.startOffset = start + 1; // serveRequest uses the 1-based $Get convention
                m.bytesToTransfer = bytes;
                break;
            case "tthl":
                m.startOffset = start;
                m.bytesToTransfer = bytes;
                m.is_tthl = true;
                break;
            case "list":
                m.adc_recursive = "1".equals(c.named("RE", 4));
                break;
            default:
                return null;
        }
        return m;
    }

    /** ADC: read CGETs and serve them until the peer is done or idle. */
    private void runAdc() {
        try {
            state = ConnectionState.REQUESTING;
            boolean first = true;
            while (true) {
                peerSocket.setSoTimeout((int) (first ? FIRST_REQUEST_TIMEOUT_MS : IDLE_REQUEST_TIMEOUT_MS));
                AdcCommand c;
                try {
                    c = adc.readCommand();
                } catch (java.net.SocketTimeoutException e) {
                    if (first) throw new IOException("No CGET request received from " + hisNick);
                    Log.d("andcpp", "No further requests from " + hisNick + "; closing connection");
                    break;
                }
                if (c == null) break; // peer closed
                if (c.type != 'C' || !"GET".equals(c.cmd)) continue;
                DCMessage req = requestFromCget(c);
                if (req == null) {
                    adc.sendStatus("140", "Unsupported request");
                    continue;
                }
                first = false;
                peerSocket.setSoTimeout(0); // streaming: no read timeout
                state = ConnectionState.REQUESTING;
                if (!serveRequest(adcResponder(adc), req)) break;
            }
            isFinished = true;
            state = ConnectionState.COMPLETED;
            if (listener != null) listener.onUploadFinished(this);
        } catch (Exception e) {
            if (state == ConnectionState.UPLOADING) {
                uploadManager.incrementFailedTransfers();
            } else if (state != ConnectionState.HANDSHAKING && state != ConnectionState.FAILED) {
                uploadManager.incrementConnectionErrors();
            }
            state = ConnectionState.FAILED;
            lastError = e.getMessage() != null ? e.getMessage() : e.toString();
            Log.e("andcpp", "[CC] ADC upload to " + hisNick + " failed: " + lastError);
            if (listener != null) listener.onUploadError(this, lastError);
        } finally {
            isFinished = true;
            adc.close();
        }
    }

    public PeerConnectionHandler(Socket socket, UploadManager uploadManager, boolean isServer) {
        this.peerSocket = socket;
        this.uploadManager = uploadManager;
        this.isServer = isServer;
        
        if (uploadManager.getService() instanceof UploadListener) {
            this.listener = (UploadListener) uploadManager.getService();
        }
    }

    public PeerConnectionHandler(Socket socket, UploadManager uploadManager, MessageHandler existingHandler,
            String hisNick, String hisLock) {
        this(socket, uploadManager, false);
        this.preHandler = existingHandler;
        this.preNick = hisNick;
        this.preLock = hisLock;
    }

    public String getHisNick() { return hisNick; }

    /** Set when the connection never identified itself (no $MyNick): not counted or reported as an error. */
    private volatile boolean unidentified = false;

    /** True once the peer told us its nick ($MyNick), i.e. it is a real DC client. */
    public boolean isIdentified() {
        return hisNick != null && !hisNick.isEmpty() && !"Unknown".equals(hisNick);
    }
    public String getFileName() { return fileName; }
    public long getBytesSent() { return bytesSent.get(); }
    public long getTotalSize() { return totalSize; }
    public boolean isFinished() { return isFinished; }
    public ConnectionState getState() { return state; }
    public String getLastError() { return lastError; }

    public long getConnectionStartTime() { return connectionStartTime; }

    public double getSpeed() {
        if (state != ConnectionState.UPLOADING || startTime == 0) return 0.0;
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed <= 0) return 0.0;
        return (bytesSent.get() * 1000.0) / elapsed;
    }

    /** Set when the user (or the Wi-Fi only rule) stops this upload; the stream loop aborts on it. */
    private volatile String stopReason = null;

    /** Stops this upload and closes the connection. */
    public void stop(String reason) {
        stopReason = reason;
        Log.i("andcpp", "Stopping upload to " + hisNick + ": " + reason);
        close();
    }

    public void close() {
        try {
            peerSocket.close();
        } catch (IOException ignored) {}
    }

    @Override
    public void run() {
        if (adc != null) {
            runAdc();
            return;
        }
        MessageHandler handler = null;
        boolean intercepted = false;
        try {
            // Socket optimization similar to LibreTorrent
            peerSocket.setKeepAlive(true);
            peerSocket.setTcpNoDelay(true);
            peerSocket.setSoTimeout(30000); 
            peerSocket.setSendBufferSize(64 * 1024);
            peerSocket.setReceiveBufferSize(64 * 1024);

            if (preHandler != null) {
                handler = preHandler;
            } else if (isServer) {
                // Someone connected to our port: NMDC peers start with '$' ($MyNick), ADC peers with 'C' (CSUP),
                // encrypted ADC (ADCS) peers with a TLS handshake (0x16). TLS needs that byte left in place, so it
                // is peeked at, not read (Android 5+; older devices don't advertise ADCS, so no TLS arrives).
                if (SocketPeek.supported()) {
                    int peeked = SocketPeek.peekFirstByte(peerSocket, 30000);
                    if (peeked == SocketPeek.NOTHING) {
                        unidentified = true;
                        throw new IOException("Peer sent nothing");
                    }
                    if (peeked == 0x16) {
                        intercepted = true; // the ADCS code owns the socket now
                        uploadManager.getService().getAdcTransfers().onIncomingTls(peerSocket);
                        return;
                    }
                }
                int first;
                try {
                    first = peerSocket.getInputStream().read();
                } catch (IOException e) {
                    unidentified = true; // sent nothing at all: a port scanner, not a DC client
                    throw e;
                }
                if (first < 0) {
                    unidentified = true;
                    throw new IOException("Peer closed connection during handshake");
                }
                if (first == 'C') {
                    intercepted = true; // the ADC code owns the socket now
                    uploadManager.getService().getAdcTransfers().onIncoming(peerSocket, (byte) first);
                    return;
                }
                handler = new MessageHandler(peerSocket, new byte[] {(byte) first});
            } else {
                handler = new MessageHandler(peerSocket);
            }
            
            // Get our session to get our identity
            DCUser myUser = null;
            for (DCPPService.HubSession s : uploadManager.getService().getActiveSessions()) {
                myUser = s.myuser;
                break;
            }
            if (myUser == null) {
                uploadManager.incrementConnectionErrors();
                throw new IOException("No active hub sessions available for handshake identity");
            }

            try {
                if (!performHandshake(handler, myUser)) {
                    intercepted = true;
                    return;
                }
            } catch (Exception e) {
                if (isIdentified()) {
                    uploadManager.incrementHandshakeFailures(); // a real DC user's handshake went wrong
                } else {
                    // Never sent $MyNick: an internet scanner / probe hitting our public port, not a DC user.
                    unidentified = true;
                }
                throw e;
            }
            
            // No read timeout from here on: a socket read timeout permanently stops MessageHandler's reader
            // thread (it reports HubQuit), so a request arriving after a long upload would never be read.
            // Idle connections are closed by handleUploadRequests() instead.
            peerSocket.setSoTimeout(0);

            state = ConnectionState.REQUESTING;
            handleUploadRequests(handler);

            isFinished = true;
            state = ConnectionState.COMPLETED;
            if (listener != null) listener.onUploadFinished(this);

        } catch (Exception e) {
            if (state == ConnectionState.UPLOADING) {
                uploadManager.incrementFailedTransfers();
            } else if (state != ConnectionState.HANDSHAKING && state != ConnectionState.FAILED) {
                uploadManager.incrementConnectionErrors();
            }

            state = ConnectionState.FAILED;
            lastError = e.getMessage() != null ? e.getMessage() : e.toString();
            if (unidentified) {
                Log.d("andcpp", "Ignored unidentified connection from " + peerSocket.getRemoteSocketAddress()
                        + " (no DC handshake): " + lastError);
            } else {
                Log.e("andcpp", "PeerConnection Error: " + lastError);
                if (listener != null) listener.onUploadError(this, lastError);
            }
        } finally {
            isFinished = true;
            if (!intercepted) {
                if (handler != null) {
                    handler.close();
                } else {
                    try {
                        peerSocket.close();
                    } catch (IOException ignored) {}
                }
            }
        }
    }

    /** How long the server side waits for $Lock after $MyNick before sending its own $MyNick/$Lock first. */
    private static final long LOCK_GRACE_MS = 2000;
    /** Phase-2 messages that arrived while awaitLock() was still waiting for the peer's $Lock. */
    private boolean earlyKey = false;
    private DCMessage earlyDirection = null;

    /** Waits for the peer's $Lock (after we sent ours). Throws if it doesn't come before the deadline. */
    private String awaitLock(MessageHandler handler, long deadline) throws IOException, InterruptedException {
        while (System.currentTimeMillis() < deadline) {
            DCMessage m = handler.getNextMessage(1000, TimeUnit.MILLISECONDS);
            if (m == null) continue;
            if ("HubQuit".equals(m.command)) throw new IOException("Peer closed connection during handshake");
            if ("Lock".equals(m.command) && m.lock_s != null) {
                Log.d("andcpp", "Received $Lock (after our reply): " + m.lock_s);
                return m.lock_s;
            }
            // Keep what phase 2 waits for, in case it arrives before the $Lock.
            if ("Key".equals(m.command)) earlyKey = true;
            else if ("Direction".equals(m.command)) earlyDirection = m;
            else if ("Get".equals(m.command) || "ADCGET".equals(m.command)) earlyRequest = m;
            Log.d("andcpp", "Handshake: got $" + m.command + " from " + hisNick + " while waiting for $Lock");
        }
        throw new IOException("Handshake failed: Peer identity or challenge not received");
    }

    private boolean performHandshake(MessageHandler handler, DCUser myUser) throws IOException, InterruptedException {
        long handshakeTimeout = 15000;
        long startTime = System.currentTimeMillis();
        String hisLock = null;
        StringBuilder sentOrder = new StringBuilder();

        if (preHandler != null) {
            // We connected out after a $ConnectToMe. The handler already sent $MyNick/$Lock and received the
            // peer's, and found no pending download, so we continue as the uploader on the same connection.
            this.hisNick = preNick;
            hisLock = preLock;
            sentOrder.append("MyNick, Lock (by ConnectToMe handler), ");
            Log.d("andcpp", "Continuing pre-handshaken connection with " + hisNick);
        } else {
            Log.d("andcpp", "Starting handshake (isServer=" + isServer + ") with " + peerSocket.getRemoteSocketAddress());

            if (!isServer) {
                handler.send_mynick(myUser);
                handler.send_lock();
                sentOrder.append("MyNick, Lock, ");
            }

            // Handshake Phase 1: Identity Exchange.
            // As the server, some clients send only $MyNick and then wait for OUR $MyNick/$Lock before sending
            // their $Lock. Waiting for both first deadlocked those connections (each side waiting ~15 s), so once
            // the nick is in we wait only briefly for $Lock, answer, and collect their $Lock afterwards.
            DCMessage hisNickMsg = null;
            long nickAt = 0;
            while ((hisNickMsg == null || hisLock == null) && (System.currentTimeMillis() - startTime < handshakeTimeout)) {
                if (isServer && hisNickMsg != null && System.currentTimeMillis() - nickAt >= LOCK_GRACE_MS) {
                    break; // nick but no $Lock yet: reply first (below), then wait for their $Lock
                }
                DCMessage m = handler.getNextMessage(isServer && hisNickMsg != null ? 250 : 2000, TimeUnit.MILLISECONDS);
                if (m == null) continue;
                if ("HubQuit".equals(m.command)) throw new IOException("Peer closed connection during handshake");
                if ("MyNick".equals(m.command)) {
                    hisNickMsg = m;
                    nickAt = System.currentTimeMillis();
                    Log.d("andcpp", "Received $MyNick: " + m.hisinfo.nick);
                }
                if ("Lock".equals(m.command)) {
                    hisLock = m.lock_s;
                    Log.d("andcpp", "Received $Lock: " + m.lock_s);
                }
            }

            if (hisNickMsg == null || hisNickMsg.hisinfo == null || (hisLock == null && !isServer)) {
                throw new IOException("Handshake failed: Peer identity or challenge not received");
            }
            this.hisNick = hisNickMsg.hisinfo.nick;

            if (isServer) {
                com.phinvader.libjdcpp.DCDownloader.DownloadQueueEntity pendingDownload = null;
                com.phinvader.libjdcpp.DCClient matchClient = null;
                DCUser correctMyUser = null;
                for (DCPPService.HubSession s : uploadManager.getService().getActiveSessions()) {
                    if (s.client != null && s.client.downloadHandler != null
                            && s.client.downloadHandler.hasPendingFor(hisNick)) {
                        pendingDownload = s.client.downloadHandler.getDownloadEntityCaseInsensitive(hisNick);
                        if (pendingDownload != null) {
                            correctMyUser = s.myuser;
                            matchClient = s.client;
                            break;
                        }
                    }
                }
                if (pendingDownload != null) {
                    // The peer connected to our listening port because we sent it $ConnectToMe: this is our download.
                    Log.d("andcpp", "Intercepted incoming connection as pending active download for " + hisNick);
                    handler.send_mynick(correctMyUser);
                    handler.send_lock();
                    if (hisLock == null) hisLock = awaitLock(handler, startTime + handshakeTimeout);

                    DCMessage rlockMsg = new DCMessage();
                    rlockMsg.lock_s = hisLock;
                    matchClient.startDownloadingFile(pendingDownload, matchClient, rlockMsg, handler);

                    return false;
                }

                // Normal upload server path: answer with $MyNick then $Lock.
                handler.send_mynick(myUser);
                handler.send_lock();
                sentOrder.append("MyNick, Lock, ");
                if (hisLock == null) hisLock = awaitLock(handler, startTime + handshakeTimeout);
            }
        }

        // Handshake Phase 2: Direction and Key (after $Lock). As the uploader we send $Direction Upload.
        byte[] hisKey = DCFunctions.convert_lock_to_key(DCFunctions.toBytes(hisLock));
        handler.send_supports(java.util.Arrays.asList("MiniSlots", "XmlBZList", "ADCGet", "TTHL", "TTHF"));
        sentOrder.append("Supports, ");
        handler.send_direction(false);
        handler.send_key(hisKey);
        sentOrder.append("Direction Upload, Key");
        Log.d("andcpp", "Handshake send order with " + hisNick + ": " + sentOrder);

        // Wait for Peer's Key and Direction
        boolean hisKeyReceived = earlyKey;
        boolean hisDirReceived = earlyRequest != null;
        if (earlyDirection != null) {
            hisDirReceived = true;
            if (!earlyDirection.dir_download) {
                throw new IOException("Protocol conflict: Peer " + hisNick + " also wants to upload.");
            }
        }
        if (earlyRequest != null) hisKeyReceived = true; // a request means the handshake is done on their side
        startTime = System.currentTimeMillis();
        while ((!hisKeyReceived || !hisDirReceived) && (System.currentTimeMillis() - startTime < handshakeTimeout)) {
            DCMessage m = handler.getNextMessage(2000, TimeUnit.MILLISECONDS);
            if (m == null) continue;
            if ("Key".equals(m.command)) {
                hisKeyReceived = true;
                Log.d("andcpp", "Received $Key");
            }
            if ("Direction".equals(m.command)) {
                hisDirReceived = true;
                Log.d("andcpp", "Received $Direction: " + (m.dir_download ? "Download" : "Upload"));
                if (!m.dir_download) {
                    throw new IOException("Protocol conflict: Peer " + hisNick + " also wants to upload.");
                }
            }
            if ("Get".equals(m.command) || "ADCGET".equals(m.command)) {
                // Peer sent Get early, which implies they are the downloader. Keep it: it is the first request.
                hisDirReceived = true;
                earlyRequest = m;
                Log.d("andcpp", "Received early request: " + m.command);
                break;
            }
        }

        state = ConnectionState.CONNECTED;
        Log.d("andcpp", "Handshake completed successfully with " + hisNick);
        return true;
    }

    /**
     * Serves $Get/$ADCGET requests on this connection. Modern clients reuse one connection for several
     * requests (e.g. file list, then TTHL, then the file), so after each $ADCGET we wait for the next one.
     */
    private void handleUploadRequests(MessageHandler handler) throws IOException, InterruptedException {
        boolean first = true;
        while (true) {
            DCMessage req;
            if (first && earlyRequest != null) {
                req = earlyRequest;
                earlyRequest = null;
            } else {
                req = waitForRequest(handler, first ? FIRST_REQUEST_TIMEOUT_MS : IDLE_REQUEST_TIMEOUT_MS);
                if (req == null) {
                    if (first) {
                        throw new IOException("No $Get/ADCGET request received from " + hisNick);
                    }
                    Log.d("andcpp", "No further requests from " + hisNick + "; closing connection");
                    return;
                }
            }
            first = false;
            state = ConnectionState.REQUESTING;
            if (!serveRequest(nmdcResponder(handler), req)) {
                return;
            }
        }
    }

    /** Next $Get/$ADCGET, or null on timeout or when the peer closed the connection. */
    private DCMessage waitForRequest(MessageHandler handler, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            DCMessage m = handler.getNextMessage(1000, TimeUnit.MILLISECONDS);
            if (m == null) continue;
            if ("Get".equals(m.command) || "ADCGET".equals(m.command)) {
                return m;
            }
            if ("HubQuit".equals(m.command)) {
                Log.d("andcpp", "Peer " + hisNick + " closed the connection");
                return null;
            }
        }
        return null;
    }

    /**
     * Shortly after start the share index may not know every file yet. "$Error File Not Available" would make
     * the other client drop us as a source for good, so answer "busy" instead: it retries later.
     */
    private boolean replyNotReadyYet(Responder handler, String requestedFile) throws IOException {
        handler.maxedOut();
        Log.i("andcpp", "Share index not ready yet; sent $MaxedOut to " + hisNick + " for " + requestedFile
                + " (will retry)");
        return false;
    }

    /** Serves one request. Returns true to keep the connection open for further requests. */
    private boolean serveRequest(Responder handler, DCMessage req) throws IOException, InterruptedException {
        boolean adc = "ADCGET".equals(req.command);
        String requestedFile = req.get_filename;
        Log.d("andcpp", "Received request from " + hisNick + ": " + req.command
                + (adc ? " " + req.adc_type : "") + " " + requestedFile);

        // Wi-Fi only on mobile data, or a nick blocked from the upload menu: answer "busy" so the peer retries later.
        String refusal = uploadManager.uploadRefusalReason(hisNick);
        if (refusal != null) {
            handler.maxedOut();
            Log.i("andcpp", "Upload refused to " + hisNick + " (" + refusal + "); sent $MaxedOut");
            return false;
        }

        // Partial file list of one directory ("$ADCGET list /Share/Folder/ 0 -1 [RE1]"). Clients use this to
        // download a whole folder (e.g. from search results). Uncompressed XML; no slot needed.
        if (adc && "list".equals(req.adc_type)) {
            byte[] xml = uploadManager.buildPartialFileList(requestedFile, req.adc_recursive);
            if (xml == null && !uploadManager.isShareIndexReady()) {
                return replyNotReadyYet(handler, requestedFile);
            }
            if (xml == null) {
                handler.notAvailable();
                Log.w("andcpp", "Partial list requested for unknown directory: " + requestedFile);
                return true;
            }
            handler.snd("list", requestedFile, 0, xml.length);
            handler.out().write(xml);
            handler.out().flush();
            uploadManager.addUploadedBytes(xml.length);
            Log.d("andcpp", "Sent $ADCSND list " + requestedFile + (req.adc_recursive ? " (recursive)" : "")
                    + " 0 " + xml.length);
            return true;
        }

        File fileToUpload = (requestedFile != null) ? uploadManager.resolveSharedFile(requestedFile) : null;
        if ((fileToUpload == null || !fileToUpload.exists()) && !uploadManager.isShareIndexReady()) {
            return replyNotReadyYet(handler, requestedFile);
        }
        if (fileToUpload == null || !fileToUpload.exists()) {
            handler.notAvailable();
            Log.w("andcpp", "Requested file not available: " + requestedFile);
            return adc; // $ADCGET clients may ask for something else on the same connection
        }

        // TTHL: tree leaves, sent right after $ADCSND. No slot needed.
        if (req.is_tthl) {
            List<byte[]> leaves = com.phinmadvader.andcpp.util.TTH.getLeaves(fileToUpload);
            long len = leaves.size() * 24L;
            handler.snd("tthl", requestedFile, 0, len);
            Log.d("andcpp", "Sent $ADCSND tthl " + requestedFile + " 0 " + len);
            for (byte[] leaf : leaves) {
                handler.out().write(leaf);
            }
            handler.out().flush();
            return true;
        }

        long fileSize = fileToUpload.length();
        long offset = Math.max(0, req.startOffset - 1);
        if (offset > fileSize) {
            handler.offsetError();
            Log.w("andcpp", "Offset out of range: " + offset + "/" + fileSize + " for " + requestedFile);
            return adc;
        }
        long length = fileSize - offset;
        // Only $ADCGET carries a length (-1 = to end of file); a legacy $Get always means "to the end".
        if (adc && req.bytesToTransfer >= 0) {
            length = Math.min(length, req.bytesToTransfer);
        }

        // Our file list never needs a slot. Other files need one; a connection keeps its slot across requests.
        boolean isFileList = UploadManager.isFileListRequest(requestedFile);
        if (!isFileList && !haveSlot) {
            if (!uploadManager.requestSlot(this)) {
                uploadManager.releaseSlot(this); // do not leave this connection in the waiting queue
                handler.maxedOut();
                Log.d("andcpp", "Upload slots full; sent $MaxedOut to " + hisNick);
                return false;
            }
            haveSlot = true;
        }

        this.fileName = isFileList ? "File List" : fileToUpload.getName();
        this.totalSize = length;
        this.bytesSent.set(0);

        if (adc) {
            // $ADCGET: announce and start sending immediately (no $Send).
            handler.snd("file", requestedFile, offset, length);
            Log.d("andcpp", "Sent $ADCSND file " + requestedFile + " " + offset + " " + length);
        } else {
            // Legacy $Get: $FileLength, then wait for $Send.
            handler.fileLength(length);
            Log.d("andcpp", "Sent $FileLength " + length);
            handler.awaitSend();
        }

        streamFile(handler, fileToUpload, offset, length);

        if (!isFileList) {
            uploadManager.incrementCompletedTransfers();
        }
        state = ConnectionState.CONNECTED;
        Log.d("andcpp", "Upload complete: " + fileName + " to " + hisNick + " (" + length + " bytes)");
        return adc;
    }

    /** Streams [offset, offset+length) of the file to the peer with progress callbacks. */
    private void streamFile(Responder handler, File file, long offset, long length) throws IOException {
        state = ConnectionState.UPLOADING;
        Log.d("andcpp", "Starting upload: " + fileName + " to " + hisNick + " (Offset: " + offset + ", Size: " + length + ")");
        this.startTime = System.currentTimeMillis();
        if (listener != null) listener.onUploadStarted(this);

        try (FileInputStream fis = new FileInputStream(file)) {
            if (offset > 0) {
                long skipped = 0;
                while (skipped < offset) {
                    long s = fis.skip(offset - skipped);
                    if (s <= 0) break;
                    skipped += s;
                }
            }

            byte[] buffer = new byte[64 * 1024]; // Large buffer for better throughput
            int bytesRead;
            long totalSent = 0;
            long lastNotifyTime = 0;

            while (totalSent < length && !Thread.currentThread().isInterrupted()) {
                int toRead = (int) Math.min(buffer.length, length - totalSent);
                bytesRead = fis.read(buffer, 0, toRead);
                if (bytesRead == -1) break;

                handler.out().write(buffer, 0, bytesRead);
                totalSent += bytesRead;
                this.bytesSent.set(totalSent);
                uploadManager.addUploadedBytes(bytesRead);

                if (stopReason == null && (totalSent & 0xFFFFF) < bytesRead) { // about once per MiB
                    stopReason = uploadManager.uploadRefusalReason(hisNick);
                }
                if (stopReason != null) {
                    throw new IOException("Upload stopped: " + stopReason);
                }

                // Throttle progress updates to avoid flooding
                long now = System.currentTimeMillis();
                if (now - lastNotifyTime > 1000) {
                    if (listener != null) listener.onUploadProgress(this, totalSent, length);
                    lastNotifyTime = now;
                }
            }
            handler.out().flush();

            if (totalSent < length && !Thread.currentThread().isInterrupted()) {
                throw new IOException("Connection lost during transfer. Sent " + totalSent + "/" + length);
            }
        }
    }
}
