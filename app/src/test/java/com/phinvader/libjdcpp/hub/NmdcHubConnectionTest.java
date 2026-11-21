package com.phinvader.libjdcpp.hub;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The NMDC strings moved out of DCPPService in phase 3. Expected values are written out the way the old
 * DCPPService code built them, so any change to what goes on the wire shows up here.
 */
public class NmdcHubConnectionTest {

    @Test
    public void mainChat() {
        assertEquals("<me> hello there", NmdcHubConnection.formatMainChat("me", "  hello there \n"));
    }

    @Test
    public void privateMessageEscapesPipeAndDollar() {
        assertEquals("$To: bob From: me $<me> a&#124;b &#36;5",
                NmdcHubConnection.formatPrivateMessage("me", "bob", " a|b $5 "));
    }

    @Test
    public void fileSearchResult() {
        SearchResult r = new SearchResult("Share\\Movies\\a.mkv", 12345L, "ABCDEF", false, 2, 3);
        assertEquals("$SR me Share\\Movies\\a.mkv\u000512345 2/3\u0005TTH:ABCDEF (1.2.3.4:411)",
                NmdcHubConnection.formatSearchResult("me", r, "My Hub", "1.2.3.4:411"));
    }

    @Test
    public void directorySearchResultUsesHubName() {
        SearchResult r = new SearchResult("Share\\Movies", 0, null, true, 2, 3);
        assertEquals("$SR me Share\\Movies 2/3\u0005My Hub (1.2.3.4:411)",
                NmdcHubConnection.formatSearchResult("me", r, " My Hub ", "1.2.3.4:411"));
    }

    @Test
    public void directorySearchResultWithoutHubNameUsesAddress() {
        SearchResult r = new SearchResult("Share\\Movies", 0, null, true, 0, 3);
        assertEquals("$SR me Share\\Movies 0/3\u00051.2.3.4:1069 (1.2.3.4:1069)",
                NmdcHubConnection.formatSearchResult("me", r, null, "1.2.3.4:1069"));
        assertEquals("$SR me Share\\Movies 0/3\u00051.2.3.4:1069 (1.2.3.4:1069)",
                NmdcHubConnection.formatSearchResult("me", r, "  ", "1.2.3.4:1069"));
    }

    @Test
    public void connectToMe() {
        assertEquals("$ConnectToMe peer 220.158.199.170:47621",
                NmdcHubConnection.formatConnectToMe("peer", "220.158.199.170", 47621));
    }
}
