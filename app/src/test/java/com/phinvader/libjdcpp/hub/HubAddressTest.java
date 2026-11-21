package com.phinvader.libjdcpp.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;

import org.junit.Test;

public class HubAddressTest {

    @Test
    public void bareHostIsNmdcOnDefaultPort() {
        HubAddress a = HubAddress.parse("hub.example.net");
        assertEquals(HubAddress.Protocol.NMDC, a.protocol);
        assertEquals("hub.example.net", a.host);
        assertEquals(411, a.port);
        assertFalse(a.portExplicit);
        assertEquals("hub.example.net", a.id());
        assertEquals("hub.example.net", a.prefKey());
        assertTrue(a.isSupported());
    }

    @Test
    public void bareHostPortKeepsOldFormat() {
        HubAddress a = HubAddress.parse("bigchill.servemp3.com:1069");
        assertEquals(HubAddress.Protocol.NMDC, a.protocol);
        assertEquals(1069, a.port);
        assertEquals("bigchill.servemp3.com:1069", a.id());
        assertEquals(Arrays.asList("bigchill.servemp3.com:1069", "bigchill.servemp3.com"), a.prefKeys());
        assertEquals(Arrays.asList("bigchill.servemp3.com:1069", "bigchill.servemp3.com"), a.prefKeysToWrite());
    }

    @Test
    public void explicit411IsKept() {
        HubAddress a = HubAddress.parse("host:411");
        assertTrue(a.portExplicit);
        assertEquals("host:411", a.id());
        assertEquals(Arrays.asList("host:411", "host"), a.prefKeys());
    }

    @Test
    public void bareHostPrefKeysAlsoTryHostPort() {
        assertEquals(Arrays.asList("host", "host:411"), HubAddress.parse("host").prefKeys());
        assertEquals(Arrays.asList("host"), HubAddress.parse("host").prefKeysToWrite());
    }

    @Test
    public void dchubAndNmdcSchemesNormaliseToBare() {
        assertEquals("host:1234", HubAddress.parse("dchub://host:1234").id());
        assertEquals("host:1234", HubAddress.parse("DCHUB://host:1234/").id());
        assertEquals("host", HubAddress.parse("nmdc://host").id());
        assertEquals("dchub://host:411", HubAddress.parse("host").toUrl());
    }

    @Test
    public void adcKeepsScheme() {
        HubAddress a = HubAddress.parse("adc://192.168.1.10:2780");
        assertEquals(HubAddress.Protocol.ADC, a.protocol);
        assertEquals("192.168.1.10", a.host);
        assertEquals(2780, a.port);
        assertEquals("adc://192.168.1.10:2780", a.id());
        assertEquals("adc://192.168.1.10:2780", a.prefKey());
        assertEquals(Arrays.asList("adc://192.168.1.10:2780"), a.prefKeys());
        assertEquals(Arrays.asList("adc://192.168.1.10:2780"), a.prefKeysToWrite());
        assertTrue(a.isSupported()); // since phase 5
        assertNull(a.unsupportedReason());
    }

    @Test
    public void adcsWithKeyprint() {
        HubAddress a = HubAddress.parse("adcs://hub.dcbase.org:16591/?kp=SHA256/ABCDEFGH234567");
        assertEquals(HubAddress.Protocol.ADCS, a.protocol);
        assertTrue(a.protocol.secure);
        assertEquals(16591, a.port);
        assertEquals("SHA256/ABCDEFGH234567", a.kp);
        assertEquals("adcs://hub.dcbase.org:16591/?kp=SHA256/ABCDEFGH234567", a.id());
        assertEquals("adcs://hub.dcbase.org:16591", a.prefKey());
        // Round-trips through its own id.
        assertEquals(a, HubAddress.parse(a.id()));
    }

    @Test
    public void keyprintWithoutSlashBeforeQuery() {
        assertEquals("SHA256/XYZ", HubAddress.parse("adcs://h:1?kp=SHA256/XYZ").kp);
        assertEquals("SHA256/XYZ", HubAddress.parse("adcs://h:1/?foo=1&KP=SHA256/XYZ").kp);
        assertNull(HubAddress.parse("adcs://h:1/").kp);
    }

    @Test
    public void nmdcsHasDefaultPortAndScheme() {
        HubAddress a = HubAddress.parse("nmdcs://international.dchub.net");
        assertEquals(HubAddress.Protocol.NMDCS, a.protocol);
        assertEquals(411, a.port);
        assertEquals("nmdcs://international.dchub.net:411", a.id());
        assertTrue(a.isSupported());
    }

    @Test
    public void sessionKeys() {
        assertEquals("host:411", HubAddress.parse("host").sessionKey());
        assertEquals("host:1069", HubAddress.parse("dchub://host:1069").sessionKey());
        assertEquals("nmdcs://host:411", HubAddress.parse("nmdcs://host").sessionKey());
        assertEquals("adcs://h:1", HubAddress.parse("adcs://h:1/?kp=SHA256/X").sessionKey());
    }

    @Test
    public void adcNeedsPort() {
        expectInvalid("adc://host");
        expectInvalid("adcs://host/");
    }

    @Test
    public void invalidAddresses() {
        expectInvalid("");
        expectInvalid("   ");
        expectInvalid(null);
        expectInvalid("ftp://host:21");
        expectInvalid("host:abc");
        expectInvalid("host:0");
        expectInvalid("host:70000");
        expectInvalid("a:b:c");
        expectInvalid(":411");
        assertNull(HubAddress.tryParse("a:b:c"));
    }

    @Test
    public void ipv6InBrackets() {
        HubAddress a = HubAddress.parse("adc://[2001:db8::1]:2780");
        assertEquals("[2001:db8::1]", a.host);
        assertEquals(2780, a.port);
        assertEquals("adc://[2001:db8::1]:2780", a.id());
    }

    @Test
    public void sameHubIgnoresCaseAndKeyprint() {
        assertTrue(HubAddress.parse("adcs://Hub.Example:1/?kp=SHA256/A")
                .sameHub(HubAddress.parse("adcs://hub.example:1")));
        assertFalse(HubAddress.parse("adc://h:1").sameHub(HubAddress.parse("adcs://h:1")));
        assertFalse(HubAddress.parse("h:1").sameHub(HubAddress.parse("adc://h:1")));
        assertTrue(HubAddress.parse("h").sameHub(HubAddress.parse("h:411")));
    }

    @Test
    public void ofBuildsSessionIds() {
        assertEquals("host", HubAddress.of(HubAddress.Protocol.NMDC, "host", 411).id());
        assertEquals("host:1069", HubAddress.of(HubAddress.Protocol.NMDC, "host", 1069).id());
        assertEquals("adc://host:411", HubAddress.of(HubAddress.Protocol.ADC, "host", 411).id());
    }

    private static void expectInvalid(String s) {
        try {
            HubAddress.parse(s);
            fail("Expected invalid: " + s);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
