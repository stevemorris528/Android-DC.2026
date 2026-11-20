package com.phinmadvader.andcpp;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.phinmadvader.andcpp.util.TTH;
import com.phinvader.libjdcpp.MessageHandler;

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

    // ---- Own file list (share index) ----
    /** Name shown in "Saved File Lists" for our own list. */
    public static final String MY_FILE_LIST_NAME = "My File List";
    private static final long FILE_LIST_REFRESH_MINUTES = 15;
    private static final long FILE_LIST_AFTER_DOWNLOAD_DELAY_SECONDS = 60;

    private final Object fileListLock = new Object();
    private volatile long cachedShareSize = -1;
    /** "absolutePath|size|lastModified" -> TTH, persisted so restarts don't re-hash the share. */
    private final Map<String, String> tthCache = new ConcurrentHashMap<>();
    /** TTH -> file, for "TTH/<hash>" requests. Replaced wholesale after each refresh. */
    private volatile Map<String, File> fileByTth = new ConcurrentHashMap<>();
    /** In-memory share tree of the last published list, for partial ($ADCGET list) requests. */
    private volatile ShareDir shareTree = null;
    private java.util.concurrent.ScheduledExecutorService fileListScheduler;
    private final java.util.concurrent.atomic.AtomicBoolean quickRefreshPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean soonRefreshPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** While hashing a big share, republish the partial list this often so files appear progressively. */
    private static final long PARTIAL_PUBLISH_MILLIS = 30000L;

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

    public long getTotalBytesUploaded() {
        return totalBytesUploaded.get();
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

        startFileListRefresher();
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
        if (fileListScheduler != null) {
            fileListScheduler.shutdownNow();
            fileListScheduler = null;
        }
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

    /** Upload connection already handshaken ($MyNick/$Lock exchanged) by the $ConnectToMe handler. */
    public void handleHandshakenUploadConnection(Socket s, MessageHandler h, String hisNick, String hisLock) {
        try {
            s.setSoTimeout(30000);
        } catch (SocketException e) {
            Log.e("andcpp", "Failed to set socket timeout for handshaken upload", e);
        }
        registerAndExecute(new PeerConnectionHandler(s, this, h, hisNick, hisLock));
    }
    
    public List<PeerConnectionHandler> getActiveUploads() {
        return new ArrayList<>(activeUploads.values());
    }
    
    public int getPort() {
        return port;
    }
    
    /** True for requests of our own file list (files.xml / files.xml.bz2 / legacy MyList.DcLst). */
    public static boolean isFileListRequest(String requestPath) {
        if (requestPath == null) return false;
        String p = requestPath.trim().toLowerCase(java.util.Locale.ROOT);
        return p.equals("files.xml") || p.equals("files.xml.bz2") || p.endsWith(".dclst");
    }

    public File resolveSharedFile(String requestPath) {
        if (requestPath == null) return null;
        requestPath = requestPath.trim();
        String downloadDir = Settings.getDCDownloadsDirectory(service);

        // Our file list: always served from the pre-built copy (refreshed in the background).
        if (isFileListRequest(requestPath)) {
            File f = requestPath.toLowerCase(java.util.Locale.ROOT).endsWith(".bz2")
                    ? myFileListBz2() : myFileListXml(downloadDir);
            if (!f.exists()) {
                Log.w("andcpp", "File list requested before the first one was built: " + requestPath);
                requestFileListRefresh();
                return null;
            }
            return f;
        }

        // Download by hash (TTHF): "TTH/<base32>"
        if (requestPath.regionMatches(true, 0, "TTH/", 0, 4)) {
            File f = fileByTth.get(requestPath.substring(4).trim().toUpperCase(java.util.Locale.ROOT));
            return (f != null && f.isFile()) ? f : null;
        }

        // $ADCGET paths are absolute, e.g. "/Share/Music/a.mp3".
        while (requestPath.startsWith("/")) {
            requestPath = requestPath.substring(1);
        }

        // The user's files are under a virtual "Share" directory in the file list.
        if (requestPath.startsWith("Share\\") || requestPath.startsWith("Share/")) {
            requestPath = requestPath.substring(6);
        }
        // DC paths use '\' separators.
        requestPath = requestPath.replace('\\', '/');

        try {
            File root = new File(downloadDir).getCanonicalFile();
            File requested = new File(root, requestPath).getCanonicalFile();
            // Never serve anything outside the shared folder (e.g. "..\..\") or other users' saved lists.
            String rootPath = root.getPath() + File.separator;
            String fileListsPath = new File(root, "FileLists").getPath() + File.separator;
            if (!requested.getPath().startsWith(rootPath) || requested.getPath().startsWith(fileListsPath)) {
                Log.w("andcpp", "Rejected request outside share: " + requestPath);
                return null;
            }
            if (requested.isFile()) {
                return requested;
            }
        } catch (IOException e) {
            Log.w("andcpp", "Could not resolve requested path: " + requestPath, e);
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // File list generation
    // ---------------------------------------------------------------------------------------------

    private static final class ShareFile {
        final String name;
        final File file;
        final long size;
        String tth;
        ShareFile(File f) {
            this.name = f.getName();
            this.file = f;
            this.size = f.length();
        }
    }

    private static final class ShareDir {
        final String name;
        final List<ShareDir> dirs = new ArrayList<>();
        final List<ShareFile> files = new ArrayList<>();
        ShareDir(String name) {
            this.name = name;
        }
    }

    /** Plain XML copy, kept in FileLists so it shows up under "Saved File Lists". */
    private File myFileListXml(String downloadDir) {
        File dir = new File(downloadDir, "FileLists");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, MY_FILE_LIST_NAME + ".xml");
    }

    /** Compressed copy served as files.xml.bz2; kept in app storage. */
    private File myFileListBz2() {
        return new File(service.getFilesDir(), "files.xml.bz2");
    }

    private File tthCacheFile() {
        // v2: hashes from the old (incorrect) Tiger implementation must never be reused.
        new File(service.getFilesDir(), "tth_cache.txt").delete();
        return new File(service.getFilesDir(), "tth_cache_v2.txt");
    }

    private void startFileListRefresher() {
        if (fileListScheduler != null) return;
        fileListScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FileListRefresher");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        // First build shortly after start (after interrupted downloads are restored), then every 15 minutes.
        fileListScheduler.scheduleWithFixedDelay(this::refreshFileListSafe,
                10, TimeUnit.MINUTES.toSeconds(FILE_LIST_REFRESH_MINUTES), TimeUnit.SECONDS);
    }

    /** Rebuild soon (debounced), e.g. after a download finished. */
    public void requestFileListRefresh() {
        java.util.concurrent.ScheduledExecutorService s = fileListScheduler;
        if (s == null || s.isShutdown()) return;
        if (quickRefreshPending.compareAndSet(false, true)) {
            try {
                s.schedule(() -> {
                    quickRefreshPending.set(false);
                    refreshFileListSafe();
                }, FILE_LIST_AFTER_DOWNLOAD_DELAY_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                quickRefreshPending.set(false);
            }
        }
    }

    /** Rebuild within a few seconds: app opened, or storage permission just granted. */
    public void requestFileListRefreshSoon() {
        java.util.concurrent.ScheduledExecutorService s = fileListScheduler;
        if (s == null || s.isShutdown()) return;
        if (soonRefreshPending.compareAndSet(false, true)) {
            try {
                s.schedule(() -> {
                    soonRefreshPending.set(false);
                    refreshFileListSafe();
                }, 3, TimeUnit.SECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                soonRefreshPending.set(false);
            }
        }
    }

    private boolean hasStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        return service.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void refreshFileListSafe() {
        try {
            refreshFileList();
        } catch (Throwable t) {
            Log.e("andcpp", "File list refresh failed", t);
        }
    }

    private void refreshFileList() throws Exception {
        synchronized (fileListLock) {
            long started = System.currentTimeMillis();
            if (!hasStorageAccess()) {
                // Without it Android hides files we didn't create (e.g. from a previous install):
                // the list would show folders with no files. Rebuilt as soon as access is granted.
                Log.w("andcpp", "File list refresh: no storage permission yet; list may be incomplete");
            }
            if (tthCache.isEmpty()) loadTthCache();

            String downloadDir = Settings.getDCDownloadsDirectory(service);
            File root = new File(downloadDir);
            // Older builds wrote our list as "my_files.xml"; it's now "My File List.xml".
            new File(new File(downloadDir, "FileLists"), "my_files.xml").delete();
            Set<String> incomplete = service.getIncompleteDownloadPaths();
            Set<String> seenKeys = new java.util.HashSet<>();
            List<ShareFile> needHash = new ArrayList<>();
            long[] totalSize = {0};

            ShareDir tree = scanDir(root, "Share", incomplete, seenKeys, needHash, totalSize, true);

            File xmlOut = myFileListXml(downloadDir);
            File bz2Out = myFileListBz2();
            // No list at all yet: publish what's already hashed right away, then hash the rest.
            if (!needHash.isEmpty() && (!xmlOut.exists() || !bz2Out.exists())) {
                writeFileList(tree, xmlOut, bz2Out);
            }

            int hashed = 0;
            int failed = 0;
            long lastPublish = System.currentTimeMillis();
            for (ShareFile sf : needHash) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    sf.tth = TTH.calculateTTH(sf.file);
                    tthCache.put(cacheKey(sf.file, sf.size), sf.tth);
                    hashed++;
                } catch (Exception e) { // one bad file must never abort the whole list
                    failed++;
                    Log.w("andcpp", "Could not hash " + sf.file + ": " + e.getMessage());
                }
                // Big share: publish progress so files show up while the rest is still hashing.
                if (System.currentTimeMillis() - lastPublish > PARTIAL_PUBLISH_MILLIS) {
                    writeFileList(tree, xmlOut, bz2Out);
                    saveTthCache();
                    lastPublish = System.currentTimeMillis();
                    Log.i("andcpp", "File list: hashed " + hashed + "/" + needHash.size() + " so far");
                }
            }
            if (failed > 0) {
                Log.w("andcpp", "File list: " + failed + " files could not be read (storage permission?)");
            }

            tthCache.keySet().retainAll(seenKeys); // forget deleted/changed files
            writeFileList(tree, xmlOut, bz2Out);
            if (hashed > 0) saveTthCache();

            Map<String, File> byTth = new ConcurrentHashMap<>();
            collectTths(tree, byTth);
            fileByTth = byTth;
            cachedShareSize = totalSize[0];

            Log.i("andcpp", "File list refreshed: " + byTth.size() + " files, " + totalSize[0] + " bytes, "
                    + hashed + " newly hashed, in " + (System.currentTimeMillis() - started) + " ms");
        }
    }

    private ShareDir scanDir(File dir, String name, Set<String> incomplete, Set<String> seenKeys,
                             List<ShareFile> needHash, long[] totalSize, boolean isRoot) {
        ShareDir node = new ShareDir(name);
        File[] entries = dir.listFiles();
        if (entries == null) return node;
        java.util.Arrays.sort(entries);
        for (File f : entries) {
            if (isRoot && f.getName().equals("FileLists")) continue; // saved file lists are not shared
            if (f.isDirectory()) {
                node.dirs.add(scanDir(f, f.getName(), incomplete, seenKeys, needHash, totalSize, false));
            } else if (f.isFile()) {
                if (incomplete.contains(f.getAbsolutePath())) continue; // still downloading / partial
                ShareFile sf = new ShareFile(f);
                String key = cacheKey(f, sf.size);
                seenKeys.add(key);
                sf.tth = tthCache.get(key);
                if (sf.tth == null) needHash.add(sf);
                totalSize[0] += sf.size;
                node.files.add(sf);
            }
        }
        return node;
    }

    private static String cacheKey(File f, long size) {
        return f.getAbsolutePath() + "|" + size + "|" + f.lastModified();
    }

    private void collectTths(ShareDir dir, Map<String, File> out) {
        for (ShareFile f : dir.files) {
            if (f.tth != null) out.put(f.tth.toUpperCase(java.util.Locale.ROOT), f.file);
        }
        for (ShareDir d : dir.dirs) collectTths(d, out);
    }

    /**
     * Partial file list for "$ADCGET list <path>": the contents of one shared directory as uncompressed XML.
     * Non-recursive: files plus sub-directories marked Incomplete="1". Recursive (RE1): the whole subtree.
     * Returns null if the directory is not shared or no list has been built yet.
     */
    public byte[] buildPartialFileList(String adcPath, boolean recursive) throws IOException {
        ShareDir root = shareTree;
        if (root == null) return null;

        List<String> segs = new ArrayList<>();
        for (String s : (adcPath == null ? "/" : adcPath).replace('\\', '/').split("/")) {
            if (!s.isEmpty()) segs.add(s);
        }

        ShareDir dir = null;
        if (!segs.isEmpty()) {
            if (!segs.get(0).equalsIgnoreCase(root.name)) return null;
            dir = root;
            for (int i = 1; i < segs.size(); i++) {
                ShareDir next = null;
                for (ShareDir d : dir.dirs) {
                    if (d.name.equals(segs.get(i))) { next = d; break; }
                }
                if (next == null) {
                    for (ShareDir d : dir.dirs) {
                        if (d.name.equalsIgnoreCase(segs.get(i))) { next = d; break; }
                    }
                }
                if (next == null) return null;
                dir = next;
            }
        }

        StringBuilder base = new StringBuilder("/");
        for (String s : segs) base.append(s).append('/');

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        org.xmlpull.v1.XmlSerializer s = android.util.Xml.newSerializer();
        s.setOutput(bos, "UTF-8");
        s.startDocument("UTF-8", true);
        s.startTag(null, "FileListing");
        s.attribute(null, "Version", "1");
        s.attribute(null, "CID", cid);
        s.attribute(null, "Base", base.toString());
        s.attribute(null, "Generator", "AnDCpp");
        if (dir == null) {
            writePartialDir(s, root, recursive); // "/": the virtual root holds the "Share" directory
        } else {
            writePartialContents(s, dir, recursive);
        }
        s.endTag(null, "FileListing");
        s.endDocument();
        s.flush();
        return bos.toByteArray();
    }

    private void writePartialDir(org.xmlpull.v1.XmlSerializer s, ShareDir dir, boolean recursive) throws IOException {
        s.startTag(null, "Directory");
        s.attribute(null, "Name", dir.name);
        if (recursive) {
            writePartialContents(s, dir, true);
        } else {
            s.attribute(null, "Incomplete", "1");
        }
        s.endTag(null, "Directory");
    }

    private void writePartialContents(org.xmlpull.v1.XmlSerializer s, ShareDir dir, boolean recursive) throws IOException {
        for (ShareDir d : dir.dirs) writePartialDir(s, d, recursive);
        for (ShareFile f : dir.files) {
            if (f.tth == null) continue;
            s.startTag(null, "File");
            s.attribute(null, "Name", f.name);
            s.attribute(null, "Size", String.valueOf(f.size));
            s.attribute(null, "TTH", f.tth);
            s.endTag(null, "File");
        }
    }

    /** Writes the XML and the .bz2 to temp files, then swaps them in so peers never get a half-written list. */
    private void writeFileList(ShareDir tree, File xmlOut, File bz2Out) throws Exception {
        File xmlTmp = new File(xmlOut.getPath() + ".tmp");
        try (java.io.OutputStream os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(xmlTmp))) {
            org.xmlpull.v1.XmlSerializer s = android.util.Xml.newSerializer();
            s.setOutput(os, "UTF-8");
            s.startDocument("UTF-8", true);
            s.startTag(null, "FileListing");
            s.attribute(null, "Version", "1");
            s.attribute(null, "CID", cid);
            s.attribute(null, "Base", "/");
            s.attribute(null, "Generator", "AnDCpp");
            writeDir(s, tree);
            s.endTag(null, "FileListing");
            s.endDocument();
            s.flush();
        }

        File bz2Tmp = new File(bz2Out.getPath() + ".tmp");
        try (java.io.InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(xmlTmp));
             java.io.OutputStream out = new org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(
                     new java.io.BufferedOutputStream(new java.io.FileOutputStream(bz2Tmp)))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }

        if (!xmlTmp.renameTo(xmlOut)) throw new IOException("Could not replace " + xmlOut);
        if (!bz2Tmp.renameTo(bz2Out)) throw new IOException("Could not replace " + bz2Out);
        shareTree = tree; // partial lists always match the published full list
    }

    private void writeDir(org.xmlpull.v1.XmlSerializer s, ShareDir dir) throws IOException {
        s.startTag(null, "Directory");
        s.attribute(null, "Name", dir.name);
        for (ShareDir d : dir.dirs) writeDir(s, d);
        for (ShareFile f : dir.files) {
            if (f.tth == null) continue; // not hashed yet: shared on the next refresh
            s.startTag(null, "File");
            s.attribute(null, "Name", f.name);
            s.attribute(null, "Size", String.valueOf(f.size));
            s.attribute(null, "TTH", f.tth);
            s.endTag(null, "File");
        }
        s.endTag(null, "Directory");
    }

    private void loadTthCache() {
        File f = tthCacheFile();
        if (!f.exists()) return;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) tthCache.put(line.substring(tab + 1), line.substring(0, tab));
            }
        } catch (IOException e) {
            Log.w("andcpp", "Could not read TTH cache", e);
        }
    }

    private void saveTthCache() {
        File f = tthCacheFile();
        File tmp = new File(f.getPath() + ".tmp");
        try (java.io.Writer w = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(tmp), "UTF-8"))) {
            for (Map.Entry<String, String> e : tthCache.entrySet()) {
                w.write(e.getValue());
                w.write('\t');
                w.write(e.getKey());
                w.write('\n');
            }
        } catch (IOException e) {
            Log.w("andcpp", "Could not save TTH cache", e);
            return;
        }
        tmp.renameTo(f);
    }

    public DCPPService getService() {
        return service;
    }

    public long getSharedSize() {
        if (cachedShareSize >= 0) return cachedShareSize;
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
