package com.phinmadvader.andcpp;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.phinmadvader.andcpp.util.TTH;

import android.util.Log;

public class UploadManager {
    
    private ServerSocket serverSocket;
    private boolean isListening = false;
    private Thread listenerThread;
    private final ExecutorService uploadSlotManager;
    private final DCPPService service;
    private int port = -1;
    
    private final Map<Long, PeerConnectionHandler> activeUploads = new ConcurrentHashMap<>();
    private final List<PeerConnectionHandler> transferringUploads = new ArrayList<>();
    private final List<PeerConnectionHandler> waitingQueue = new ArrayList<>();
    private int maxUploadSlots = Constants.DEFAULT_UPLOAD_SLOTS;

    // Statistics
    private final AtomicLong totalBytesUploaded = new AtomicLong(0);
    private final AtomicInteger completedTransfers = new AtomicInteger(0);
    private final AtomicInteger failedTransfers = new AtomicInteger(0);
    private final AtomicInteger connectionErrors = new AtomicInteger(0);
    private final AtomicInteger handshakeFailures = new AtomicInteger(0);

    private File cachedFileList = null;
    private long lastFileListUpdate = 0;
    private static final long FILE_LIST_CACHE_DURATION = TimeUnit.MINUTES.toMillis(5);

    public static class UploadStats {
        public double currentSpeed;
        public long totalBytes;
        public int activeCount;
        public int queueLength;
        public List<String> connectedPeers;
        public int completedCount;
        public int failedCount;
        public int connectionErrors;
        public int handshakeFailures;
    }

    public UploadStats getUploadStats() {
        UploadStats stats = new UploadStats();
        stats.totalBytes = totalBytesUploaded.get();
        stats.completedCount = completedTransfers.get();
        stats.failedCount = failedTransfers.get();
        stats.connectionErrors = connectionErrors.get();
        stats.handshakeFailures = handshakeFailures.get();

        double totalSpeed = 0;
        List<String> peers = new ArrayList<>();
        int active = 0;

        synchronized (this) {
            stats.queueLength = waitingQueue.size();
            for (PeerConnectionHandler h : activeUploads.values()) {
                totalSpeed += h.getSpeed();
                peers.add(h.getHisNick());
                if (h.getState() == PeerConnectionHandler.ConnectionState.UPLOADING) {
                    active++;
                }
            }
        }
        stats.currentSpeed = totalSpeed;
        stats.activeCount = active;
        stats.connectedPeers = peers;
        return stats;
    }

    public void addUploadedBytes(long bytes) {
        totalBytesUploaded.addAndGet(bytes);
    }

    public void incrementCompletedTransfers() {
        completedTransfers.incrementAndGet();
    }

    public void incrementFailedTransfers() {
        failedTransfers.incrementAndGet();
    }

    public void incrementConnectionErrors() {
        connectionErrors.incrementAndGet();
    }

    public void incrementHandshakeFailures() {
        handshakeFailures.incrementAndGet();
    }
    
    private String cid = null;

    public UploadManager(DCPPService service) {
        this.service = service;
        this.maxUploadSlots = Settings.getUploadSlots(service);
        // LibreTorrent-like thread pool management: separate pool for network I/O
        this.uploadSlotManager = Executors.newCachedThreadPool();
        
        // Ensure persistent CID
        android.content.SharedPreferences prefs = service.getSharedPreferences(Constants.PREFS_NAME, 0);
        cid = prefs.getString("cid", null);
        if (cid == null) {
            cid = java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase();
            prefs.edit().putString("cid", cid).apply();
            Log.d("andcpp", "Generated new CID: " + cid);
        }
    }

    public boolean isListening() {
        return isListening && serverSocket != null && serverSocket.isBound();
    }
    
