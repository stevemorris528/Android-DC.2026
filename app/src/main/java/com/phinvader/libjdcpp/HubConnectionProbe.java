package com.phinvader.libjdcpp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Minimal NMDC hub-entry probe built on the libjdcpp protocol code.
 * It stops after completing the initial login exchange; it does not start the
 * full DC++ client, user-list, search, or download subsystems.
 */
public final class HubConnectionProbe {
    public interface Listener {
        void onStatus(String status);
    }

    public static final class Result {
        public final boolean tcpConnected;
        public final boolean hubResponded;
        public final boolean enteredHub;
        public final String message;
        public final String lastHubMessage;

        Result(boolean tcpConnected, boolean hubResponded, boolean enteredHub,
               String message, String lastHubMessage) {
            this.tcpConnected = tcpConnected;
            this.hubResponded = hubResponded;
            this.enteredHub = enteredHub;
            this.message = message;
            this.lastHubMessage = lastHubMessage;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 10000;

    private HubConnectionProbe() { }

    public static Result run(String host, int port, String nick, Listener listener) {
        return run(host, port, nick, null, listener);
    }

    public static Result run(String host, int port, String nick, String password, Listener listener) {
        Socket socket = new Socket();
        String lastMessage = "";
        boolean tcpConnected = false;
        boolean hubResponded = false;
        try {
            report(listener, "Connecting to " + host + ":" + port + "...");
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            tcpConnected = true;
            socket.setSoTimeout(READ_TIMEOUT_MS);
            report(listener, "TCP connection established. Waiting for hub handshake...");

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String lock = readMessage(in);
            lastMessage = lock;
            if (lock == null || !lock.startsWith("$Lock ")) {
                return new Result(true, false, false, "TCP connection succeeded, but the hub did not send a valid NMDC $Lock handshake.", lastMessage);
            }

            DCMessage lockMessage = DCMessage.parse_message(DCFunctions.toBytes(lock));
            if (lockMessage.lock_s == null || lockMessage.lock_s.length() < 3) {
                return new Result(true, true, false, "The remote endpoint responded, but its $Lock message was invalid.", lastMessage);
            }
            report(listener, "Hub handshake received. Sending NMDC key and nickname...");
            send(out, "$Supports NoGetINFO");
            byte[] key = DCFunctions.convert_lock_to_key(DCFunctions.toBytes(lockMessage.lock_s));
            sendBytes(out, "$Key " + new String(key, "ISO-8859-1") + "|");
            send(out, "$ValidateNick " + nick);

            while (true) {
                String response = readMessage(in);
                if (response == null) {
                    return new Result(tcpConnected, hubResponded, false, "The hub closed the connection before confirming the nickname.", lastMessage);
                }
                lastMessage = response;
                hubResponded = true;
                report(listener, "Hub: " + response);
                if (response.equals("$Hello " + nick)) {
                    report(listener, "Hub accepted the nickname. Completing initial client entry...");
                    send(out, "$Version 1,0091");
                    send(out, "$GetNickList");
                    send(out, "$MyINFO $ALL " + nick + "  <++ V:1.0,M:P,H:1/0/0,S:3>$ $1\001$ $0$");
                    return new Result(true, true, true, "SUCCESS: the hub accepted the nickname and completed the initial NMDC entry exchange.", lastMessage);
                }
                if (response.startsWith("$GetPass")) {
                    if (password != null && !password.isEmpty()) {
                        report(listener, "Hub requested password. Sending password...");
                        send(out, "$MyPass " + password);
                        continue;
                    } else {
                        return new Result(true, true, false, "Hub requires password, but no password was provided for " + nick, lastMessage);
                    }
                }
                if (response.startsWith("$ValidateDenide") || response.startsWith("$HubIsFull") ||
                    response.startsWith("$BadPass") ||
                    response.startsWith("$ForceMove") || response.startsWith("$HubQuit")) {
                    return new Result(true, true, false, "The hub was reached but did not admit this login: " + response, lastMessage);
                }
            }
        } catch (SocketTimeoutException e) {
            return new Result(tcpConnected, hubResponded, false, "TCP connection: " + (tcpConnected ? "successful" : "not established") + ". Hub response: " + (hubResponded ? "received" : "not received") + ". Timed out waiting for the next hub message. Last response: " + lastMessage, lastMessage);
        } catch (Exception e) {
            return new Result(tcpConnected, hubResponded, false, "Connection test failed. TCP connection: " + (tcpConnected ? "successful" : "not established") + ". Hub response: " + (hubResponded ? "received" : "not received") + ". " + e.getClass().getSimpleName() + ": " + e.getMessage(), lastMessage);
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static void report(Listener listener, String status) {
        if (listener != null) listener.onStatus(status);
    }

    private static void send(OutputStream out, String message) throws IOException {
        sendBytes(out, message + "|");
    }

    private static void sendBytes(OutputStream out, String message) throws IOException {
        out.write(message.getBytes("ISO-8859-1"));
        out.flush();
    }

    private static String readMessage(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) != -1) {
            if (value == '|') return new String(bytes.toByteArray(), "ISO-8859-1");
            if (bytes.size() >= 65536) throw new IOException("Hub message exceeded 64 KB");
            bytes.write(value);
        }
        if (bytes.size() > 0) return new String(bytes.toByteArray(), "ISO-8859-1");
        return null;
    }
}
