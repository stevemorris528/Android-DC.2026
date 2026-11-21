package com.phinmadvader.andcpp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.phinvader.libjdcpp.DCMessage;
import com.phinvader.libjdcpp.adc.AdcCommand;

import org.junit.Test;

/** ADC CGETs become the same request object an NMDC $ADCGET gives serveRequest(). */
public class CgetRequestTest {

    @Test
    public void fileByPathKeepsResumeOffset() {
        DCMessage m = PeerConnectionHandler.requestFromCget(AdcCommand.parse("CGET file /Share/a\\sb.mkv 1000 -1"));
        assertEquals("ADCGET", m.command);
        assertEquals("file", m.adc_type);
        assertEquals("/Share/a b.mkv", m.get_filename);
        assertEquals(1001, m.startOffset); // serveRequest subtracts 1 ($Get is 1-based)
        assertEquals(-1, m.bytesToTransfer);
        assertFalse(m.is_tthl);
    }

    @Test
    public void fileByTthAndFileList() {
        DCMessage t = PeerConnectionHandler.requestFromCget(AdcCommand.parse("CGET file TTH/ABCDEF 0 4096"));
        assertEquals("TTH/ABCDEF", t.get_filename);
        assertEquals(1, t.startOffset);
        assertEquals(4096, t.bytesToTransfer);
        DCMessage l = PeerConnectionHandler.requestFromCget(AdcCommand.parse("CGET file files.xml.bz2 0 -1"));
        assertEquals("files.xml.bz2", l.get_filename);
    }

    @Test
    public void tthlAndPartialList() {
        DCMessage t = PeerConnectionHandler.requestFromCget(AdcCommand.parse("CGET tthl TTH/ABCDEF 0 -1"));
        assertTrue(t.is_tthl);
        assertEquals(0, t.startOffset);
        DCMessage l = PeerConnectionHandler.requestFromCget(AdcCommand.parse("CGET list /Share/Music/ 0 -1 RE1"));
        assertEquals("list", l.adc_type);
        assertTrue(l.adc_recursive);
        DCMessage l2 = PeerConnectionHandler.requestFromCget(AdcCommand.parse("CGET list /Share/Music/ 0 -1"));
        assertFalse(l2.adc_recursive);
    }

    @Test
    public void rejectsOthers() {
        assertNull(PeerConnectionHandler.requestFromCget(AdcCommand.parse("CGET blom / 0 32")));
        assertNull(PeerConnectionHandler.requestFromCget(AdcCommand.parse("CGET file /x")));
        assertNull(PeerConnectionHandler.requestFromCget(AdcCommand.parse("CGET file /x abc -1")));
        assertNull(PeerConnectionHandler.requestFromCget(AdcCommand.parse("CSND file /x 0 1")));
    }
}
