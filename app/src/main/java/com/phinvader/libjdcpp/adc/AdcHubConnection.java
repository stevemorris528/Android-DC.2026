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

        send(AdcCommand.of('H', "SUP").param("ADBASE").param("ADTIGR").build());

        if (!loginDone.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            closeQuietly();
            throw new IOException("Hub login timed out");
        }
        if (!loggedIn) {
            closeQuietly();
            throw new IOException(loginFailure != null ? loginFailure : "Hub closed the connection during login");
        }
        log("logged in to " + address.toUrl() + " as " + nick + " (SID " + mySid + ", " + users.size() + " users)");
    }

    private void failLogin(String reason) {
        if (loggedIn) return;
        if (loginFailure == null) loginFailure = reason;
        loginDone.countDown();
        closeQuietly();
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
        if (!loggedIn) {
            failLogin(reason);
        } else if (!closedByUs) {
            listener.onDisconnected(reason, redirect);
        }
    }

    void handleLine(String raw) {
        String l = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
        if (l.isEmpty()) return; // keep-alive
        AdcCommand c;
        try {
            c = AdcCommand.parse(l);
        } catch (IllegalArgumentException e) {
            log("ignoring unparsable line: " + e.getMessage());
            return;
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
                refuseTransfer(c); // phase 8
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
        if (severity == 2) {
            if (!loggedIn) {
                failLogin(msg);
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
            if (tl != null) why.append(" (can't reconnect for ").append(tl).append(" s)");
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

    private void refuseTransfer(AdcCommand c) {
        if (mySid == null || c.fromSid == null || !mySid.equals(c.toSid)) return;
        String token = c.name().equals("DCTM") ? c.param(2) : c.param(1);
        AdcCommand.Builder b = AdcCommand.of('D', "STA").from(mySid).to(c.fromSid)
                .param("141").param("Android DC++ can't transfer files on ADC hubs yet")
                .named("PR", c.param(0));
        if (token != null) b.named("TO", token);
        send(b.build());
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

        AdcCommand.Builder b = AdcCommand.of('B', "INF").from(sid);
        boolean any = false;
        synchronized (sentInf) {
            for (Map.Entry<String, String> e : now.entrySet()) {
                if (full || !e.getValue().equals(sentInf.get(e.getKey()))) {
                    b.named(e.getKey(), e.getValue());
                    any = true;
                }
                if (!e.getKey().equals("PD")) sentInf.put(e.getKey(), e.getValue());
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
        log("search on ADC hubs comes in phase 9; not sent: " + text);
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
        Socket s = socket;
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    // ---- helpers ----

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
