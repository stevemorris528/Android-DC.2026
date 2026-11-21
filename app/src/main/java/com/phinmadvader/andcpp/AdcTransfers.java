package com.phinmadvader.andcpp;

import android.util.Log;

import com.phinvader.libjdcpp.DCConstants;
import com.phinvader.libjdcpp.DCDownloader;
import com.phinvader.libjdcpp.DCUser;
import com.phinvader.libjdcpp.MessageHandler;
import com.phinvader.libjdcpp.adc.AdcClientConnection;
import com.phinvader.libjdcpp.adc.AdcHubConnection;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;

import com.phinvader.libjdcpp.tls.Keyprint;
import com.phinvader.libjdcpp.tls.TlsSocketFactory;

/**
 * ADC client-to-client transfers (plain ADC/1.0; TLS "ADCS/0.10" comes later).
 * <ul>
 * <li>Download: we send DCTM (we accept the connection) or, when we can't, DRCM (the peer must then send us a
 * DCTM). A token ties the connection that arrives to the request.</li>
 * <li>A peer's DCTM: we connect out. A peer's DRCM: we answer with a DCTM (if we can accept connections).</li>
 * <li>Every connection: the token is one of our download requests → we download; otherwise the peer wants to
 * download from us → it's served like an NMDC upload (slots, limits, progress) by PeerConnectionHandler.</li>
 * </ul>
 * Downloads fill a {@link DCDownloader.DownloadQueueEntity} exactly as the NMDC downloader does, so the service's
 * progress, resume, retry and "no slots" handling work unchanged.
 */
final class AdcTransfers {

    static final String PROTO = "ADC/1.0";
    /** Encrypted transfers (ADC-EXT ADCS). */
    static final String PROTO_TLS = "ADCS/0.10";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int IO_TIMEOUT_MS = 60000;
    /** Download requests the peer never answered are dropped after this long. */
    private static final long PENDING_MAX_AGE_MS = 5 * 60 * 1000L;

    private final DCPPService service;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final SecureRandom random = new SecureRandom();

    /** Our download requests waiting for their connection, by token. */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private static final class Pending {
        final String peerCid;
        final String peerNick;
        final DCDownloader.DownloadQueueEntity entity;
        final String identifier;
        /** The hub the request went through (a hub refusal fails its requests). */
        volatile AdcHubConnection hub;
        final long createdAt = System.currentTimeMillis();

        Pending(String peerCid, String peerNick, DCDownloader.DownloadQueueEntity entity, String identifier) {
            this.peerCid = peerCid;
            this.peerNick = peerNick;
            this.entity = entity;
            this.identifier = identifier;
        }
    }

    AdcTransfers(DCPPService service) {
        this.service = service;
    }

    // ---- our downloads ----

    /**
     * Asks the peer for a connection for this download. Returns null when the request went out, else why it
     * can't (shown as the download's wait reason).
     */
    String requestDownload(DCPPService.HubSession session, AdcHubConnection hub, DCUser peer,
                           DCDownloader.DownloadQueueEntity entity, String remotePath) {
        forget(entity);
        expireOld();
        boolean weActive = canAccept(session);
        if (!weActive && !peer.active) return "both passive";
        // TLS when both sides support it (some clients refuse unencrypted transfers on secure hubs).
        boolean wantsTls = peer.features.contains("ADCS") || (peer.cid != null && tlsOnlyPeers.contains(peer.cid));
        String proto = tlsAvailable() && wantsTls ? PROTO_TLS : PROTO;
        Log.d("andcpp", "[CC] " + peer.nick + " SU=" + peer.features + (peer.kp != null ? " KP" : "") + " -> " + proto);
        String token = newToken();
        // DC++ / AirDC++ serve files by TTH only (a path gets "151 File Not Available"): use the TTH from the
        // peer's saved file list when we have it, else the path.
        String tth = null;
        if (!AdcClientConnection.isFileListName(remotePath)) {
            tth = tthFromSavedList(peer.nick, remotePath);
            if (tth == null) tth = rememberedTth(peer.nick, remotePath); // e.g. a search result
        }
        String identifier = tth != null ? "TTH/" + tth : AdcClientConnection.identifierFor(remotePath);
        Pending p = new Pending(peer.cid, peer.nick, entity, identifier);
        p.hub = hub;
        pending.put(token, p);
        entity.status = DCConstants.DownloadStatus.QUEUED;
        if (weActive) {
            hub.sendConnectToMe(peer.sid, proto, service.getUploadManager().getPort(), token);
            Log.d("andcpp", "[CC] DCTM to " + peer.nick + " for " + remotePath + " (" + proto + ", port " + service.getUploadManager().getPort() + ", token " + token + ")");
        } else {
            hub.sendRevConnectToMe(peer.sid, proto, token);
            Log.d("andcpp", "[CC] DRCM to " + peer.nick + " for " + remotePath + " (" + proto + ", token " + token + ")");
        }
        return null;
    }

