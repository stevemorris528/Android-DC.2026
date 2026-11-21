package com.phinvader.libjdcpp.adc;

import com.phinmadvader.andcpp.util.Base32;
import com.phinmadvader.andcpp.util.TigerDigest;
import com.phinvader.libjdcpp.DCPreferences;
import com.phinvader.libjdcpp.DCUser;
import com.phinvader.libjdcpp.hub.HubAddress;
import com.phinvader.libjdcpp.hub.HubConnection;
import com.phinvader.libjdcpp.hub.HubEventListener;
import com.phinvader.libjdcpp.hub.SearchResult;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An ADC hub link (ADC 1.0: BASE + TIGR), over a plain or TLS socket.
 * <pre>
 * PROTOCOL  we send HSUP ADBASE ADTIGR; hub sends ISUP, ISID &lt;our SID&gt;, IINF (hub name/topic)
 * IDENTIFY  we send BINF &lt;SID&gt; ID&lt;CID&gt; PD&lt;PID&gt; NI&lt;nick&gt; ...
 * VERIFY    only if the hub sends IGPA &lt;data&gt;: we send HPAS Base32(Tiger(password + data))
 * NORMAL    hub sends every user's BINF, ours last (= logged in); then BINF/IQUI/BMSG/EMSG/ISTA ...
 * </pre>
 * Still to come: searches (phase 9), BLOM (phase 7), client-to-client transfers (phase 8), hub menu commands
 * (phase 9). Until transfers exist we advertise no TCP4/UDP4, so peers treat us as passive.
 */
public final class AdcHubConnection implements HubConnection {

    /** VE sent to hubs (owner decision). */
    public static final String CLIENT_VERSION = "AndroidDC++ 1.0.5";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_LINE = 256 * 1024;
    private static final int MAX_BOARD_LINES = 500;

    /** What we tell the hub about ourselves besides nick and share (read on every INF update). */
    public interface MyInfo {
        int slots();

        int freeSlots();

        long sharedFiles();

        /** Hubs we're on as normal user / registered / operator (HN/HR/HO). */
        int[] hubCounts();

        /** Base32 TTHs of our shared files, for the BLOM filter. Called off the reader thread. */
        java.util.Collection<String> sharedTths();

        /** True if peers can connect to us (TCP4 + I4 in our INF, and we send CTMs instead of RCMs). */
        boolean active();

        /** Our TLS keyprint if we can do encrypted (ADCS) transfers, else null. */
        String keyprint();
    }

    private final HubAddress address;
    private final AdcIdentity identity;
    private final MyInfo myInfo;
    private final HubEventListener listener;

    private Socket socket;
    private OutputStream out;
    private final Object writeLock = new Object();
    private volatile long lastReceivedAt = System.currentTimeMillis();

    private volatile String mySid;
    private volatile String nick;
    private volatile String password;
    private volatile long shareSize;

    /** Latest INF fields per SID (INF updates are deltas, merged here). */
    private final Map<String, Map<String, String>> infs = new ConcurrentHashMap<>();
    private final Map<String, DCUser> users = new ConcurrentHashMap<>();
    /** INF fields we last sent, so updates only carry what changed. */
    private final Map<String, String> sentInf = new HashMap<>();
    private final LinkedList<String> board = new LinkedList<>();
    private final Set<String> hubFeatures = Collections.synchronizedSet(new HashSet<String>());

    private volatile String hubName;
    private volatile String hubTopic;
    private volatile String hubSoftware;

    private final CountDownLatch loginDone = new CountDownLatch(1);
    private volatile boolean loggedIn;
    private volatile String loginFailure;
    private volatile String quitReason;
    private volatile String redirect;
    private volatile boolean closedByUs;
    /** The hub kicked us with TL-1: it asks us not to reconnect. */
    private volatile boolean noReconnect;
    private final CountDownLatch readerDone = new CountDownLatch(1);
    private final AtomicBoolean ended = new AtomicBoolean();

    public AdcHubConnection(HubAddress address, AdcIdentity identity, MyInfo myInfo, HubEventListener listener) {
        this.address = address;
        this.identity = identity;
        this.myInfo = myInfo;
        this.listener = listener;
    }

    // ---- login ----

