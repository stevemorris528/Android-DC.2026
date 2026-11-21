package com.phinvader.libjdcpp.hub;

import com.phinvader.libjdcpp.DCPreferences;
import com.phinvader.libjdcpp.DCUser;

import java.util.List;

/**
 * Protocol-neutral link to one hub. DCPPService sends everything that goes to a hub through this interface,
 * so it never builds protocol text itself: {@link NmdcHubConnection} turns these calls into NMDC commands,
 * and the ADC implementation (phase 5) will turn them into ADC commands.
 * <p>
 * Still NMDC-only for now, and reached through {@code HubSession.client}: login, incoming-message handlers,
 * and client-to-client transfers. Those move behind this interface with the ADC hub (phase 5) and the ADC
 * transfers (phase 8).
 */
public interface HubConnection {

    HubAddress.Protocol protocol();

    HubAddress address();

    /** Milliseconds since anything was last received from the hub (grows without bound on a dead link). */
    long millisSinceLastReceived();

    /** Cheap keep-alive with no reply expected. NMDC: an empty command ("|"). */
    void sendKeepAlive();

    /** A command every hub answers, to tell a quiet link from a dead one. NMDC: $GetNickList. */
    void sendLivenessProbe();

    /** Sends (or re-sends) our user info. NMDC: $MyINFO. */
    void updateMyInfo(DCUser me, DCPreferences prefs);

    /** The users currently on the hub. */
    List<DCUser> users();

    void sendMainChat(DCUser me, String text);

    void sendPrivate(DCUser me, String toNick, String text);

    /** Searches for files by words. */
    void search(DCUser me, String text);

    /** Sends one search result back through the hub, to a passive searcher. */
    void sendSearchResultViaHub(DCUser me, SearchResult result, String hubName, String searcher);

    /** The datagram that carries one search result straight to an active searcher's UDP port. */
    byte[] searchResultDatagram(DCUser me, SearchResult result, String hubName);

    /** Asks the peer to connect to us at ip:port (NMDC: $ConnectToMe, e.g. answering its $RevConnectToMe). */
    void sendConnectToMe(String peerNick, String ip, int port);

    /** Sends a command from the hub's own user-command menu; the hub wrote it, so it goes out as is. */
    void sendHubCommand(String rawCommand);

    void close();
}