    public void startListening(int port) {
        // We no longer synchronously poll PIA here. DCPPService's handlePIAPortBound 
        // calls startListening(piaPort) explicitly to coordinate updates.
        
        if (isListening && this.port == port) return;
        
        // If already listening on a different port, stop first
        if (isListening) {
            stopListeningOnly();
        }

        this.port = port;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            // Optimization: Bind to all interfaces (0.0.0.0)
            serverSocket.bind(new java.net.InetSocketAddress(port));
            
            isListening = true;
            listenerThread = new Thread(() -> {
                while (isListening && !Thread.currentThread().isInterrupted()) {
                    try {
                        Socket peerSocket = serverSocket.accept();
                        
                        // Socket configuration inspired by LibreTorrent/libtorrent
                        peerSocket.setKeepAlive(true);
                        peerSocket.setTcpNoDelay(true); // Disable Nagle's algorithm for better latency
                        peerSocket.setSoTimeout(30000); 
                        peerSocket.setSendBufferSize(64 * 1024);
                        peerSocket.setReceiveBufferSize(64 * 1024);

                        Log.d("andcpp", "Accepted incoming peer connection from " + peerSocket.getInetAddress());
                        registerAndExecute(new PeerConnectionHandler(peerSocket, this));
                    } catch (IOException e) {
                        if (isListening) {
                            Log.e("andcpp", "Error accepting peer connection", e);
                            // Brief sleep to avoid tight loop on persistent errors
                            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                        }
                    }
                }
            }, "UploadListener");
            listenerThread.setPriority(Thread.MAX_PRIORITY);
            listenerThread.start();
            Log.d("andcpp", "UploadManager listening on port " + port);
        } catch (IOException e) {
            Log.e("andcpp", "Failed to start UploadManager listener on port " + port, e);
            isListening = false;
        }
    }

    private void stopListeningOnly() {
        isListening = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e("andcpp", "Error closing ServerSocket", e);
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
            try {
                listenerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    public void stopListening() {
        stopListeningOnly();
        uploadSlotManager.shutdown();
        try {
            if (!uploadSlotManager.awaitTermination(2, TimeUnit.SECONDS)) {
                uploadSlotManager.shutdownNow();
            }
        } catch (InterruptedException e) {
            uploadSlotManager.shutdownNow();
        }

        // Prevention of resource leaks: close all active sockets
        for (PeerConnectionHandler h : activeUploads.values()) {
            h.close();
        }
        activeUploads.clear();
        synchronized (this) {
            transferringUploads.clear();
            waitingQueue.clear();
        }
    }

    private void registerAndExecute(PeerConnectionHandler handler) {
        long id = System.identityHashCode(handler);
        activeUploads.put(id, handler);
        uploadSlotManager.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    handler.run();
                } finally {
                    activeUploads.remove(id);
                    releaseSlot(handler);
                }
            }
        });
    }

    public synchronized boolean requestSlot(PeerConnectionHandler handler) {
        // Refresh max slots from settings to handle dynamic changes
        this.maxUploadSlots = Settings.getUploadSlots(service);

        if (transferringUploads.size() < maxUploadSlots) {
            // If we are in the queue, we can only take a slot if we are at the front
            if (waitingQueue.isEmpty() || waitingQueue.get(0).equals(handler)) {
                waitingQueue.remove(handler);
                if (!transferringUploads.contains(handler)) {
                    transferringUploads.add(handler);
                }
                return true;
            }
        }

        // No slot available or not our turn, add to waiting queue if not already there
        if (!waitingQueue.contains(handler)) {
            waitingQueue.add(handler);
        }
        return false;
    }

    public synchronized void releaseSlot(PeerConnectionHandler handler) {
        transferringUploads.remove(handler);
        waitingQueue.remove(handler);
        this.notifyAll();
    }

    public void cleanupZombieConnections() {
        try {
            long now = System.currentTimeMillis();
            for (PeerConnectionHandler h : activeUploads.values()) {
                if (h.isFinished()) {
                    activeUploads.values().remove(h);
                    continue;
                }

                long elapsed = now - h.getConnectionStartTime();
                
                // Handshake should not take more than 30 seconds
                if (h.getState() == PeerConnectionHandler.ConnectionState.HANDSHAKING && elapsed > 30000) {
                    Log.d("andcpp", "Closing zombie handshaking connection from " + h.getHisNick());
                    h.close();
                }
            }
        } catch (Exception e) {
            Log.e("andcpp", "Exception in cleanupZombieConnections", e);
        }
    }

    public synchronized int getFreeSlots() {
        int transferringCount = transferringUploads.size();
        return Math.max(0, maxUploadSlots - transferringCount);
    }

    public synchronized int getMaxSlots() {
        return maxUploadSlots;
    }

    public synchronized int getQueuePosition(PeerConnectionHandler handler) {
        int index = waitingQueue.indexOf(handler);
        return index == -1 ? -1 : index + 1;
    }

    public synchronized List<PeerConnectionHandler> getTransferringUploads() {
        return new ArrayList<>(transferringUploads);
    }

    public List<PeerConnectionHandler> getHandshakingUploads() {
        List<PeerConnectionHandler> list = new ArrayList<>();
        synchronized (this) {
            for (PeerConnectionHandler h : activeUploads.values()) {
                if (!transferringUploads.contains(h) && !waitingQueue.contains(h)) {
                    list.add(h);
                }
            }
        }
        return list;
    }

    public void handlePassiveUploadConnection(Socket s) {
        try {
            s.setSoTimeout(30000);
            registerAndExecute(new PeerConnectionHandler(s, this, false));
        } catch (SocketException e) {
            Log.e("andcpp", "Failed to set socket timeout for passive upload", e);
        }
    }

    public void handleClientUploadConnection(Socket s) {
        try {
            s.setSoTimeout(30000);
            registerAndExecute(new PeerConnectionHandler(s, this, false));
        } catch (SocketException e) {
            Log.e("andcpp", "Failed to set socket timeout for client upload", e);
        }
    }
    
    public List<PeerConnectionHandler> getActiveUploads() {
        return new ArrayList<>(activeUploads.values());
    }
    
    public int getPort() {
        return port;
    }
    
    public synchronized File resolveSharedFile(String requestPath) {
        String downloadDir = Settings.getDCDownloadsDirectory(service);
        
        // Optimized file list handling with caching
        if (requestPath.toLowerCase().contains("files.xml") || requestPath.toLowerCase().endsWith(".dclst")) {
             long now = System.currentTimeMillis();
             if (cachedFileList == null || !cachedFileList.exists() || (now - lastFileListUpdate > FILE_LIST_CACHE_DURATION)) {
                 File fileListDir = new File(downloadDir, "FileLists");
                 if (!fileListDir.exists()) fileListDir.mkdirs();
                 cachedFileList = new File(fileListDir, "my_files.xml");
                 generateFileListXML(new File(downloadDir), cachedFileList);
                 lastFileListUpdate = now;
             }
             return cachedFileList;
        }

        // The user's files are under a virtual "Share" directory in the filelist
        if (requestPath.startsWith("Share\\") || requestPath.startsWith("Share/")) {
            requestPath = requestPath.substring(6); // remove "Share\"
        }

        File requested = new File(downloadDir, requestPath);
        if (requested.exists() && requested.isFile()) {
            return requested;
        }
        
        return null;
    }

    private void generateFileListXML(File rootDir, File outputFile) {
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outputFile);
            org.xmlpull.v1.XmlSerializer serializer = android.util.Xml.newSerializer();
            serializer.setOutput(fos, "UTF-8");
            serializer.startDocument(null, true);
            serializer.startTag(null, "FileListing");
            serializer.attribute(null, "Version", "1");
            
            serializer.attribute(null, "CID", cid);
            
            serializer.attribute(null, "Base", "/");
            serializer.attribute(null, "Generator", "AnDCpp");
            
            serializer.startTag(null, "Directory");
            serializer.attribute(null, "Name", "Share");
            
            addFilesToXml(rootDir, serializer);
            
            serializer.endTag(null, "Directory");
            serializer.endTag(null, "FileListing");
            serializer.endDocument();
            serializer.flush();
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addFilesToXml(File dir, org.xmlpull.v1.XmlSerializer serializer) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().equals("FileLists")) continue;
            if (f.isDirectory()) {
                serializer.startTag(null, "Directory");
                serializer.attribute(null, "Name", f.getName());
                addFilesToXml(f, serializer);
                serializer.endTag(null, "Directory");
            } else {
                serializer.startTag(null, "File");
                serializer.attribute(null, "Name", f.getName());
                serializer.attribute(null, "Size", String.valueOf(f.length()));
                try {
                    String tth = TTH.calculateTTH(f);
                    serializer.attribute(null, "TTH", tth);
                } catch (IOException e) {
                    Log.e("andcpp", "Failed to calculate TTH for " + f.getName(), e);
                }
                serializer.endTag(null, "File");
            }
        }
    }
    
    public DCPPService getService() {
        return service;
    }

    public long getSharedSize() {
        String downloadDir = Settings.getDCDownloadsDirectory(service);
        return getDirectorySize(new File(downloadDir));
    }

    private long getDirectorySize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().equals("FileLists")) continue;
                if (f.isFile()) {
                    size += f.length();
                } else {
                    size += getDirectorySize(f);
                }
            }
        }
        return size;
    }
}
