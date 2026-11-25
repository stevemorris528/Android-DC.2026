package com.phinvader.libjdcpp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 
 * This is the core main class which serves as the DC Client , all operations
 * occur through interfacing with a connected instance of this object. The
 * DCClient must be connected before any operations can take place
 * 
 * @author phinfinity
 * @see DCPreferences
 */
public class DCClient {
	InetAddress HubAddress;
	int HubPort;

	private Socket DCConnectionSocket;
	private MessageHandler handler;
	private UsersHandler mainUserHandler;
	private MessageRouter mainMessageRouter;
	public DCDownloader downloadHandler;
	/** Build m: register of pending download requests carried over from the previous hub connection. */
	private DCDownloader keptDownloadHandler;

	/** Build m: call before bootstrap() so a hub reconnect keeps pending download requests. */
	public void adoptDownloadHandler(DCDownloader kept) {
		this.keptDownloadHandler = kept;
	}
	public DCHandlers.MainDownloadHandler mainDownloader;
	public DCRevconnect revConnectHandler;
	private DCHandlers.BoardMessageHandler mainBoardMessageHandler;

	// BASIC ALIASES to other classes.

	public static class NotifyUsersChange extends UsersHandler {
		/* Just a stub */
	}

	public static class DCBasicDownloadHandler
			extends
				DCDownloader.DownloadQueueEntity {

		public DCBasicDownloadHandler(DCUser target_user, DCUser my_user,
				String remote_filename, String local_filename) {
			super(target_user, my_user, remote_filename, local_filename);
		}
	}

	public static class ActiveDownloadConnection extends DCRevconnect {

		public ActiveDownloadConnection(DCUser target_user, DCUser my_user,
				DCPreferences prefs, String local_filename,
				String remote_filaname, DCClient client) {
			super(target_user, my_user, prefs, local_filename, remote_filaname,
					client);
		}
		/* Just a stub */
	}

	public static class PassiveDownloadConnection extends DCRevconnect {

		public PassiveDownloadConnection(DCUser target_user, DCUser my_user,
				DCPreferences prefs, String local_filename,
				String remote_filaname, DCClient client) {
			super(target_user, my_user, prefs, local_filename, remote_filaname,
					client);
		}
		/* Just a stub */
	}

	public static class BasicCallbackHandler implements DCCommand {

		@Override
		public void onCommand(DCMessage msg) {
			/* This is just a stub */
		}

	}

	/**
	 * 
	 * This function is used to connect to a DC-enabled hub on the specified ip
	 * address and port. The DCClient object must first be connected before any
	 * other operations can take place
	 * 
	 * @param ip
	 *            - IP Address of Server to Connect to
	 * @param port
	 *            - Port Number to connect to (default 411)
	 * 
	 * @param pref
	 *            - Preference object containing user prefences , such as
	 *            nickname
	 * @throws IOException
	 * @throws UnknownHostException
	 * @throws InterruptedException
	 * 
	 * @see DCClient#connect(String, DCPreferences)
	 * 
	 */
	public void connect(String ip, int port, DCPreferences pref)
			throws UnknownHostException, IOException, InterruptedException {

		Socket s = new Socket();
		s.connect(new java.net.InetSocketAddress(ip, port), 15000); // 15s connection timeout
		connect(s, pref);
	}