    /**
     * Logs in over an already connected socket (plain, or TLS with its handshake done). Blocks until we're
     * logged in, or throws with a user-readable reason (wrong password, nick taken, banned, timeout, ...).
     */
    public void connect(Socket connected, String nick, String password, long shareSize, int timeoutMs)
            throws IOException, InterruptedException {
        this.socket = connected;
        this.nick = nick;
        this.password = password;
        this.shareSize = shareSize;
        socket.setSoTimeout(0);
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        out = new BufferedOutputStream(socket.getOutputStream());
        lastReceivedAt = System.currentTimeMillis();

        Thread reader = new Thread(this::readLoop, "adc-reader-" + address.host);
        reader.setDaemon(true);
        reader.start();

        // BLO0: we can send the hub a Bloom filter of our TTHs (some hubs require it).
        send(AdcCommand.of('H', "SUP").param("ADBASE").param("ADTIGR").param("ADBLO0").build());

        if (!loginDone.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            closeQuietly();
            throw new IOException("Hub login timed out");
        }
        if (!loggedIn) {
            // A refusal is usually ISTA (the reason) then IQUI (with TL-1 = "don't reconnect"): give the reader a
            // moment to see the IQUI before deciding.
            readerDone.await(1000, TimeUnit.MILLISECONDS);
            closeQuietly();
            String why = loginFailure != null ? loginFailure : "Hub closed the connection during login";
            if (noReconnect) throw new com.phinvader.libjdcpp.hub.HubRefusedException(why);
            throw new IOException(why);
        }
        log("logged in to " + address.toUrl() + " as " + nick + " (SID " + mySid + ", " + users.size() + " users)");
    }

    private void failLogin(String reason) {
        if (loggedIn) return;
        if (loginFailure == null) loginFailure = reason;
        loginDone.countDown();
        closeQuietly();
    }

    /** Like failLogin, but keeps reading so the hub's following IQUI (and its TL) is still seen. */
    private void failLoginKeepReading(String reason) {
        if (loggedIn) return;
        if (loginFailure == null) loginFailure = reason;
        loginDone.countDown();
    }

    /** True if the hub kicked us with TL-1 ("don't reconnect"): automatic retries can't help. */
    public boolean hubAskedNotToReconnect() {
        return noReconnect;
    }

    // ---- reading ----