    /**
     * The hub refused our transfer requests ("You are not allowed to download in this hub"): fail the downloads
     * waiting on it instead of leaving them queued. Permanent, so they aren't retried in a loop; the reason is
     * also shown in that hub's chat.
     */
    void refusedByHub(AdcHubConnection hub, String reason) {
        for (Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Pending p = it.next().getValue();
            if (p.hub != hub) continue;
            it.remove();
            p.entity.permanentError = true;
            p.entity.status = DCConstants.DownloadStatus.FAILED;
            Log.i("andcpp", "[CC] Hub refused the transfer of " + p.identifier + " from " + p.peerNick + ": " + reason);
        }
    }

    /** Peers (CID) that refused plain ADC/1.0 ("241 Protocol unknown"): always ask them for ADCS/0.10. */
    private final java.util.Set<String> tlsOnlyPeers = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * A peer answered a transfer request with DSTA. "x41" (transfer protocol unsupported) for ADC/1.0 from a
     * TLS-only client: remember it and re-send the same request (same token) as ADCS/0.10.
     */
    void onPeerStatus(DCPPService.HubSession session, AdcHubConnection hub, DCUser from, String code, String text,
                      String proto, String token) {
        Log.i("andcpp", "[CC] " + from.nick + " answered " + code + " " + text + (proto != null ? " (" + proto + ")" : ""));
        if (code == null || !code.endsWith("41") || !PROTO.equals(proto) || !tlsAvailable()) return;
        if (from.cid != null) tlsOnlyPeers.add(from.cid);
        Pending p = token != null ? pending.get(token) : null;
        if (p == null) return;
        if (canAccept(session)) {
            hub.sendConnectToMe(from.sid, PROTO_TLS, service.getUploadManager().getPort(), token);
        } else {
            hub.sendRevConnectToMe(from.sid, PROTO_TLS, token);
        }
        Log.i("andcpp", "[CC] " + from.nick + " only accepts encrypted transfers: asked again with " + PROTO_TLS);
    }