	/**
	 * Logs in over an already connected socket: plain TCP, or a TLS socket with its handshake done (NMDCS).
	 * The NMDC login itself is the same either way.
	 */
	public void connect(Socket connectedSocket, DCPreferences pref)
			throws IOException, InterruptedException {
		DCConnectionSocket = connectedSocket;
		DCConnectionSocket.setKeepAlive(true);
		DCConnectionSocket.setTcpNoDelay(true);
		// Disable read timeout (0 = infinite) so TCP connection does not time out during long deep sleep idle periods
		DCConnectionSocket.setSoTimeout(0);
		
		// Removed automatic self_ip assignment from local address as it may be a private IP.
		// DCPPService will handle external IP discovery and hub-reported IP ($UserIP).

		handler = new MessageHandler(DCConnectionSocket);
		DCMessage lock = handler.getNextMessage();
		if (lock == null || !"Lock".equals(lock.command)) {
			throw new IOException("Failed to receive Lock from hub. Received: " + (lock != null ? lock.command : "null"));
		}

		ArrayList<String> supported_methods = new ArrayList<String>();
		supported_methods.add("NoHello");
		supported_methods.add("NoGetINFO");
		supported_methods.add("UserIP2");
		supported_methods.add("UserIP");
		supported_methods.add("TTHL");
		supported_methods.add("TTHF");
		supported_methods.add("UserCommand"); // hubs only send $UserCommand (hub/user menus) to clients announcing it

		handler.send_supports(supported_methods);
		handler.send_key(DCFunctions.convert_lock_to_key(lock.lock_s.getBytes()));

		handler.send_validatenick(pref.getNick());

		long startTime = System.currentTimeMillis();
		String hubSaid = null; // hubs usually explain a refusal in main chat, then close the connection
		String refusal = null; // or with $ValidateDenide / $HubIsFull / $ForceMove
		while (true) {
			if (System.currentTimeMillis() - startTime > 30000) {
				throw new IOException("Hub handshake timeout (waiting for Hello)"
						+ (refusal != null ? ": " + refusal : hubSaid != null ? ": " + hubSaid : ""));
			}
			DCMessage msg = handler.getNextMessage();
			if (msg == null || "HubQuit".equals(msg.command)) {
				if (refusal != null || hubSaid != null) {
					throw new IOException(refusal != null ? refusal : hubSaid);
				}
				throw new IOException("Connection closed by hub during handshake: " + (msg != null ? msg.quit_s : "null"));
			}
			DCLogger.Log(msg.toString());
			if ("BoardMessage".equals(msg.command) && msg.msg_s != null && !msg.msg_s.trim().isEmpty()) {
				hubSaid = msg.msg_s.trim();
			} else if (msg.command == null && msg.msg_s != null) {
				String raw = msg.msg_s.trim();
				if (raw.startsWith("$ValidateDenide")) refusal = "Hub refused the nick " + pref.getNick() + " (taken or not allowed)";
				else if (raw.startsWith("$HubIsFull")) refusal = "Hub is full";
				else if (raw.startsWith("$ForceMove")) refusal = "Hub redirected us to " + raw.substring("$ForceMove".length()).trim();
			}
			if ("Hello".equals(msg.command) && pref.getNick().equals(msg.hello_s))
				break;
			
			if ("GetPass".equals(msg.command)) {
				if (pref.password != null && !pref.password.isEmpty()) {
					handler.send_mypass(pref.password);
				} else {
					throw new IOException("Hub requires password, but no password was provided for " + pref.getNick());
				}
			}

			if ("BadPass".equals(msg.command)) {
				throw new IOException("Invalid password for " + pref.getNick());
			}
		}

		handler.send_version();
		handler.send_getnicklist();

		DCUser myuser = new DCUser();
		myuser.nick = pref.getNick();
		myuser.share_size = pref.getShare_size();
		myuser.active = pref.active;
		myuser.description = pref.description;

		handler.send_myinfo(myuser, pref);
	}

	/**
	 * To start all transactions.
	 */
	public void bootstrap(DCUser myuser) {
		mainMessageRouter = new MessageRouter(handler);
		mainUserHandler = new UsersHandler();
		mainBoardMessageHandler = new DCHandlers.BoardMessageHandler();
		downloadHandler = (keptDownloadHandler != null) ? keptDownloadHandler : new DCDownloader();
		keptDownloadHandler = null;
		mainDownloader = new DCHandlers.MainDownloadHandler(myuser,
				downloadHandler, this);
		downloadHandler.initDownloadQ();
	}

