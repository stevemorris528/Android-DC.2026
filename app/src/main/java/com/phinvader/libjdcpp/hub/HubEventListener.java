package com.phinvader.libjdcpp.hub;

import com.phinvader.libjdcpp.DCUser;

/**
 * What a hub connection reports to the app. Called on the connection's reader thread: implementations must
 * not block it (no network calls, no long work).
 */
public interface HubEventListener {

    /** A user joined, or their info changed. {@code user} is a snapshot. */
    void onUserUpdated(DCUser user);

    /** A user left. */
    void onUserQuit(DCUser user);

    /** A main chat line, already formatted for display ("&lt;nick&gt; text" or "* nick text"). */
    void onMainChat(String line);

    /**
     * A private message. {@code conversationNick} is whom to reply to (the sender, or the chat room/bot the
     * message came through); {@code fromNick} who wrote it.
     */
    void onPrivateMessage(String conversationNick, String fromNick, String text);

    /** The hub's name (and topic, may be null) arrived or changed. */
    void onHubInfo(String name, String topic);

    /**
     * A peer wants a client-to-client connection (ADC). {@code reverse} false: DCTM, the peer listens on
     * {@code port} and we connect to it; true: DRCM, the peer asks us to send it a CTM. {@code token} ties the
     * connection to the request.
     */
    void onConnectRequest(DCUser peer, String protocol, int port, String token, boolean reverse);

    /**
     * The hub refused to pass on our transfer request (an ISTA whose FC is DCTM or DRCM, e.g. "You are not
     * allowed to download in this hub"). {@code message} is user-readable.
     */
    void onTransferRefused(String message);

    /** Another user's search (ADC BSCH/DSCH/FSCH), to answer off this thread. */
    void onSearch(DCUser from, com.phinvader.libjdcpp.adc.AdcSearch search);

    /**
     * A result for our search (ADC DRES): {@code fn} is the ADC path ("/dir/file", directories end in '/'),
     * {@code tth} may be null.
     */
    void onSearchResult(DCUser from, String fn, long size, int freeSlots, String tth, String token);

    /**
     * A hub menu entry (ADC ICMD): {@code name} uses '/' between submenus, {@code command} is ADC text with
     * %[...] placeholders, {@code context} the UCMD context bits (1 hub, 2 user, 4 search, 8 file list).
     */
    void onUserCommand(String name, String command, int context, boolean remove, boolean separator);

    /**
     * A peer answered our transfer request with a status (ADC DSTA), e.g. code "241" + protocol "ADC/1.0":
     * it doesn't accept that protocol. {@code token} is the request's token (may be null).
     */
    void onPeerStatus(DCUser from, String code, String text, String protocol, String token);

    /** A status or error message from the hub, worth showing in the hub chat. */
    void onStatus(String message);

    /**
     * The connection ended (hub closed it, network error, kicked, ...). Not called after our own close().
     * {@code redirect} is an address the hub asked us to go to instead, or null.
     */
    void onDisconnected(String reason, String redirect);
}