    /** Drops a waiting request for this download (abandoned attempt, cancel). */
    void forget(DCDownloader.DownloadQueueEntity entity) {
        if (entity == null) return;
        for (Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().entity == entity) it.remove();
        }
    }

    private void expireOld() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().createdAt > PENDING_MAX_AGE_MS) it.remove();
        }
    }

    // ---- requests from peers, via the hub ----

    /** The hub passed on a peer's DCTM (it listens) or DRCM (it wants our DCTM). Runs off the hub reader. */
    void onConnectRequest(final DCPPService.HubSession session, final AdcHubConnection hub, final DCUser peer,
                          final String proto, final int port, final String token, final boolean reverse) {
        final boolean tls = PROTO_TLS.equals(proto);
        if (!PROTO.equals(proto) && !(tls && tlsAvailable())) {
            hub.sendDirectStatus(peer.sid, "141", "Transfer protocol not supported", token, proto);
            Log.i("andcpp", "[CC] " + peer.nick + " asked for " + proto + "; refused (not supported here)");
            return;
        }
        if (reverse) {
            if (canAccept(session)) {
                hub.sendConnectToMe(peer.sid, proto, service.getUploadManager().getPort(), token);
                Log.d("andcpp", "[CC] " + peer.nick + " sent DRCM " + proto + "; answered with DCTM (token " + token + ")");
            } else {
                Log.d("andcpp", "[CC] " + peer.nick + " sent DRCM but we're passive too; ignored");
            }
            return;
        }
        if (peer.ip == null || peer.ip.isEmpty() || port <= 0) {
            Log.w("andcpp", "[CC] DCTM from " + peer.nick + " without a usable address");
            return;
        }
        pool.execute(() -> {
            Socket s = null;
            AdcClientConnection conn = null;
            try {
                if (tls) {
                    // ADCS: TLS first, checked against the keyprint the peer published in its hub INF (KP).
                    s = TlsSocketFactory.connect(peer.ip, port, CONNECT_TIMEOUT_MS, ourKeyManagers(), peer.kp);
                } else {
                    s = new Socket();
                    s.connect(new InetSocketAddress(peer.ip, port), CONNECT_TIMEOUT_MS);
                }
                s.setSoTimeout(IO_TIMEOUT_MS);
                conn = new AdcClientConnection(s, null);
                conn.handshakeAsConnector(AdcIdentityStore.cid(service), token);
                Log.d("andcpp", "[CC] Connected to " + peer.nick + " (" + proto + ")");
                established(conn, peer.nick);
            } catch (Exception e) {
                Log.w("andcpp", "[CC] Connecting to " + peer.nick + " (" + peer.ip + ":" + port + ", " + proto + ") failed: " + e.getMessage());
                if (conn != null) conn.close();
                else if (s != null) closeQuietly(s);
            }
        });
    }

    /**
     * An incoming connection that starts with a TLS handshake (ADCS): TLS server side with our certificate, then
     * the ADC acceptor handshake; the peer's certificate must match the KP it published on the hub (if any).
     */
    void onIncomingTls(final Socket raw) {
        pool.execute(() -> {
            AdcClientConnection conn = null;
            try {
                SSLSocket ssl = TlsSocketFactory.acceptOver(raw, IO_TIMEOUT_MS, ourKeyManagers());
                ssl.setSoTimeout(IO_TIMEOUT_MS);
                conn = new AdcClientConnection(ssl, null);
                conn.handshakeAsAcceptor(AdcIdentityStore.cid(service));
                String theirKp = TlsSocketFactory.peerKeyprintOrNull(ssl);
                DCUser u = userForCid(conn.peerCid);
                if (u != null && u.kp != null && !Keyprint.matches(u.kp, theirKp)) {
                    Log.w("andcpp", "[CC] " + u.nick + ": TLS certificate doesn't match its keyprint on the hub; closing");
                    conn.close();
                    return;
                }
                Log.d("andcpp", "[CC] Incoming ADCS connection from " + (u != null ? u.nick : conn.peerCid)
                        + " (" + TlsSocketFactory.describe(ssl) + ")");
                established(conn, u != null ? u.nick : null);
            } catch (Exception e) {
                Log.w("andcpp", "[CC] Incoming ADCS connection from " + raw.getInetAddress() + " failed: " + e.getMessage());
                if (conn != null) conn.close();
                else closeQuietly(raw);
            }
        });
    }

    /** We can do TLS transfers here: Android 5+ (to peek at incoming TLS) and our certificate exists. */
    boolean tlsAvailable() {
        return SocketPeek.supported() && ClientCertificateStore.peekKeyprint(service) != null;
    }

    private javax.net.ssl.KeyManager[] ourKeyManagers() throws java.security.GeneralSecurityException {
        com.phinvader.libjdcpp.tls.SelfSignedCertificate c = ClientCertificateStore.get(service);
        return c != null ? c.keyManagers() : null;
    }

    private DCUser userForCid(String cid) {
        if (cid == null) return null;
        for (DCPPService.HubSession s : service.getActiveSessions()) {
            if (s.hub instanceof AdcHubConnection) {
                DCUser u = ((AdcHubConnection) s.hub).userByCid(cid);
                if (u != null) return u;
            }
        }
        return null;
    }

    // ---- connections to our listening port ----

    /** An incoming connection whose first byte ('C') shows it's ADC. */
    void onIncoming(final Socket s, final byte firstByte) {
        pool.execute(() -> {
            AdcClientConnection conn = null;
            try {
                s.setSoTimeout(IO_TIMEOUT_MS);
                conn = new AdcClientConnection(s, new byte[] {firstByte});
                conn.handshakeAsAcceptor(AdcIdentityStore.cid(service));
                established(conn, null);
            } catch (Exception e) {
                Log.w("andcpp", "[CC] Incoming ADC connection from " + s.getInetAddress() + " failed: " + e.getMessage());
                if (conn != null) conn.close();
                else closeQuietly(s);
            }
        });
    }

    /** Handshake done: our download (known token) or the peer's (anything else). */
    private void established(AdcClientConnection conn, String knownNick) {
        Pending p = conn.token != null ? pending.remove(conn.token) : null;
        if (p != null && (p.peerCid == null || p.peerCid.equals(conn.peerCid))) {
            download(conn, p);
            return;
        }
        String nick = knownNick != null ? knownNick : nickForCid(conn.peerCid);
        Log.d("andcpp", "[CC] Upload connection from " + nick + " (CID " + conn.peerCid + ")");
        service.getUploadManager().serveAdcUpload(conn, nick);
    }

    // ---- download one file ----

    private void download(AdcClientConnection conn, Pending p) {
        DCDownloader.DownloadQueueEntity e = p.entity;
        e.adcConnection = conn;
        e.status = DCConstants.DownloadStatus.INITIATED;
        long start = Math.max(0, e.startOffset);
        try {
            AdcClientConnection.Reply r = conn.request("file", p.identifier, start, -1);
            if (!r.ok()) {
                if (r.noSlots()) {
                    e.status = DCConstants.DownloadStatus.WAITING_FOR_PEER; // like $MaxedOut: retried later
                } else {
                    e.status = DCConstants.DownloadStatus.FAILED;
                    if (r.notAvailable()) e.permanentError = true;
                }
                Log.i("andcpp", "[CC] " + p.peerNick + " answered " + r.statusCode + " " + r.statusText + " for " + p.identifier);
                return;
            }
            final long from = r.start >= 0 ? r.start : start;
            e.startOffset = from;
            e.expectedDownloadSize = from + r.length;
            e.downloadedSize = from;
            e.status = DCConstants.DownloadStatus.STARTED;
            Log.d("andcpp", "[CC] Downloading " + p.identifier + " from " + p.peerNick + ": " + r.length + " bytes from " + from);

            File target = new File(e.local_filename);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            long got;
            try (RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
                if (from == 0) raf.setLength(0);
                raf.seek(from);
                e.status = DCConstants.DownloadStatus.DOWNLOADING;
                final long[] counted = {0};
                got = conn.readRaw(r.length, new RafOutput(raf), n -> {
                    e.downloadedSize = from + n;
                    MessageHandler.totalDownloadedBytes.addAndGet(n - counted[0]);
                    counted[0] = n;
                });
            }
            if (got >= r.length) {
                e.status = DCConstants.DownloadStatus.COMPLETED;
            } else {
                e.status = got > 0 ? DCConstants.DownloadStatus.INTERUPTED : DCConstants.DownloadStatus.FAILED;
            }
            Log.d("andcpp", "[CC] " + p.identifier + " from " + p.peerNick + ": " + e.status + " (" + got + "/" + r.length + ")");
        } catch (IOException ex) {
            boolean some = e.downloadedSize > e.startOffset;
            e.status = some ? DCConstants.DownloadStatus.INTERUPTED : DCConstants.DownloadStatus.FAILED;
            Log.w("andcpp", "[CC] Download of " + p.identifier + " from " + p.peerNick + " ended: " + ex.getMessage());
        } finally {
            e.adcConnection = null;
            conn.close();
        }
    }

    // ---- TTHs from search results (a download may start from one without the user's file list) ----

    private final Map<String, String> resultTths = new java.util.LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 2000;
        }
    };

    /** Remembers the TTH of a search result ("nick" + NMDC-style path). */
    void rememberTth(String nick, String remotePath, String tth) {
        if (nick == null || remotePath == null || tth == null || tth.isEmpty()) return;
        synchronized (resultTths) {
            resultTths.put(nick.toLowerCase(java.util.Locale.ROOT) + "\u0000" + remotePath, tth);
        }
    }

    private String rememberedTth(String nick, String remotePath) {
        synchronized (resultTths) {
            return resultTths.get(nick.toLowerCase(java.util.Locale.ROOT) + "\u0000" + remotePath);
        }
    }

    // ---- TTH from the peer's saved file list ----

    /** Parsed saved file lists by nick, with the file time they were parsed from (a few, most recent). */
    private final Map<String, Object[]> listCache = new java.util.LinkedHashMap<String, Object[]>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
            return size() > 3;
        }
    };

    /** The TTH of "dir\sub\file" in this user's saved file list (FileLists/&lt;nick&gt;.xml), or null. */
    String tthFromSavedList(String nick, String remotePath) {
        if (nick == null || remotePath == null) return null;
        File f = new File(service.getFileListDir(), nick + ".xml");
        if (!f.isFile()) return null;
        com.phinvader.libjdcpp.DCFileList root;
        synchronized (listCache) {
            Object[] c = listCache.get(nick);
            if (c != null && (Long) c[0] == f.lastModified()) {
                root = (com.phinvader.libjdcpp.DCFileList) c[1];
            } else {
                root = com.phinvader.libjdcpp.DCFileList.parseXML(f);
                if (root == null) return null;
                listCache.put(nick, new Object[] {f.lastModified(), root});
            }
        }
        com.phinvader.libjdcpp.DCFileList node = root;
        for (String part : remotePath.replace('/', '\\').split("\\\\")) {
            if (part.isEmpty()) continue;
            com.phinvader.libjdcpp.DCFileList next = null;
            if (node.children != null) {
                for (com.phinvader.libjdcpp.DCFileList ch : node.children) {
                    if (part.equals(ch.name)) {
                        next = ch;
                        break;
                    }
                }
            }
            if (next == null) return null;
            node = next;
        }
        return node != root && node.tth != null && !node.tth.isEmpty() ? node.tth : null;
    }

    // ---- helpers ----

    /** Peers can connect to us: active mode and our listener is up. */
    private boolean canAccept(DCPPService.HubSession session) {
        UploadManager um = service.getUploadManager();
        return session != null && session.prefs != null && session.prefs.active && um != null && um.isListening();
    }

    private String nickForCid(String cid) {
        if (cid != null) {
            for (DCPPService.HubSession s : service.getActiveSessions()) {
                if (s.hub instanceof AdcHubConnection) {
                    DCUser u = ((AdcHubConnection) s.hub).userByCid(cid);
                    if (u != null && u.nick != null) return u.nick;
                }
            }
        }
        return cid != null ? "ADC " + cid.substring(0, Math.min(8, cid.length())) : "ADC peer";
    }

    private String newToken() {
        return Long.toString(random.nextLong() & Long.MAX_VALUE, 36);
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    /** An OutputStream over a RandomAccessFile at its current position. */
    private static final class RafOutput extends OutputStream {
        private final RandomAccessFile raf;

        RafOutput(RandomAccessFile raf) {
            this.raf = raf;
        }

        @Override
        public void write(int b) throws IOException {
            raf.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            raf.write(b, off, len);
        }
    }
}