	/**
	 * Start listening to Messages
	 */
	public void InitiateDefaultRouting() {

		mainMessageRouter.subscribe("MyINFO", mainUserHandler);
		mainMessageRouter.subscribe("Quit", mainUserHandler);
		mainMessageRouter.subscribe("OpList", mainUserHandler);
		mainMessageRouter.subscribe("BoardMessage", mainBoardMessageHandler);
		mainMessageRouter.subscribe("ConnectToMe", mainDownloader);
		Thread routing_thread = new Thread(mainMessageRouter);
		mainMessageRouter.setWorkerThread(routing_thread);
		routing_thread.start();
	}

	/**
	 * set handlers to change userlist
	 * 
	 * @param handler
	 */
	public void setUserChangeHandler(DCCommand handler) {
		mainMessageRouter.subscribe("MyINFO", handler);
		mainMessageRouter.subscribe("Quit", handler);
	}

	/**
	 * Custom callback for changes in userlist
	 * 
	 * @param handler
	 */
	public void setCustomUserChangeHandler(DCCommand handler) {
		mainMessageRouter.customSubscribe("MyINFO", handler);
		mainMessageRouter.customSubscribe("Quit", handler);
		mainMessageRouter.customSubscribe("OpList", handler);
	}

	public DCDownloader.DownloadQueueEntity startPassiveDownload(
			PassiveDownloadConnection o, int timeout)
			throws InterruptedException {
		DCDownloader.DownloadQueueEntity downloadE = new DCDownloader.DownloadQueueEntity(
				o.getTarget_user(), o.getMy_user(), o.getRemote_filaname(),
				o.getLocal_filename());
		downloadE = downloadHandler.addDownloadEntity(o.getTarget_user().nick, downloadE);
		downloadE.revConnectSent = true; // the peer's $ConnectToMe will answer this one
		handler.send_revconnect(o.getMy_user(), o.getTarget_user());
		return downloadE;
	}

	public DCDownloader.DownloadQueueEntity startActiveDownload(
			ActiveDownloadConnection o, int timeout, int port)
			throws InterruptedException {
		DCDownloader.DownloadQueueEntity downloadE = new DCDownloader.DownloadQueueEntity(
				o.getTarget_user(), o.getMy_user(), o.getRemote_filaname(),
				o.getLocal_filename());
		downloadE = downloadHandler.addDownloadEntity(o.getTarget_user().nick, downloadE);
		downloadE.revConnectSent = false; // the peer connects to our port; its own $ConnectToMe is an upload request
		handler.send_connecttome(o.getTarget_user(), o.getMy_user().ip, port);
		return downloadE;
	}

	public boolean stopDownloadHandler(
			DCDownloader.DownloadQueueEntity downloadEntity) {
		if (downloadEntity == null) return false;
		if (downloadEntity.connectionHandler != null) {
			downloadEntity.connectionHandler.close();
		}
		if (downloadEntity.thread != null && downloadEntity.thread.isAlive()) {
			downloadEntity.thread.interrupt();
			return true;
		}
		return false;
	}
	public DCDownloader.DownloadQueueEntity startPassiveDownload(
			PassiveDownloadConnection o) throws InterruptedException {
		return startPassiveDownload(o, 1000);
	}

	public DCDownloader.DownloadQueueEntity startActiveDownload(
			ActiveDownloadConnection o) throws InterruptedException {
		return startActiveDownload(o, 1000, o.getPrefs().upload_port);
	}

	public void unsetPassiveDownloadHandler(DCCommand o) {
		DCLogger.Log("UNSETTING" + o.toString());
		mainMessageRouter.unsubscribe("ConnectToMe", o);
	}

	public void setSearchHandler(DCCommand handler) {
		mainMessageRouter.subscribe("SR", handler);
	}
	public void setCustomSearchHandler(DCCommand handler) {
		mainMessageRouter.customSubscribe("SR", handler);
	}

	public void setCustomBoardMessageHandler(DCCommand handler) {
		mainMessageRouter.subscribe("BoardMessage", handler);
	}

	public void setCustomRawHandler(String command, DCCommand handler) {
		mainMessageRouter.subscribe(command, handler);
	}

