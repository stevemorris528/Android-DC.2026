package com.phinvader.libjdcpp.adc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

public class AdcCommandTest {

    @Test
    public void escapeRoundTrip() {
        String s = "a b\\c\nd  e";
        assertEquals("a\\sb\\\\c\\nd\\s\\se", AdcCommand.escape(s));
        assertEquals(s, AdcCommand.unescape(AdcCommand.escape(s)));
        assertEquals("plain", AdcCommand.unescape("plain"));
    }

    @Test
    public void parseBroadcastInf() {
        AdcCommand c = AdcCommand.parse("BINF AAAB IDABCDEF NIbob\\sthe\\sbuilder SS1024 SUTCP4,UDP4");
        assertEquals('B', c.type);
        assertEquals("INF", c.cmd);
        assertEquals("AAAB", c.fromSid);
        assertEquals("bob the builder", c.named("NI"));
        assertEquals("1024", c.named("SS"));
        assertEquals("TCP4,UDP4", c.named("SU"));
        assertNull(c.named("DE"));
    }

    @Test
    public void parseDirectAndEcho() {
        AdcCommand c = AdcCommand.parse("EMSG AAAC AAAB hi\\sthere PMAAAC");
        assertEquals("AAAC", c.fromSid);
        assertEquals("AAAB", c.toSid);
        assertEquals("hi there", c.param(0));
        assertEquals("AAAC", c.named("PM", 1));
    }

    @Test
    public void positionalTextIsNotMistakenForACode() {
        AdcCommand c = AdcCommand.parse("BMSG AAAC PMs\\sare\\sfun");
        assertEquals("PMs are fun", c.param(0));
        assertNull(c.named("PM", 1)); // skipping the positional text
    }

    @Test
    public void parseHubAndInfoMessages() {
        AdcCommand sid = AdcCommand.parse("ISID AAAB");
        assertEquals("AAAB", sid.param(0));
        assertNull(sid.fromSid);
        AdcCommand sta = AdcCommand.parse("ISTA 223 Bad\\spassword");
        assertEquals("223", sta.param(0));
        assertEquals("Bad password", sta.param(1));
        AdcCommand f = AdcCommand.parse("FSCH AAAB +TCP4 ANfoo TOabc");
        assertEquals("+TCP4", f.extra);
        assertEquals("foo", f.named("AN"));
        AdcCommand u = AdcCommand.parse("URES SOMECID FN/a\\sb.txt");
        assertEquals("SOMECID", u.extra);
        assertEquals("/a b.txt", u.named("FN"));
    }

    @Test
    public void buildAndFormat() {
        assertEquals("HSUP ADBASE ADTIGR", AdcCommand.of('H', "SUP").param("ADBASE").param("ADTIGR").build().toString());
        assertEquals("BINF AAAB NIa\\sb SS0",
                AdcCommand.of('B', "INF").from("AAAB").named("NI", "a b").named("SS", "0").named("DE", null)
                        .build().toString());
        assertEquals("EMSG AAAB AAAC hello\\sworld PMAAAB",
                AdcCommand.of('E', "MSG").from("AAAB").to("AAAC").param("hello world").named("PM", "AAAB")
                        .build().toString());
        String line = "DSTA AAAB AAAC 141 No\\stransfers PRADC/1.0 TOxyz";
        assertEquals(line, AdcCommand.parse(line).toString());
    }

    @Test
    public void rejectsGarbage() {
        for (String bad : new String[] {"", "BIN", "XINF AAAB", "BINF", "BINFX AAAB", "DMSG AAAB"}) {
            try {
                AdcCommand.parse(bad);
                fail("Expected rejection: " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void statusDescriptions() {
        assertEquals("Wrong password", AdcHubConnection.describeStatus("223", "Wrong password", null));
        assertEquals("Nick is already taken (Nick taken, please pick another one)",
                AdcHubConnection.describeStatus("222", "Nick taken, please pick another one", null));
        assertEquals("Temporarily banned for 600 s",
                AdcHubConnection.describeStatus("232", "", AdcCommand.parse("ISTA 232 \\s TL600")));
        assertEquals("Something odd", AdcHubConnection.describeStatus("299", "Something odd", null));
    }
}
