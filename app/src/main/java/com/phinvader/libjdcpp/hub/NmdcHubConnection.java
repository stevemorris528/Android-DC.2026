package com.phinvader.libjdcpp.hub;

import com.phinvader.libjdcpp.DCClient;
import com.phinvader.libjdcpp.DCPreferences;
import com.phinvader.libjdcpp.DCUser;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link HubConnection} for NMDC, a thin adapter over the existing {@link DCClient}. The NMDC strings that
 * DCPPService used to build itself live here, unchanged; the static format methods are unit-tested against
 * the exact strings the service sent before.
 */
public final class NmdcHubConnection implements HubConnection {

    /** NMDC text goes out as ISO-8859-1 (see MessageHandler.send_msg and DCMessage). */
    private static final Charset NMDC_CHARSET = Charset.forName("ISO-8859-1");

    private final HubAddress address;
    private final DCClient client;

    public NmdcHubConnection(HubAddress address, DCClient client) {
        this.address = address;
        this.client = client;
    }

    /** The wrapped client, for the NMDC-only parts not behind HubConnection yet (handlers, transfers). */
    public DCClient client() {
        return client;
    }

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
        return client.getMillisSinceLastReceived();
    }

    @Override
    public void sendKeepAlive() {
        client.sendRaw(""); // standard keep-alive: a lone "|"
    }

    @Override
    public void sendLivenessProbe() {
        client.sendRaw("$GetNickList"); // every NMDC hub answers it, even an empty one
    }

    @Override
    public void updateMyInfo(DCUser me, DCPreferences prefs) {
        client.sendMyInfo(me, prefs);
    }

    @Override
    public List<DCUser> users() {
        List<DCUser> l = client.get_nick_list();
        return l != null ? l : new ArrayList<DCUser>();
    }

    @Override
    public void sendMainChat(DCUser me, String text) {
        client.sendRaw(formatMainChat(me.nick, text));
    }

    @Override
    public void sendPrivate(DCUser me, String toNick, String text) {
        client.sendRaw(formatPrivateMessage(me.nick, toNick, text));
    }

    @Override
    public void search(DCUser me, String text) {
        client.searchForFile(text, me, 1);
    }

    @Override
    public void sendSearchResultViaHub(DCUser me, SearchResult r, String hubName, String searcher) {
        client.sendRaw(formatSearchResult(me.nick, r, hubName, hubPort()) + "\u0005" + searcher);
    }

    @Override
    public byte[] searchResultDatagram(DCUser me, SearchResult r, String hubName) {
        return (formatSearchResult(me.nick, r, hubName, hubPort()) + "|").getBytes(NMDC_CHARSET);
    }

    @Override
    public void sendConnectToMe(String peerNick, String ip, int port) {
        client.sendRaw(formatConnectToMe(peerNick, ip, port));
    }

    @Override
    public void sendHubCommand(String rawCommand) {
        client.sendRaw(rawCommand);
    }

    @Override
    public void close() {
        client.close();
    }

    private String hubPort() {
        return address.host + ":" + address.port;
    }

    // ---- NMDC text (exactly what DCPPService sent before phase 3) ----

    /** Main chat: {@code <nick> text}. */
    public static String formatMainChat(String myNick, String text) {
        return "<" + myNick + "> " + text.trim();
    }

    /** {@code $To: <to> From: <me> $<me> text}, with '|' and '$' escaped as NMDC requires. */
    public static String formatPrivateMessage(String myNick, String toNick, String text) {
        String sanitized = text.trim().replace("|", "&#124;").replace("$", "&#36;");
        return "$To: " + toNick + " From: " + myNick + " $<" + myNick + "> " + sanitized;
    }

    /**
     * {@code $SR} without the trailing target/"|":
     * file: {@code $SR <me> <path>\x05<size> <free>/<total>\x05TTH:<tth> (<hub ip:port>)};
     * dir: {@code $SR <me> <path> <free>/<total>\x05<hub name> (<hub ip:port>)}.
     * An empty hub name falls back to the hub address.
     */
    public static String formatSearchResult(String myNick, SearchResult r, String hubName, String hubAddr) {
        String slots = r.freeSlots + "/" + r.totalSlots;
        String name = (hubName != null && !hubName.trim().isEmpty()) ? hubName.trim() : hubAddr;
        if (r.dir) {
            return "$SR " + myNick + " " + r.path + " " + slots + "\u0005" + name + " (" + hubAddr + ")";
        }
        return "$SR " + myNick + " " + r.path + "\u0005" + r.size + " " + slots + "\u0005TTH:" + r.tth
                + " (" + hubAddr + ")";
    }

    /** {@code $ConnectToMe <peer> <ip>:<port>}. */
    public static String formatConnectToMe(String peerNick, String ip, int port) {
        return "$ConnectToMe " + peerNick + " " + ip + ":" + port;
    }
}