	public void searchForFile(String key, DCUser myuser, int fileType) {
		// TODO: Filesize restrictions.
		key = key.replaceAll("\\s+", "\\$");
		String searchString = "F?T?0?"+fileType+"?" + key;
		handler.send_search(searchString, myuser);
	}
	
	/** Milliseconds since any byte was last read from this connection (a dead/half-open link grows without bound). */
	public long getMillisSinceLastReceived() {
		return handler != null ? handler.millisSinceLastReceived() : 0L;
	}

	public void sendRaw(String msg) {
		if (handler != null) {
			handler.send_msg(msg);
		}
	}

	public Socket getSocket() {
		return DCConnectionSocket;
	}

	public void sendMyInfo(DCUser myuser, DCPreferences prefs) {
		if (handler != null) {
			handler.send_myinfo(myuser, prefs);
		}
	}

	public void startDownloadingFile(DCDownloader.DownloadQueueEntity entity,
			DCClient client, DCMessage rlock, MessageHandler handler) {

		if (this.downloadHandler == null)
			this.downloadHandler = new DCDownloader();

		DCDownloader.downloadManager dm = this.downloadHandler.new downloadManager(
				entity, rlock, handler);
		Thread dm_thread = new Thread(dm);
		entity.thread = dm_thread;
		dm_thread.start();

	}

	/**
	 * Connects using the default port 411.
	 * 
	 * @param ip
	 *            - IP Address of Server to Connect to
	 * 
	 * @param pref
	 *            - Preference object containing user prefences , such as
	 *            nickname
	 * @throws IOException
	 * @throws UnknownHostException
	 * @throws InterruptedException
	 * 
	 * @see DCClient#connect(String, int, DCPreferences)
	 */
	public void connect(String ip, DCPreferences pref)
			throws UnknownHostException, IOException, InterruptedException {
		connect(ip, 411, pref);
	}

	/**
	 * @return The list of users registered with this hub.
	 */
	public ArrayList<DCUser> get_nick_list() {
		ArrayList<DCUser> nick_array = new ArrayList<DCUser>();
		if (mainUserHandler == null || mainUserHandler.nick_q == null) {
			return nick_array;
		}
		synchronized (mainUserHandler.nick_q) {
			Iterator<DCUser> it = mainUserHandler.nick_q.iterator();
			while (it.hasNext()) {
				nick_array.add(it.next());
			}
		}
		return nick_array;
	}

	public List<DCMessage> getBoardMessages() {
		if (mainBoardMessageHandler == null) {
			return new ArrayList<>();
		}
		return mainBoardMessageHandler.getLatestMessages();
	}

	public List<DCMessage> getBoardMessages(int limit) {
		if (mainBoardMessageHandler == null) {
			return new ArrayList<>();
		}
		return mainBoardMessageHandler.getLatestMessages(limit);
	}

	public String getBoardMessageLog() {
		if (mainBoardMessageHandler == null) {
			return "";
		}
		return mainBoardMessageHandler.toString();
	}
	public void close() {
		if (mainMessageRouter != null)
			mainMessageRouter.terminate();
		// Closing a TLS socket (NMDCS) writes a close_notify to the hub: network I/O, which Android refuses on
		// the main thread (Delete Hub / Reconnect crashed the app). Close TLS links on a background thread;
		// plain sockets are closed right here, as before.
		if (DCConnectionSocket instanceof javax.net.ssl.SSLSocket) {
			final MessageHandler h = handler;
			final Socket s = DCConnectionSocket;
			Thread t = new Thread(new Runnable() {
				@Override
				public void run() {
					closeNow(h, s);
				}
			}, "nmdcs-close");
			t.setDaemon(true);
			t.start();
			return;
		}
		closeNow(handler, DCConnectionSocket);
	}

	private static void closeNow(MessageHandler h, Socket s) {
		if (h != null)
			h.close();
		try {
			if (s != null)
				s.close();
		} catch (IOException e) {
		}
	}
}
