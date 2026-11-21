package com.phinvader.libjdcpp.adc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.phinvader.libjdcpp.DCUser;
import com.phinvader.libjdcpp.hub.HubAddress;
import com.phinvader.libjdcpp.hub.SearchResult;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import org.junit.Test;

public class AdcSearchTest {

    @Test
    public void parsesTermsAndFilters() {
        AdcSearch s = AdcSearch.parse(AdcCommand.parse("BSCH AAAC ANred ANdwarf NOcam EX.mkv EXavi GE1000 LE5000 TY1 TOt1"));
        assertEquals("AAAC", s.fromSid);
        assertEquals("t1", s.token);
        assertEquals("red$dwarf", s.nmdcPattern());
        assertEquals(1, s.nmdcType());
        assertEquals(Arrays.asList("mkv", "avi"), s.extensions);
        assertTrue(s.accepts("Share\\TV\\Red Dwarf S01.mkv", 2000, false));
        assertFalse(s.accepts("Share\\TV\\Red Dwarf CAM.mkv", 2000, false)); // NO
        assertFalse(s.accepts("Share\\TV\\Red Dwarf S01.mp4", 2000, false)); // EX
        assertFalse(s.accepts("Share\\TV\\Red Dwarf S01.mkv", 999, false));  // GE
        assertFalse(s.accepts("Share\\TV\\Red Dwarf S01.mkv", 5001, false)); // LE
        assertFalse(s.accepts("Share\\TV\\Red Dwarf", 0, true));             // TY1 = files only
    }

    @Test
    public void tthAndDirectorySearches() {
        AdcSearch t = AdcSearch.parse(AdcCommand.parse("BSCH AAAC TRabcdef TOx"));
        assertEquals(9, t.nmdcType());
        assertEquals("TTH:ABCDEF", t.nmdcPattern());
        AdcSearch d = AdcSearch.parse(AdcCommand.parse("BSCH AAAC ANmusic TY2"));
        assertEquals(8, d.nmdcType());
        assertTrue(d.accepts("Share\\Music", 0, true));
        assertFalse(d.accepts("Share\\Music\\a.mp3", 10, false));
        AdcSearch eq = AdcSearch.parse(AdcCommand.parse("BSCH AAAC ANx EQ42"));
        assertTrue(eq.accepts("x", 42, false));
        assertFalse(eq.accepts("x", 43, false));
        assertNull(AdcSearch.parse(AdcCommand.parse("BSCH AAAC TOonly")));
    }

    @Test
    public void paths() {
        assertEquals("/Share/TV/a b.mkv", AdcSearch.adcPath("Share\\TV\\a b.mkv", false));
        assertEquals("/Share/TV/", AdcSearch.adcPath("Share\\TV", true));
        assertEquals("Share\\TV\\a b.mkv", AdcSearch.nmdcPath("/Share/TV/a b.mkv"));
        assertEquals("Share\\TV", AdcSearch.nmdcPath("/Share/TV/"));
    }

    /** The hub side: search out, others' searches in (not our own), results in, menus in, our results out. */
    @Test
    public void hubSearchFlow() {
        AdcHubConnectionTest.Events ev = new AdcHubConnectionTest.Events();
        AdcHubConnection c = new AdcHubConnection(HubAddress.parse("adc://h:1"), AdcIdentity.generate(),
                new AdcHubConnection.MyInfo() {
                    public int slots() { return 3; }
                    public int freeSlots() { return 2; }
                    public long sharedFiles() { return 0; }
                    public int[] hubCounts() { return new int[] {1, 0, 0}; }
                    public java.util.Collection<String> sharedTths() { return AdcHubConnectionTest.TTHS; }
                    public boolean active() { return false; }
                }, ev);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        c.setOutputForTest(wire);
        c.handleLine("ISID AAAB");
        c.handleLine("BINF AAAC IDCIDBOB NIbob SUTCP4");
        wire.reset();

        DCUser me = new DCUser();
        me.nick = "me";
        c.search(me, "red dwarf -cam");
        String out = new String(wire.toByteArray());
        assertTrue(out, out.startsWith("BSCH AAAB ANred ANdwarf NOcam TO"));
        wire.reset();
        c.search(me, "TTH:LWPNACQDBZRYXW3VHJVCJ64QBZNGHOHHHZWCLNQ");
        assertTrue(new String(wire.toByteArray()).startsWith("BSCH AAAB TRLWPNACQDBZRYXW3VHJVCJ64QBZNGHOHHHZWCLNQ TO"));

        c.handleLine("BSCH AAAC ANmovie TOt1");
        c.handleLine("BSCH AAAB ANself TOx");       // our own: ignored
        c.handleLine("DSCH AAAC AAAD ANnot\\sus");   // for someone else: ignored
        c.handleLine("FSCH AAAC +TCP4 ANactiveonly"); // we're passive: ignored
        c.handleLine("DRES AAAC AAAB FN/Share/a\\sb.mkv SI100 SL2 TRTTHX TOt9");
        c.handleLine("ICMD Menu/Show\\sIP TTBMSG\\s%[mySID]\\s+myip\\\\s\\n CT1");
        assertEquals(Arrays.asList("bob|movie|t1", "res:bob|/Share/a b.mkv|100|2|TTHX|t9"), ev.searches);
        assertEquals(Arrays.asList("Menu/Show IP|BMSG %[mySID] +myip\\s\n|1|false|false"), ev.menus);

        wire.reset();
        c.sendResults("AAAC", "t1", Arrays.asList(
                new SearchResult("Share\\dir\\a b.mkv", 100, "TTHX", false, 2, 3),
                new SearchResult("Share\\dir", 0, null, true, 2, 3)));
        assertEquals("DRES AAAB AAAC FN/Share/dir/a\\sb.mkv SI100 SL2 TRTTHX TOt1\n"
                + "DRES AAAB AAAC FN/Share/dir/ SI0 SL2 TOt1\n", new String(wire.toByteArray()));
    }
}
