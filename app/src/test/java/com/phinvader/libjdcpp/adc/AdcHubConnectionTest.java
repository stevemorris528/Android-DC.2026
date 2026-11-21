package com.phinvader.libjdcpp.adc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.phinmadvader.andcpp.util.Base32;
import com.phinmadvader.andcpp.util.TigerDigest;
import com.phinvader.libjdcpp.DCUser;
import com.phinvader.libjdcpp.hub.HubAddress;
import com.phinvader.libjdcpp.hub.HubEventListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/** AdcHubConnection against a scripted fake hub on a loopback socket (the ADCH++ login sequence). */
public class AdcHubConnectionTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final AdcIdentity ID = AdcIdentity.generate();

    private static final AdcHubConnection.MyInfo INFO = new AdcHubConnection.MyInfo() {
        public int slots() { return 3; }
        public int freeSlots() { return 2; }
        public long sharedFiles() { return 42; }
        public int[] hubCounts() { return new int[] {1, 0, 0}; }
    };

    /** Records listener events. */
    static final class Events implements HubEventListener {
        final List<DCUser> updated = new CopyOnWriteArrayList<>();
        final List<DCUser> quit = new CopyOnWriteArrayList<>();
        final List<String> chat = new CopyOnWriteArrayList<>();
        final List<String> pms = new CopyOnWriteArrayList<>();
        final List<String> status = new CopyOnWriteArrayList<>();
        final BlockingQueue<String> disconnected = new LinkedBlockingQueue<>();
        volatile String hubName;

        public void onUserUpdated(DCUser u) { updated.add(u); }
        public void onUserQuit(DCUser u) { quit.add(u); }
        public void onMainChat(String line) { chat.add(line); }
        public void onPrivateMessage(String conv, String from, String text) { pms.add(conv + "|" + from + "|" + text); }
        public void onHubInfo(String name, String topic) { hubName = name; }
        public void onStatus(String m) { status.add(m); }
        public void onDisconnected(String reason, String redirect) {
            disconnected.add(reason + (redirect != null ? " -> " + redirect : ""));
        }
    }

    /** One scripted hub connection: read our line, check it, write the hub's lines. */
    interface Script {
        void run(BufferedReader in, OutputStream out) throws Exception;
    }

    private static final class FakeHub implements AutoCloseable {
        final ServerSocket server;
        final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();

        FakeHub(Script script) throws IOException {
            server = new ServerSocket(0);
            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), UTF8));
                    script.run(new BufferedReader(in) {
                        @Override
                        public String readLine() throws IOException {
                            String l = super.readLine();
                            if (l != null) received.add(l);
                            return l;
                        }
                    }, s.getOutputStream());
                    // keep the socket open until the client closes it
                    while (in.read() >= 0) { /* drain */ }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            t.setDaemon(true);
            t.start();
        }

        Socket connect() throws IOException {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()), 5000);
            return s;
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    static void w(OutputStream out, String... lines) throws IOException {
        for (String l : lines) out.write((l + "\n").getBytes(UTF8));
        out.flush();
    }

    static Map<String, String> fields(String infLine) {
        AdcCommand c = AdcCommand.parse(infLine);
        Map<String, String> m = new HashMap<>();
        for (String p : c.params) m.put(p.substring(0, 2), p.substring(2));
        return m;
    }

    private static AdcHubConnection newConn(Events ev) {
        return new AdcHubConnection(HubAddress.parse("adc://127.0.0.1:1"), ID, INFO, ev);
    }

    @Test
    public void passwordHashMatchesSpec() {
        String data = Base32.encode(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24});
        TigerDigest t = new TigerDigest();
        t.update("sécret".getBytes(UTF8));
        t.update(Base32.decode(data));
        assertEquals(Base32.encode(t.digest()), AdcHubConnection.passwordHash("sécret", data));
    }

    @Test
    public void fullLoginWithPasswordUsersChatAndPm() throws Exception {
        final String gpa = Base32.encode(new byte[24]);
        Events ev = new Events();
        try (FakeHub hub = new FakeHub((in, out) -> {
            assertEquals("HSUP ADBASE ADTIGR", in.readLine());
            w(out, "ISUP ADBASE ADTIGR", "ISID AAAB", "IINF CT32 VEADCH++\\s3.1.0 NITest\\sHub DEA\\stopic");
            Map<String, String> inf = fields(in.readLine());
            assertEquals(ID.cid, inf.get("ID"));
            assertEquals(ID.pid, inf.get("PD"));
            assertEquals("me", inf.get("NI"));
            assertEquals("1000", inf.get("SS"));
            assertEquals("42", inf.get("SF"));
            assertEquals("3", inf.get("SL"));
            assertEquals(AdcHubConnection.CLIENT_VERSION, inf.get("VE"));
            assertFalse("passive until ADC transfers exist", inf.containsKey("SU") || inf.containsKey("I4"));
            w(out, "IGPA " + gpa);
            String pas = in.readLine();
            assertEquals("HPAS " + AdcHubConnection.passwordHash("pw", gpa), pas);
            w(out, "BINF AAAC IDCIDOFBOB NIbob SS5000 SUTCP4,UDP4 I41.2.3.4 CT2 VEDC++\\s0.868 SL5 HN1 HR2 HO0",
                    "BINF AAAD NIbot CT1",
                    "BINF AAAB ID" + ID.cid + " NIme SS1000");
            // after login
            w(out, "BMSG AAAC hello\\sworld",
                    "BMSG AAAC waves ME1",
                    "IMSG Welcome!",
                    "EMSG AAAC AAAB hi\\sme PMAAAC",
                    "EMSG AAAB AAAC our\\sown\\secho PMAAAB",
                    "DMSG AAAD AAAB room\\smessage PMAAAD",
                    "BINF AAAC SS6000",
                    "IQUI AAAD");
            assertEquals("EMSG AAAB AAAC yo PMAAAB", in.readLine());
            assertEquals("BMSG AAAB hi\\sall", in.readLine());
            assertEquals("BINF AAAB SS2000", in.readLine()); // delta: only what changed
        })) {
            AdcHubConnection c = newConn(ev);
            c.connect(hub.connect(), "me", "pw", 1000, 5000);
            assertEquals("AAAB", c.mySid());
            assertEquals("Test Hub", c.hubName());
            assertEquals("ADCH++ 3.1.0", c.hubSoftware());

            waitFor(() -> ev.quit.size() == 1);
            DCUser bob = find(c.users(), "bob");
            assertNotNull(bob);
            assertEquals("CIDOFBOB", bob.cid);
            assertEquals(6000, bob.share_size); // delta applied, other fields kept
            assertEquals("1.2.3.4", bob.ip);
            assertTrue(bob.active);
            assertFalse(bob.op);
            assertEquals("DC++ 0.868", bob.client);
            assertEquals("<DC++ 0.868,M:A,H:1/2/0,S:5>", bob.tag);
            assertEquals("bot", ev.quit.get(0).nick);
            assertEquals(null, find(c.users(), "bot"));
            assertNotNull(find(c.users(), "me"));

            assertEquals("<bob> hello world", ev.chat.get(0));
            assertEquals("* bob waves", ev.chat.get(1));
            assertEquals("<Test Hub> Welcome!", ev.chat.get(2));
            assertEquals(3, c.boardMessages().size());
            assertEquals(2, ev.pms.size()); // our own echo is ignored
            assertEquals("bob|bob|hi me", ev.pms.get(0));
            assertEquals("bot|bot|room message", ev.pms.get(1));

            DCUser me = new DCUser();
            me.nick = "me";
            c.sendPrivate(me, "BOB", "yo");
            c.sendMainChat(me, "  hi all ");
            me.share_size = 2000;
            c.updateMyInfo(me, null);

            hub.received.poll(5, TimeUnit.SECONDS);
            waitFor(() -> hub.received.size() >= 0 && hub.errors.isEmpty() && receivedContains(hub, "BINF AAAB SS2000"));
            c.close();
            assertTrue(ev.disconnected.poll(500, TimeUnit.MILLISECONDS) == null); // our own close: no event
            Throwable e = hub.errors.poll(1, TimeUnit.SECONDS);
            if (e != null && !(e instanceof IOException)) throw new AssertionError(e);
        }
    }

    @Test
    public void wrongPasswordFailsLogin() throws Exception {
        Events ev = new Events();
        try (FakeHub hub = new FakeHub((in, out) -> {
            in.readLine();
            w(out, "ISUP ADBASE ADTIGR", "ISID AAAB");
            in.readLine();
            w(out, "IGPA " + Base32.encode(new byte[24]));
            in.readLine();
            w(out, "ISTA 223 Invalid\\spassword", "IQUI AAAB");
        })) {
            try {
                newConn(ev).connect(hub.connect(), "me", "bad", 0, 5000);
                fail("login should fail");
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().startsWith("Wrong password"));
            }
        }
    }

    @Test
    public void registeredNickWithoutPassword() throws Exception {
        try (FakeHub hub = new FakeHub((in, out) -> {
            in.readLine();
            w(out, "ISUP ADBASE ADTIGR", "ISID AAAB");
            in.readLine();
            w(out, "IGPA " + Base32.encode(new byte[24]));
        })) {
            try {
                newConn(new Events()).connect(hub.connect(), "me", "", 0, 5000);
                fail("login should fail");
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("password"));
            }
        }
    }

    @Test
    public void nickTakenAndKickAfterLogin() throws Exception {
        try (FakeHub hub = new FakeHub((in, out) -> {
            in.readLine();
            w(out, "ISUP ADBASE ADTIGR", "ISID AAAB");
            in.readLine();
            w(out, "ISTA 222 Nick\\staken");
        })) {
            try {
                newConn(new Events()).connect(hub.connect(), "me", null, 0, 5000);
                fail("login should fail");
            } catch (IOException e) {
                assertTrue(e.getMessage(), e.getMessage().startsWith("Nick is already taken"));
            }
        }

        Events ev = new Events();
        try (FakeHub hub = new FakeHub((in, out) -> {
            in.readLine();
            w(out, "ISUP ADBASE ADTIGR", "ISID AAAB");
            in.readLine();
            w(out, "BINF AAAB NIme");
            Thread.sleep(200);
            w(out, "IQUI AAAB MSFlooding TL60 RDadc://other.hub:2780");
        })) {
            newConn(ev).connect(hub.connect(), "me", null, 0, 5000);
            String d = ev.disconnected.poll(5, TimeUnit.SECONDS);
            assertEquals("Disconnected by the hub: Flooding (can't reconnect for 60 s) -> adc://other.hub:2780"
                    .replace("Disconnected by the hub", "Redirected to adc://other.hub:2780"), d);
        }
    }

    /** A hub that puts a raw newline in message text: the kick glued to the text's tail must still count. */
    @Test
    public void kickGluedToStrayTextIsRecovered() throws Exception {
        assertEquals(5, AdcHubConnection.embeddedCommandStart("sorryIQUI AAAB"));
        assertEquals(-1, AdcHubConnection.embeddedCommandStart("just text"));
        Events ev = new Events();
        try (FakeHub hub = new FakeHub((in, out) -> {
            in.readLine();
            w(out, "ISUP ADBASE ADTIGR", "ISID AAAB");
            in.readLine();
            w(out, "BINF AAAB NIme");
            Thread.sleep(200);
            out.write("IMSG Your\\sshare\\sis\\stoo\\ssmall,\nsorryIQUI AAAB MSMinimum\\sshare\\s10\\sGiB\n".getBytes(UTF8));
            out.flush();
        })) {
            newConn(ev).connect(hub.connect(), "me", null, 0, 5000);
            assertEquals("Disconnected by the hub: Minimum share 10 GiB", ev.disconnected.poll(5, TimeUnit.SECONDS));
            assertEquals("<Hub> Your share is too small,", ev.chat.get(0));
        }
    }

    @Test
    public void loginTimesOut() throws Exception {
        try (FakeHub hub = new FakeHub((in, out) -> in.readLine())) {
            try {
                newConn(new Events()).connect(hub.connect(), "me", null, 0, 500);
                fail("should time out");
            } catch (IOException e) {
                assertEquals("Hub login timed out", e.getMessage());
            }
        }
    }

    // ---- helpers ----

    private static DCUser find(List<DCUser> users, String nick) {
        for (DCUser u : users) if (nick.equals(u.nick)) return u;
        return null;
    }

    private static boolean receivedContains(FakeHub hub, String line) {
        for (String s : hub.received) if (s.equals(line)) return true;
        return false;
    }

    interface Cond {
        boolean ok();
    }

    private static void waitFor(Cond c) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!c.ok()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting");
            Thread.sleep(20);
        }
    }
}
