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
                uploadManager.incrementHandshakeFailures();
                throw e;
            }
            
            state = ConnectionState.REQUESTING;
            handleUploadRequest(handler);
            
            isFinished = true;
            state = ConnectionState.COMPLETED;
            uploadManager.incrementCompletedTransfers();
            if (listener != null) listener.onUploadFinished(this);

        } catch (Exception e) {
            if (state == ConnectionState.UPLOADING) {
                uploadManager.incrementFailedTransfers();
            } else if (state != ConnectionState.HANDSHAKING && state != ConnectionState.FAILED) {
                uploadManager.incrementConnectionErrors();
            }

            state = ConnectionState.FAILED;
            lastError = e.getMessage() != null ? e.getMessage() : e.toString();
            Log.e("andcpp", "PeerConnection Error: " + lastError);
            if (listener != null) listener.onUploadError(this, lastError);
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
                // Peer sent Get early, which implies they are the downloader
                hisDirReceived = true;
                Log.d("andcpp", "Received early request: " + m.command);
                break;
            }
        }

        state = ConnectionState.CONNECTED;
        Log.d("andcpp", "Handshake completed successfully with " + hisNick);
        return true;
    }

    private void handleUploadRequest(MessageHandler handler) throws IOException, InterruptedException {
        // Handshake Phase 3: Request Handling
        DCMessage nextMsg = null;
        long timeout = 15000;
        long startTime = System.currentTimeMillis();
        
        while (nextMsg == null && (System.currentTimeMillis() - startTime < timeout)) {
            DCMessage m = handler.getNextMessage(1000, TimeUnit.MILLISECONDS);
            if (m == null) continue;
            if ("Get".equals(m.command) || "ADCGET".equals(m.command)) {
                nextMsg = m;
            } else if ("HubQuit".equals(m.command)) {
                throw new IOException("Peer closed connection while waiting for request");
            }
        }
        
        if (nextMsg == null) {
            throw new IOException("No $Get/ADCGET request received from " + hisNick);
        }

        String requestedFile = nextMsg.get_filename;
        boolean isTTHL = nextMsg.is_tthl;

        if (isTTHL) {
            Log.d("andcpp", "Received TTHL request for: " + requestedFile);
            File fileToUpload = uploadManager.resolveSharedFile(requestedFile);
            if (fileToUpload == null || !fileToUpload.exists()) {
                handler.send_msg("$Error File Not Available");
                throw new IOException("Requested file for TTHL not available: " + requestedFile);
            }
            
            List<byte[]> leaves = com.phinmadvader.andcpp.util.TTH.getLeaves(fileToUpload);
            long totalBytes = leaves.size() * 24;
            handler.send_msg("$FileLength " + totalBytes);
            
            // Wait for $Send
            startTime = System.currentTimeMillis();
            DCMessage sendMsg = null;
            while (sendMsg == null && (System.currentTimeMillis() - startTime < timeout)) {
                DCMessage m = handler.getNextMessage(1000, TimeUnit.MILLISECONDS);
                if (m == null) continue;
                if ("Send".equals(m.command)) {
                    sendMsg = m;
                    Log.d("andcpp", "Received $Send for TTHL");
                }
            }

            if (sendMsg == null) {
                throw new IOException("Peer did not acknowledge TTHL with $Send");
            }

            for (byte[] leaf : leaves) {
                handler.os.write(leaf);
            }
            handler.os.flush();
            Log.d("andcpp", "Sent TTHL leaves for " + requestedFile);
            return;
        }

        long offset = Math.max(0, nextMsg.startOffset - 1);

        Log.d("andcpp", "Received request: " + nextMsg.command + " " + requestedFile + " offset=" + offset);

        File fileToUpload = uploadManager.resolveSharedFile(requestedFile);
        if (fileToUpload == null || !fileToUpload.exists()) {
            handler.send_msg("$Error File Not Available");
            throw new IOException("Requested file not available: " + requestedFile);
        }

        this.fileName = fileToUpload.getName();
        long actualFileSize = fileToUpload.length();
        
        if (offset >= actualFileSize && actualFileSize > 0) {
            handler.send_msg("$Error Offset out of range");
            throw new IOException("Offset out of range: " + offset + "/" + actualFileSize);
        }

        long sendSize = actualFileSize - offset;
        if ("ADCGET".equals(nextMsg.command) && nextMsg.bytesToTransfer > 0) {
            sendSize = Math.min(sendSize, nextMsg.bytesToTransfer);
        }
        
        this.totalSize = sendSize;

        // Check slots before sending $FileLength
        if (!uploadManager.requestSlot(this)) {
            state = ConnectionState.QUEUED;
            int pos = uploadManager.getQueuePosition(this);
            Log.d("andcpp", "Upload slots full. Peer " + hisNick + " queued at position " + pos);
            
            // NMDC slot full response
            handler.send_msg("$MaxSlotsReached");
            
            // Wait for slot
            while (!uploadManager.requestSlot(this) && !Thread.currentThread().isInterrupted()) {
                if (listener != null) listener.onUploadProgress(this, 0, sendSize);
                synchronized (uploadManager) {
                    try {
                        uploadManager.wait(10000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Upload cancelled while in queue");
        }

        handler.send_msg("$FileLength " + sendSize);
        Log.d("andcpp", "Sent $FileLength " + sendSize);

        // Wait for $Send
        startTime = System.currentTimeMillis();
        DCMessage sendMsg = null;
        while (sendMsg == null && (System.currentTimeMillis() - startTime < timeout)) {
            DCMessage m = handler.getNextMessage(1000, TimeUnit.MILLISECONDS);
            if (m == null) continue;
            if ("Send".equals(m.command)) {
                sendMsg = m;
                Log.d("andcpp", "Received $Send");
            }
        }

        if (sendMsg == null) {
            throw new IOException("Peer did not acknowledge with $Send");
        }

        // Transfer Phase
        state = ConnectionState.UPLOADING;
        Log.d("andcpp", "Starting upload: " + fileName + " to " + hisNick + " (Offset: " + offset + ", Size: " + sendSize + ")");
        this.startTime = System.currentTimeMillis();
        if (listener != null) listener.onUploadStarted(this);

        try (FileInputStream fis = new FileInputStream(fileToUpload)) {
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

            while (totalSent < sendSize && !Thread.currentThread().isInterrupted()) {
                int toRead = (int) Math.min(buffer.length, sendSize - totalSent);
                bytesRead = fis.read(buffer, 0, toRead);
                if (bytesRead == -1) break;

                handler.os.write(buffer, 0, bytesRead);
                totalSent += bytesRead;
                this.bytesSent.set(totalSent);
                uploadManager.addUploadedBytes(bytesRead);

                // Throttle progress updates to avoid flooding
                long now = System.currentTimeMillis();
                if (now - lastNotifyTime > 1000) {
                    if (listener != null) listener.onUploadProgress(this, totalSent, sendSize);
                    lastNotifyTime = now;
                }
            }
            handler.os.flush();
            
            if (totalSent < sendSize && !Thread.currentThread().isInterrupted()) {
                throw new IOException("Connection lost during transfer. Sent " + totalSent + "/" + sendSize);
            }
        }
        
        Log.d("andcpp", "Upload complete: " + fileName);
    }
}