    private void readLoop() {
        String reason = null;
        try {
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream(256);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                lastReceivedAt = System.currentTimeMillis();
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    if (b == '\n') {
                        handleLine(new String(line.toByteArray(), UTF8));
                        line.reset();
                    } else if (line.size() < MAX_LINE) {
                        line.write(b);
                    }
                }
            }
            reason = "Hub closed the connection";
        } catch (IOException e) {
            reason = e.getMessage() != null ? e.getMessage() : e.toString();
        } catch (RuntimeException e) {
            reason = "Internal error: " + e;
            log("reader crashed: " + e);
        }
        end(quitReason != null ? quitReason : reason);
    }

    private void end(String reason) {
        if (!ended.compareAndSet(false, true)) return;
        closeQuietly();
        if (!closedByUs) {
            synchronized (recentLines) {
                log("connection ended (" + reason + "); last lines from the hub: " + recentLines);
            }
        }
        if (!loggedIn) {
            failLogin(reason);
        } else if (!closedByUs) {
            listener.onDisconnected(reason, redirect);
        }
        readerDone.countDown();
    }

    /** The last few lines from the hub (shortened), logged when the connection ends: why were we dropped? */
    private final LinkedList<String> recentLines = new LinkedList<>();

    private void remember(String l) {
        synchronized (recentLines) {
            recentLines.add(l.length() > 200 ? l.substring(0, 200) + "…" : l);
            while (recentLines.size() > 20) recentLines.removeFirst();
        }
    }

    void handleLine(String raw) {
        String l = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
        if (l.isEmpty()) return; // keep-alive
        // User INFs and hub menu entries would crowd out the interesting lines.
        if (!l.startsWith("BINF ") && !l.startsWith("ICMD ")) remember(l);
        AdcCommand c;
        try {
            c = AdcCommand.parse(l);
        } catch (IllegalArgumentException e) {
            // Some hubs put a raw newline inside message text, so the rest of the text arrives glued to the
            // start of the next command ("...sorryIQUI ABCD MS..."). Find that command and handle it.
            int at = embeddedCommandStart(l);
            if (at > 0) {
                log("line with a stray text fragment \"" + shorten(l.substring(0, at)) + "\"; handling the command after it");
                handleLine(l.substring(at));
            } else {
                log("ignoring unparsable line: " + shorten(l));
            }
            return;
        }
        if ((c.type == 'I' && (c.cmd.equals("MSG") || c.cmd.equals("STA") || c.cmd.equals("QUI")))
                || (c.type == 'D' && c.cmd.equals("STA") && mySid != null && mySid.equals(c.toSid))) { // a peer refusing our request
            log("hub: " + shorten(l));
        }
        switch (c.name()) {
            case "ISUP":
                for (String p : c.params) {
                    if (p.startsWith("AD")) hubFeatures.add(p.substring(2));
                    else if (p.startsWith("RM")) hubFeatures.remove(p.substring(2));
                }
                break;
            case "ISID":
                mySid = c.param(0);
                sendInf(true);
                break;
            case "IINF":
                hubName = orDefault(c.named("NI"), hubName);
                hubTopic = orDefault(c.named("DE"), hubTopic);
                hubSoftware = orDefault(c.named("VE"), hubSoftware);
                listener.onHubInfo(hubName, hubTopic);
                break;
            case "IGPA":
                answerPassword(c.param(0));
                break;
            case "ISTA":
                handleStatus(c);
                break;
            case "IQUI":
                handleQuit(c);
                break;
            case "BINF":
                handleInf(c.fromSid, c.params);
                break;
            case "BMSG":
                handleChat(c.fromSid, c.param(0), "1".equals(c.named("ME", 1)));
                break;
            case "IMSG": {
                String text = c.param(0);
                if (text != null) addChat("<" + (hubName != null ? hubName : "Hub") + "> " + text);
                break;
            }
            case "EMSG":
            case "DMSG":
                handleDirectMessage(c);
                break;
            case "DCTM":
            case "DRCM":
                handleConnectRequest(c);
                break;
            case "BSCH":
            case "DSCH":
            case "FSCH":
                handleSearch(c);
                break;
            case "DRES":
                handleResult(c);
                break;
            case "ICMD":
                handleUserCommand(c);
                break;
            case "IGET":
                if ("blom".equals(c.param(0))) sendBloomLater(c);
                break;
            default:
                // BSCH/DSCH/FSCH (phase 9), ICMD (phase 9), IGET blom (phase 7), DSTA, ...: not handled yet
                break;
        }
    }

    private void answerPassword(String data) {
        String pw = password;
        if (pw == null || pw.isEmpty()) {
            failLogin("This nick is registered on the hub: set its password with Edit Nick & Password");
            return;
        }
        send(AdcCommand.of('H', "PAS").param(passwordHash(pw, data)).build());
    }

    /** HPAS value: Base32(Tiger(UTF-8 password bytes followed by the Base32-decoded IGPA data)). */
    public static String passwordHash(String password, String gpaData) {
        byte[] pw = password.getBytes(UTF8);
        byte[] rnd = Base32.decode(gpaData);
        TigerDigest t = new TigerDigest();
        t.update(pw);
        t.update(rnd);
        return Base32.encode(t.digest());
    }

    private void handleStatus(AdcCommand c) {
        String code = c.param(0);
        String desc = c.param(1);
        if (code == null || code.length() != 3) return;
        int severity = code.charAt(0) - '0';
        String msg = describeStatus(code, desc, c);
        String fc = c.named("FC", 2);
        if (loggedIn && severity >= 1 && ("DCTM".equals(fc) || "DRCM".equals(fc))) {
            listener.onTransferRefused(desc != null && !desc.trim().isEmpty() ? desc.trim() : msg);
        }
        if (severity == 2) {
            if (!loggedIn) {
                failLoginKeepReading(msg); // the hub usually follows with IQUI (maybe TL-1)
            } else {
                quitReason = msg;
                listener.onStatus("Hub error: " + msg);
                closeQuietly(); // the reader then reports the disconnect
            }
        } else if (desc != null && !desc.isEmpty()) {
            listener.onStatus((severity == 1 ? "Hub: " : "") + msg);
        }
    }

    /** User-readable text for an ADC STA code (2-digit part of the 3-digit code) plus the hub's description. */
    public static String describeStatus(String code, String desc, AdcCommand c) {
        if (desc != null) desc = desc.trim();
        String what;
        switch (code.substring(1)) {
            case "11": what = "Hub is full"; break;
            case "12": what = "Hub is disabled"; break;
            case "21": what = "Nick is invalid"; break;
            case "22": what = "Nick is already taken"; break;
            case "23": what = "Wrong password"; break;
            case "24": what = "Our client ID is already in use on this hub"; break;
            case "25": what = "Access denied"; break;
            case "26": what = "Only registered users can log in"; break;
            case "27": what = "Invalid private ID"; break;
            case "31": what = "Permanently banned"; break;
            case "32": {
                String tl = c != null ? c.named("TL", 2) : null;
                what = "Temporarily banned" + (tl != null ? " for " + tl + " s" : "");
                break;
            }
            case "40": what = "Protocol error"; break;
            case "43": {
                String f = c != null ? orDefault(c.named("FM", 2), c.named("FB", 2)) : null;
                what = "The hub needs more user info" + (f != null ? " (" + f + ")" : "");
                break;
            }
            case "45": {
                String f = c != null ? c.named("FC", 2) : null;
                what = "The hub requires a feature we don't support yet" + (f != null ? " (" + f + ")" : "");
                break;
            }
            case "46": what = "The hub says our IP address is invalid"; break;
            case "47": what = "No common hash algorithm with the hub"; break;
            default: what = null;
        }
        if (what == null) return desc != null ? desc : "Status " + code;
        return desc != null && !desc.isEmpty() && !desc.equalsIgnoreCase(what) ? what + " (" + desc + ")" : what;
    }

    private void handleQuit(AdcCommand c) {
        String sid = c.param(0);
        if (sid == null) return;
        if (sid.equals(mySid)) {
            String ms = c.named("MS", 1);
            String tl = c.named("TL", 1);
            String rd = c.named("RD", 1);
            StringBuilder why = new StringBuilder(rd != null ? "Redirected to " + rd : "Disconnected by the hub");
            if (ms != null && !ms.isEmpty()) why.append(": ").append(ms);
            if ("-1".equals(tl)) {
                noReconnect = true; // TL-1: the hub asks us not to reconnect (until something changes)
            } else if (tl != null) {
                why.append(" (can't reconnect for ").append(tl).append(" s)");
            }
            redirect = rd;
            quitReason = why.toString();
            if (!loggedIn) failLogin(quitReason);
            else closeQuietly();
            return;
        }
        infs.remove(sid);
        DCUser gone = users.remove(sid);
        if (gone != null) listener.onUserQuit(gone);
    }

    private void handleInf(String sid, List<String> params) {
        if (sid == null) return;
        Map<String, String> f = infs.get(sid);
        if (f == null) {
            f = new HashMap<>();
            infs.put(sid, f);
        }
        synchronized (f) {
            for (String p : params) {
                if (p.length() < 2) continue;
                String code = p.substring(0, 2);
                String v = p.substring(2);
                if (v.isEmpty()) f.remove(code); // an empty field removes it
                else f.put(code, v);
            }
        }
        DCUser u = userFromInf(sid, f);
        if (u.nick == null) return; // not usable yet
        if ((u.ct & 32) != 0) { // the hub itself, listed like a user by some hubs
            users.remove(sid);
        } else {
            users.put(sid, u);
            listener.onUserUpdated(u);
        }
        if (sid.equals(mySid) && !loggedIn) { // the hub sends our own INF last: we're in
            loggedIn = true;
            loginDone.countDown();
        }
    }

    /** Builds a user from merged INF fields. Package-private for tests. */
    static DCUser userFromInf(String sid, Map<String, String> f) {
        DCUser u = new DCUser();
        synchronized (f) {
            u.sid = sid;
            u.cid = f.get("ID");
            u.nick = f.get("NI");
            u.description = f.get("DE");
            u.email = f.get("EM");
            u.share_size = parseLong(f.get("SS"));
            u.sharedFiles = parseLong(f.get("SF"));
            u.slots = (int) parseLong(f.get("SL"));
            u.freeSlots = (int) parseLong(f.get("FS"));
            u.client = f.get("VE");
            u.ip = f.get("I4");
            u.udp4 = (int) parseLong(f.get("U4"));
            u.ct = (int) parseLong(f.get("CT"));
            u.kp = f.get("KP");
            u.away = f.get("AW") != null && !"0".equals(f.get("AW"));
            String su = f.get("SU");
            if (su != null) {
                for (String s : su.split(",")) if (!s.isEmpty()) u.features.add(s);
            }
            u.active = u.features.contains("TCP4") || u.features.contains("TCP6");
            u.op = (u.ct & (4 | 8 | 16)) != 0;
            // NMDC-style tag, so the existing user list shows client, mode, hubs and slots.
            u.tag = "<" + (u.client != null ? u.client : "ADC") + ",M:" + (u.active ? "A" : "P")
                    + ",H:" + orDefault(f.get("HN"), "0") + "/" + orDefault(f.get("HR"), "0") + "/"
                    + orDefault(f.get("HO"), "0") + ",S:" + u.slots + ">";
        }
        return u;
    }

    private void handleChat(String sid, String text, boolean me) {
        if (text == null) return;
        String who = nickOf(sid);
        addChat(me ? "* " + who + " " + text : "<" + who + "> " + text);
    }

    private void addChat(String line) {
        synchronized (board) {
            board.add(line);
            while (board.size() > MAX_BOARD_LINES) board.removeFirst();
        }
        listener.onMainChat(line);
    }

    private void handleDirectMessage(AdcCommand c) {
        String text = c.param(0);
        if (text == null || c.fromSid == null) return;
        if (c.fromSid.equals(mySid)) return; // the hub's echo of our own PM (E type)
        String group = c.named("PM", 1);
        if (group == null) { // a direct message that isn't a PM: show it in the main chat
            handleChat(c.fromSid, text, "1".equals(c.named("ME", 1)));
            return;
        }
        String from = nickOf(c.fromSid);
        String conversation = group.equals(c.fromSid) ? from : nickOf(group);
        boolean me = "1".equals(c.named("ME", 1));
        listener.onPrivateMessage(conversation, from, me ? "* " + from + " " + text : text);
    }

    /**
     * DCTM &lt;from&gt; &lt;us&gt; &lt;proto&gt; &lt;port&gt; &lt;token&gt; (the peer listens: we connect to it) or
     * DRCM &lt;from&gt; &lt;us&gt; &lt;proto&gt; &lt;token&gt; (the peer asks us to send a CTM). Passed to the app.
     */
    private void handleConnectRequest(AdcCommand c) {
        if (mySid == null || c.fromSid == null || !mySid.equals(c.toSid)) return;
        boolean reverse = c.cmd.equals("RCM");
        String proto = c.param(0);
        int port = reverse ? 0 : (int) parseLong(c.param(1));
        String token = reverse ? c.param(1) : c.param(2);
        DCUser peer = users.get(c.fromSid);
        if (peer == null || proto == null || token == null) return;
        listener.onConnectRequest(peer, proto, port, token, reverse);
    }

    /** Asks the peer to connect to us: DCTM &lt;us&gt; &lt;peer&gt; &lt;proto&gt; &lt;port&gt; &lt;token&gt;. */
    public void sendConnectToMe(String peerSid, String proto, int port, String token) {
        send(AdcCommand.of('D', "CTM").from(mySid).to(peerSid).param(proto).param(String.valueOf(port))
                .param(token).build());
    }

    /** Asks the peer to send us a CTM (we can't accept connections): DRCM &lt;us&gt; &lt;peer&gt; &lt;proto&gt; &lt;token&gt;. */
    public void sendRevConnectToMe(String peerSid, String proto, String token) {
        send(AdcCommand.of('D', "RCM").from(mySid).to(peerSid).param(proto).param(token).build());
    }

    /** DSTA to a peer, e.g. refusing its RCM when we're passive too. */
    public void sendDirectStatus(String peerSid, String code, String text, String token, String proto) {
        AdcCommand.Builder b = AdcCommand.of('D', "STA").from(mySid).to(peerSid).param(code).param(text);
        if (proto != null) b.named("PR", proto);
        if (token != null) b.named("TO", token);
        send(b.build());
    }

    /** The user with this nick (case-insensitive), or null. */
    public DCUser userByNick(String nickName) {
        if (nickName == null) return null;
        for (DCUser u : users.values()) {
            if (u.nick != null && u.nick.equalsIgnoreCase(nickName)) return u;
        }
        return null;
    }

    /** The user with this CID, or null. */
    public DCUser userByCid(String cid) {
        if (cid == null) return null;
        for (DCUser u : users.values()) {
            if (cid.equals(u.cid)) return u;
        }
        return null;
    }

    private String nickOf(String sid) {
        DCUser u = sid != null ? users.get(sid) : null;
        if (u != null && u.nick != null) return u.nick;
        Map<String, String> f = sid != null ? infs.get(sid) : null;
        String ni = f != null ? f.get("NI") : null;
        return ni != null ? ni : (sid != null ? sid : "?");
    }

    private String sidOf(String nickName) {
        for (DCUser u : users.values()) {
            if (u.nick != null && u.nick.equalsIgnoreCase(nickName)) return u.sid;
        }
        return null;
    }

    // ---- our INF ----

    /** Sends our INF: everything the first time, then only fields that changed. */
    private void sendInf(boolean full) {
        String sid = mySid;
        if (sid == null) return;
        Map<String, String> now = new LinkedHashMap<>();
        if (full) {
            now.put("ID", identity.cid);
            now.put("PD", identity.pid); // only ever sent to the hub, in this first INF
        }
        now.put("NI", nick);
        now.put("SS", String.valueOf(Math.max(0, shareSize)));
        now.put("SF", String.valueOf(Math.max(0, myInfo.sharedFiles())));
        now.put("SL", String.valueOf(myInfo.slots()));
        now.put("FS", String.valueOf(myInfo.freeSlots()));
        int[] h = myInfo.hubCounts();
        now.put("HN", String.valueOf(h[0]));
        now.put("HR", String.valueOf(h[1]));
        now.put("HO", String.valueOf(h[2]));
        now.put("VE", CLIENT_VERSION);
        // Active: TCP4 in SU, and I4 0.0.0.0 asks the hub to fill in the address it sees (our public IP).
        // Passive: both empty, which removes them ("" = not set).
        boolean active = myInfo.active();
        // ADCS (encrypted transfers) with our keyprint when the app can do TLS transfers here.
        String kp = myInfo.keyprint();
        String su = (active ? "TCP4" : "") + (kp != null ? (active ? "," : "") + "ADCS" : "");
        now.put("SU", su);
        now.put("KP", kp != null ? kp : "");
        now.put("I4", active ? "0.0.0.0" : "");

        AdcCommand.Builder b = AdcCommand.of('B', "INF").from(sid);
        boolean any = false;
        synchronized (sentInf) {
            for (Map.Entry<String, String> e : now.entrySet()) {
                String v = e.getValue() != null ? e.getValue() : ""; // unknown = not sent
                String before = sentInf.get(e.getKey());
                boolean changed = full ? !v.isEmpty() : !v.equals(before == null ? "" : before);
                if (changed) {
                    b.named(e.getKey(), v); // an empty value removes the field on the hub
                    any = true;
                }
                if (!e.getKey().equals("PD")) sentInf.put(e.getKey(), v);
            }
        }
        if (any) send(b.build());
    }

    // ---- HubConnection ----

    @Override
    public HubAddress.Protocol protocol() {
        return address.protocol;
    }

    @Override
    public HubAddress address() {
        return address;
    }

    @Override
    public long millisSinceLastReceived() {
        return System.currentTimeMillis() - lastReceivedAt;
    }

    @Override
    public void sendKeepAlive() {
        sendRawLine("");
    }

    /** The hub relays every INF to all users, us included, so a one-field INF gets an answer. */
    @Override
    public void sendLivenessProbe() {
        String sid = mySid;
        if (sid == null) return;
        send(AdcCommand.of('B', "INF").from(sid).named("SL", String.valueOf(myInfo.slots())).build());
    }

    @Override
    public void updateMyInfo(DCUser me, DCPreferences prefs) {
        if (prefs != null && prefs.getShare_size() > 0) shareSize = prefs.getShare_size();
        else if (me != null) shareSize = me.share_size;
        sendInf(false);
    }

    @Override
    public List<DCUser> users() {
        return new ArrayList<>(users.values());
    }

    /** The main chat seen so far (newest last), for a chat view opened later. */
    public List<String> boardMessages() {
        synchronized (board) {
            return new ArrayList<>(board);
        }
    }

    public String hubName() {
        return hubName;
    }

    public String hubTopic() {
        return hubTopic;
    }

    public String hubSoftware() {
        return hubSoftware;
    }

    public String mySid() {
        return mySid;
    }

    @Override
    public void sendMainChat(DCUser me, String text) {
        String t = text.trim();
        if (t.isEmpty() || mySid == null) return;
        boolean isMe = t.startsWith("/me ");
        AdcCommand.Builder b = AdcCommand.of('B', "MSG").from(mySid).param(isMe ? t.substring(4) : t);
        if (isMe) b.named("ME", "1");
        send(b.build());
    }

    @Override
    public void sendPrivate(DCUser me, String toNick, String text) {
        String to = sidOf(toNick);
        if (to == null) throw new IllegalStateException(toNick + " is not on the hub");
        send(AdcCommand.of('E', "MSG").from(mySid).to(to).param(text.trim()).named("PM", mySid).build());
    }

    @Override
    public void search(DCUser me, String text) {
        String sid = mySid;
        if (sid == null || text == null || text.trim().isEmpty()) return;
        String t = text.trim();
        String token = Long.toString(System.nanoTime() & Long.MAX_VALUE, 36);
        lastSearchToken = token;
        AdcCommand.Builder b = AdcCommand.of('B', "SCH").from(sid);
        String hash = t.regionMatches(true, 0, "TTH:", 0, 4) ? t.substring(4).trim() : t;
        if (AdcIdentity.isValidId(hash.toUpperCase(Locale.ROOT))) { // a 39-char Base32 TTH
            b.named("TR", hash.toUpperCase(Locale.ROOT));
        } else {
            for (String term : t.split("\\s+")) {
                if (term.startsWith("-") && term.length() > 1) b.named("NO", term.substring(1));
                else if (!term.isEmpty()) b.named("AN", term);
            }
        }
        b.named("TO", token);
        send(b.build());
        log("search sent: " + t);
    }

    /** Token of our latest search (results carry it). */
    private volatile String lastSearchToken;

    private void handleSearch(AdcCommand c) {
        if (c.fromSid == null || c.fromSid.equals(mySid)) return; // never answer our own search
        if (c.type == 'D' && (mySid == null || !mySid.equals(c.toSid))) return; // a direct search for someone else
        if (c.type == 'F' && !featuresMatch(c.extra)) return;
        AdcSearch s = AdcSearch.parse(c);
        DCUser from = users.get(c.fromSid);
        if (s != null && from != null) listener.onSearch(from, s);
    }

    /** FSCH "+TCP4-NAT0": the search is only for users whose features match. */
    private boolean featuresMatch(String selector) {
        if (selector == null) return true;
        boolean active = myInfo.active();
        for (int i = 0; i + 5 <= selector.length(); i += 5) {
            char sign = selector.charAt(i);
            String f = selector.substring(i + 1, i + 5);
            boolean have = "TCP4".equals(f) ? active : false; // we advertise no other features in SU
            if (sign == '+' && !have) return false;
            if (sign == '-' && have) return false;
        }
        return true;
    }

    private void handleResult(AdcCommand c) {
        if (mySid == null || !mySid.equals(c.toSid)) return;
        String fn = c.named("FN");
        if (fn == null) return;
        DCUser from = users.get(c.fromSid);
        if (from == null) return;
        listener.onSearchResult(from, fn, parseLong(c.named("SI")), (int) parseLong(c.named("SL")),
                c.named("TR"), c.named("TO"));
    }

    /** ICMD name TT&lt;command&gt; CT&lt;context&gt; [RM1] [SP1]. */
    private void handleUserCommand(AdcCommand c) {
        String name = c.param(0);
        if (name == null) return;
        int ct = (int) parseLong(c.named("CT", 1));
        listener.onUserCommand(name, c.named("TT", 1), ct, "1".equals(c.named("RM", 1)), "1".equals(c.named("SP", 1)));
    }

    /** Answers a search through the hub: DRES &lt;us&gt; &lt;searcher&gt; FN SI SL [TR] [TO], one per hit. */
    public void sendResults(String searcherSid, String token, List<SearchResult> hits) {
        String sid = mySid;
        if (sid == null) return;
        for (SearchResult r : hits) {
            AdcCommand.Builder b = AdcCommand.of('D', "RES").from(sid).to(searcherSid)
                    .named("FN", AdcSearch.adcPath(r.path, r.dir))
                    .named("SI", String.valueOf(r.dir ? 0 : r.size))
                    .named("SL", String.valueOf(r.freeSlots));
            if (!r.dir && r.tth != null) b.named("TR", r.tth);
            if (token != null) b.named("TO", token);
            send(b.build());
        }
    }

    /** Our own SID on this hub (for hub menu placeholders). */
    public String sid() {
        return mySid;
    }

    /** Our CID (for hub menu placeholders). */
    public String cid() {
        return identity.cid;
    }

    @Override
    public void sendSearchResultViaHub(DCUser me, SearchResult result, String hubName, String searcher) {
        // phase 9
    }

    @Override
    public byte[] searchResultDatagram(DCUser me, SearchResult result, String hubName) {
        return new byte[0]; // phase 9
    }

    @Override
    public void sendConnectToMe(String peerNick, String ip, int port) {
        // phase 8 (DCTM)
    }

    /** A command from the hub's menu: already ADC text, sent as is. */
    @Override
    public void sendHubCommand(String rawCommand) {
        sendRawLine(rawCommand.endsWith("\n") ? rawCommand.substring(0, rawCommand.length() - 1) : rawCommand);
    }

    @Override
    public void close() {
        closedByUs = true;
        closeQuietly();
    }

    // ---- writing ----

    // ---- BLOM ----

    /**
     * Hub: {@code IGET blom / 0 <bytes> BK<k> BH<h>}. We answer {@code HSND blom / 0 <bytes> BK<k> BH<h>} plus the
     * raw filter bytes (as DC++ does), or {@code HSTA} if the parameters are unusable. Built off the reader
     * thread: a big share takes a moment.
     */
    private void sendBloomLater(final AdcCommand get) {
        Thread t = new Thread(() -> {
            try {
                sendBloom(get);
            } catch (RuntimeException e) {
                log("BLOM failed: " + e);
            }
        }, "adc-blom");
        t.setDaemon(true);
        t.start();
    }

    void sendBloom(AdcCommand get) {
        long bytes = parseLong(get.param(3));
        int k = (int) parseLong(get.named("BK", 4));
        int h = (int) parseLong(get.named("BH", 4));
        java.util.Collection<String> tths = myInfo.sharedTths();
        String problem = bytes < 0 || bytes > Integer.MAX_VALUE / 8
                ? "Unsupported m" : AdcBloom.checkParameters(k, h, bytes * 8, tths.size());
        if (problem != null) {
            log("BLOM request refused (" + problem + "): bytes=" + bytes + " k=" + k + " h=" + h);
            send(AdcCommand.of('H', "STA").param("140").param(problem).build());
            return;
        }
        List<byte[]> raw = new ArrayList<>(tths.size());
        for (String b32 : tths) {
            byte[] v = Base32.decode(b32);
            if (v.length == AdcBloom.TTH_BITS / 8) raw.add(v);
        }
        byte[] filter = AdcBloom.build(raw, k, h, bytes * 8);
        AdcCommand.Builder snd = AdcCommand.of('H', "SND");
        for (String p : get.params) snd.param(p); // blom / 0 <bytes> BK.. BH.. echoed, as DC++ does
        sendWithPayload(snd.build().toString(), filter);
        log("sent BLOM filter: " + raw.size() + " files, " + filter.length + " bytes, k=" + k + " h=" + h);
    }

    /** Tests only: capture what we would write to the hub. */
    void setOutputForTest(OutputStream o) {
        this.out = o;
    }

    /** A command line immediately followed by raw bytes, with nothing else written in between. */
    private void sendWithPayload(String line, byte[] payload) {
        OutputStream o = out;
        if (o == null) return;
        try {
            synchronized (writeLock) {
                o.write((line + "\n").getBytes(UTF8));
                o.write(payload);
                o.flush();
            }
        } catch (IOException e) {
            log("write failed: " + e.getMessage());
            closeQuietly();
        }
    }

    private void send(AdcCommand c) {
        sendRawLine(c.toString());
    }

    private void sendRawLine(String line) {
        OutputStream o = out;
        if (o == null) return;
        try {
            synchronized (writeLock) {
                o.write((line + "\n").getBytes(UTF8));
                o.flush();
            }
        } catch (IOException e) {
            log("write failed: " + e.getMessage());
            closeQuietly(); // the reader then reports the disconnect
        }
    }

    private void closeQuietly() {
        final Socket s = socket;
        if (s == null) return;
        if (s instanceof javax.net.ssl.SSLSocket) {
            // Closing TLS writes a close_notify (network I/O): never on the caller's thread, which may be
            // Android's main thread (Delete Hub, Reconnect).
            Thread t = new Thread(() -> closeSocket(s), "adcs-close");
            t.setDaemon(true);
            t.start();
        } else {
            closeSocket(s);
        }
    }

    private static void closeSocket(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    // ---- helpers ----

    private static final String[] RECOVERABLE = {"IQUI ", "ISTA ", "IMSG ", "IINF ", "ISID ", "IGPA ", "ISUP ",
            "BINF ", "BMSG ", "EMSG ", "DMSG "};

    /** Index of a known command glued into the middle of a line, or -1. Package-private for tests. */
    static int embeddedCommandStart(String l) {
        int best = -1;
        for (String k : RECOVERABLE) {
            int i = l.indexOf(k, 1);
            if (i > 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private static String shorten(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static long parseLong(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String orDefault(String v, String d) {
        return v != null ? v : d;
    }

    private void log(String s) {
        try {
            android.util.Log.d("andcpp", "[ADC] " + s);
        } catch (RuntimeException ignored) {
            // JVM unit tests: android.util.Log isn't there
        }
    }

    @Override
    public String toString() {
        return "AdcHubConnection(" + address.toUrl() + ", sid=" + mySid + ")";
    }
}
