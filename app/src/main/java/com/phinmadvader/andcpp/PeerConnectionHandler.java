package com.phinmadvader.andcpp;

import android.util.Log;

import com.phinvader.libjdcpp.DCFunctions;
import com.phinvader.libjdcpp.DCMessage;
import com.phinvader.libjdcpp.DCUser;
import com.phinvader.libjdcpp.MessageHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
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
    
    public PeerConnectionHandler(Socket socket, UploadManager uploadManager) {
        this(socket, uploadManager, true);
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

    public void close() {
        try {
            peerSocket.close();
        } catch (IOException ignored) {}
    }

    @Override
    public void run() {
        MessageHandler handler = null;
        boolean intercepted = false;
        try {
            // Socket optimization similar to LibreTorrent
            peerSocket.setKeepAlive(true);
            peerSocket.setTcpNoDelay(true);
            peerSocket.setSoTimeout(30000); 
            peerSocket.setSendBufferSize(64 * 1024);
            peerSocket.setReceiveBufferSize(64 * 1024);

            handler = (preHandler != null) ? preHandler : new MessageHandler(peerSocket);
            
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

            // Handshake Phase 1: Identity Exchange
            DCMessage hisNickMsg = null;
            while ((hisNickMsg == null || hisLock == null) && (System.currentTimeMillis() - startTime < handshakeTimeout)) {
                DCMessage m = handler.getNextMessage(2000, TimeUnit.MILLISECONDS);
                if (m == null) continue;
                if ("HubQuit".equals(m.command)) throw new IOException("Peer closed connection during handshake");
                if ("MyNick".equals(m.command)) {
                    hisNickMsg = m;
                    Log.d("andcpp", "Received $MyNick: " + m.hisinfo.nick);
                }
                if ("Lock".equals(m.command)) {
                    hisLock = m.lock_s;
                    Log.d("andcpp", "Received $Lock: " + m.lock_s);
                }
            }

            if (hisNickMsg == null || hisNickMsg.hisinfo == null || hisLock == null) {
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

                    DCMessage rlockMsg = new DCMessage();
                    rlockMsg.lock_s = hisLock;
                    matchClient.startDownloadingFile(pendingDownload, matchClient, rlockMsg, handler);

                    return false;
                }

                // Normal upload server path: answer with $MyNick then $Lock.
                handler.send_mynick(myUser);
                handler.send_lock();
                sentOrder.append("MyNick, Lock, ");
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
        boolean hisKeyReceived = false;
        boolean hisDirReceived = false;
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
            if (!serveRequest(handler, req)) {
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
    private boolean replyNotReadyYet(MessageHandler handler, String requestedFile) {
        handler.send_msg("$MaxedOut");
        Log.i("andcpp", "Share index not ready yet; sent $MaxedOut to " + hisNick + " for " + requestedFile
                + " (will retry)");
        return false;
    }

    /** Serves one request. Returns true to keep the connection open for further requests. */
    private boolean serveRequest(MessageHandler handler, DCMessage req) throws IOException, InterruptedException {
        boolean adc = "ADCGET".equals(req.command);
        String requestedFile = req.get_filename;
        Log.d("andcpp", "Received request from " + hisNick + ": " + req.command
                + (adc ? " " + req.adc_type : "") + " " + requestedFile);

        // Partial file list of one directory ("$ADCGET list /Share/Folder/ 0 -1 [RE1]"). Clients use this to
        // download a whole folder (e.g. from search results). Uncompressed XML; no slot needed.
        if (adc && "list".equals(req.adc_type)) {
            byte[] xml = uploadManager.buildPartialFileList(requestedFile, req.adc_recursive);
            if (xml == null && !uploadManager.isShareIndexReady()) {
                return replyNotReadyYet(handler, requestedFile);
            }
            if (xml == null) {
                handler.send_msg("$Error File Not Available");
                Log.w("andcpp", "Partial list requested for unknown directory: " + requestedFile);
                return true;
            }
            handler.send_msg("$ADCSND list " + DCMessage.escapeAdc(requestedFile) + " 0 " + xml.length);
            handler.os.write(xml);
            handler.os.flush();
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
            handler.send_msg("$Error File Not Available");
            Log.w("andcpp", "Requested file not available: " + requestedFile);
            return adc; // $ADCGET clients may ask for something else on the same connection
        }

        // TTHL: tree leaves, sent right after $ADCSND. No slot needed.
        if (req.is_tthl) {
            List<byte[]> leaves = com.phinmadvader.andcpp.util.TTH.getLeaves(fileToUpload);
            long len = leaves.size() * 24L;
            handler.send_msg("$ADCSND tthl " + DCMessage.escapeAdc(requestedFile) + " 0 " + len);
            Log.d("andcpp", "Sent $ADCSND tthl " + requestedFile + " 0 " + len);
            for (byte[] leaf : leaves) {
                handler.os.write(leaf);
            }
            handler.os.flush();
            return true;
        }

        long fileSize = fileToUpload.length();
        long offset = Math.max(0, req.startOffset - 1);
        if (offset > fileSize) {
            handler.send_msg("$Error Offset out of range");
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
                handler.send_msg("$MaxedOut");
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
            handler.send_msg("$ADCSND file " + DCMessage.escapeAdc(requestedFile) + " " + offset + " " + length);
            Log.d("andcpp", "Sent $ADCSND file " + requestedFile + " " + offset + " " + length);
        } else {
            // Legacy $Get: $FileLength, then wait for $Send.
            handler.send_msg("$FileLength " + length);
            Log.d("andcpp", "Sent $FileLength " + length);
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

        streamFile(handler, fileToUpload, offset, length);

        if (!isFileList) {
            uploadManager.incrementCompletedTransfers();
        }
        state = ConnectionState.CONNECTED;
        Log.d("andcpp", "Upload complete: " + fileName + " to " + hisNick + " (" + length + " bytes)");
        return adc;
    }

    /** Streams [offset, offset+length) of the file to the peer with progress callbacks. */
    private void streamFile(MessageHandler handler, File file, long offset, long length) throws IOException {
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

                handler.os.write(buffer, 0, bytesRead);
                totalSent += bytesRead;
                this.bytesSent.set(totalSent);
                uploadManager.addUploadedBytes(bytesRead);

                // Throttle progress updates to avoid flooding
                long now = System.currentTimeMillis();
                if (now - lastNotifyTime > 1000) {
                    if (listener != null) listener.onUploadProgress(this, totalSent, length);
                    lastNotifyTime = now;
                }
            }
            handler.os.flush();

            if (totalSent < length && !Thread.currentThread().isInterrupted()) {
                throw new IOException("Connection lost during transfer. Sent " + totalSent + "/" + length);
            }
        }
    }
}
