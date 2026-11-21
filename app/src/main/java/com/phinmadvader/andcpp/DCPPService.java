package com.phinmadvader.andcpp;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import android.app.Service;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.os.SystemClock;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.content.pm.ServiceInfo;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

import android.os.Looper;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import com.phinvader.libjdcpp.DCClient;
import com.phinvader.libjdcpp.DCCommand;
import com.phinvader.libjdcpp.DCConstants;
import com.phinvader.libjdcpp.DCDownloader;
import com.phinvader.libjdcpp.DCFileList;
import com.phinvader.libjdcpp.DCMessage;
import com.phinvader.libjdcpp.DCPreferences;
import com.phinvader.libjdcpp.DCUser;
import com.phinvader.libjdcpp.adc.AdcHubConnection;
import com.phinvader.libjdcpp.hub.HubAddress;
import com.phinvader.libjdcpp.hub.HubConnection;
import com.phinvader.libjdcpp.hub.HubEventListener;
import com.phinvader.libjdcpp.hub.NmdcHubConnection;
import com.phinvader.libjdcpp.hub.SearchResult;
import com.phinvader.libjdcpp.tls.Keyprint;
import com.phinvader.libjdcpp.tls.SelfSignedCertificate;
import com.phinvader.libjdcpp.tls.TlsSocketFactory;

import javax.net.ssl.SSLSocket;
import com.phinmadvader.andcpp.pia.PIAPortForwardingManager;

/**
 * Created by phinfinity on 11/08/13.
 */
public class DCPPService extends Service implements PeerConnectionHandler.UploadListener {
	public static final String ACTION_STOP_SERVICE = "com.phinmadvader.andcpp.ACTION_STOP_SERVICE";
	public static final String CHANNEL_ID = "dcpp_service_channel";
	
	private PowerManager.WakeLock wakeLock;
	private WifiManager.WifiLock wifiLock;
	private BroadcastReceiver networkReceiver;
	private Timer keepAliveTimer;
	// Identity of the last connected network (type + SSID/APN); survives disconnects so
	// a wifi->wifi or wifi->mobile handover is detected even with a gap in between.
	private String lastNetworkKey = null;
	private boolean networkLostSinceLastConnect = false;
	private long lastNetworkRestartAt = 0L;

	// "Download only over Wi-Fi": cached result, refreshed on network/setting changes.
	static final String WAIT_REASON_WIFI = "waiting for Wi-Fi";
	private volatile boolean downloadsBlockedByNetwork = false;
	private SharedPreferences.OnSharedPreferenceChangeListener wifiOnlyPrefListener;

	public static enum DCClientStatus {
		DISCONNECTED, INVALIDIP, CONNECTED, CONNECTING, RECONNECTING
	}

	/** One entry of a hub's command menu ($UserCommand). */
	public static class UserCommand {
		public static final int TYPE_SEPARATOR = 0;
		public static final int CONTEXT_HUB = 1, CONTEXT_USER = 2, CONTEXT_SEARCH = 4, CONTEXT_FILELIST = 8;
		public final int type;
		public final int context;
		/** Menu path; submenus separated by '\'. Null for an untitled separator. */
		public final String title;
		/** Raw NMDC command(s) with %[...] placeholders; '|' encoded as &#124;. */
		public final String command;

		UserCommand(int type, int context, String title, String command) {
			this.type = type;
			this.context = context;
			this.title = title;
			this.command = command;
		}

		public boolean isSeparator() {
			return type == TYPE_SEPARATOR;
		}

		/** Prompts of the %[line:...] placeholders, in order of first appearance. */
		public List<String> linePrompts() {
			List<String> prompts = new ArrayList<>();
			if (command == null) return prompts;
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("%\\[line:([^\\]]*)\\]").matcher(command);
			while (m.find()) {
				if (!prompts.contains(m.group(1))) prompts.add(m.group(1));
			}
			return prompts;
		}
	}

	public class HubSession {
		public String hubIp;
		public int hubPort;
		public String hubName;
		public DCClientStatus status = DCClientStatus.DISCONNECTED;
		public DCClient client = null;
		/**
		 * Protocol-neutral link to the hub: everything we send to the hub goes through this. Set and cleared
		 * together with {@link #client} (NMDC: wraps it). Read it into a local before use; it changes on reconnect.
		 */
		public volatile HubConnection hub = null;
		public DCCommand board_message_handler = null;
		public DCCommand user_handler = null;
		public DCCommand search_handler = null;
		public DCCommand private_message_handler = null;
		public DCPreferences prefs;
		public DCUser myuser;
		public LinkedBlockingQueue<DCMessage> search_results = new LinkedBlockingQueue<>();
		public int reconnectAttempts = 0;
		public long lastReconnectAttempt = 0;
		/** Build m: pending download requests, kept across hub reconnects. */
		public DCDownloader keptDownloads = null;
		/** Hub/user menu commands sent by this hub ($UserCommand). Reset on every (re)connect. */
		public final List<UserCommand> userCommands = new java.util.concurrent.CopyOnWriteArrayList<>();

		/** NMDC, or NMDCS (NMDC over TLS). */
		public HubAddress.Protocol protocol = HubAddress.Protocol.NMDC;
		/** Keyprint the hub's certificate must have (from ?kp= in its address), or null. */
		public String expectedKeyprint;
		/** Keyprint of the certificate the hub presented (secure hubs only), for logs and hub details. */
		public volatile String hubKeyprint;
		/**
		 * Set when a secure hub's certificate didn't match the expected keyprint: we don't connect (or retry)
		 * until the user decides. Cleared by the next connect attempt.
		 */
		public volatile String keyprintProblem;
		/** The new keyprint the user may choose to trust (only for a changed remembered keyprint). */
		public volatile String pendingNewKeyprint;
		/** Hub software (ADC VE), for hub details. */
		public volatile String hubSoftware;

		public HubSession(String ip, int port) {
			this.hubIp = ip;
			this.hubPort = port;
		}

		public HubAddress address() {
			return HubAddress.of(protocol, hubIp, hubPort);
		}

		/** A per-hub string preference (nick, password); NMDC tries host:port then host, exactly as before. */
		String hubPref(SharedPreferences prefs, String prefix) {
			List<String> keys = protocol == HubAddress.Protocol.NMDC
					? java.util.Arrays.asList(hubIp + ":" + hubPort, hubIp)
					: address().prefKeys();
			for (String k : keys) {
				String v = prefs.getString(prefix + k, "");
				if (v != null && !v.isEmpty()) return v;
			}
			return "";
		}

		public void shutdown() {
			status = DCClientStatus.DISCONNECTED;
			if (prefs != null) {
				prefs.set_self_ip(null);
			}
			if (client != null) {
				client.close();
			} else if (hub != null) {
				hub.close(); // ADC link
			}
		}
	}

	private Map<String, HubSession> sessions = new ConcurrentHashMap<>();

	private String getSessionKey(String ip, int port) {
		return ip + ":" + port;
	}

	/** True if a session exists under exactly this key (no fallback to another hub, unlike getSession). */
	public boolean hasSession(String hubKey) {
		return hubKey != null && sessions.containsKey(hubKey);
	}

	public HubSession getSession(String hubKey) {
		if (hubKey == null) {
			if (!sessions.isEmpty()) {
				for (HubSession s : sessions.values()) {
					if (s.status == DCClientStatus.CONNECTED) return s;
				}
				return sessions.values().iterator().next();
			}
			return null;
		}
		HubSession session = sessions.get(hubKey);
		if (session == null && !hubKey.contains(":")) {
			// Fallback search by IP if key doesn't have port
			for (Map.Entry<String, HubSession> entry : sessions.entrySet()) {
				if (entry.getValue().hubIp.equals(hubKey)) {
					return entry.getValue();
				}
			}
		}
		if (session == null && !sessions.isEmpty()) {
			for (HubSession s : sessions.values()) {
				if (s.status == DCClientStatus.CONNECTED) return s;
			}
			return sessions.values().iterator().next();
		}
		return session;
	}

	public List<HubSession> getActiveSessions() {
		return new ArrayList<>(sessions.values());
	}

	public static String[] FileUnits = { "Bytes", "KB", "MB", "GB", "TB", "PB", "EB" };

	public static class FileSize {
		public double fileSize = 0.0;
		public String unit = FileUnits[0];

		public FileSize(double size) {
			if (Double.isNaN(size) || Double.isInfinite(size)) {
				size = 0.0;
			}
			this.fileSize = size;
			int i = 0;
			while (size >= 1024 && i < FileUnits.length - 1) {
				size /= 1024;
				i++;
				unit = FileUnits[i];
				fileSize = size;
			}
		}
	}

	private DCPreferences prefs;
	private DCUser myuser;
	public int downloadID = 1000;
	NotificationManager mNotifyManager2;
	
	private UploadManager uploadManager;

	private ExecutorService downloadEnqueueExecutor = Executors.newSingleThreadExecutor();

	public interface DownloadItem {
		String getFileName();
		String getTarget_nick();
		long total_bytes();
		long bytes_done();
		double avg_speed();
		DCConstants.DownloadStatus get_status();
		boolean is_finished();
		boolean isFolder();
		void pause_download();
		void resume_download();
		void cancel_download();
		boolean delete_download();
		String getLocalFilePath();
		String getWaitReason();
	}

	public static class FolderProgress {
		public String groupLabel;
		public String folderName;
		public int totalFiles = 0;
		public int completedFiles = 0;
		public long totalSize = 0;
		public long completedSize = 0;
		public int notificationId;
		public NotificationCompat.Builder builder;

		public FolderProgress(String label, String folderName, int totalFiles, long totalSize, Context context) {
			this.groupLabel = label;
			this.folderName = folderName;
			this.totalFiles = totalFiles;
			this.totalSize = totalSize;
			this.notificationId = (int) (System.currentTimeMillis() % 10000) + 20000;
			this.builder = new NotificationCompat.Builder(context, CHANNEL_ID)
					.setContentTitle("Downloading Folder: " + folderName)
					.setSmallIcon(R.drawable.ic_launcher)
					.setOngoing(true);
		}
	}

	private ConcurrentHashMap<String, FolderProgress> folderProgressMap = new ConcurrentHashMap<>();
	private ConcurrentHashMap<PeerConnectionHandler, Integer> uploadNotificationIds = new ConcurrentHashMap<>();
	private AtomicInteger nextUploadNotificationId = new AtomicInteger(30000);

	public class DownloadObject implements DownloadItem {
		private NotificationCompat.Builder mBuilder2;
		private String hubIp, target_nick, local_file, remote_file;
		private DCClient.PassiveDownloadConnection myrc;
		private DCClient.ActiveDownloadConnection activeRc;
		private long milis_began;
		private String file_name;
		private long file_size = 0;
		public int currentID;
		private long pendingStartOffset = 0;

		public volatile DCDownloader.DownloadQueueEntity download_status = null;
		public volatile String waitReason = null;
		/**
		 * Set when we asked the peer to connect to us ($ConnectToMe) and it never arrived: e.g. both of us are on
		 * the same VPN server (no NAT loop-back) or a firewall. Next attempts let an active peer accept the
		 * connection instead ($RevConnectToMe), which is what worked in passive mode.
		 */
		private volatile boolean preferRevConnect = false;
		private volatile long lastSignalTime = 0L;
		private volatile int peerWaitRetryCount = 0;
		private volatile long nextRetryAt = 0L;
		/** Peer reported the file as unavailable: not re-queued automatically (user Resume clears it). */
		private volatile boolean permanentlyFailed = false;
		public String getWaitReason() {
			return waitReason;
		}
		private volatile boolean isPaused = false;
		private volatile boolean isCancelled = false;
		public String groupLabel = null;
		public String groupName = null;

		public String getLocalFilePath() {
			return local_file;
		}

		public void pause_download() {
			isPaused = true;
			try {
				File downloadedFile = new File(local_file);
				if (downloadedFile.exists()) {
					this.pendingStartOffset = downloadedFile.length();
				}
				if (download_status != null && !is_finished_internal()) {
					download_status.status = DCConstants.DownloadStatus.INTERUPTED;
					closeAdcTransfer(download_status); // ADC download: close its connection
					HubSession session = getSession(hubIp);
					if (session != null && session.client != null) {
						Thread stopThread = new Thread(() -> {
							try {
								session.client.stopDownloadHandler(download_status);
							} catch (Exception e) {
								Log.e("andcpp", "Error stopping download", e);
							}
						});
						stopThread.start();
						try {
							stopThread.join(3000);
						} catch (InterruptedException ignored) {}
					}
				}
				if (mNotifyManager2 != null && mBuilder2 != null) {
					mBuilder2.setContentText("Download Paused")
							.setOngoing(false);
					mNotifyManager2.notify(currentID, mBuilder2.build());
				}
				saveDownloadsState();
			} catch (Exception e) {
				Log.e("andcpp", "Error in pause_download", e);
			}
		}

		public void resume_download() {
			try {
				if (isPaused || (download_status != null && (download_status.status == DCConstants.DownloadStatus.INTERUPTED || download_status.status == DCConstants.DownloadStatus.FAILED))) {
					waitReason = null;
					peerWaitRetryCount = 0;
					nextRetryAt = 0L;
					permanentlyFailed = false;
					isPaused = false;
					File downloadedFile = new File(local_file);
					if (downloadedFile.exists()) {
						this.pendingStartOffset = downloadedFile.length();
					} else if (download_status != null) {
						this.pendingStartOffset = download_status.downloadedSize;
					}
					if (download_status != null) {
						download_status.status = DCConstants.DownloadStatus.QUEUED;
					}
					synchronized (initiated_downloads) {
						if (!initiated_downloads.contains(this)) {
							initiated_downloads.add(this);
						}
					}
					try {
						if (normal_queue != null && !normal_queue.contains(this)) {
							normal_queue.put(this);
						}
						startNormalWorkersIfNeeded();
						saveDownloadsState();
					} catch (InterruptedException e) {
						Log.e("andcpp", "Failed to re-queue download", e);
					}
				}
			} catch (Exception e) {
				Log.e("andcpp", "Error in resume_download", e);
			}
		}

		public void cancel_download() {
			isCancelled = true;
			try {
				if (download_status != null && !is_finished_internal()) {
					download_status.status = DCConstants.DownloadStatus.INTERUPTED;
					closeAdcTransfer(download_status); // ADC download: close its connection
					HubSession session = getSession(hubIp);
					if (session != null && session.client != null) {
						new Thread(() -> {
							try {
								session.client.stopDownloadHandler(download_status);
							} catch (Exception e) {
								Log.e("andcpp", "Error stopping download", e);
							}
						}).start();
					}
				}
			} catch (Exception e) {
				Log.e("andcpp", "Error in cancel_download", e);
			}
			
			synchronized (initiated_downloads) {
				initiated_downloads.remove(this);
			}
			if (normal_queue != null) {
				normal_queue.remove(this);
			}
			NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			if (nm != null) {
				nm.cancel(currentID);
			}
			saveDownloadsState();
		}

		public boolean delete_download() {
			cancel_download();
			File f = new File(local_file);
			if (f.exists()) {
				// Retry deletion as the file might still be locked for a brief moment
				for (int i = 0; i < 5; i++) {
					if (f.delete()) {
						Log.d("andcpp", "File deleted successfully: " + local_file);
						return true;
					}
					try {
						Thread.sleep(200);
					} catch (InterruptedException e) {
						break;
					}
				}
				Log.e("andcpp", "Failed to delete file after retries: " + local_file);
				return false;
			}
			return true;
		}

		public DownloadObject(String hubIp, String target_nick, String local_file,
				String remote_file, DCPPService parent, String groupLabel, String groupName) {
			this.hubIp = hubIp;
			this.target_nick = target_nick;
			this.local_file = local_file;
			this.remote_file = remote_file;
			this.groupLabel = groupLabel;
			this.groupName = groupName;
			String[] file_name_parts = remote_file.split("[\\\\/]");
			file_name = file_name_parts[file_name_parts.length - 1];

			downloadID++;
			this.currentID = downloadID;

			// Icon and title set up front: Android rejects any notify() without a small icon, and
			// get_file_list() posts progress right after the first start_download() attempt, which
			// doesn't always set one ("Invalid notification (no valid small icon)" aborted the list).
			mBuilder2 = new NotificationCompat.Builder(parent, CHANNEL_ID)
					.setSmallIcon(R.drawable.ic_launcher)
					.setContentTitle(file_name);
			// Pre-flight checks run at the start of each attempt (start_download), not here:
			// on restore the hubs are usually not connected yet.
		}

		private void postWaitNotification(String text) {
			try {
				if (mBuilder2 == null || isCancelled) return;
				mBuilder2.setContentTitle(file_name)
						.setSmallIcon(R.drawable.ic_launcher)
						.setProgress(0, 0, false)
						.setContentText(text)
						.setOngoing(false);
				if (mNotifyManager2 != null && groupLabel == null) {
					mNotifyManager2.notify(currentID, mBuilder2.build());
				}
			} catch (Exception e) {
				Log.w("andcpp", "Could not post wait notification", e);
			}
		}

		/** Parks this download with a visible reason. The scheduler retries it after nextRetryAt (30s, 60s, 120s, then 300s). */
		private void markWaiting(String reason) {
			final long[] steps = {30000L, 60000L, 120000L, 300000L};
			int idx = Math.min(peerWaitRetryCount, steps.length - 1);
			waitReason = reason;
			nextRetryAt = System.currentTimeMillis() + steps[idx];
			peerWaitRetryCount = peerWaitRetryCount + 1;
			Log.w("andcpp", "[DLTRACE] " + file_name + " (" + target_nick + ") -> WAITING_FOR_PEER: " + reason
					+ ", retry in " + (steps[idx] / 1000) + "s");
			updateFolderStatus(this, DCConstants.DownloadStatus.WAITING_FOR_PEER);
			postWaitNotification("Waiting for peer (" + reason + ")");
		}

		/** Parks this download until Wi-Fi is back ("Download only over Wi-Fi" is on and we're on mobile data). */
		void markWaitingForWifi() {
			waitReason = WAIT_REASON_WIFI;
			nextRetryAt = System.currentTimeMillis() + 60000L; // re-checked every minute; a Wi-Fi connect retries at once
			Log.i("andcpp", "[DLTRACE] " + file_name + " -> waiting for Wi-Fi (Wi-Fi only downloads enabled)");
			updateFolderStatus(this, DCConstants.DownloadStatus.WAITING_FOR_PEER);
			postWaitNotification("Waiting for Wi-Fi");
		}

		/** Drops the pending request/connection of the current attempt so a later attempt starts clean. */
		private void abandonCurrentAttempt() {
			DCDownloader.DownloadQueueEntity e = download_status;
			if (e == null) return;
			HubSession s = getSession(hubIp);
			if (s != null && s.client != null && s.client.downloadHandler != null) {
				s.client.downloadHandler.removeDownloadEntity(target_nick, e);
			}
			try {
				if (e.connectionHandler != null) e.connectionHandler.close();
			} catch (Exception ignored) {}
			closeAdcTransfer(e);
			adcTransfers.forget(e); // an ADC request still waiting for the peer: drop its token
		}

		/**
		 * Pre-flight checks and connection setup, run at the start of every attempt.
		 * - No connected hub yet: leaves activeRc/myrc null and waitReason null, so the item simply stays queued.
		 * - Peer unreachable right now: markWaiting() with a reason and backoff.
		 * Decision: if we can accept incoming connections we send $ConnectToMe (active download, whatever the peer's
		 * mode); otherwise we send $RevConnectToMe, which needs an active peer.
		 */
		public void initialize_download_connection() {
			activeRc = null;
			myrc = null;

			HubSession session = downloadSession(hubIp);
			if (session != null && !session.hubIp.equals(this.hubIp)) {
				this.hubIp = session.hubIp; // our hub isn't up: try through another NMDC hub (old behaviour)
			}
			if (session == null || session.client == null || session.prefs == null
					|| session.status != DCClientStatus.CONNECTED) {
				Log.d("andcpp", "[DLTRACE] initialize_download_connection: hub not connected yet for " + file_name + "; staying queued");
				return;
			}

			// Pre-flight 1: is the peer online on any connected hub?
			boolean userFound = false;
			DCUser peerUser = null;
			for (HubSession s : getActiveSessions()) {
				if (s.client != null) {
					for (DCUser u : s.client.get_nick_list()) {
						if (u.nick != null && u.nick.equalsIgnoreCase(target_nick)) {
							userFound = true;
							peerUser = u;
							break;
						}
					}
				}
				if (userFound) break;
			}
			if (!userFound) {
				markWaiting("user offline");
				return;
			}

			boolean weAreActive = session.prefs.active;
			boolean peerIsActive = peerUser != null
					&& (peerUser.active || (peerUser.tag != null && peerUser.tag.contains("M:A")));

			// Pre-flight 2: can we accept an incoming connection, and on which address?
			String advertiseIp = resolveAdvertiseIp(session);
			boolean canAcceptIncoming = weAreActive && uploadManager != null
					&& uploadManager.isListening() && advertiseIp != null;
			if (weAreActive && advertiseIp == null) {
				Log.w("andcpp", "[DLTRACE] Active mode but no usable public address to advertise; using passive for this attempt");
			}

			DCUser my_user = new DCUser();
			DCUser his_user = new DCUser();
			my_user.nick = session.prefs.getNick();
			my_user.ip = advertiseIp != null ? advertiseIp : "";
			his_user.nick = target_nick;

			if (!preferRevConnect && peersPreferRevConnect.contains(target_nick.toLowerCase(Locale.ROOT))) {
				preferRevConnect = true; // another download already found this peer can't connect to us
			}
			if (canAcceptIncoming && preferRevConnect && peerIsActive) {
				// Our $ConnectToMe went unanswered before: let the (active) peer accept the connection instead.
				Log.d("andcpp", "[DLTRACE] " + file_name + ": peer didn't connect to us last time; using RevConnectToMe");
				canAcceptIncoming = false;
			}
			if (canAcceptIncoming) {
				activeRc = new DCClient.ActiveDownloadConnection(his_user, my_user,
						session.prefs, local_file, remote_file, session.client);
			} else if (peerIsActive) {
				myrc = new DCClient.PassiveDownloadConnection(his_user, my_user,
						session.prefs, local_file, remote_file, session.client);
			} else {
				// Pre-flight 3: neither side can accept a connection.
				markWaiting(weAreActive ? "cannot accept incoming, peer passive" : "both passive");
				return;
			}
		}

		public void start_download() throws InterruptedException {
			if (isCancelled || isPaused) return;

			if (waitReason != null) {
				if (System.currentTimeMillis() < nextRetryAt) {
					return; // still backing off
				}
				waitReason = null; // backoff elapsed: this attempt re-runs the pre-flight checks
			}

			if (waitReason != null) {
				return;
			}

			if (refreshDownloadsBlockedByNetwork()) {
				markWaitingForWifi();
				return;
			}

			if (this.download_status != null && (this.download_status.status == DCConstants.DownloadStatus.INITIATED
					|| this.download_status.status == DCConstants.DownloadStatus.STARTED
					|| this.download_status.status == DCConstants.DownloadStatus.DOWNLOADING)) {
				Log.d("andcpp", "[DLTRACE] DownloadObject.start_download skipping entity reassignment because current status is active: " + this.download_status.status);
				return;
			}

			updateFolderStatus(this, DCConstants.DownloadStatus.STARTED);
			set_start_download_time();

			HubSession own = hubIp != null ? sessionForUi(hubIp) : null;
			if (own != null && own.protocol.isAdc()) {
				startAdcDownload(own);
				return;
			}

			HubSession session = downloadSession(hubIp);
			if (session == null) {
				Log.w("andcpp", "No NMDC hub connected for " + (hubIp != null ? hubIp : "any hub") + " yet. Retrying download.");
			} else if (!session.hubIp.equals(this.hubIp)) {
				this.hubIp = session.hubIp; // our hub isn't up: try through another NMDC hub (old behaviour)
			}

			initialize_download_connection(); // every attempt: peer state and our address can change

			if (waitReason != null) {
				return;
			}

			if (session == null) {
				session = downloadSession(hubIp);
			}
			if (session == null || session.client == null) {
				return;
			}

			DCDownloader.DownloadQueueEntity entity;
			if (activeRc != null) {
				int port = uploadManager != null ? uploadManager.getPort() : session.prefs.upload_port;
				entity = session.client.startActiveDownload(activeRc,
						(int) Constants.DOWNLOAD_TIMEOUT_MILLIS, port);
			} else if (myrc != null) {
				entity = session.client.startPassiveDownload(myrc,
						(int) Constants.DOWNLOAD_TIMEOUT_MILLIS);
			} else {
				initialize_download_connection();
				if (waitReason != null) {
					return;
				}
				if (activeRc != null) {
					int port = uploadManager != null ? uploadManager.getPort() : session.prefs.upload_port;
					entity = session.client.startActiveDownload(activeRc,
							(int) Constants.DOWNLOAD_TIMEOUT_MILLIS, port);
				} else if (myrc != null) {
					entity = session.client.startPassiveDownload(myrc,
							(int) Constants.DOWNLOAD_TIMEOUT_MILLIS);
				} else {
					return;
				}
			}

			if (entity != null && (entity.status == null || entity.status == DCConstants.DownloadStatus.UNDEFINED)) {
				entity.status = DCConstants.DownloadStatus.QUEUED;
			}

			this.download_status = entity;
			this.download_status.startOffset = this.pendingStartOffset;
			if (this.download_status.downloadedSize < this.pendingStartOffset)
				this.download_status.downloadedSize = this.pendingStartOffset;
			lastSignalTime = System.currentTimeMillis();
		}

		/**
		 * A download from a user on an ADC hub: same pre-flight as NMDC (hub up, user online, someone can accept
		 * the connection), then AdcTransfers sends DCTM / DRCM; the entity is filled like the NMDC downloader's.
		 */
		private void startAdcDownload(HubSession session) {
			if (session.status != DCClientStatus.CONNECTED || !(session.hub instanceof AdcHubConnection)) {
				Log.d("andcpp", "[DLTRACE] " + file_name + ": ADC hub " + session.hubIp + " not connected yet; staying queued");
				return;
			}
			AdcHubConnection hub = (AdcHubConnection) session.hub;
			DCUser peer = hub.userByNick(target_nick);
			if (peer == null || peer.sid == null) {
				markWaiting("user offline");
				return;
			}
			DCUser me = new DCUser();
			me.nick = session.prefs != null ? session.prefs.getNick() : null;
			DCDownloader.DownloadQueueEntity entity = this.download_status;
			if (entity == null) {
				entity = new DCDownloader.DownloadQueueEntity(peer, me, remote_file, local_file);
			} else {
				entity.permanentError = false;
			}
			entity.startOffset = this.pendingStartOffset;
			if (entity.downloadedSize < this.pendingStartOffset) entity.downloadedSize = this.pendingStartOffset;
			String why = adcTransfers.requestDownload(session, hub, peer, entity, remote_file);
			if (why != null) {
				markWaiting(why);
				return;
			}
			this.download_status = entity;
			lastSignalTime = System.currentTimeMillis();
		}

		public void set_start_download_time() {
			milis_began = System.currentTimeMillis();
		}

		public void set_total_bytes(long size) {
			file_size = size;
		}

		public String getFileName() {
			if (remote_file.equalsIgnoreCase("files.xml")) {
				return "File List (" + target_nick + ")";
			}
			return file_name;
		}

		public String getTarget_nick() {
			return target_nick;
		}

		public boolean isFileList() {
			return remote_file != null && remote_file.equalsIgnoreCase("files.xml");
		}

		public long total_bytes() {
			if (download_status != null
					&& download_status.expectedDownloadSize != 0)
				return download_status.expectedDownloadSize;
			return file_size;
		}

		public long bytes_done() {
			if (download_status != null)
				return download_status.downloadedSize;
			return 0;
		}

		public long millis_elapsed() {
			return System.currentTimeMillis() - milis_began;
		}

		public double avg_speed() {
			long elapsed = millis_elapsed();
			if (elapsed <= 0) return 0.0;
			long done = bytes_done();
			long offset = download_status != null ? download_status.startOffset : 0;
			return ((double) (done - offset)) / (elapsed / 1000.0);
		}

		/**
		 * Returns the current status of this download entity.
		 * 
		 * @return
		 */
		public DCConstants.DownloadStatus get_status() {
			if (isPaused) return DCConstants.DownloadStatus.INTERUPTED;
			if (waitReason != null) return DCConstants.DownloadStatus.WAITING_FOR_PEER;
			if (download_status != null && download_status.status != null) {
				return download_status.status;
			} else {
				return DCConstants.DownloadStatus.QUEUED;
			}
		}

		/**
		 * Returns true if this download entity has completed either
		 * successfully or if it failed. Returns false if it has not yet been
		 * initiated.
		 * 
		 * @return
		 */
		public boolean is_finished() {
			if (isCancelled) return true;
			if (download_status == null) return false;
			return (download_status.status == DCConstants.DownloadStatus.COMPLETED
					|| download_status.status == DCConstants.DownloadStatus.FAILED
					|| download_status.status == DCConstants.DownloadStatus.SHUTDOWN);
		}

		private boolean is_finished_internal() {
			if (isCancelled || isPaused) return true;
			if (download_status == null)
				return false;
			return (download_status.status == DCConstants.DownloadStatus.COMPLETED
					|| download_status.status == DCConstants.DownloadStatus.FAILED
					|| download_status.status == DCConstants.DownloadStatus.INTERUPTED || download_status.status == DCConstants.DownloadStatus.SHUTDOWN);
		}

		@Override
		public boolean isFolder() {
			return false;
		}
	}

	public class FolderDownloadItem implements DownloadItem {
		private String groupLabel;
		private String target_nick;
		private DCPPService service;
		private List<DownloadObject> downloads = new ArrayList<>();
		private String folderName;

		public FolderDownloadItem(String groupLabel, String target_nick, DCPPService service) {
			this(groupLabel, target_nick, service, null);
		}

		public FolderDownloadItem(String groupLabel, String target_nick, DCPPService service, String groupName) {
			this.groupLabel = groupLabel;
			this.target_nick = target_nick;
			this.service = service;
			FolderProgress fp = service.folderProgressMap.get(groupLabel);
			if (fp != null) {
				this.folderName = fp.folderName;
			} else if (groupName != null) {
				this.folderName = groupName;
			} else {
				this.folderName = "Folder";
			}
		}

		public void addDownload(DownloadObject o) {
			downloads.add(o);
		}

		@Override
		public String getWaitReason() {
			// Surface a Wi-Fi wait on the folder row so it reads "Waiting for Wi-Fi", not "Waiting for peer".
			for (DownloadObject o : downloads) {
				if (WAIT_REASON_WIFI.equals(o.waitReason)) return WAIT_REASON_WIFI;
			}
			return null;
		}

		@Override
		public String getFileName() {
			return folderName;
		}

		@Override
		public String getTarget_nick() {
			return target_nick;
		}

		@Override
		public long total_bytes() {
			long total = 0;
			for (DownloadObject o : downloads) total += o.total_bytes();
			return total;
		}

		@Override
		public long bytes_done() {
			long done = 0;
			for (DownloadObject o : downloads) done += o.bytes_done();
			return done;
		}

		@Override
		public double avg_speed() {
			double speed = 0;
			for (DownloadObject o : downloads) {
				if (o.get_status() == DCConstants.DownloadStatus.DOWNLOADING)
					speed += o.avg_speed();
			}
			return speed;
		}

		@Override
		public DCConstants.DownloadStatus get_status() {
			boolean allFinished = true;
			boolean anyDownloading = false;
			boolean anyPaused = false;
			boolean anyFailed = false;

			for (DownloadObject o : downloads) {
				DCConstants.DownloadStatus s = o.get_status();
				if (!o.is_finished()) allFinished = false;
				if (s == DCConstants.DownloadStatus.DOWNLOADING) anyDownloading = true;
				if (s == DCConstants.DownloadStatus.INTERUPTED) anyPaused = true;
				if (s == DCConstants.DownloadStatus.FAILED) anyFailed = true;
			}

			if (allFinished) return DCConstants.DownloadStatus.COMPLETED;
			if (anyDownloading) return DCConstants.DownloadStatus.DOWNLOADING;
			if (anyPaused) return DCConstants.DownloadStatus.INTERUPTED;
			if (getWaitReason() != null) return DCConstants.DownloadStatus.WAITING_FOR_PEER; // held for Wi-Fi
			if (anyFailed) return DCConstants.DownloadStatus.FAILED;
			return DCConstants.DownloadStatus.QUEUED;
		}

		@Override
		public boolean is_finished() {
			if (downloads.isEmpty()) return true;
			for (DownloadObject o : downloads) {
				if (!o.is_finished()) return false;
			}
			return true;
		}

		@Override
		public boolean isFolder() {
			return true;
		}

		@Override
		public void pause_download() {
			for (DownloadObject o : downloads) o.pause_download();
		}

		@Override
		public void resume_download() {
			for (DownloadObject o : downloads) o.resume_download();
		}

		@Override
		public void cancel_download() {
			List<DownloadObject> copy = new ArrayList<>(downloads);
			for (DownloadObject o : copy) o.cancel_download();
		}

		@Override
		public boolean delete_download() {
			boolean success = true;
			List<DownloadObject> copy = new ArrayList<>(downloads);
			for (DownloadObject o : copy) {
				if (!o.delete_download()) success = false;
			}
			String path = getLocalFilePath();
			if (!path.isEmpty()) {
				File f = new File(path);
				if (f.exists() && f.isDirectory()) {
					f.delete();
				}
			}
			return success;
		}

		@Override
		public String getLocalFilePath() {
			if (downloads.isEmpty()) return "";
			String firstFile = downloads.get(0).getLocalFilePath();
			int lastIndex = firstFile.lastIndexOf(File.separator);
			if (lastIndex == -1) lastIndex = firstFile.lastIndexOf('/');
			if (lastIndex != -1) return firstFile.substring(0, lastIndex);
			return firstFile;
		}
	}

	private Runnable generate_worker(
			final BlockingQueue<DownloadObject> q,
			final List<DownloadObject> initiated_list) {
		return new Runnable() {

			@Override
			public void run() {

				try {
					int blockedStreak = 0;
					while (true) {
						DownloadObject work = q.take();
						boolean claimed = false;
						try {
						// Never restart a finished download, and never process one object on two workers
						// or two downloads from the same peer at once.
						if (work.download_status != null && work.download_status.status == DCConstants.DownloadStatus.COMPLETED) {
							continue;
						}
						if (!work.isFileList() && !work.isCancelled && !work.isPaused) {
							if (!tryClaim(work)) {
								if (!q.contains(work)) q.put(work); // back of the queue
								// Keep scanning for another peer's item; pause only after a full pass found nothing runnable.
								blockedStreak++;
								if (blockedStreak > q.size()) {
									blockedStreak = 0;
									Log.d("andcpp", "[DLTRACE] worker " + Thread.currentThread().getName()
											+ ": full pass found nothing runnable (" + q.size() + " queued, peers busy: " + activeNicksSnapshot() + ")");
									// Every queued item belongs to a busy peer: sleep until a claim is released (or 5s) instead of spinning.
									awaitClaimRelease(5000L);
								}
								continue;
							}
							blockedStreak = 0;
							claimed = true;
							Log.d("andcpp", "[DLTRACE] worker " + Thread.currentThread().getName() + " claimed " + work.file_name + " (" + work.target_nick + ")");
						}
						if (work == null) continue;
						if (work.isFileList()) {
							updateFolderProgress(work);
							continue;
						}
						if (work.isCancelled) {
							updateFolderProgress(work);
							continue;
						}
						if (work.isPaused) {
							synchronized (initiated_downloads) {
								if (!initiated_list.contains(work)) {
									initiated_list.add(work);
								}
							}
							continue;
						}
						synchronized (initiated_downloads) {
							if (!initiated_list.contains(work)) {
								initiated_list.add(work);
							}
						}
						
						try {
							work.start_download();
						} catch (Exception e) {
							Log.e("andcpp", "Failed to start download: " + work.file_name, e);
							if (work.download_status != null) {
								work.download_status.status = DCConstants.DownloadStatus.FAILED;
							}
							continue;
						}
						
						manageWakeLock();

						if (work.download_status == null) {
							Log.e("andcpp", "Download status is null for: " + work.file_name);
							continue;
						}

						work.mBuilder2.setContentTitle(work.file_name)
								.setContentText(work.getFileName())
								.setSmallIcon(R.drawable.ic_launcher);
						mNotifyManager2 = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

						long lastProgressBytes = work.bytes_done();
						long lastProgressAt = System.currentTimeMillis();
						boolean hasObservedDownload = false;
						long attemptStart = System.currentTimeMillis();
						DCConstants.DownloadStatus seenStatus = null;
						long seenSince = attemptStart;

						while (true) {
							if (work.isCancelled || work.isPaused) break;
							if (work.waitReason != null) break; // parked: pre-flight failure, timeout or $MaxedOut
							if (downloadsBlockedByNetwork) { // switched to mobile data with Wi-Fi only on
								work.markWaitingForWifi();
								break;
							}
							updateFolderStatus(work, work.get_status());
							if (work.download_status.status != DCConstants.DownloadStatus.QUEUED) {
								unansweredConnectRequests = 0; // a peer connected to us: the hub is relaying fine
							}
							if (work.download_status.status == DCConstants.DownloadStatus.COMPLETED) {
								

				                Intent intent = new Intent();
				                intent.setAction(android.content.Intent.ACTION_VIEW);
				                File file = new File(work.local_file);
				              
				                MimeTypeMap mime = MimeTypeMap.getSingleton();
				                String fileName = file.getName();
				                int lastDot = fileName.lastIndexOf('.');
				                String ext = lastDot != -1 ? fileName.substring(lastDot + 1) : "";
				                String type = mime.getMimeTypeFromExtension(ext.toLowerCase());
				                if (type == null) type = "*/*";

				                Uri contentUri;
				                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				                    contentUri = FileProvider.getUriForFile(getBaseContext(),
				                            "com.phinmadvader.andcpp.fileprovider", file);
				                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
				                } else {
				                    contentUri = Uri.fromFile(file);
				                }
				             
				                intent.setDataAndType(contentUri, type);
				              
				                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
				                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
				                    flags |= PendingIntent.FLAG_IMMUTABLE;
				                }
				                
				                // Using a safer way to open files on modern Android
				                // Note: Uri.fromFile will fail on API 24+ if targetSdk >= 24
				                // For now, we wrap in try-catch to prevent service crash
				                PendingIntent pIntent = null;
				                try {
				                    pIntent = PendingIntent.getActivity(getBaseContext(), 0, intent, flags);
				                } catch (Exception e) {
				                    Log.e("andcpp", "Failed to create PendingIntent for file", e);
				                }

								// Notify MediaScanner to make the file available in other apps
								MediaScannerConnection.scanFile(getBaseContext(),
										new String[]{file.getAbsolutePath()}, null, null);

								// Show completion toast
								new Handler(Looper.getMainLooper()).post(() ->
										Toast.makeText(getApplicationContext(), "Download complete: " + work.file_name, Toast.LENGTH_SHORT).show()
								);

								work.mBuilder2.setProgress(0, 0, false);
								work.mBuilder2.setContentText(
										"Download Complete")
										.setContentIntent(pIntent)
										.setOngoing(false)
										.setTicker(
												"Download Complete : "
														+ work.file_name);

								mNotifyManager2.notify(work.currentID,
										work.mBuilder2.build());

								// Share the new file: rebuild our file list shortly (debounced).
								if (uploadManager != null) {
									uploadManager.requestFileListRefresh();
								}

								break;

							} else if (work.is_finished()) {
								work.mBuilder2.setOngoing(false);
								if (mNotifyManager2 != null && !work.isCancelled)
									mNotifyManager2.notify(work.currentID, work.mBuilder2.build());
								break;
							} else if (work.download_status != null && work.download_status.status == DCConstants.DownloadStatus.INTERUPTED) {
								break;
							} else if (work.download_status != null && work.download_status.status == DCConstants.DownloadStatus.WAITING_FOR_PEER) {
								// Peer answered $MaxedOut: park the item and free this worker.
								work.markWaiting("peer has no free slots");
								break;
							} else if (work.download_status != null && (work.download_status.status == DCConstants.DownloadStatus.QUEUED
									|| work.download_status.status == DCConstants.DownloadStatus.INITIATED
									|| work.download_status.status == DCConstants.DownloadStatus.STARTED)) {
								long nowMs = System.currentTimeMillis();
								DCConstants.DownloadStatus st = work.download_status.status;
								if (st != seenStatus) {
									seenStatus = st;
									seenSince = nowMs;
								}
								// QUEUED = the peer never connected; INITIATED/STARTED = a connection was claimed but stalled.
								boolean connectTimedOut =
										(st == DCConstants.DownloadStatus.QUEUED
												&& nowMs - attemptStart > Constants.DOWNLOAD_CONNECT_DEADLINE_MILLIS)
										|| ((st == DCConstants.DownloadStatus.INITIATED || st == DCConstants.DownloadStatus.STARTED)
												&& nowMs - seenSince > Constants.DOWNLOAD_HANDSHAKE_DEADLINE_MILLIS);
								if (connectTimedOut) {
									Log.w("andcpp", "[DLTRACE] No response from " + work.target_nick + " for " + work.file_name
											+ " (status=" + st + "); parking and moving on");
									// Asked the peer to connect to us and it never arrived (QUEUED = nobody came):
									// next time let it accept the connection instead (same VPN server, firewall, ...).
									boolean wasConnectToMe = work.activeRc != null && st == DCConstants.DownloadStatus.QUEUED;
									work.abandonCurrentAttempt();
									if (wasConnectToMe && !work.preferRevConnect) {
										work.preferRevConnect = true;
										// Remember it for this peer: its other queued files skip the wait.
										peersPreferRevConnect.add(work.target_nick.toLowerCase(Locale.ROOT));
										work.peerWaitRetryCount = 0;
										work.waitReason = "retrying with the peer accepting the connection";
										work.nextRetryAt = System.currentTimeMillis() + 5000L; // retry almost at once
										Log.i("andcpp", "[DLTRACE] " + work.file_name + " (" + work.target_nick
												+ "): no connection from peer to us; retrying with RevConnectToMe");
									} else {
										work.markWaiting("no response from peer");
									}
									if (st == DCConstants.DownloadStatus.QUEUED) {
										noteUnansweredConnectRequest(work.hubIp);
									}
									break;
								}
								// Re-send the connect request only while our request is still pending on the peer.
								if (st == DCConstants.DownloadStatus.QUEUED
										&& nowMs - work.lastSignalTime >= Constants.DOWNLOAD_RESIGNAL_INTERVAL_MILLIS) {
									HubSession rs = getSession(work.hubIp);
									boolean stillPending = rs != null && rs.client != null && rs.client.downloadHandler != null
											&& rs.client.downloadHandler.isEntityPending(work.target_nick, work.download_status);
									if (stillPending) {
										try {
											Log.d("andcpp", "[DLTRACE] Re-signaling pending request for " + work.file_name);
											work.start_download();
										} catch (Exception e) {
											Log.e("andcpp", "Failed to re-signal download: " + work.file_name, e);
										}
									}
								}
							} else if (work.download_status != null && work.download_status.status != DCConstants.DownloadStatus.DOWNLOADING) {
								if (work.millis_elapsed() > 300000) { // 5 minute safety timeout for unresponsive connections
									Log.e("andcpp", "Download timed out after 5 minutes in status: " + work.download_status.status + " for file: " + work.file_name);
									work.download_status.status = DCConstants.DownloadStatus.FAILED;
									break;
								}
							}

							long currentProgressBytes = work.bytes_done();
							long now = System.currentTimeMillis();

							if (work.download_status != null
									&& work.download_status.status == DCConstants.DownloadStatus.DOWNLOADING
									&& !hasObservedDownload) {
								hasObservedDownload = true;
								work.peerWaitRetryCount = 0; // the peer answered: reset the backoff
								lastProgressAt = now;
							}

							if (currentProgressBytes > lastProgressBytes) {
								lastProgressBytes = currentProgressBytes;
								lastProgressAt = now;
							} else if (work.download_status != null
									&& work.download_status.status == DCConstants.DownloadStatus.DOWNLOADING
									&& now - lastProgressAt > 120000) {
								Log.w("andcpp", "Download made no progress for 2 minutes; restarting from byte "
										+ currentProgressBytes + ": " + work.file_name);

								closeAdcTransfer(work.download_status); // ADC download: close its connection
								HubSession stalledSession = getSession(work.hubIp);
								if (stalledSession != null && stalledSession.client != null) {
									stalledSession.client.stopDownloadHandler(work.download_status);
								}

								File partialFile = new File(work.local_file);
								work.pendingStartOffset =
										partialFile.exists() ? partialFile.length() : 0;
								work.download_status.status = DCConstants.DownloadStatus.INTERUPTED;
								break;
							}

							FileSize fs = new FileSize(work.avg_speed());
							long total = work.total_bytes();
							long done = work.bytes_done();

							String progressStr = "";
							if (total > 0) {
								int p = (int) (((double) done / total) * 1000);
								double percent = ((double) done / total) * 100.0;
								work.mBuilder2.setProgress(1000, p, false);

								FileSize doneFs = new FileSize(done);
								FileSize totalFs = new FileSize(total);
								progressStr = String.format(java.util.Locale.US, " (%.1f%% - %.2f %s / %.2f %s)",
										percent, doneFs.fileSize, doneFs.unit, totalFs.fileSize, totalFs.unit);
							} else {
								work.mBuilder2.setProgress(0, 0, true);
							}

							if (work.download_status.status == DCConstants.DownloadStatus.QUEUED) {
								work.mBuilder2.setContentText("Queued: Waiting for slot...");
							} else {
								String etaStr = "";
								if (work.avg_speed() > 0 && total > done) {
									long remainingSecs = (long) ((total - done) / work.avg_speed());
									if (remainingSecs >= 3600) {
										etaStr = String.format(java.util.Locale.US, " | ETA: %02d:%02d:%02d",
												remainingSecs / 3600, (remainingSecs % 3600) / 60, remainingSecs % 60);
									} else {
										etaStr = String.format(java.util.Locale.US, " | ETA: %02d:%02d",
												remainingSecs / 60, remainingSecs % 60);
									}
								}
								work.mBuilder2.setContentText(
										"Speed: "
												+ String.format(java.util.Locale.US, "%.2f",
												fs.fileSize) + " "
												+ fs.unit + "/s" + progressStr + etaStr);
							}
							if (!work.isCancelled) {
								mNotifyManager2.notify(work.currentID,
										work.mBuilder2.build());
							}

							try {
								Thread.sleep(Constants.DOWNLOAD_UPDATE_INTERVAL_MILLIS);
							} catch (InterruptedException e) {
								Log.d("andcpp", "sleep failure");
								return; // Exit worker thread
							}
						}
						
						manageWakeLock();

						if (work.isCancelled) {
							Log.i("andcpp", "Download " + work.getFileName() + " cancelled.");
							updateFolderProgress(work);
						} else if (work.isPaused) {
							Log.i("andcpp", "Download " + work.getFileName() + " paused by user.");
							File downloadedFile = new File(work.local_file);
							if (downloadedFile.exists()) {
								work.pendingStartOffset = downloadedFile.length();
							}
							if (work.download_status != null) {
								work.download_status.status = DCConstants.DownloadStatus.INTERUPTED;
							}
							if (work.mBuilder2 != null) {
								work.mBuilder2.setProgress(0, 0, false);
								work.mBuilder2.setContentText("Download Paused")
										.setOngoing(false);
								if (mNotifyManager2 != null) {
									mNotifyManager2.notify(work.currentID, work.mBuilder2.build());
								}
							}
						} else if (work.waitReason != null) {
							// Parked with a reason and backoff (markWaiting already updated UI and notification).
							// The scheduler re-queues it after nextRetryAt; this worker moves on to the next item.
							work.abandonCurrentAttempt();
							Log.i("andcpp", "[DLTRACE] " + work.file_name + " parked: " + work.waitReason);
						} else if (work.download_status != null && work.download_status.status == DCConstants.DownloadStatus.COMPLETED) {
							// Verify if expected file length was reached; if not, treat as interrupted/failed
							File downloadedFile = new File(work.local_file);
							long expectedSize = work.total_bytes();
							if (expectedSize > 0 && downloadedFile.exists() && downloadedFile.length() < expectedSize) {
								Log.w("andcpp", "Download ended prematurely (" + downloadedFile.length() + "/" + expectedSize + "), marking INTERUPTED for auto-resume.");
								work.download_status.status = DCConstants.DownloadStatus.INTERUPTED;
								work.pendingStartOffset = downloadedFile.length();
								work.mBuilder2.setProgress(0, 0, false);
								work.mBuilder2.setContentText("Connection lost, re-queuing...");
								mNotifyManager2.notify(work.currentID, work.mBuilder2.build());
								try {
									Thread.sleep(1500);
									work.resume_download();
								} catch (Exception ignored) {}
							} else {
								Log.i("andcpp", "Download " + work.getFileName() + " completed.");
								updateFolderProgress(work);
							}

						} else if (work.download_status != null && work.download_status.status == DCConstants.DownloadStatus.INTERUPTED) {
							File downloadedFile = new File(work.local_file);
							if (downloadedFile.exists()) {
								work.pendingStartOffset = downloadedFile.length();
							}
							work.mBuilder2.setProgress(0, 0, false);
							work.mBuilder2.setContentText("Connection interrupted, auto-resuming...");
							mNotifyManager2.notify(work.currentID, work.mBuilder2.build());
							try {
								Thread.sleep(2000);
								work.resume_download();
							} catch (Exception ignored) {}
						} else {
							// Failed download: check if partially downloaded to auto-resume instead of failing permanently
							File downloadedFile = new File(work.local_file);
							long expectedSize = work.total_bytes();
							if (work.download_status != null && work.download_status.permanentError) {
								work.permanentlyFailed = true;
								Log.e("andcpp", "[DLTRACE] " + work.file_name + " not available on " + work.target_nick + "; not retrying automatically");
								work.mBuilder2.setProgress(0, 0, false);
								work.mBuilder2.setContentText("Failed: file not available on " + work.target_nick).setOngoing(false);
								mNotifyManager2.notify(work.currentID, work.mBuilder2.build());
								updateFolderProgress(work);
							} else if (downloadedFile.exists() && downloadedFile.length() > 0 && (expectedSize <= 0 || downloadedFile.length() < expectedSize)) {
								Log.w("andcpp", "Download interrupted, scheduling retry from offset " + downloadedFile.length());
								work.pendingStartOffset = downloadedFile.length();
								work.download_status.status = DCConstants.DownloadStatus.INTERUPTED;
								try {
									Thread.sleep(3000);
									work.resume_download();
								} catch (Exception ignored) {}
							} else {
								Log.e("andcpp",
										"Download " + work.getFileName()
												+ " Failed!.");

								work.mBuilder2.setProgress(0, 0, false);
								work.mBuilder2.setContentText(
										"Failed: No slot or Timeout on " + work.target_nick)
										.setTicker(
												"Download Failed: "
														+ work.file_name);

								mNotifyManager2.notify(work.currentID,
										work.mBuilder2.build());

								updateFolderProgress(work);
							}
						}
						saveDownloadsState();
						} catch (RuntimeException ex) {
							Log.e("andcpp", "Download worker error on " + work.file_name, ex);
							if (work.download_status != null) {
								work.download_status.status = DCConstants.DownloadStatus.FAILED;
							}
						} finally {
							if (claimed) {
								releaseClaim(work);
								Log.d("andcpp", "[DLTRACE] worker " + Thread.currentThread().getName() + " released " + work.file_name
										+ " (" + work.target_nick + ") status=" + work.get_status());
							}
						}
					}
				} catch (InterruptedException e) {
					return;
				}
			}
		};
	}

	private final List<Thread> normalWorkers = new ArrayList<>();
	private final Set<String> activeNicks = new HashSet<>();
	private final Set<DownloadObject> activeWorkerObjects = new HashSet<>();
	private LinkedBlockingQueue<DownloadObject> normal_queue; // unbounded: adding must never block (a full fixed-size queue froze the app)
	private ArrayList<DownloadObject> initiated_downloads;

	private final Object claimLock = new Object();

	private static String nickKey(DownloadObject w) {
		return w.target_nick == null ? "" : w.target_nick.toLowerCase(Locale.ROOT);
	}

	/** One worker per object and one active download per peer. */
	private static final class ClaimInfo {
		Thread owner;
		long since;
		long lastBytes;
		long lastChangeAt;
	}

	private final Map<DownloadObject, ClaimInfo> claimInfos = new HashMap<>();
	/** A claim whose download made no byte progress for this long is force-released by the scheduler. */
	private static final long STALE_CLAIM_MILLIS = 240000L;

	private static long safeBytes(DownloadObject w) {
		try {
			return w.bytes_done();
		} catch (Exception e) {
			return -1L;
		}
	}

	private boolean tryClaim(DownloadObject w) {
		synchronized (claimLock) {
			String nick = nickKey(w);
			if (activeWorkerObjects.contains(w) || activeNicks.contains(nick)) {
				return false;
			}
			activeWorkerObjects.add(w);
			activeNicks.add(nick);
			ClaimInfo ci = new ClaimInfo();
			ci.owner = Thread.currentThread();
			ci.since = System.currentTimeMillis();
			ci.lastChangeAt = ci.since;
			ci.lastBytes = safeBytes(w);
			claimInfos.put(w, ci);
			return true;
		}
	}

	/** Only the thread that owns the claim can release it (a force-released zombie worker must not free a newer claim). */
	private void releaseClaim(DownloadObject w) {
		synchronized (claimLock) {
			ClaimInfo ci = claimInfos.get(w);
			if (ci == null || ci.owner != Thread.currentThread()) return;
			claimInfos.remove(w);
			activeWorkerObjects.remove(w);
			activeNicks.remove(nickKey(w));
			claimLock.notifyAll(); // wake workers waiting for a free peer
		}
	}

	private void awaitClaimRelease(long millis) throws InterruptedException {
		synchronized (claimLock) {
			claimLock.wait(millis);
		}
	}

	/**
	 * Watchdog, run every scheduler pass (30s) before anything else. A claimed download whose byte count has not
	 * moved for STALE_CLAIM_MILLIS is stuck (silent socket, blocked thread): close its connection, interrupt and
	 * replace the worker, free the peer and park the item so it is retried with backoff.
	 */
	private void reapStaleClaims() {
		List<DownloadObject> stale = new ArrayList<>();
		long now = System.currentTimeMillis();
		synchronized (claimLock) {
			for (Map.Entry<DownloadObject, ClaimInfo> en : claimInfos.entrySet()) {
				ClaimInfo ci = en.getValue();
				long b = safeBytes(en.getKey());
				if (b != ci.lastBytes) {
					ci.lastBytes = b;
					ci.lastChangeAt = now;
				} else if (now - ci.lastChangeAt > STALE_CLAIM_MILLIS) {
					stale.add(en.getKey());
				}
			}
		}
		for (DownloadObject w : stale) {
			Thread owner;
			synchronized (claimLock) {
				ClaimInfo ci = claimInfos.remove(w);
				if (ci == null) continue;
				owner = ci.owner;
				activeWorkerObjects.remove(w);
				activeNicks.remove(nickKey(w));
				claimLock.notifyAll();
			}
			Log.e("andcpp", "[DLTRACE] STALE CLAIM: " + w.file_name + " (" + w.target_nick + ") no progress for "
					+ (STALE_CLAIM_MILLIS / 1000) + "s on " + (owner != null ? owner.getName() : "?")
					+ " state=" + (owner != null ? owner.getState() : "?") + " status=" + w.get_status() + "; force-releasing");
			try {
				w.abandonCurrentAttempt();
				w.markWaiting("stalled, restarting");
			} catch (Exception e) {
				Log.e("andcpp", "Error parking stale download", e);
			}
			if (owner != null) {
				owner.interrupt();
				synchronized (normalWorkers) {
					normalWorkers.remove(owner);
				}
			}
		}
	}

	private String activeNicksSnapshot() {
		synchronized (claimLock) {
			return activeNicks.toString();
		}
	}

	/** Diagnostic: one line for the scheduler plus one per unfinished download, logged every retry pass (~30s). */
	private void logQueueSnapshot() {
		try {
			int alive = 0;
			synchronized (normalWorkers) {
				for (Thread t : normalWorkers) {
					if (t != null && t.isAlive()) alive++;
				}
			}
			int claimedObjs;
			synchronized (claimLock) {
				claimedObjs = activeWorkerObjects.size();
			}
			synchronized (claimLock) {
				long t = System.currentTimeMillis();
				for (Map.Entry<DownloadObject, ClaimInfo> en : claimInfos.entrySet()) {
					ClaimInfo ci = en.getValue();
					Log.d("andcpp", "[DLTRACE]   CLAIM " + en.getKey().file_name + " nick=" + en.getKey().target_nick
							+ " held=" + (t - ci.since) / 1000 + "s noProgress=" + (t - ci.lastChangeAt) / 1000 + "s bytes=" + ci.lastBytes
							+ " thread=" + ci.owner.getName() + "/" + ci.owner.getState());
				}
			}
			Log.d("andcpp", "[DLTRACE] snapshot: queue=" + (normal_queue != null ? normal_queue.size() : -1)
					+ " workersAlive=" + alive + " claimed=" + claimedObjs + " busyPeers=" + activeNicksSnapshot());
			if (initiated_downloads == null) return;
			// Only the first few items: with ~1000 queued downloads, listing all of them (each with a
			// queue.contains() scan) every 30 s flooded the log and held this lock for a long time.
			final int maxListed = 20;
			int listed = 0;
			int unfinished = 0;
			synchronized (initiated_downloads) {
				for (DownloadObject o : initiated_downloads) {
					if (o.isFileList() || o.is_finished()) continue;
					unfinished++;
					if (listed >= maxListed) continue;
					listed++;
					HubSession hs = getSession(o.hubIp);
					Log.d("andcpp", "[DLTRACE]   " + o.file_name + " nick=" + o.target_nick
							+ " status=" + o.get_status() + " paused=" + o.isPaused
							+ " wait=" + o.waitReason
							+ " retryIn=" + (o.waitReason != null ? Math.max(0L, o.nextRetryAt - System.currentTimeMillis()) / 1000 : 0) + "s"
							+ " inQueue=" + (normal_queue != null && normal_queue.contains(o))
							+ " owned=" + isActiveWorkerObject(o)
							+ " hub=" + (hs == null ? "none" : String.valueOf(hs.status)));
				}
			}
			if (unfinished > listed) {
				Log.d("andcpp", "[DLTRACE]   ... and " + (unfinished - listed) + " more unfinished downloads");
			}
		} catch (Exception e) {
			Log.w("andcpp", "logQueueSnapshot failed", e);
		}
	}

	private boolean isActiveWorkerObject(DownloadObject w) {
		synchronized (claimLock) {
			return activeWorkerObjects.contains(w);
		}
	}

	private static boolean isPrivateOrLoopbackIp(String ip) {
		if (ip == null || ip.isEmpty()) return true;
		int pct = ip.indexOf('%');
		if (pct >= 0) ip = ip.substring(0, pct);
		if (ip.indexOf(':') >= 0) {
			String l = ip.toLowerCase(Locale.US);
			return l.equals("::1") || l.equals("::") || l.startsWith("fe80") || l.startsWith("fc") || l.startsWith("fd");
		}
		String[] p = ip.split("\\.");
		if (p.length != 4) return true;
		try {
			int a = Integer.parseInt(p[0]);
			int b = Integer.parseInt(p[1]);
			if (a == 0 || a == 10 || a == 127) return true;
			if (a == 169 && b == 254) return true;
			if (a == 172 && b >= 16 && b <= 31) return true;
			if (a == 192 && b == 168) return true;
			if (a == 100 && b >= 64 && b <= 127) return true; // carrier-grade NAT
		} catch (NumberFormatException e) {
			return true;
		}
		return false;
	}

	// ---- Active / passive auto-detection ----
	/**
	 * Peers (lower-case nicks) that didn't connect to us after a $ConnectToMe: downloads from them use
	 * $RevConnectToMe (the peer accepts the connection) straight away. Cleared when our connectivity changes.
	 */
	private final Set<String> peersPreferRevConnect = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
	private ConnectivityDetector connectivityDetector;
	/** Last detection result; null until the first detection has finished (we are passive until then). */
	private volatile ConnectivityDetector.Result connectivityResult = null;
	private final java.util.concurrent.ScheduledExecutorService connectivityExecutor =
			java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "Connectivity");
				t.setDaemon(true);
				return t;
			});

	/**
	 * True once detection has a final answer (active, or passive for a known reason). Still waiting for PIA to
	 * hand out a port doesn't count.
	 */
	public boolean isConnectivityDecided() {
		ConnectivityDetector.Result r = connectivityResult;
		return r != null && !r.reason.startsWith("VPN active, waiting");
	}

	/**
	 * Runs action on the main thread as soon as active/passive is decided, or after timeoutMs at the latest
	 * (then with whatever is known; passive until decided).
	 */
	public void whenConnectivityDecided(final Runnable action, final long timeoutMs) {
		final long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
		final Handler h = new Handler(Looper.getMainLooper());
		h.post(new Runnable() {
			@Override
			public void run() {
				boolean decided = isConnectivityDecided();
				if (decided || android.os.SystemClock.elapsedRealtime() >= deadline) {
					Log.i("andcpp", decided ? "Connection mode decided (" + getConnectionModeDescription() + "); continuing"
							: "Connection mode not decided after " + (timeoutMs / 1000) + " s; continuing as "
							+ getConnectionModeDescription());
					action.run();
				} else {
					h.postDelayed(this, 500);
				}
			}
		});
	}

	/** Current connection mode for display, e.g. "Active (UPnP, 1.2.3.4)" or "Passive (...)". */
	public String getConnectionModeDescription() {
		ConnectivityDetector.Result r = connectivityResult;
		return r != null ? r.describe() : "Detecting…";
	}

	/** True when the current mode is active (for display colours). */
	public boolean isConnectionActive() {
		return isActiveNow();
	}

	/** Active only when allowed and detection showed other users can reach us. */
	private boolean isActiveNow() {
		ConnectivityDetector.Result r = connectivityResult;
		return Settings.getActiveMode(this) && r != null && r.active;
	}

	private volatile boolean lastVpnActive = false;

	/** True while the active network is a VPN (then incoming connections arrive through the VPN, not the router). */
	private boolean isVpnActive() {
		try {
			ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
			if (cm == null) return false;
			if (Build.VERSION.SDK_INT >= 23) {
				android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
				return caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
			}
			NetworkInfo ni = cm.getActiveNetworkInfo();
			return ni != null && ni.getType() == ConnectivityManager.TYPE_VPN;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * The port to listen on: while a VPN is on, PIA's automatically forwarded port (if PIA port forwarding is
	 * on and has a port) or the manually entered VPN port; otherwise the normal port.
	 */
	private int desiredListenPort() {
		if (isVpnActive()) {
			PIAPortForwardingManager pia = PIAPortForwardingManager.getInstance(this);
			if (pia.isEnabled() && pia.getCurrentPort() != null) {
				try {
					return Integer.parseInt(pia.getCurrentPort());
				} catch (NumberFormatException ignored) {
				}
			}
			int vpnPort = Settings.getVpnForwardedPort(this);
			if (vpnPort > 0) return vpnPort;
		}
		return Settings.getListeningPort(this);
	}

	/** Re-runs detection in the background (start, network change, ...) and applies the result to all hubs. */
	public void redetectConnectivity(final String why) {
		try {
			connectivityExecutor.execute(() -> {
				try {
					if (connectivityDetector == null) connectivityDetector = new ConnectivityDetector(DCPPService.this);
					connectivityDetector.removeMapping(); // a mapping from a previous network is useless now
					// Listen on the right port for this network (VPN forwarded port or the normal one).
					int port = desiredListenPort();
					if (uploadManager != null && (!uploadManager.isListening() || uploadManager.getPort() != port)) {
						uploadManager.startListening(port);
					}
					ConnectivityDetector.Result r;
					if (!Settings.getActiveMode(DCPPService.this)) {
						r = new ConnectivityDetector.Result(false, null, "passive mode chosen");
					} else if (isVpnActive()) {
						// Through a VPN only the VPN provider can forward a port to us; the home router can't.
						// Other users then connect to the VPN address the hub reports for us ($UserIP).
						PIAPortForwardingManager pia = PIAPortForwardingManager.getInstance(DCPPService.this);
						// After a clean install the login comes back from the backup file once storage is readable.
						pia.restoreFromBackupIfNeeded();
						int vpnPort = Settings.getVpnForwardedPort(DCPPService.this);
						boolean listening = uploadManager != null && uploadManager.isListening();
						if (pia.isEnabled()) {
							if (why.startsWith("network changed")) {
								pia.triggerRebind(); // new tunnel (maybe another server): re-check with PIA
							} else {
								pia.ensureRunning(); // no-op while already running
							}
							if (pia.getCurrentPort() != null && listening) {
								r = new ConnectivityDetector.Result(true, null, "PiA port forwarding, port " + pia.getCurrentPort());
							} else if (pia.getLastError() != null) {
								r = new ConnectivityDetector.Result(false, null, "PiA port forwarding: " + pia.getLastError().replace("PIA", "PiA"));
							} else {
								r = new ConnectivityDetector.Result(false, null, "VPN active, waiting for PiA port forwarding");
							}
						} else if (vpnPort > 0 && listening) {
							r = new ConnectivityDetector.Result(true, null, "VPN port forwarding, port " + vpnPort);
						} else {
							r = new ConnectivityDetector.Result(false, null,
									"VPN active: turn on PIA Port Forwarding (or enter the VPN's forwarded port) in Settings");
						}
					} else {
						PIAPortForwardingManager.getInstance(DCPPService.this).stopPortForwarding(); // no VPN now
						r = connectivityDetector.detect(port);
					}
					applyConnectivity(r, why);
				} catch (Exception e) {
					Log.w("andcpp", "Connectivity re-detection failed", e);
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
			// service is shutting down
		}
	}

	/** Sets every hub session to the detected mode; re-sends $MyINFO where it changed. Runs off the UI thread. */
	private void applyConnectivity(ConnectivityDetector.Result r, String why) {
		ConnectivityDetector.Result old = connectivityResult;
		connectivityResult = r;
		Log.i("andcpp", "Connection mode (" + why + "): " + r.describe());
		boolean changed = old == null || old.active != r.active
				|| (r.externalIp != null && !r.externalIp.equals(old.externalIp));
		if (old == null || !r.reason.equals(old.reason) || changed) {
			peersPreferRevConnect.clear(); // new network / port: peers may be able to reach us now
		}
		for (HubSession s : sessions.values()) {
			if (s.prefs == null) continue;
			if (r.active && uploadManager != null) s.prefs.upload_port = uploadManager.getPort();
			if (s.prefs.active != r.active) {
				s.prefs.active = r.active;
				if (s.myuser != null) {
					s.myuser.active = r.active;
					s.myuser.tag = null; // regenerate the tag with M:A / M:P
				}
				if (s.status == DCClientStatus.CONNECTED && s.hub != null && s.myuser != null) { // NMDC and ADC
					try {
						sendMyInfo(s);
					} catch (Exception e) {
						Log.w("andcpp", "Could not re-send MyINFO to " + s.hubIp, e);
					}
				}
			}
		}
		if (changed) {
			final String text = "Connection mode: " + r.describe();
			new Handler(Looper.getMainLooper()).post(() ->
					Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show());
		}
	}

	/**
	 * The hub tells us which address it sees ($UserIP). If we went active but the hub sees a different public
	 * address, there is another NAT layer (e.g. the carrier's) and nobody can reach us: fall back to passive.
	 */
	private void checkHubSeesOurAddress(final String hubSeenIp) {
		final ConnectivityDetector.Result r = connectivityResult;
		if (r == null || !r.active || r.externalIp == null || hubSeenIp == null) return;
		if (hubSeenIp.indexOf(':') >= 0 || ConnectivityDetector.isPrivateOrLoopback(hubSeenIp)) return; // LAN hub / IPv6
		if (hubSeenIp.equals(r.externalIp)) return;
		try {
			connectivityExecutor.execute(() -> {
				if (connectivityDetector != null) connectivityDetector.removeMapping();
				applyConnectivity(new ConnectivityDetector.Result(false, null,
						"hub sees us at " + hubSeenIp + ", not " + r.externalIp + ": extra NAT"), "hub address check");
			});
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
		}
	}

	/**
	 * The address we can advertise in $ConnectToMe / $SR: the address the hub reported for us ($UserIP), else our
	 * local socket address if it is public, or if the hub itself is on the same private network. Never the hub's
	 * own address. Returns null when nothing usable is known (callers then fall back to passive).
	 */
	private String resolveAdvertiseIp(HubSession s) {
		if (s == null) return null;
		try {
			String hubReported = (s.prefs != null) ? s.prefs.get_self_ip() : null;
			if (hubReported != null && !hubReported.isEmpty()) {
				return hubReported; // the hub saw us at this address
			}
			ConnectivityDetector.Result r = connectivityResult;
			if (r != null && r.active && r.externalIp != null) {
				return r.externalIp; // public address found by detection (own IP or the router's, via UPnP)
			}
			if (s.client != null && s.client.getSocket() != null && s.client.getSocket().getLocalAddress() != null) {
				String local = s.client.getSocket().getLocalAddress().getHostAddress();
				if (local != null && !local.isEmpty() && !"0.0.0.0".equals(local)) {
					java.net.InetAddress hubAddr = s.client.getSocket().getInetAddress();
					boolean hubIsLan = hubAddr != null && isPrivateOrLoopbackIp(hubAddr.getHostAddress());
					if (!isPrivateOrLoopbackIp(local) || hubIsLan) {
						return local;
					}
				}
			}
		} catch (Exception e) {
			Log.w("andcpp", "resolveAdvertiseIp failed", e);
		}
		return null;
	}

	private void installUploadCallback() {
		com.phinvader.libjdcpp.DCHandlers.setUploadCallback(
				new com.phinvader.libjdcpp.DCHandlers.UploadHandlerCallback() {
					@Override
					public void handleHandshakenUploadConnection(java.net.Socket s,
							com.phinvader.libjdcpp.MessageHandler h, String hisNick, String hisLock) {
						UploadManager um = uploadManager;
						if (um != null) {
							um.handleHandshakenUploadConnection(s, h, hisNick, hisLock);
						} else {
							h.close();
						}
					}
				});
	}

	private synchronized void startNormalWorkersIfNeeded() {
		int workerCount = Settings.getDownloadWorkers(this);
		if (workerCount <= 0) workerCount = Constants.DEFAULT_DOWNLOAD_WORKERS;
		synchronized (normalWorkers) {
			for (int i = normalWorkers.size() - 1; i >= 0; i--) {
				if (!normalWorkers.get(i).isAlive()) {
					normalWorkers.remove(i);
				}
			}
			while (normalWorkers.size() < workerCount) {
				Thread t = new Thread(generate_worker(normal_queue, initiated_downloads), "DownloadWorker-" + (normalWorkers.size() + 1));
				t.setPriority(Thread.NORM_PRIORITY);
				normalWorkers.add(t);
				t.start();
			}
		}
	}

	public void download_file(final String nick, final String local_file,
			final String remote_file, final long file_size) {
		download_file(nick, local_file, remote_file, file_size, null, null);
	}

	public void download_file(final String nick, final String local_file,
			final String remote_file, final long file_size, final String groupLabel) {
		download_file(nick, local_file, remote_file, file_size, groupLabel, null);
	}

	public void download_file(final String nick, final String local_file,
			final String remote_file, final long file_size, final String groupLabel, final String groupName) {
		String hubIp = hubKeyWhereOnline(nick); // ADC users must be downloaded through their ADC hub
		if (hubIp == null && !sessions.isEmpty()) {
			hubIp = sessions.keySet().iterator().next();
		}
		download_file(hubIp, nick, local_file, remote_file, file_size, groupLabel, groupName);
	}

	public void download_file(final String hubIp, final String nick, final String local_file,
			final String remote_file, final long file_size, final String groupLabel, final String groupName) {

		downloadEnqueueExecutor.execute(() -> {
			HubSession session = getSession(hubIp);
			String targetHubIp = hubIp;
			if (session != null) {
				targetHubIp = session.hubIp;
			}
			synchronized (initiated_downloads) {
				for (DownloadObject existing : initiated_downloads) {
					if (existing.local_file.equals(local_file) && !existing.is_finished()) {
						Log.d("andcpp", "Download already in progress/queued: " + local_file);
						return;
					}
				}
			}

			// A file may already be on disk (e.g. after a clean install, from an earlier run).
			// Never overwrite it: skip it if complete, resume it if partial, use a new name if it differs.
			String targetFile = local_file;
			long resumeFrom = 0;
			File onDisk = new File(local_file);
			if (onDisk.isFile()) {
				long have = onDisk.length();
				if (file_size > 0 && have == file_size) {
					Log.i("andcpp", "Already downloaded, skipping: " + local_file);
					final DownloadObject done = new DownloadObject(targetHubIp, nick, local_file,
							remote_file, this, groupLabel, groupName);
					done.set_total_bytes(file_size);
					updateFolderProgress(done); // a folder download still counts this file as done
					if (groupLabel == null) {
						new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(),
								"Already downloaded: " + onDisk.getName(), Toast.LENGTH_SHORT).show());
					}
					return;
				} else if (file_size <= 0 || have < file_size) {
					resumeFrom = have; // partial: continue where it stopped (appends, never truncates)
					Log.i("andcpp", "Partial file found (" + have + "/" + file_size + " bytes); resuming: " + local_file);
				} else {
					// Bigger than the remote file: a different file with the same name. Keep it.
					targetFile = uniqueLocalPath(local_file);
					Log.w("andcpp", "Existing " + local_file + " is larger than the remote file (" + have + " > "
							+ file_size + "); downloading to " + targetFile + " instead");
				}
			}

			final DownloadObject obj = new DownloadObject(targetHubIp, nick, targetFile,
					remote_file, this, groupLabel, groupName);
			obj.set_total_bytes(file_size);
			obj.pendingStartOffset = resumeFrom;
			synchronized (initiated_downloads) {
				initiated_downloads.add(obj);
				manageWakeLock();
			}
			try {
				if (normal_queue != null && !normal_queue.contains(obj))
					normal_queue.put(obj);
				startNormalWorkersIfNeeded();
				saveDownloadsState();
			} catch (InterruptedException e) {
				Log.e("andcpp", "Interrupted while adding to queue", e);
			}
		});

	}

	/** "dir/name.ext" -> first free "dir/name (1).ext", "dir/name (2).ext", ... */
	private static String uniqueLocalPath(String path) {
		File f = new File(path);
		String name = f.getName();
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		String ext = dot > 0 ? name.substring(dot) : "";
		for (int i = 1; i < 1000; i++) {
			File candidate = new File(f.getParentFile(), base + " (" + i + ")" + ext);
			if (!candidate.exists()) return candidate.getPath();
		}
		return path + "." + System.currentTimeMillis();
	}

	public void startFolderDownload(String groupLabel, String folderName, int totalFiles, long totalSize) {
		FolderProgress fp = new FolderProgress(groupLabel, folderName, totalFiles, totalSize, this);
		folderProgressMap.put(groupLabel, fp);
		if (mNotifyManager2 != null) {
			mNotifyManager2.notify(fp.notificationId, fp.builder.build());
		}
	}

	/**
	 * Gives all downloads in Queue as well as finished/failed downloads use
	 * member functions of DownloadObject to get details.
	 * 
	 * is_finished() -> Use this to find if it has completed (either
	 * successfully or failed) get_status() -> Use this first to assert its in
	 * Downloading before using others to get progress getFileName()
	 * getTargetNick() total_bytes() bytes_done() millis_ellapsed() avg_speed()
	 * 
	 * @return
	 */
	public List<DownloadItem> get_download_queue() {
		java.util.LinkedHashSet<DownloadObject> all = new java.util.LinkedHashSet<DownloadObject>();
		if (initiated_downloads != null) {
			synchronized (initiated_downloads) {
				all.addAll(initiated_downloads);
			}
		}
		if (normal_queue != null) {
			for (DownloadObject o : normal_queue)
				all.add(o);
		}

		Map<String, FolderDownloadItem> folders = new HashMap<>();
		List<DownloadItem> ret = new ArrayList<>();

		for (DownloadObject o : all) {
			if (o.groupLabel == null) {
				ret.add(o);
			} else {
				FolderDownloadItem folder = folders.get(o.groupLabel);
				if (folder == null) {
					folder = new FolderDownloadItem(o.groupLabel, o.target_nick, this, o.groupName);
					folders.put(o.groupLabel, folder);
					ret.add(folder);
				}
				folder.addDownload(o);
			}
		}
		return ret;
	}

	/**
	 * Use this to clear all finished/failed downloads
	 */
	public void clear_finished_downloads() {
		synchronized (initiated_downloads) {
			ArrayList<DownloadObject> to_remove = new ArrayList<DownloadObject>();
			for (DownloadObject o : initiated_downloads) {
				if (o.is_finished())
					to_remove.add(o);
			}
			initiated_downloads.removeAll(to_remove);
		}
		saveDownloadsState();
	}

	private File getFileListDir() {
		String downloadDir = Settings.getDCDownloadsDirectory(this);
		File fileListDir = new File(downloadDir, "FileLists");
		if (!fileListDir.exists()) {
			fileListDir.mkdirs();
		}
		return fileListDir;
	}

	public DCFileList get_file_list(String hubIp, String nick) {
		File file_list_dir = getFileListDir();
		File saved_file_list = new File(file_list_dir, nick + ".xml");

		// Check if we already have an active/queued download for this file list
		DownloadObject existingObj = null;
		synchronized (initiated_downloads) {
			for (DownloadObject o : initiated_downloads) {
				if (o.getTarget_nick().equals(nick) && o.isFileList() && !o.is_finished()) {
					existingObj = o;
					break;
				}
			}
		}

		final DownloadObject obj;
		if (existingObj != null) {
			obj = existingObj;
		} else {
			obj = new DownloadObject(hubIp, nick,
					saved_file_list.getAbsolutePath(), "files.xml", this, null, null);
			synchronized (initiated_downloads) {
				initiated_downloads.add(obj);
			}
		}
		try {
			long lastRevConnect = 0;
			// File list requests are more persistent, 5 minute timeout
			long timeout = 300000;
			while (true) {
				if (System.currentTimeMillis() - lastRevConnect > 30000) {
					obj.start_download(); // This re-sends RevConnect and ensures it's in the lib's queue
					lastRevConnect = System.currentTimeMillis();
				}

				if (obj.download_status != null && obj.download_status.status == DCConstants.DownloadStatus.COMPLETED)
					break;
				else if (obj.is_finished() && obj.get_status() != DCConstants.DownloadStatus.QUEUED)
					break;
				else if (obj.millis_elapsed() > timeout) {
					Log.d("andcpp", "download_filelist Timeout reached : " + obj.millis_elapsed());
					break;
				}

				// Update notification for file list download
				if (mNotifyManager2 != null) {
					FileSize fs = new FileSize(obj.avg_speed());
					long total = obj.total_bytes();
					long done = obj.bytes_done();
					String progressStr = "";
					if (total > 0) {
						int p = (int) (((double) done / total) * 1000);
						double percent = ((double) done / total) * 100.0;
						obj.mBuilder2.setProgress(1000, p, false);
						FileSize doneFs = new FileSize(done);
						FileSize totalFs = new FileSize(total);
						progressStr = String.format(java.util.Locale.US, " (%.1f%% - %.2f %s / %.2f %s)",
								percent, doneFs.fileSize, doneFs.unit, totalFs.fileSize, totalFs.unit);
					} else {
						obj.mBuilder2.setProgress(0, 0, true);
					}

					if (obj.get_status() == DCConstants.DownloadStatus.QUEUED) {
						obj.mBuilder2.setContentText("Queued: Waiting for slot...");
					} else {
						obj.mBuilder2.setContentText(
								"Speed: "
										+ String.format(java.util.Locale.US, "%.2f",
										fs.fileSize) + " "
										+ fs.unit + "/s" + progressStr);
					}
					mNotifyManager2.notify(obj.currentID, obj.mBuilder2.build());
				}

				Thread.sleep(Constants.DOWNLOAD_UPDATE_INTERVAL_MILLIS);
			}
			if (obj.download_status != null && obj.download_status.status == DCConstants.DownloadStatus.COMPLETED) {
				// Handle completion notification for file list
				obj.mBuilder2.setProgress(0, 0, false);
				obj.mBuilder2.setContentText("Download Complete")
						.setOngoing(false);
				mNotifyManager2.notify(obj.currentID, obj.mBuilder2.build());

				DCFileList ret = DCFileList.parseXML(saved_file_list);
				return ret;
			} else {
				if (obj.download_status != null) {
					obj.download_status.status = DCConstants.DownloadStatus.FAILED;
				}
				synchronized (initiated_downloads) {
					initiated_downloads.remove(obj);
				}
				if (mNotifyManager2 != null) {
					obj.mBuilder2.setOngoing(false);
					mNotifyManager2.cancel(obj.currentID);
				}
				String s = "null";
				if (obj.download_status != null && obj.download_status.status != null)
					s = obj.download_status.status.name();
				Log.d("andcpp", "download_file_list: Got download status as : " + s);
				return null;
			}
		} catch (InterruptedException e) {
			Log.d("andcpp", "DCPPService: Got an interrupted exception");
			if (obj.download_status != null) {
				obj.download_status.status = DCConstants.DownloadStatus.FAILED;
			}
			synchronized (initiated_downloads) {
				initiated_downloads.remove(obj);
			}
			if (mNotifyManager2 != null) {
				mNotifyManager2.cancel(obj.currentID);
			}
			return null;
		} catch (Exception e) {
			Log.d("andcpp", "DCPPService: Got an unknown exception in get_file_list", e);
			if (obj.download_status != null) {
				obj.download_status.status = DCConstants.DownloadStatus.FAILED;
			}
			synchronized (initiated_downloads) {
				initiated_downloads.remove(obj);
			}
			if (mNotifyManager2 != null) {
				mNotifyManager2.cancel(obj.currentID);
			}
			return null;
		}

	}

	public DCFileList get_cached_file_list(String nick) {
		File file_list_dir = getFileListDir();
		File saved_file_list = new File(file_list_dir, nick + ".xml");
		if (saved_file_list.exists()) {
			return DCFileList.parseXML(saved_file_list);
		}
		return null;
	}

	private void updateFolderProgress(DownloadObject work) {
		if (work.groupLabel == null) return;
		FolderProgress fp = folderProgressMap.get(work.groupLabel);
		if (fp != null) {
			synchronized (fp) {
				fp.completedFiles++;
				fp.completedSize += work.total_bytes();

				if (mNotifyManager2 != null) {
					int progress = (int) (((double) fp.completedFiles / fp.totalFiles) * 1000);
					fp.builder.setProgress(1000, progress, false)
							.setContentText(String.format(java.util.Locale.US, "Downloaded %d/%d files (%.2f%%)",
									fp.completedFiles, fp.totalFiles, (progress / 10.0)));

					if (fp.completedFiles >= fp.totalFiles) {
						fp.builder.setOngoing(false)
								.setContentText("Folder Download Complete: " + fp.folderName)
								.setProgress(0, 0, false);
						folderProgressMap.remove(work.groupLabel);
					}
					mNotifyManager2.notify(fp.notificationId, fp.builder.build());
				}
			}
		}
	}

	/** Rebuild our shared file list within a few seconds (app opened / storage permission granted). */
	public void refreshMyFileListSoon() {
		if (uploadManager != null) {
			uploadManager.requestFileListRefreshSoon();
		}
	}

	/**
	 * Absolute paths of downloads that are not complete (queued, running, paused, failed or cancelled).
	 * These files are partial on disk, so they are left out of our shared file list.
	 */
	public Set<String> getIncompleteDownloadPaths() {
		Set<String> paths = new HashSet<>();
		if (initiated_downloads == null) return paths;
		synchronized (initiated_downloads) {
			for (DownloadObject o : initiated_downloads) {
				if (o.local_file == null) continue;
				boolean complete = !o.isCancelled && o.download_status != null
						&& o.download_status.status == DCConstants.DownloadStatus.COMPLETED;
				if (!complete) paths.add(new File(o.local_file).getAbsolutePath());
			}
		}
		return paths;
	}

	public List<String> get_saved_file_lists() {
		File file_list_dir = getFileListDir();
		File[] files = file_list_dir.listFiles();
		List<String> nicks = new ArrayList<>();
		if (files != null) {
			for (File f : files) {
				if (f.isFile() && f.getName().endsWith(".xml")) {
					String name = f.getName().substring(0, f.getName().length() - 4);
					if (UploadManager.isLegacyFileListName(name)) continue; // old-build leftover
					nicks.add(name);
				}
			}
		}
		Collections.sort(nicks);
		return nicks;
	}

	public boolean delete_saved_file_list(String nick) {
		File file_list_dir = getFileListDir();
		File saved_file_list = new File(file_list_dir, nick + ".xml");
		if (saved_file_list.exists()) {
			return saved_file_list.delete();
		}
		return false;
	}

	private void updateFolderStatus(DownloadObject work, DCConstants.DownloadStatus status) {
		if (work.groupLabel == null) return;
		FolderProgress fp = folderProgressMap.get(work.groupLabel);
		if (fp != null) {
			synchronized (fp) {
				if (status == DCConstants.DownloadStatus.DOWNLOADING) {
					fp.builder.setContentTitle("Downloading Folder: " + fp.folderName);
				} else if (status == DCConstants.DownloadStatus.QUEUED) {
					// Don't change title for queued if already downloading something else
				} else if (status == DCConstants.DownloadStatus.WAITING_FOR_PEER
						&& WAIT_REASON_WIFI.equals(work.waitReason) && fp.builder != null) {
					fp.builder.setContentTitle("Waiting for Wi-Fi: " + fp.folderName).setOngoing(false);
					if (mNotifyManager2 != null) {
						mNotifyManager2.notify(fp.notificationId, fp.builder.build());
					}
				}
				// We don't notify here to avoid flooding, updateFolderProgress handles it on completion
			}
		}
	}

	public void setBoard_message_handler(String hubKey, DCCommand handler) {
		if (hubKey == null) {
			for (HubSession session : sessions.values()) {
				session.board_message_handler = handler;
			}
			return;
		}
		HubSession session = getSession(hubKey);
		if (session != null) session.board_message_handler = handler;
	}

	public void setUser_handler(String hubKey, DCCommand handler) {
		if (hubKey == null) {
			for (HubSession session : sessions.values()) {
				session.user_handler = handler;
			}
			return;
		}
		HubSession session = getSession(hubKey);
		if (session != null) session.user_handler = handler;
	}

	public void setSearch_handler(String hubKey, DCCommand handler) {
		HubSession session = getSession(hubKey);
		if (session != null) session.search_handler = handler;
	}

	/** The UI's PM handler, also used for sessions that don't have their own yet (see handleIncomingPrivateMessage). */
	private volatile DCCommand defaultPrivateMessageHandler = null;

	/** A PM while no UI is listening: a notification that opens the chat when tapped (no window pops up). */
	void showPrivateMessageNotification(String hubIp, String sender, String text) {
		try {
			Intent open = new Intent(this, PrivateChatActivity.class);
			open.putExtra(PrivateChatActivity.EXTRA_HUB_IP, hubIp);
			open.putExtra(PrivateChatActivity.EXTRA_TARGET_NICK, sender);
			open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
			int id = 50000 + ((hubIp + "#" + sender).toLowerCase(Locale.US).hashCode() & 0xFFFF);
			int flags = PendingIntent.FLAG_UPDATE_CURRENT;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
			NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
					.setSmallIcon(R.drawable.ic_launcher)
					.setContentTitle("PM from " + sender)
					.setContentText(text)
					.setAutoCancel(true)
					.setContentIntent(PendingIntent.getActivity(this, id, open, flags));
			if (mNotifyManager2 != null) mNotifyManager2.notify(id, b.build());
		} catch (Exception e) {
			Log.w("andcpp", "Could not show PM notification", e);
		}
	}

	public void setPrivate_message_handler(String hubKey, DCCommand handler) {
		if (hubKey == null) {
			defaultPrivateMessageHandler = handler;
			for (HubSession session : sessions.values()) {
				session.private_message_handler = handler;
			}
			return;
		}
		HubSession session = getSession(hubKey);
		if (session != null) session.private_message_handler = handler;
	}

	private class MyUserHandler implements DCCommand {
		private String hubKey;
		public MyUserHandler(String hubKey) { this.hubKey = hubKey; }
		@Override
		public void onCommand(DCMessage dcMessage) {
			HubSession session = getSession(hubKey);
			if (session != null) {
				dcMessage.hubname_s = session.hubIp;
				if (session.user_handler != null)
					session.user_handler.onCommand(dcMessage);
			}
		}
	}

	private class MySearchHandler implements DCCommand {
		private String hubKey;
		public MySearchHandler(String hubKey) { this.hubKey = hubKey; }
		@Override
		public void onCommand(DCMessage dcMessage) {
			HubSession session = getSession(hubKey);
			if (session != null) {
				dcMessage.hubname_s = session.hubIp; // which hub the result came from (used to download it)
				if (session.search_handler != null)
					session.search_handler.onCommand(dcMessage);
				synchronized (session.search_results) {
					if (session.search_results.size() < 2000) session.search_results.add(dcMessage);
				}
			}
		}
	}

	/**
	 * Answers other users' searches off the hub reader thread (a slow answer must never stall the hub
	 * connection). Bounded: on a busy hub, searches beyond the queue are dropped rather than piling up.
	 */
	private final java.util.concurrent.ThreadPoolExecutor searchReplyExecutor =
			new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
					new java.util.concurrent.ArrayBlockingQueue<Runnable>(32),
					new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
	private java.net.DatagramSocket searchUdpSocket;

	private class MyIncomingSearchHandler implements DCCommand {
		private String hubKey;
		public MyIncomingSearchHandler(String hubKey) { this.hubKey = hubKey; }
		@Override
		public void onCommand(DCMessage msg) {
			if (msg.search_ip_port == null || msg.search_term == null) return;
			final String target = msg.search_ip_port;
			final String term = msg.search_term;
			searchReplyExecutor.execute(() -> {
				try {
					replyToSearch(hubKey, target, term);
				} catch (Exception e) {
					Log.e("andcpp", "Failed to answer search", e);
				}
			});
		}
	}

	/**
	 * $Search <Hub:nick | ip:port> <sizeRestricted>?<isMax>?<size>?<type>?<pattern>
	 * Passive searchers (Hub:nick) get results through the hub with "\x05nick" appended (max 5);
	 * active searchers (ip:port) get them by UDP (max 10).
	 */
	private void replyToSearch(String hubKey, String target, String term) throws Exception {
		HubSession session = getSession(hubKey);
		if (session == null || session.client == null || session.myuser == null || uploadManager == null
				|| session.status != DCClientStatus.CONNECTED) return;
		String myNick = session.myuser.nick;

		String[] q = term.split("\\?", 5);
		if (q.length < 5) return;
		boolean sizeRestricted = "T".equals(q[0]);
		boolean isMax = "T".equals(q[1]);
		long size;
		int type;
		try {
			size = Long.parseLong(q[2]);
			type = Integer.parseInt(q[3]);
		} catch (NumberFormatException e) {
			return;
		}
		String pattern = q[4];

		boolean passiveSearcher = target.startsWith("Hub:");
		String searcherNick = passiveSearcher ? target.substring(4) : null;
		if (passiveSearcher && searcherNick.equalsIgnoreCase(myNick)) return; // our own search echoed back
		// Two passive users can never connect, so a passive searcher can't use our results while we're passive.
		if (passiveSearcher && (session.prefs == null || !session.prefs.active)) return;

		List<UploadManager.SearchHit> hits = uploadManager.searchShare(sizeRestricted, isMax, size, type, pattern,
				passiveSearcher ? 5 : 10);
		if (hits.isEmpty()) return;
		Log.d("andcpp", "Search from " + target + " for '" + pattern + "' (type " + type + "): " + hits.size() + " result(s)");

		HubConnection hub = session.hub;
		if (hub == null) return;
		int freeSlots = uploadManager.getFreeSlots();
		int maxSlots = uploadManager.getMaxSlots();
		for (UploadManager.SearchHit h : hits) {
			SearchResult r = new SearchResult(h.path, h.size, h.tth, h.dir, freeSlots, maxSlots);
			if (passiveSearcher) {
				hub.sendSearchResultViaHub(session.myuser, r, session.hubName, searcherNick); // the hub routes it
			} else {
				sendSearchResultUdp(target, hub.searchResultDatagram(session.myuser, r, session.hubName));
			}
		}
	}

	private void sendSearchResultUdp(String ipPort, byte[] data) {
		int colon = ipPort.lastIndexOf(':');
		if (colon <= 0) return;
		try {
			int port = Integer.parseInt(ipPort.substring(colon + 1).trim());
			java.net.InetAddress addr = java.net.InetAddress.getByName(ipPort.substring(0, colon).trim());
			synchronized (this) {
				if (searchUdpSocket == null || searchUdpSocket.isClosed()) {
					searchUdpSocket = new java.net.DatagramSocket();
				}
				searchUdpSocket.send(new java.net.DatagramPacket(data, data.length, addr, port));
			}
		} catch (Exception e) {
			Log.w("andcpp", "Could not send search result to " + ipPort + ": " + e.getMessage());
		}
	}

	private class MyBoardMessageHandler implements DCCommand {
		private String hubKey;
		public MyBoardMessageHandler(String hubKey) { this.hubKey = hubKey; }
		@Override
		public void onCommand(DCMessage dcMessage) {
			HubSession session = getSession(hubKey);
			if (session != null) {
				dcMessage.hubname_s = session.hubIp;
				if (session.board_message_handler != null)
					session.board_message_handler.onCommand(dcMessage);
			}
		}
	}

	private class MyPrivateMessageHandler implements DCCommand {
		private String hubKey;
		public MyPrivateMessageHandler(String hubKey) { this.hubKey = hubKey; }
		@Override
		public void onCommand(DCMessage dcMessage) {
			HubSession session = getSession(hubKey);
			if (session != null) {
				dcMessage.hubname_s = session.hubIp;
				handleIncomingPrivateMessage(session, dcMessage);
			}
		}
	}

	private class MyHubQuitHandler implements DCCommand {
		private String hubKey;
		public MyHubQuitHandler(String hubKey) { this.hubKey = hubKey; }
		@Override
		public void onCommand(DCMessage dcMessage) {
			Log.d("andcpp", "Hub connection lost for " + hubKey + ": " + dcMessage.quit_s);
			// Exact lookup: getSession() falls back to another hub when this one was removed (Delete Hub,
			// or replaced by its secure/plain twin), and that other hub would then be "reconnected".
			HubSession session = sessions.get(hubKey);
			if (session != null && session.status != DCClientStatus.DISCONNECTED) {
				handleConnectionFailure(hubKey, session, dcMessage.quit_s);
			}
		}
	}
	
	private class MyRevConnectHandler implements DCCommand {
		private String hubKey;
		public MyRevConnectHandler(String hubKey) { this.hubKey = hubKey; }
		@Override
		public void onCommand(DCMessage msg) {
			Log.d("andcpp", "Received RevConnectToMe from " + msg.revconnect_from + " for " + msg.revconnect_to);
			HubSession session = getSession(hubKey);
			if (session != null && session.client != null && session.myuser != null) {
				if (msg.revconnect_to.equals(session.myuser.nick)) {
					if (session.prefs.active && uploadManager.isListening() && resolveAdvertiseIp(session) != null) {
						String ourIp = resolveAdvertiseIp(session);
						int ourPort = uploadManager.getPort();
						Log.d("andcpp", "Sending $ConnectToMe " + msg.revconnect_from + " " + ourIp + ":" + ourPort
								+ " in response to RevConnectToMe (Active Mode)");
						try {
							HubConnection hub = session.hub;
							if (hub != null) hub.sendConnectToMe(msg.revconnect_from, ourIp, ourPort);
						} catch (Exception e) {
							Log.e("andcpp", "Failed to send ConnectToMe", e);
						}
					} else {
						Log.d("andcpp", "Ignoring RevConnectToMe (Passive Mode or Listener not ready). Peer should have sent ConnectToMe if they were Active.");
					}
				}
			}
		}
	}

	private class MyUserInfoHandler implements DCCommand {
		private String hubKey;
		public MyUserInfoHandler(String hubKey) { this.hubKey = hubKey; }
		@Override
		public void onCommand(DCMessage msg) {
			HubSession session = getSession(hubKey);
			if (session != null && session.myuser != null && msg.myinfo != null && msg.myinfo.nick.equals(session.myuser.nick)) {
				// Some hubs send our IP in $MyINFO
				// The parser doesn't extract IP from MyINFO, but we could check if needed.
			}
			if (msg.myinfo != null && msg.myinfo.nick != null) {
				retryDownloadsOfUserBackOnline(msg.myinfo.nick);
			}
		}
	}

	/**
	 * A user (re)appeared on a hub: downloads parked as "user offline" for that nick retry now instead of after
	 * their backoff (which grows to several minutes).
	 */
	private void retryDownloadsOfUserBackOnline(String nick) {
		boolean any = false;
		synchronized (initiated_downloads) {
			for (DownloadObject obj : initiated_downloads) {
				if ("user offline".equals(obj.waitReason) && obj.target_nick != null
						&& obj.target_nick.equalsIgnoreCase(nick)) {
					obj.nextRetryAt = 0;
					any = true;
				}
			}
		}
		if (any) {
			Log.i("andcpp", "[DLTRACE] " + nick + " is online again; retrying their downloads now");
			new Handler(Looper.getMainLooper()).post(this::retryPendingDownloads);
		}
	}

	private class MyUserIPHandler implements DCCommand {
		private String hubKey;
		public MyUserIPHandler(String hubKey) { this.hubKey = hubKey; }
		@Override
		public void onCommand(DCMessage msg) {
			// Hub often sends $UserIP <MyNick> <MyIP>|
			// libjdcpp parse() might have set msg_s if it didn't fully parse UserIP
			String data = msg.msg_s != null ? msg.msg_s : "";
			if (data.isEmpty()) return;
			
			// $UserIP is a "$$"-separated list ("nick ip$$nick2 ip2$$"); many hubs end even a single
			// entry with "$$", which used to end up in our IP ("1.2.3.4$$").
			String detectedIp = null;
			HubSession me = getSession(hubKey);
			String[] entries = data.trim().split("\\$\\$");
			for (String entry : entries) {
				String[] parts = entry.trim().split(" ");
				if (parts.length >= 2) {
					if (me != null && me.myuser != null && parts[0].equals(me.myuser.nick)) {
						detectedIp = parts[1].trim();
						break;
					}
				} else if (parts.length == 1 && entries.length == 1 && !parts[0].isEmpty()) {
					detectedIp = parts[0].trim();
				}
			}

			if (detectedIp != null && !detectedIp.isEmpty()) {
				Log.d("andcpp", "Hub reported our IP as: " + detectedIp);
				HubSession session = getSession(hubKey);
				if (session != null && session.prefs != null) {
					session.prefs.set_self_ip(detectedIp);
				}
				checkHubSeesOurAddress(detectedIp);
				
				if (session != null && session.client != null && session.myuser != null) {
					// Update user object and preferences
					session.myuser.active = session.prefs.active;
					
					// Active Mode PIA overrides
					if (session.prefs.active) {
						com.phinmadvader.andcpp.pia.PIAPortForwardingManager piaManager = com.phinmadvader.andcpp.pia.PIAPortForwardingManager.getInstance(DCPPService.this);
						if (piaManager.isEnabled() && piaManager.getCurrentPort() != null) {
							// If PIA is active, $UserIP gives us our public IP, which is correct for $MyINFO.
							// But we need to make sure the active port sent in the tag is the PIA port.
							session.prefs.upload_port = Integer.parseInt(piaManager.getCurrentPort());
							Log.d("andcpp", "Using PIA IP/Port for Active Mode: " + detectedIp + ":" + session.prefs.upload_port);
						} else {
							session.prefs.upload_port = uploadManager.getPort();
						}
					}
					
					session.myuser.tag = null; // Force tag regeneration in sendMyInfo
					
					// Re-send MyINFO to confirm active status with updated IP
					sendMyInfo(session);
					Log.d("andcpp", "Sent updated $MyINFO to hub with detected IP");
				}
			}
		}
	}

	public DCClientStatus get_status(String hubIp) {
		HubSession session = getSession(hubIp);
		return session != null ? session.status : DCClientStatus.DISCONNECTED;
	}

	public DCClientStatus get_status() {
		String hubIp = null;
		if (!sessions.isEmpty()) {
			hubIp = sessions.keySet().iterator().next();
		}
		return get_status(hubIp);
	}

	/**
	 * The session for a hub the UI names (session key or host). Unlike getSession(), never another hub's
	 * session when that hub isn't there: its users and chat would be shown under the wrong hub.
	 * A null hubIp still means "any connected hub", as before.
	 */
	private HubSession sessionForUi(String hubIp) {
		if (hubIp == null) return getSession(null);
		HubSession s = sessions.get(hubIp);
		if (s != null) return s;
		for (HubSession x : sessions.values()) {
			if (x.hubIp.equals(hubIp)) return x;
		}
		return null;
	}

	/**
	 * The hub a download goes through: its own hub if that's an NMDC hub with a client, else (as before) another
	 * connected NMDC hub. Never an ADC hub: downloads there need ADC client-to-client transfers (phase 8), and
	 * the old "first session" fallback moved queued NMDC downloads onto an ADC hub, where they got stuck.
	 */
	HubSession downloadSession(String hubIp) {
		HubSession own = hubIp != null ? sessionForUi(hubIp) : null;
		if (own != null && own.client != null) return own;
		for (HubSession s : sessions.values()) {
			if (s.client != null && s.status == DCClientStatus.CONNECTED) return s;
		}
		for (HubSession s : sessions.values()) {
			if (s.client != null) return s;
		}
		return null;
	}

	/** Session key of a connected hub where this nick is online (an NMDC hub first), or null. */
	String hubKeyWhereOnline(String nick) {
		if (nick == null) return null;
		String adcKey = null;
		for (Map.Entry<String, HubSession> e : sessions.entrySet()) {
			HubConnection h = e.getValue().hub;
			if (e.getValue().status != DCClientStatus.CONNECTED || h == null) continue;
			for (DCUser u : h.users()) {
				if (u != null && nick.equalsIgnoreCase(u.nick)) {
					if (e.getValue().protocol.isAdc()) {
						if (adcKey == null) adcKey = e.getKey();
					} else {
						return e.getKey(); // NMDC: the long-tested path
					}
					break;
				}
			}
		}
		return adcKey;
	}

	/** True if the hub the UI names is an ADC / ADCS hub. */
	public boolean isAdcHub(String hubIp) {
		HubSession s = sessionForUi(hubIp);
		return s != null && s.protocol.isAdc();
	}

	public List<DCUser> get_nick_list(String hubIp) {
		HubSession session = sessionForUi(hubIp);
		if (session == null) return new ArrayList<>();
		if (session.client != null) return session.client.get_nick_list();
		HubConnection hub = session.hub; // ADC
		return hub != null ? hub.users() : new ArrayList<>();
	}

	public List<String> get_board_messages(String hubIp) {
		ArrayList<String> ret = new ArrayList<String>();
		HubSession session = sessionForUi(hubIp);
		if (session != null && session.client != null) {
			for(DCMessage msg: session.client.getBoardMessages()) {
				ret.add(msg.msg_s);
			}
		} else if (session != null && session.hub instanceof AdcHubConnection) {
			ret.addAll(((AdcHubConnection) session.hub).boardMessages());
		}
		return ret;
	}

	public List<String> get_board_messages() {
		return get_board_messages(null);
	}

	public boolean send_chat_message(String hubIp, String text) {
		if (text == null || text.trim().isEmpty()) return false;
		HubSession session = sessionForUi(hubIp); // never another hub: the text would go to the wrong hub
		final HubConnection hub = session != null ? session.hub : null;
		if (hub != null && session.myuser != null && session.status == DCClientStatus.CONNECTED) {
			final DCUser me = session.myuser;
			new Thread(() -> {
				try {
					hub.sendMainChat(me, text);
				} catch (Exception e) {
					Log.e("andcpp", "Failed to send chat message: " + e.getMessage(), e);
				}
			}).start();
			return true;
		}
		return false;
	}

	public boolean send_chat_message(String text) {
		return send_chat_message(null, text);
	}

	private final Map<String, List<ChatMessage>> privateChatHistory = new ConcurrentHashMap<>();

	private String getPmConversationKey(String hubIp, String targetNick) {
		String hub = (hubIp != null) ? hubIp.toLowerCase(Locale.US) : "";
		String nick = (targetNick != null) ? targetNick.toLowerCase(Locale.US) : "";
		return hub + "#" + nick;
	}

	public List<ChatMessage> get_private_messages(String hubIp, String targetNick) {
		String key = getPmConversationKey(hubIp, targetNick);
		List<ChatMessage> list = privateChatHistory.get(key);
		if (list == null) {
			list = Collections.synchronizedList(new ArrayList<>());
			privateChatHistory.put(key, list);
		}
		synchronized (list) {
			return new ArrayList<>(list);
		}
	}

	public boolean send_private_message(String hubIp, String targetNick, String text) {
		if (text == null || text.trim().isEmpty() || targetNick == null || targetNick.trim().isEmpty()) {
			return false;
		}
		HubSession session = sessionForUi(hubIp); // never another hub: the PM would go to the wrong hub
		final HubConnection hub = session != null ? session.hub : null;
		if (hub != null && session.myuser != null && session.status == DCClientStatus.CONNECTED) {
			String myNick = session.myuser.nick;
			final DCUser me = session.myuser;
			new Thread(() -> {
				try {
					hub.sendPrivate(me, targetNick, text); // NMDC: $To: ... with '|' and '$' escaped
				} catch (Exception e) {
					Log.e("andcpp", "Failed to send private message: " + e.getMessage(), e);
				}
			}).start();

			// Record outgoing message in history
			String key = getPmConversationKey(session.hubIp, targetNick);
			List<ChatMessage> list = privateChatHistory.get(key);
			if (list == null) {
				list = Collections.synchronizedList(new ArrayList<>());
				privateChatHistory.put(key, list);
			}
			ChatMessage outgoingMsg = new ChatMessage(myNick, text.trim(), false);
			list.add(outgoingMsg);

			// Broadcast intent to notify open PrivateChatActivity or MainActivity if any
			Intent intent = new Intent("com.phinmadvader.andcpp.PRIVATE_MESSAGE_RECEIVED");
			intent.putExtra("hubIp", session.hubIp);
			intent.putExtra("senderNick", targetNick);
			sendBroadcast(intent);

			return true;
		}
		return false;
	}

	private void handleIncomingPrivateMessage(HubSession session, DCMessage dcMessage) {
		String sender = dcMessage.pm_from;
		String text = dcMessage.pm_text;
		if (sender == null || sender.isEmpty() || text == null) return;

		String key = getPmConversationKey(session.hubIp, sender);
		List<ChatMessage> list = privateChatHistory.get(key);
		if (list == null) {
			list = Collections.synchronizedList(new ArrayList<>());
			privateChatHistory.put(key, list);
		}
		ChatMessage incomingMsg = new ChatMessage(sender, text, true);
		list.add(incomingMsg);

		// Hand it to the UI (MainActivity adds the PM tab without switching to it). A session that only just
		// logged in may not have its own handler yet (hub bots PM right after login), so use the UI's
		// general one. Never start an activity from here: it took over the screen on every hub (re)login.
		DCCommand handler = session.private_message_handler != null
				? session.private_message_handler : defaultPrivateMessageHandler;
		if (handler != null) {
			handler.onCommand(dcMessage);
		} else {
			showPrivateMessageNotification(session.hubIp, sender, text);
		}

		// Also send broadcast so PrivateChatActivity or MainActivity / tab list update immediately
		Intent intent = new Intent("com.phinmadvader.andcpp.PRIVATE_MESSAGE_RECEIVED");
		intent.putExtra("hubIp", session.hubIp);
		intent.putExtra("senderNick", sender);
		intent.putExtra("messageText", text);
		sendBroadcast(intent);
	}

	public DCPPService() {
		super();
		initiated_downloads = new ArrayList<DownloadObject>();
		normal_queue = new LinkedBlockingQueue<DownloadObject>();
	}

	@Override
	public void onCreate() {
		super.onCreate();
		runningInstance = this;
		ensureForeground("");
		// Make our TLS certificate now (first start only: RSA key generation takes a moment on old phones),
		// so the first secure hub doesn't wait for it and About can show our keyprint.
		new Thread(() -> ClientCertificateStore.get(DCPPService.this), "client-cert").start();
		if (initiated_downloads == null)
			initiated_downloads = new ArrayList<DownloadObject>();
		if (normal_queue == null)
			normal_queue = new LinkedBlockingQueue<DownloadObject>();
		mNotifyManager2 = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		
		        uploadManager = new UploadManager(this);
		installUploadCallback();
		// Listen now; detection moves the listener to a forwarded VPN/PIA port when there is one.
		uploadManager.startListening(desiredListenPort());

		// PIA port forwarding reports its port (or failure) here; detection then applies it.
		PIAPortForwardingManager.getInstance(this).setListener(new PIAPortForwardingManager.PortForwardingListener() {
			@Override
			public void onPortBound(String port) {
				handlePIAPortBound(port);
			}

			@Override
			public void onPortError() {
				Log.w("andcpp", "PIA port forwarding failed: " + PIAPortForwardingManager.getInstance(DCPPService.this).getLastError());
				redetectConnectivity("PIA port forwarding failed");
			}
		});

		// Active/passive auto-detection: now, and keep a UPnP port mapping alive (lease is 1 hour).
		// Record the current VPN state first, so the first network event isn't mistaken for "VPN turned on"
		// (that restarted PIA port forwarding and got a second, different port on every start).
		lastVpnActive = isVpnActive();
		redetectConnectivity("start");
		connectivityExecutor.scheduleWithFixedDelay(() -> {
			ConnectivityDetector.Result r = connectivityResult;
			if (r != null && r.active && ("UPnP".equals(r.reason) || "NAT-PMP".equals(r.reason)) && connectivityDetector != null
					&& !connectivityDetector.refreshMapping()) {
				redetectConnectivity("UPnP mapping lost");
			}
		}, 30, 30, java.util.concurrent.TimeUnit.MINUTES);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
		if (powerManager != null) {
			wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnDCpp::NetworkWakeLock");
		}
		
		WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
		if (wifiManager != null) {
			wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AnDCpp::WifiLock");
		}
		
		startKeepAliveTimer();
		
		// Restore active/queued/incomplete downloads after service creation
		new Handler(Looper.getMainLooper()).postDelayed(this::restoreDownloadsState, 1500);
		
		networkReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
					handleNetworkEvent();
				}
			}
		};

		ConnectivityManager initialConnectivity =
				(ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo initialNetwork = initialConnectivity != null
				? initialConnectivity.getActiveNetworkInfo() : null;
		if (initialNetwork != null && initialNetwork.isConnected()) {
			lastNetworkKey = networkKeyOf(initialConnectivity, initialNetwork);
		}
		registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
		registerPhysicalNetworkCallback(initialConnectivity);

		refreshDownloadsBlockedByNetwork();
		wifiOnlyPrefListener = (sp, key) -> {
			if (Constants.SETTINGS_WIFI_ONLY_DOWNLOADS_KEY.equals(key)) {
				new Handler(Looper.getMainLooper()).post(this::onWifiOnlyConditionChanged);
			} else if (Constants.SETTINGS_VPN_PORT_KEY.equals(key)) {
				redetectConnectivity("VPN forwarded port changed"); // re-listens on the new port and re-checks
			} else if (PIAPortForwardingManager.PREF_ENABLED.equals(key)
					|| PIAPortForwardingManager.PREF_PASSWORD.equals(key)) {
				PIAPortForwardingManager.getInstance(this).stopPortForwarding(); // start over with the new settings
				redetectConnectivity("PIA settings changed");
			}
		};
		getSharedPreferences(Constants.PREFS_NAME, 0)
				.registerOnSharedPreferenceChangeListener(wifiOnlyPrefListener);

		// Persist all-time DL/UL totals every 30 s (and on destroy).
		saveExecutor.scheduleWithFixedDelay(() -> {
			try {
				saveAllTimeTotals();
			} catch (Exception e) {
				Log.w("andcpp", "Could not save all-time totals", e);
			}
		}, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
	}

	/**
	 * Recomputes whether downloads must wait: true only when "Download only over Wi-Fi" is on
	 * and the active network is not Wi-Fi/Ethernet. With no network at all we don't block
	 * (nothing can download anyway, and the reconnect logic takes over).
	 */
	boolean refreshDownloadsBlockedByNetwork() {
		boolean blocked = false;
		if (Settings.getWifiOnlyDownloads(this)) {
			ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
			NetworkInfo active = cm != null ? cm.getActiveNetworkInfo() : null;
			if (active != null && active.isConnected()) {
				blocked = !isUnmeteredLink(cm, active);
			}
		}
		downloadsBlockedByNetwork = blocked;
		return blocked;
	}

	private static boolean isUnmeteredLink(ConnectivityManager cm, NetworkInfo active) {
		int type = active.getType();
		if (type == ConnectivityManager.TYPE_WIFI || type == ConnectivityManager.TYPE_ETHERNET) {
			return true;
		}
		// Behind a VPN (e.g. PIA) the active network may be the VPN itself: look at what it runs over.
		if (android.os.Build.VERSION.SDK_INT >= 23) {
			android.net.Network net = cm.getActiveNetwork();
			android.net.NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
			if (caps != null) {
				if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
						|| caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
					return true;
				}
				if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
					return false;
				}
			}
		}
		if (type == ConnectivityManager.TYPE_VPN && android.os.Build.VERSION.SDK_INT >= 21) {
			for (android.net.Network n : cm.getAllNetworks()) {
				NetworkInfo ni = cm.getNetworkInfo(n);
				if (ni != null && ni.isConnected()
						&& (ni.getType() == ConnectivityManager.TYPE_WIFI
						|| ni.getType() == ConnectivityManager.TYPE_ETHERNET)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Called on network changes and when the Wi-Fi only setting is toggled. */
	private void onWifiOnlyConditionChanged() {
		boolean wasBlocked = downloadsBlockedByNetwork;
		boolean blocked = refreshDownloadsBlockedByNetwork();
		if (wasBlocked && !blocked && initiated_downloads != null) {
			// Wi-Fi is back (or the setting was turned off): release Wi-Fi waits right away.
			synchronized (initiated_downloads) {
				for (DownloadObject obj : initiated_downloads) {
					if (WAIT_REASON_WIFI.equals(obj.waitReason)) {
						obj.waitReason = null;
						obj.nextRetryAt = 0L;
					}
				}
			}
			Log.i("andcpp", "Downloads allowed on current network; resuming Wi-Fi waits");
			retryPendingDownloads();
		}
		// When it becomes blocked, running downloads park themselves in the worker loop.
		// Uploads are stopped here: peers retry later and get $MaxedOut until Wi-Fi is back.
		if (!wasBlocked && blocked && uploadManager != null) {
			int n = uploadManager.stopAllUploads();
			Log.i("andcpp", "Wi-Fi only is on and we're on mobile data: stopped " + n + " upload connection(s)");
		}
	}

	/** May be null before the service has started listening. */
	/** ADC client-to-client transfers (connection setup, downloads; uploads go through UploadManager). */
	private final AdcTransfers adcTransfers = new AdcTransfers(this);

	AdcTransfers getAdcTransfers() {
		return adcTransfers;
	}

	/** Closes an ADC download's client-to-client connection (pause / cancel / stall); no-op for NMDC. */
	void closeAdcTransfer(DCDownloader.DownloadQueueEntity e) {
		final java.io.Closeable c = e != null ? e.adcConnection : null;
		if (c == null) return;
		new Thread(() -> {
			try {
				c.close();
			} catch (Exception ignored) {}
		}, "adc-cc-close").start();
	}

	public UploadManager getUploadManager() {
		return uploadManager;
	}

	/** True while "Wi-Fi only" is on and the active link is mobile data: no uploads are served. */
	public boolean isUploadBlockedByNetwork() {
		return downloadsBlockedByNetwork;
	}

	/**
	 * Identifies the network we're on, to tell when it changed. Type + SSID/APN alone wasn't enough: without
	 * location permission Android hides the Wi-Fi name, so every Wi-Fi looked the same ("1|") and a switch from
	 * one Wi-Fi to another never restarted transfers. Each physical connection (not the VPN) gets its own network
	 * id from Android, so the key also lists those ids and their IPv4 addresses.
	 */
	private static String networkKeyOf(ConnectivityManager cm, NetworkInfo info) {
		String extra = info.getExtraInfo(); // SSID for wifi, APN for mobile (may be null; SSID hidden without location)
		StringBuilder key = new StringBuilder().append(info.getType()).append('|').append(extra != null ? extra : "");
		if (cm != null && Build.VERSION.SDK_INT >= 23) {
			try {
				List<String> parts = new ArrayList<>();
				for (android.net.Network n : cm.getAllNetworks()) {
					android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
					if (caps == null || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
					if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
							&& !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
							&& !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
					StringBuilder p = new StringBuilder(n.toString()); // network id: new for every (re)connection
					android.net.LinkProperties lp = cm.getLinkProperties(n);
					if (lp != null) {
						for (android.net.LinkAddress a : lp.getLinkAddresses()) {
							if (a.getAddress() instanceof java.net.Inet4Address) { // IPv6 rotates; IPv4 is stable
								p.append('@').append(a.getAddress().getHostAddress());
							}
						}
					}
					parts.add(p.toString());
				}
				Collections.sort(parts);
				key.append('|').append(android.text.TextUtils.join(",", parts));
			} catch (Exception ignored) {
			}
		}
		return key.toString();
	}

	/** Reacts to a possible network change (connectivity broadcast or a physical network coming/going). */
	private void handleNetworkEvent() {
		onWifiOnlyConditionChanged();
		ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo activeNetwork = cm != null ? cm.getActiveNetworkInfo() : null;
		if (activeNetwork != null && activeNetwork.isConnected()) {
			String networkKey = networkKeyOf(cm, activeNetwork);
			boolean networkChanged = lastNetworkKey != null
					&& (networkLostSinceLastConnect || !networkKey.equals(lastNetworkKey));
			boolean sameAsBefore = networkKey.equals(lastNetworkKey);
			lastNetworkKey = networkKey;
			networkLostSinceLastConnect = false;
			if (sameAsBefore && !networkChanged) return; // nothing new (e.g. a duplicate callback)

			Log.d("andcpp", "Network connected (" + networkKey + "), triggering reconnection logic");
			triggerReconnections();

			// VPN switched on/off: incoming connections now take another path; re-check active/passive.
			boolean vpnNow = isVpnActive();
			if (vpnNow != lastVpnActive) {
				lastVpnActive = vpnNow;
				redetectConnectivity(vpnNow ? "VPN turned on" : "VPN turned off");
			}

			long now = System.currentTimeMillis();
			if (networkChanged && now - lastNetworkRestartAt > 5000) {
				lastNetworkRestartAt = now;
				Log.i("andcpp", "Network changed; restarting active downloads");
				closeTransfersOfOldNetwork();
				restartDownloadsAfterNetworkChange();
				redetectConnectivity("network changed"); // new network: active may (no longer) be possible
			}
		} else if (lastNetworkKey != null) {
			networkLostSinceLastConnect = true;
		}
	}

	/**
	 * After a network change, connections made on the old network are dead, but on a Wi-Fi to Wi-Fi switch
	 * (especially through a VPN) neither side notices for a long time: an upload stayed "open", holding the
	 * other client's slot so its download couldn't restart, and a hub kept showing "connected" with our old
	 * address, so the other client's new connection requests never reached us. Close both: the other client
	 * reconnects and resumes where it stopped, and the hubs reconnect from the new network.
	 */
	private void closeTransfersOfOldNetwork() {
		if (uploadManager != null) {
			int n = uploadManager.stopAllUploads();
			if (n > 0) Log.i("andcpp", "Network changed: closed " + n + " upload connection(s); peers will reconnect");
		}
		for (HubSession s : getActiveSessions()) {
			if (s.status == DCClientStatus.CONNECTED || s.status == DCClientStatus.CONNECTING) {
				Log.i("andcpp", "Network changed: reconnecting hub " + s.hubIp);
				reconnectHub(s.hubIp);
			}
		}
	}

	private ConnectivityManager.NetworkCallback physicalNetworkCallback;
	private final Handler networkEventHandler = new Handler(Looper.getMainLooper());
	private final Runnable networkEventRunnable = this::handleNetworkEvent;

	/**
	 * Also watches the physical networks themselves (not the VPN): with a VPN on, a Wi-Fi to Wi-Fi handover
	 * may not change the connectivity broadcast at all. Bursts of callbacks are merged (1.5 s).
	 */
	private void registerPhysicalNetworkCallback(ConnectivityManager cm) {
		if (cm == null || Build.VERSION.SDK_INT < 21) return;
		try {
			android.net.NetworkRequest request = new android.net.NetworkRequest.Builder()
					.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
					.build(); // NOT_VPN is in the default request: only real networks
			physicalNetworkCallback = new ConnectivityManager.NetworkCallback() {
				private void changed() {
					networkEventHandler.removeCallbacks(networkEventRunnable);
					networkEventHandler.postDelayed(networkEventRunnable, 1500);
				}

				@Override
				public void onAvailable(android.net.Network network) {
					changed();
				}

				@Override
				public void onLost(android.net.Network network) {
					changed();
				}

				@Override
				public void onLinkPropertiesChanged(android.net.Network network, android.net.LinkProperties lp) {
					changed();
				}
			};
			cm.registerNetworkCallback(request, physicalNetworkCallback);
		} catch (Exception e) {
			Log.w("andcpp", "Could not watch network changes: " + e.getMessage());
			physicalNetworkCallback = null;
		}
	}

	private void restartDownloadsAfterNetworkChange() {
		for (HubSession s : getActiveSessions()) {
			if (s.prefs != null) {
				s.prefs.set_self_ip(null);
			}
		}
		if (initiated_downloads == null) return;

		List<DownloadObject> restart = new ArrayList<>();

		synchronized (initiated_downloads) {
			for (DownloadObject obj : initiated_downloads) {
				if (obj.waitReason != null && !obj.isCancelled && !obj.isPaused) {
					obj.waitReason = null; // new network: retry waiting items now
					obj.peerWaitRetryCount = 0;
					obj.nextRetryAt = 0L;
				}
				if (obj.isCancelled || obj.isPaused || obj.is_finished()
						|| obj.download_status == null) {
					continue;
				}

				DCConstants.DownloadStatus status = obj.get_status();
				if (status != DCConstants.DownloadStatus.DOWNLOADING
						&& status != DCConstants.DownloadStatus.QUEUED
						&& status != DCConstants.DownloadStatus.INITIATED
						&& status != DCConstants.DownloadStatus.STARTED) {
					continue;
				}

				closeAdcTransfer(obj.download_status); // ADC download: no DCClient handler to stop
				HubSession session = getSession(obj.hubIp);
				if (session != null && session.client != null) {
					try {
						session.client.stopDownloadHandler(obj.download_status);
					} catch (Exception e) {
						Log.w("andcpp",
								"Could not stop download before network recovery: " + obj.file_name, e);
					}
				}

				File partialFile = new File(obj.local_file);
				obj.pendingStartOffset =
						partialFile.exists() ? partialFile.length() : 0;
				obj.download_status.status = DCConstants.DownloadStatus.INTERUPTED;
				restart.add(obj);
			}
		}

		saveDownloadsState();

		// Give the worker time to leave its current download-monitor loop.
		new Handler(Looper.getMainLooper()).postDelayed(() -> {
			for (DownloadObject obj : restart) {
				if (obj.isCancelled || obj.isPaused || (obj.download_status != null && obj.download_status.status == DCConstants.DownloadStatus.COMPLETED)) continue;

				DCConstants.DownloadStatus status = obj.get_status();
				if (status == DCConstants.DownloadStatus.INTERUPTED
						|| status == DCConstants.DownloadStatus.FAILED
						|| status == DCConstants.DownloadStatus.QUEUED) {
					obj.resume_download();
				}
			}
			retryPendingDownloads();
		}, 3000);
	}

	private void triggerReconnections() {
		// (PIA port forwarding is (re)started by connectivity detection, not on every network event:
		// restarting it each time could get a new port.)
		for (Map.Entry<String, HubSession> entry : sessions.entrySet()) {
			HubSession session = entry.getValue();
			if (session.status == DCClientStatus.RECONNECTING || session.status == DCClientStatus.DISCONNECTED || session.status == DCClientStatus.INVALIDIP) {
				session.reconnectAttempts = 0; // Reset backoff
				connectToHub(entry.getKey(), session);
			}
		}
		retryPendingDownloads();
	}

	/** PIA assigned/kept a forwarded port: re-run detection, which listens on it and goes active. */
	private void handlePIAPortBound(String portStr) {
		ConnectivityDetector.Result r = connectivityResult;
		String expected = "PiA port forwarding, port " + portStr; // must match the reason text set in detection
		if (r != null && r.active && expected.equals(r.reason)
				&& uploadManager != null && String.valueOf(uploadManager.getPort()).equals(portStr)) {
			return; // keep-alive of the port we already use
		}
		Log.i("andcpp", "PIA port bound: " + portStr);
		redetectConnectivity("PIA port " + portStr + " bound");
	}

	private void manageWakeLock() {
		boolean needsWakeLock = false;
		for (HubSession session : sessions.values()) {
			if (session.status == DCClientStatus.CONNECTED || session.status == DCClientStatus.CONNECTING || session.status == DCClientStatus.RECONNECTING) {
				needsWakeLock = true;
				break;
			}
		}
		
		if (!needsWakeLock) {
			synchronized (initiated_downloads) {
				for (DownloadObject obj : initiated_downloads) {
					if (!obj.is_finished() && !obj.isPaused) {
						needsWakeLock = true;
						break;
					}
				}
			}
		}
		
		if (needsWakeLock) {
			if (wakeLock != null && !wakeLock.isHeld()) {
				// Avoid 30-minute safety timeout to prevent disconnects while sleeping
				wakeLock.acquire();
				Log.d("andcpp", "Acquired PARTIAL_WAKE_LOCK");
			}
			if (wifiLock != null && !wifiLock.isHeld()) {
				wifiLock.acquire();
				Log.d("andcpp", "Acquired WIFI_LOCK");
			}
		} else {
			if (wakeLock != null && wakeLock.isHeld()) {
				wakeLock.release();
				Log.d("andcpp", "Released PARTIAL_WAKE_LOCK");
			}
			if (wifiLock != null && wifiLock.isHeld()) {
				wifiLock.release();
				Log.d("andcpp", "Released WIFI_LOCK");
			}
		}
	}

	/**
	 * Build n: overnight test showed the hub link can stay "alive" (hub traffic keeps arriving) while our
	 * $ConnectToMe/$RevConnectToMe stop getting any answer from the peer, for hours; every fresh hub login fixed it
	 * at once. So: after UNANSWERED_CONNECT_LIMIT unanswered connect requests in a row (to a peer that is in the
	 * nick list) we log in to the hub again, at most once per FORCED_RELOGIN_MIN_INTERVAL_MILLIS.
	 * Pending requests survive the reconnect (build m) and parked downloads restart right after it (build g, M).
	 */
	private static final int UNANSWERED_CONNECT_LIMIT = 3;
	private static final long FORCED_RELOGIN_MIN_INTERVAL_MILLIS = 15 * 60000L;
	private volatile int unansweredConnectRequests = 0;
	private volatile long lastForcedReloginAt = 0L;

	private void noteUnansweredConnectRequest(String hubKey) {
		int n = ++unansweredConnectRequests;
		long now = System.currentTimeMillis();
		Log.w("andcpp", "[DLTRACE] unanswered connect requests in a row: " + n);
		if (n < UNANSWERED_CONNECT_LIMIT || now - lastForcedReloginAt < FORCED_RELOGIN_MIN_INTERVAL_MILLIS) return;
		HubSession session = getSession(hubKey);
		if (session == null || session.status != DCClientStatus.CONNECTED) return;
		String key = null;
		for (Map.Entry<String, HubSession> e : sessions.entrySet()) {
			if (e.getValue() == session) { key = e.getKey(); break; }
		}
		if (key == null) return;
		lastForcedReloginAt = now;
		unansweredConnectRequests = 0;
		Log.w("andcpp", "[DLTRACE] " + n + " connect requests unanswered; logging in to hub " + session.hubIp + " again");
		final String k = key;
		new Handler(Looper.getMainLooper()).post(() ->
				handleConnectionFailure(k, session, "peers stopped answering connect requests"));
	}

	/**
	 * Called after every file list rebuild. The share size is sent to the hub in $MyINFO at login, which can be
	 * before the share was scanned (or before storage access was granted); re-send it whenever it changes so the
	 * hub's user list shows our real share.
	 */
	public void onShareSizeChanged(long size) {
		for (HubSession s : sessions.values()) {
			if (s.prefs == null || s.myuser == null) continue;
			if (s.prefs.getShare_size() == size && s.myuser.share_size == size) continue;
			s.prefs.setShare_size(size);
			s.myuser.share_size = size;
			if (s.status == DCClientStatus.CONNECTED && s.hub != null) { // NMDC and ADC
				try {
					sendMyInfo(s);
					Log.i("andcpp", "Share size now " + size + " bytes; MyINFO re-sent to " + s.hubIp);
				} catch (Exception e) {
					Log.w("andcpp", "Could not re-send MyINFO to " + s.hubIp, e);
				}
			}
		}
	}

	/**
	 * User-requested reconnect (hub list "Reconnect"): drops the hub link and logs in again through the normal
	 * reconnect path, so pending download requests are kept (build m) and parked downloads restart afterwards.
	 */
	public boolean reconnectHub(String hubIp) {
		HubSession session = getSession(hubIp);
		if (session == null) return false;
		String key = null;
		for (Map.Entry<String, HubSession> e : sessions.entrySet()) {
			if (e.getValue() == session) { key = e.getKey(); break; }
		}
		if (key == null) return false;
		Log.i("andcpp", "[DLTRACE] user requested reconnect of hub " + session.hubIp);
		session.reconnectAttempts = 0; // first retry after the shortest delay
		unansweredConnectRequests = 0;
		final String k = key;
		new Handler(Looper.getMainLooper()).post(() -> handleConnectionFailure(k, session, "reconnect requested by user"));
		return true;
	}

	public static final String ACTION_TICK = "com.phinmadvader.andcpp.ACTION_TICK";
	private static final long ALARM_INTERVAL_MILLIS = 60000L;
	/**
	 * Dead-link detection that is friendly to hubs: a quiet hub (e.g. an empty test hub) may send nothing for a long
	 * time, and hubs flag repeated $GetNickList requests as "nicklist spam". So:
	 *  - every tick we only send the standard empty keep-alive "|";
	 *  - after HUB_PROBE_AFTER_MILLIS of total silence we send ONE $GetNickList (which every hub answers),
	 *    at most once per HUB_PROBE_MIN_INTERVAL_MILLIS;
	 *  - the link counts as dead only if nothing at all arrives within HUB_PROBE_REPLY_MILLIS of that probe.
	 * A broken socket is caught sooner anyway: the failed write ends the connection and triggers a reconnect.
	 */
	private static final long HUB_PROBE_AFTER_MILLIS = 5 * 60000L;
	private static final long HUB_PROBE_MIN_INTERVAL_MILLIS = 10 * 60000L;
	private static final long HUB_PROBE_REPLY_MILLIS = 90000L;
	/** Per hub: when we last sent the liveness probe (0 = none outstanding). */
	private final Map<String, Long> hubProbeSentAt = new ConcurrentHashMap<>();
	private final Object tickLock = new Object();
	private volatile long lastTickAt = 0L;
	private final ExecutorService tickExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "DCTick");
		t.setDaemon(true);
		return t;
	});

	/**
	 * Periodic work, driven by both the Timer (30s) and an idle-capable alarm (60s): keep the hub link alive,
	 * detect a dead hub link (a rare liveness check that goes unanswered, see HUB_PROBE_*), drop dead uploads
	 * and restart queued downloads.
	 */
	private void runKeepAliveTick(String source) {
		synchronized (tickLock) {
			long now = System.currentTimeMillis();
			if (now - lastTickAt < 10000L) return; // timer and alarm fired together
			lastTickAt = now;
			try {
				for (Map.Entry<String, HubSession> entry : sessions.entrySet()) {
					HubSession session = entry.getValue();
					HubConnection hub = session.hub;
					if (session.status != DCClientStatus.CONNECTED || hub == null) continue;
					String key = entry.getKey();
					long silent = hub.millisSinceLastReceived();
					Long probeAt = hubProbeSentAt.get(key);
					if (probeAt != null && probeAt > 0) {
						long sinceProbe = now - probeAt;
						if (silent < sinceProbe) {
							hubProbeSentAt.put(key, -probeAt); // the hub answered: link is alive (keep time for rate limit)
						} else if (sinceProbe > HUB_PROBE_REPLY_MILLIS) {
							Log.w("andcpp", "[DLTRACE] tick(" + source + "): hub " + session.hubIp + " silent for " + (silent / 1000)
									+ "s and did not answer a probe within " + (HUB_PROBE_REPLY_MILLIS / 1000) + "s; link is dead, reconnecting");
							hubProbeSentAt.remove(key);
							handleConnectionFailure(key, session, "hub did not answer for " + (silent / 1000) + "s");
							continue;
						}
					}
					try {
						long lastProbe = probeAt == null ? 0 : Math.abs(probeAt);
						boolean probeOutstanding = probeAt != null && probeAt > 0;
						if (!probeOutstanding && silent > HUB_PROBE_AFTER_MILLIS
								&& now - lastProbe > HUB_PROBE_MIN_INTERVAL_MILLIS) {
							// One liveness check the hub must answer (NMDC: $GetNickList).
							Log.d("andcpp", "[DLTRACE] tick(" + source + "): hub " + session.hubIp + " quiet for "
									+ (silent / 1000) + "s; sending one liveness check");
							hubProbeSentAt.put(key, now);
							hub.sendLivenessProbe();
						} else {
							hub.sendKeepAlive(); // NMDC: a lone "|"
						}
					} catch (Exception e) {
						Log.e("andcpp", "Failed to send keep-alive to " + session.hubIp);
					}
				}
				if (uploadManager != null) {
					uploadManager.cleanupZombieConnections();
				}
				retryPendingDownloads();
			} catch (Exception e) {
				Log.e("andcpp", "Exception in keep-alive tick (" + source + ")", e);
			}
		}
	}

	private PendingIntent tickPendingIntent() {
		Intent i = new Intent(this, DCPPService.class);
		i.setAction(ACTION_TICK);
		int flags = PendingIntent.FLAG_UPDATE_CURRENT;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			flags |= PendingIntent.FLAG_IMMUTABLE;
		}
		return PendingIntent.getService(this, 7001, i, flags);
	}

	/** One-shot alarm that is allowed to fire while the phone is idle; each tick schedules the next one. */
	private void scheduleNextTick() {
		try {
			AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
			if (am == null) return;
			long at = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MILLIS;
			PendingIntent pi = tickPendingIntent();
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
				am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
			} else {
				am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
			}
		} catch (Exception e) {
			Log.w("andcpp", "Could not schedule keep-alive alarm", e);
		}
	}

	private void cancelTick() {
		try {
			AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
			if (am != null) am.cancel(tickPendingIntent());
		} catch (Exception ignored) {}
	}

	/** After the hub link is back, parked downloads should not sit out their backoff. */
	private void clearBackoffsAfterReconnect() {
		if (initiated_downloads == null) return;
		synchronized (initiated_downloads) {
			for (DownloadObject obj : initiated_downloads) {
				if (obj.waitReason != null && !obj.isCancelled && !obj.isPaused) {
					obj.waitReason = null;
					obj.peerWaitRetryCount = 0;
					obj.nextRetryAt = 0L;
				}
			}
		}
	}

	private void startKeepAliveTimer() {
		if (keepAliveTimer != null) keepAliveTimer.cancel();
		// Use Handler with Alarm/Repeating check or Timer to ping more frequently during sleep (e.g., every 30 seconds)
		keepAliveTimer = new Timer();
		keepAliveTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				runKeepAliveTick("timer");
			}
		}, 30000, 30000); // plain timer; the alarm below keeps the tick going while the phone is idle
		scheduleNextTick();
	}

	public class LocalBinder extends Binder {
		DCPPService getService() {
			return DCPPService.this;
		}
	}

	private final IBinder mBinder = new LocalBinder();

	public IBinder onBind(Intent intent) {
		Log.d("andcpp", "Binding to service");
		return mBinder;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent == null) return START_STICKY;
		
		if (ACTION_STOP_SERVICE.equals(intent.getAction())) {
			shutdown();
			stopSelf();
			return START_NOT_STICKY;
		}

		if (ACTION_TICK.equals(intent.getAction())) {
			scheduleNextTick();
			tickExecutor.execute(() -> runKeepAliveTick("alarm"));
			return START_STICKY;
		}
		
		Bundle data = intent.getExtras();
		if (data == null) return START_STICKY;

		String nick = data.getString("nick");
		String ip = data.getString("ip");
		String portStr = data.getString("port");

		if (ip == null || nick == null) return START_STICKY;

		int port = 411;
		try {
			if (portStr != null && !portStr.isEmpty())
				port = Integer.parseInt(portStr);
		} catch (NumberFormatException e) {
			exceptionCaught(ip, "Invalid Port number");
			return START_STICKY;
		}

		// "scheme" (protocol) and "kp" extras: NMDC when missing, as from older callers.
		HubAddress.Protocol protocol = HubAddress.Protocol.NMDC;
		try {
			String scheme = data.getString("scheme");
			if (scheme != null) protocol = HubAddress.Protocol.valueOf(scheme);
		} catch (IllegalArgumentException e) {
			Log.w("andcpp", "Unknown hub protocol " + data.getString("scheme") + "; using NMDC");
		}
		if (!HubAddress.of(protocol, ip, port).isSupported()) {
			exceptionCaught(ip, protocol.label + " hubs are not supported yet");
			return START_STICKY;
		}
		String hubKey = HubAddress.of(protocol, ip, port).sessionKey(); // NMDC: ip:port, as always
		replaceTwinSessions(hubKey, protocol, ip, port);

		// Establish foreground status to satisfy Android 8.0+ requirements
		ensureForeground(ip);

		if (!sessions.containsKey(hubKey)) {
			final HubSession session = new HubSession(ip, port);
			session.protocol = protocol;
			session.expectedKeyprint = data.getString("kp");
			sessions.put(hubKey, session);
			session.status = DCClientStatus.CONNECTING;
			
			// Load preferences
			SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, 0);
			// Auto-detected: active only if other users can reach us (passive until detection has finished).
			boolean activeMode = isActiveNow();
			if (connectivityResult == null) {
				redetectConnectivity("first hub connection");
			}
			int uploadPort = desiredListenPort(); // VPN forwarded port while a VPN is on

			if (uploadManager == null) {
				uploadManager = new UploadManager(DCPPService.this);
				installUploadCallback();
			}

			PIAPortForwardingManager piaManager = PIAPortForwardingManager.getInstance(DCPPService.this);
			if (piaManager.isEnabled() && piaManager.getCurrentPort() != null) {
				try {
					uploadPort = Integer.parseInt(piaManager.getCurrentPort());
					Log.d("andcpp", "MyINFO using PIA port: " + uploadPort);
				} catch (NumberFormatException e) { }
			}

			uploadManager.startListening(uploadPort);
			
			long sharedSize = (uploadManager != null) ? uploadManager.getSharedSize() : (17000L * 1024 * 1024);
			
			String savedNick = session.hubPref(prefs, "favorite_nicks_");
			if (!savedNick.isEmpty()) {
				nick = savedNick;
			}

			session.prefs = new DCPreferences(nick, sharedSize, ip, activeMode, uploadPort);
			String savedPass = session.hubPref(prefs, "favorite_passwords_");
			if (!savedPass.isEmpty()) {
				session.prefs.password = savedPass;
			}
			session.myuser = new DCUser();
			session.myuser.nick = nick;
			session.myuser.active = activeMode;
			session.myuser.share_size = sharedSize;

			connectToHub(hubKey, session);
		} else {
			HubSession session = sessions.get(hubKey);
			Log.d("andcpp", "Session already exists for " + hubKey + ", status: " + (session != null ? session.status : "null"));
			if (session != null) {
				session.reconnectAttempts = 0;
				// The address used this time decides the keyprint check (?kp= given, or trust on first use).
				session.expectedKeyprint = data.getString("kp");
				connectToHub(hubKey, session);
			}
			mySendBroadcast(ip, false, true);
		}

		return START_STICKY;
	}

	/**
	 * The same hub (host:port) reached over the other protocol, e.g. nmdcs://hub:411 while hub:411 runs plain:
	 * the hub allows our nick once, so the two logins kick each other forever (and the hub bot's welcome PM
	 * arrives on every login). Connecting one replaces the other: its session is closed, its saved entry
	 * removed, and its saved nick/password carried over when the new address has none.
	 */
	private void replaceTwinSessions(String hubKey, HubAddress.Protocol protocol, String ip, int port) {
		HubAddress target = HubAddress.of(protocol, ip, port);
		SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, 0);
		for (Map.Entry<String, HubSession> e : new ArrayList<>(sessions.entrySet())) {
			HubSession twin = e.getValue();
			if (e.getKey().equals(hubKey) || twin.protocol == protocol || twin.hubPort != port
					|| !twin.hubIp.equalsIgnoreCase(ip)) continue;
			Log.i("andcpp", "Replacing " + twin.address().id() + " with " + target.id() + " (same hub; it allows our nick once)");
			SharedPreferences.Editor ed = prefs.edit();
			for (String prefix : new String[] {"favorite_nicks_", "favorite_passwords_"}) {
				String v = twin.hubPref(prefs, prefix);
				if (!v.isEmpty() && prefs.getString(prefix + target.prefKey(), "").isEmpty()) {
					for (String k : target.prefKeysToWrite()) ed.putString(prefix + k, v);
				}
			}
			ed.apply();
			HubListFragment.removeSavedHub(this, twin.address().id());
			shutdownSession(e.getKey());
		}
	}

	/**
	 * TLS to a secure hub (NMDCS / ADCS), checked against the keyprint in its address (?kp=) or else the one
	 * remembered from the first connection (trust on first use). A mismatch sets session.keyprintProblem:
	 * the connection is refused and not retried until the user decides (see trustNewKeyprint).
	 */
	private SSLSocket connectSecure(HubSession session, String tag) throws Exception {
		HubAddress addr = session.address();
		String fromAddress = session.expectedKeyprint;
		String remembered = KeyprintStore.get(this, addr);
		String expected = fromAddress != null ? fromAddress : remembered;
		SelfSignedCertificate ours = ClientCertificateStore.get(DCPPService.this);
		try {
			SSLSocket tls = TlsSocketFactory.connect(session.hubIp, session.hubPort, 15000,
					ours != null ? ours.keyManagers() : null, expected);
			session.hubKeyprint = TlsSocketFactory.peerKeyprint(tls);
			String how;
			if (fromAddress != null) {
				how = "matches the address";
				if (!Keyprint.matches(fromAddress, remembered)) KeyprintStore.put(this, addr, session.hubKeyprint);
			} else if (remembered == null) {
				how = "first connection: remembered (trust on first use)";
				KeyprintStore.put(this, addr, session.hubKeyprint);
			} else {
				how = "matches the remembered keyprint";
			}
			Log.i("andcpp", tag + " " + session.hubIp + ":" + session.hubPort + " TLS up (" + TlsSocketFactory.describe(tls)
					+ "), hub keyprint " + session.hubKeyprint + " (" + how + ")");
			return tls;
		} catch (TlsSocketFactory.KeyprintMismatchException e) {
			session.hubKeyprint = e.actual;
			if (fromAddress != null) {
				session.pendingNewKeyprint = null; // the address is authoritative: nothing to "trust"
				session.keyprintProblem = "The hub's certificate does not match the keyprint in its address. "
						+ "Not connecting: this may not be the real hub.\nExpected: " + fromAddress + "\nHub sent: " + e.actual;
			} else {
				session.pendingNewKeyprint = e.actual;
				session.keyprintProblem = "The hub's keyprint has changed since you last connected. Not connecting "
						+ "until you trust the new one.\nRemembered: " + remembered + "\nNow: " + e.actual;
			}
			Log.w("andcpp", tag + " " + session.hubIp + ":" + session.hubPort + ": keyprint mismatch, expected "
					+ e.expected + ", got " + e.actual);
			throw new java.io.IOException(fromAddress != null ? "Keyprint does not match the hub address"
					: "Hub keyprint changed", e);
		}
	}

	/** The user trusts the hub's new keyprint (after a "keyprint changed" warning): remember it and reconnect. */
	public boolean trustNewKeyprint(String hubKey) {
		final HubSession session = sessions.get(hubKey);
		if (session == null || session.pendingNewKeyprint == null) return false;
		KeyprintStore.put(this, session.address(), session.pendingNewKeyprint);
		Log.i("andcpp", "[TLS] " + session.address().toUrl() + ": user trusts new keyprint " + session.pendingNewKeyprint);
		session.keyprintProblem = null;
		session.pendingNewKeyprint = null;
		session.reconnectAttempts = 0;
		connectToHub(hubKey, session);
		return true;
	}

	/** Tells the UI a secure hub was refused over its keyprint (MainActivity shows Trust new / Cancel). */
	private void sendKeyprintWarning(String hubKey, HubSession session) {
		Intent i = new Intent();
		i.setAction("ACTION_DO_SOMETHING");
		i.addCategory(Intent.CATEGORY_DEFAULT);
		i.putExtra("hubIp", session.hubIp);
		i.putExtra("keyprintProblem", session.keyprintProblem);
		i.putExtra("keyprintHubKey", hubKey);
		i.putExtra("keyprintCanTrust", session.pendingNewKeyprint != null);
		i.putExtra("keyprintHubName", session.hubName != null ? session.hubName : session.address().toUrl());
		sendBroadcast(i);
	}

	/**
	 * Answers another user's ADC search from our share: at most 10 results, through the hub (DRES; we don't
	 * listen on UDP). Two passive users can't connect, so a passive searcher gets nothing while we're passive.
	 */
	private void answerAdcSearch(AdcHubConnection hub, DCUser from, com.phinvader.libjdcpp.adc.AdcSearch s) {
		UploadManager um = uploadManager;
		if (um == null || from == null || from.sid == null) return;
		if (!from.active && !adcMyInfo.active()) return;
		List<UploadManager.SearchHit> hits = um.searchShare(false, false, 0, s.nmdcType(), s.nmdcPattern(), 30);
		List<SearchResult> out = new ArrayList<>();
		int free = um.getFreeSlots(), max = um.getMaxSlots();
		for (UploadManager.SearchHit h : hits) {
			if (!s.accepts(h.path, h.size, h.dir)) continue;
			out.add(new SearchResult(h.path, h.size, h.tth, h.dir, free, max));
			if (out.size() >= 10) break;
		}
		if (out.isEmpty()) return;
		Log.d("andcpp", "[ADC] search from " + from.nick + " for '" + s.nmdcPattern() + "': " + out.size() + " result(s)");
		hub.sendResults(from.sid, s.token, out);
	}

	/** Remembers a hub's name for the hub list (also while offline) and refreshes the UI. */
	private void rememberHubName(HubSession session, String name) {
		if (name == null || name.trim().isEmpty()) return;
		session.hubName = name;
		try {
			SharedPreferences p = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
			if (session.protocol == HubAddress.Protocol.NMDC) {
				p.edit().putString("hub_name_" + session.hubIp, name).apply();
				if (session.hubPort != 411) {
					p.edit().putString("hub_name_" + session.hubIp + ":" + session.hubPort, name).apply();
				}
			} else { // nmdcs:// / adc:// / adcs://: never overwrite a plain NMDC hub's name on the same host
				p.edit().putString("hub_name_" + session.address().prefKey(), name).apply();
			}
		} catch (Exception ignored) {}
		mySendBroadcast(session.hubIp, false, true);
	}

	/** What we tell ADC hubs about ourselves besides nick and share size. */
	private final AdcHubConnection.MyInfo adcMyInfo = new AdcHubConnection.MyInfo() {
		@Override public int slots() { return uploadManager != null ? uploadManager.getMaxSlots() : 1; }
		@Override public int freeSlots() { return uploadManager != null ? uploadManager.getFreeSlots() : 1; }
		@Override public long sharedFiles() { return uploadManager != null ? uploadManager.getSharedFileCount() : 0; }
		@Override public boolean active() {
			return isActiveNow() && uploadManager != null && uploadManager.isListening();
		}
		@Override public java.util.Collection<String> sharedTths() {
			return uploadManager != null ? uploadManager.getSharedTths() : new ArrayList<String>();
		}
		@Override public int[] hubCounts() {
			int normal = 0, registered = 0;
			for (HubSession s : sessions.values()) {
				if (s.status != DCClientStatus.CONNECTED && s.status != DCClientStatus.CONNECTING) continue;
				if (s.prefs != null && s.prefs.password != null && !s.prefs.password.isEmpty()) registered++;
				else normal++;
			}
			return new int[] {Math.max(normal, registered == 0 ? 1 : 0), registered, 0};
		}
	};

	/**
	 * ADC / ADCS login. The connection reports users, chat and PMs through {@link AdcEvents}, which hands them
	 * to the same handlers as NMDC, so the user list, chat and PM screens work unchanged.
	 * Not on ADC yet: search (phase 9), transfers (phase 8), hub menus (phase 9).
	 */
	private void connectAdc(String hubKey, HubSession session) throws Exception {
		session.client = null; // no NMDC client on ADC hubs
		AdcEvents events = new AdcEvents(hubKey, session);
		AdcHubConnection adc = new AdcHubConnection(session.address(), AdcIdentityStore.get(this), adcMyInfo, events);
		events.connection = adc;
		session.hub = adc;
		session.userCommands.clear();

		java.net.Socket socket;
		if (session.protocol.secure) {
			socket = connectSecure(session, "[ADCS]");
		} else {
			socket = new java.net.Socket();
			socket.connect(new java.net.InetSocketAddress(session.hubIp, session.hubPort), 15000);
		}
		adc.connect(socket, session.prefs.getNick(), session.prefs.password, session.prefs.getShare_size(), 30000);
		if (adc.hubName() != null) rememberHubName(session, adc.hubName());
		session.hubSoftware = adc.hubSoftware();
		Log.i("andcpp", "[ADC] " + session.address().toUrl() + ": logged in, " + adc.users().size() + " users"
				+ (adc.hubSoftware() != null ? ", hub software " + adc.hubSoftware() : ""));
	}

	/** Turns ADC hub events into the DCMessages the NMDC handlers (and so the UI) already understand. */
	private final class AdcEvents implements HubEventListener {
		private final String hubKey;
		private final HubSession session;
		volatile AdcHubConnection connection;

		AdcEvents(String hubKey, HubSession session) {
			this.hubKey = hubKey;
			this.session = session;
		}

		@Override
		public void onUserUpdated(DCUser user) {
			DCMessage m = new DCMessage();
			m.command = "MyINFO";
			m.myinfo = user;
			new MyUserHandler(hubKey).onCommand(m);
		}

		@Override
		public void onUserQuit(DCUser user) {
			DCMessage m = new DCMessage();
			m.command = "Quit";
			m.quit_s = user.nick;
			new MyUserHandler(hubKey).onCommand(m);
		}

		@Override
		public void onMainChat(String line) {
			DCMessage m = new DCMessage();
			m.command = "BoardMessage";
			m.msg_s = line;
			new MyBoardMessageHandler(hubKey).onCommand(m);
		}

		@Override
		public void onPrivateMessage(String conversationNick, String fromNick, String text) {
			DCMessage m = new DCMessage();
			m.command = "To:";
			m.pm_from = conversationNick;
			m.pm_text = fromNick.equals(conversationNick) ? text : "<" + fromNick + "> " + text; // chat rooms
			m.to_user = session.myuser != null ? session.myuser.nick : null;
			new MyPrivateMessageHandler(hubKey).onCommand(m);
		}

		@Override
		public void onHubInfo(String name, String topic) {
			rememberHubName(session, name);
		}

		@Override
		public void onStatus(String message) {
			Log.i("andcpp", "[ADC] " + session.hubIp + ": " + message);
			onMainChat("*** " + message);
		}

		@Override
		public void onSearch(DCUser from, com.phinvader.libjdcpp.adc.AdcSearch search) {
			final AdcHubConnection c = connection;
			if (c == null) return;
			searchReplyExecutor.execute(() -> { // bounded, off the hub reader (same as NMDC searches)
				try {
					answerAdcSearch(c, from, search);
				} catch (Exception e) {
					Log.e("andcpp", "Failed to answer ADC search", e);
				}
			});
		}

		@Override
		public void onSearchResult(DCUser from, String fn, long size, int freeSlots, String tth, String token) {
			// As an NMDC $SR, so the Search tab and downloads handle it unchanged.
			DCMessage m = new DCMessage();
			m.command = "SR";
			m.is_dir_result = fn.endsWith("/");
			m.file_path = com.phinvader.libjdcpp.adc.AdcSearch.nmdcPath(fn);
			m.file_size = m.is_dir_result ? 0 : size;
			m.file_tth = tth;
			m.file_signature = tth != null ? "TTH:" + tth : null;
			m.hisinfo = new DCUser();
			m.hisinfo.nick = from.nick;
			new MySearchHandler(hubKey).onCommand(m);
		}

		@Override
		public void onUserCommand(String name, String command, int context, boolean remove, boolean separator) {
			String title = name.replace('/', '\\'); // our menus use '\' between submenus, as NMDC
			for (UserCommand uc : session.userCommands) {
				if (title.equals(uc.title) && (uc.context & context) != 0) session.userCommands.remove(uc);
			}
			if (remove) return;
			if (separator) {
				session.userCommands.add(new UserCommand(UserCommand.TYPE_SEPARATOR, context, title, null));
			} else if (command != null && !command.isEmpty()) {
				session.userCommands.add(new UserCommand(1, context, title, command));
				Log.d("andcpp", "Hub " + session.hubIp + " ADC user command: " + title + " (context " + context + ")");
			}
		}

		@Override
		public void onTransferRefused(String message) {
			AdcHubConnection c = connection;
			if (c != null) adcTransfers.refusedByHub(c, message);
		}

		@Override
		public void onConnectRequest(DCUser peer, String protocol, int port, String token, boolean reverse) {
			AdcHubConnection c = connection;
			if (c != null) adcTransfers.onConnectRequest(session, c, peer, protocol, port, token, reverse);
		}

		@Override
		public void onDisconnected(String reason, String redirect) {
			Log.i("andcpp", "[ADC] " + session.address().toUrl() + " disconnected: " + reason
					+ (redirect != null ? " (redirect to " + redirect + ", not followed automatically yet)" : ""));
			// Only for the connection this session still uses (not one replaced by a reconnect).
			if (session.hub == connection && session.status != DCClientStatus.DISCONNECTED
					&& sessions.get(hubKey) == session) {
				handleConnectionFailure(hubKey, session, reason);
			}
		}
	}

	private void connectToHub(final String hubKey, final HubSession session) {
		session.status = DCClientStatus.CONNECTING;
		session.keyprintProblem = null; // a new attempt (e.g. the user pressed Connect): check again
		new Thread(() -> {
			try {
				if (session.client != null) {
					if (session.client.downloadHandler != null) {
						session.keptDownloads = session.client.downloadHandler; // build m
					}
					session.client.close();
				} else if (session.hub != null) {
					session.hub.close(); // an ADC link (no DCClient)
				}
				if (session.prefs != null) {
					SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, 0);
					String savedPass = session.hubPref(prefs, "favorite_passwords_");
					if (!savedPass.isEmpty()) {
						session.prefs.password = savedPass;
					}
					String savedNick = session.hubPref(prefs, "favorite_nicks_");
					if (!savedNick.isEmpty()) {
						session.prefs.nick = savedNick;
						if (session.myuser != null) {
							session.myuser.nick = savedNick;
						}
					}
				}
				if (session.protocol.isAdc()) {
					connectAdc(hubKey, session); // ADC / ADCS: its own login and events, no DCClient
				} else {
				session.client = new DCClient();
				session.hub = new NmdcHubConnection(session.address(), session.client);
				session.client.adoptDownloadHandler(session.keptDownloads); // build m: keep pending download requests
				if (session.protocol.secure) {
					// NMDCS: TLS first (keyprint checked), then the same NMDC login over it.
					session.client.connect(connectSecure(session, "[NMDCS]"), session.prefs);
				} else {
					session.client.connect(session.hubIp, session.hubPort, session.prefs);
				}
				
				session.client.bootstrap(session.myuser);
				session.client.setCustomUserChangeHandler(new MyUserHandler(hubKey));
				session.client.setCustomBoardMessageHandler(new MyBoardMessageHandler(hubKey));
				session.client.setCustomSearchHandler(new MySearchHandler(hubKey));
				session.client.setCustomRawHandler("HubName", dcMessage -> {
					if (dcMessage.hubname_s != null) rememberHubName(session, dcMessage.hubname_s);
				});
				session.client.setCustomRawHandler("Search", new MyIncomingSearchHandler(hubKey));
				// Hub command menus: start empty on every (re)connect; the hub sends them after login.
				session.userCommands.clear();
				session.client.setCustomRawHandler("UserCommand", dcMessage -> handleUserCommand(session, dcMessage));
				session.client.setCustomRawHandler("HubQuit", new MyHubQuitHandler(hubKey));
				session.client.setCustomRawHandler("RevConnectToMe", new MyRevConnectHandler(hubKey));
				session.client.setCustomRawHandler("UserIP", new MyUserIPHandler(hubKey));
				session.client.setCustomRawHandler("MyINFO", new MyUserInfoHandler(hubKey));
				session.client.setCustomRawHandler("To:", new MyPrivateMessageHandler(hubKey));
				session.client.InitiateDefaultRouting();
				} // NMDC / NMDCS

				session.status = DCClientStatus.CONNECTED;
				session.reconnectAttempts = 0; // Reset on success
				// A hub we connected to stays in the saved hubs list (until "Delete Hub"), so it survives restarts.
				HubListFragment.saveHub(DCPPService.this, session.address().id()); // NMDC: host[:port] as before
				ensureForeground(session.hubIp);
				mySendBroadcast(session.hubIp, false, true);
				manageWakeLock();

				clearBackoffsAfterReconnect();
				retryPendingDownloads();

				// Diagnostic: how many users the hub has sent us.
				final HubConnection hubAtLogin = session.hub;
				new Handler(Looper.getMainLooper()).postDelayed(() -> {
					if (session.hub == hubAtLogin && hubAtLogin != null) {
						Log.i("andcpp", "[USERLIST] " + hubKey + ": " + hubAtLogin.users().size()
								+ " users 15 s after login");
					}
				}, 15000);
			} catch (Exception e) {
				Log.e("andcpp", "Connection failed for " + session.hubIp + ":" + session.hubPort, e);
				handleConnectionFailure(hubKey, session, e.getMessage() != null ? e.getMessage() : e.toString());
			}
		}).start();
	}
	
	private void handleConnectionFailure(final String hubKey, final HubSession session, String message) {
		if (session.client != null) {
			if (session.client.downloadHandler != null) {
				session.keptDownloads = session.client.downloadHandler; // build m
			}
			try {
				session.client.close();
			} catch (Exception ignored) {}
			session.client = null;
			session.hub = null;
		} else if (session.hub != null) { // ADC link
			try {
				session.hub.close();
			} catch (Exception ignored) {}
			session.hub = null;
		}

		if (session.keyprintProblem != null) {
			// Wrong / changed certificate: retrying can't help and may mean someone is in the middle.
			Log.w("andcpp", "Not reconnecting to " + session.address().toUrl() + ": " + session.keyprintProblem.replace('\n', ' '));
			session.status = DCClientStatus.DISCONNECTED;
			manageWakeLock();
			mySendBroadcast(session.hubIp, true, true);
			sendKeyprintWarning(hubKey, session);
			return;
		}
		if (session.status == DCClientStatus.RECONNECTING) {
			return;
		}
		session.status = DCClientStatus.RECONNECTING;
		session.reconnectAttempts++;
		manageWakeLock();
		
		// Exponential backoff: 5s, 10s, 20s, 40s, max 60s
		long backoffMillis = (long) Math.min(60000, Math.pow(2, session.reconnectAttempts - 1) * 5000L);
		Log.d("andcpp", "Connection failed to " + session.hubIp + ":" + session.hubPort + " (" + message + "). Attempting reconnect in " + backoffMillis + "ms (attempt " + session.reconnectAttempts + ")");
		
		new Handler(Looper.getMainLooper()).postDelayed(() -> {
			if (sessions.containsKey(hubKey) && session.status == DCClientStatus.RECONNECTING) {
				Log.d("andcpp", "Executing reconnect attempt " + session.reconnectAttempts + " for " + session.hubIp + ":" + session.hubPort);
				connectToHub(hubKey, session);
			}
		}, backoffMillis);
		
		mySendBroadcast(session.hubIp, true, false);
	}


	private void ensureForeground(String latestIp) {
		NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (manager != null) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				NotificationChannel serviceChannel = new NotificationChannel(
						CHANNEL_ID,
						"Android DC++ Service Channel",
						NotificationManager.IMPORTANCE_LOW
				);
				manager.createNotificationChannel(serviceChannel);
			}
		}

		Intent notification_intent = new Intent(this, MainActivity.class);
		int flags = PendingIntent.FLAG_UPDATE_CURRENT;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			flags |= PendingIntent.FLAG_IMMUTABLE;
		}
		PendingIntent pendingIntent = PendingIntent.getActivity(this,
				0, notification_intent, flags);

		int activeCount = 0;
		for (HubSession s : sessions.values()) if (s.status == DCClientStatus.CONNECTED) activeCount++;

		Intent stopIntent = new Intent(this, DCPPService.class);
		stopIntent.setAction(ACTION_STOP_SERVICE);
		PendingIntent stopPendingIntent = PendingIntent.getService(
				this, 0, stopIntent, flags);

		NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(
				this, CHANNEL_ID);
		mBuilder.setSmallIcon(R.drawable.ic_launcher)
				.setContentTitle("Android DC++ Client")
				.setContentText(activeCount + " active hub connections")
				.setTicker("Android DC++ service running")
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.setContentIntent(pendingIntent)
				.setOngoing(true)
				.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop DC++", stopPendingIntent);

		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startForeground(1, mBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
			} else {
				startForeground(1, mBuilder.build());
			}
		} catch (Exception e) {
			Log.e("andcpp", "Failed to start foreground service", e);
		}
	}

	private void exceptionCaught(final String hubIp, final String message) {
		Log.d("andcpp", "exceptionCaught for " + hubIp + "! " + message );
		new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), "Unable to connect to " + hubIp + " : " + message, Toast.LENGTH_LONG)
			.show());
		mySendBroadcast(hubIp, true, true);
		
		DCMessage closeMsg = new DCMessage();
		closeMsg.command = "CLOSE_UI";
		
		// Find session by IP and shutdown
		for (Map.Entry<String, HubSession> entry : sessions.entrySet()) {
			if (entry.getValue().hubIp.equals(hubIp)) {
				if (entry.getValue().board_message_handler != null) {
					entry.getValue().board_message_handler.onCommand(closeMsg);
				}
				shutdownSession(entry.getKey());
				break;
			}
		}
	}
	
	private void mySendBroadcast(String hubIp, Boolean stopServiceFlag, Boolean stopProgressDialogFlag){
		Intent broadcastIntent = new Intent();
		broadcastIntent.setAction("ACTION_DO_SOMETHING");
		broadcastIntent.addCategory(Intent.CATEGORY_DEFAULT);		
		broadcastIntent.putExtra("hubIp", hubIp);
		broadcastIntent.putExtra("stopServiceFlag", stopServiceFlag);
		broadcastIntent.putExtra("stopProgressDialogFlag", stopProgressDialogFlag);
		sendBroadcast(broadcastIntent);
	}

	public void shutdownSession(String hubKey) {
		HubSession session = sessions.remove(hubKey);
		if (session == null && !hubKey.contains(":")) {
			// Try finding by IP
			for (Map.Entry<String, HubSession> entry : sessions.entrySet()) {
				if (entry.getValue().hubIp.equals(hubKey)) {
					session = sessions.remove(entry.getKey());
					break;
				}
			}
		}
		if (session != null) {
			session.shutdown();
		}
		manageWakeLock();
		if (sessions.isEmpty()) {
			synchronized (initiated_downloads) {
				boolean hasActiveDownloads = false;
				for (DownloadObject obj : initiated_downloads) {
					if (!obj.is_finished() && !obj.isPaused) {
						hasActiveDownloads = true;
						break;
					}
				}
				if (!hasActiveDownloads) {
					stopForeground(true);
					synchronized (normalWorkers) {
						for (Thread t : normalWorkers) {
							if (t != null) t.interrupt();
						}
					}
					downloadEnqueueExecutor.shutdownNow();
					downloadEnqueueExecutor = Executors.newSingleThreadExecutor();
				} else {
					ensureForeground("");
				}
			}
		} else {
			ensureForeground("");
		}
	}

	public void shutdown() {
		for (String key : new ArrayList<>(sessions.keySet())) {
			shutdownSession(key);
		}
		sessions.clear();
		stopForeground(true);
		stopSelf();
	}

	// ---- Hub user commands ($UserCommand) ----

	private void handleUserCommand(HubSession session, DCMessage msg) {
		if (msg.uc_type < 0) return;
		int ctx = msg.uc_context;
		if (msg.uc_type == 255) {
			// Clear: remove every command in the given contexts (0 = all).
			for (UserCommand uc : session.userCommands) {
				if (ctx == 0 || (uc.context & ctx) != 0) session.userCommands.remove(uc);
			}
			Log.d("andcpp", "Hub " + session.hubIp + " cleared its user commands (context " + ctx + ")");
			return;
		}
		if (msg.uc_type == UserCommand.TYPE_SEPARATOR) {
			session.userCommands.add(new UserCommand(UserCommand.TYPE_SEPARATOR, ctx, msg.uc_title, null));
			return;
		}
		if ((msg.uc_type == 1 || msg.uc_type == 2) && msg.uc_title != null && !msg.uc_title.isEmpty()
				&& msg.uc_command != null) {
			// Same title in an overlapping context replaces the old entry (hubs re-send updated menus).
			for (UserCommand uc : session.userCommands) {
				if (!uc.isSeparator() && msg.uc_title.equals(uc.title) && (uc.context & ctx) != 0) {
					session.userCommands.remove(uc);
				}
			}
			session.userCommands.add(new UserCommand(msg.uc_type, ctx, msg.uc_title, msg.uc_command));
			Log.d("andcpp", "Hub " + session.hubIp + " user command: " + msg.uc_title + " (context " + ctx + ")");
		}
	}

	/**
	 * An ADC hub menu entry: fills in %[myNI] %[mySID] %[myCID] %[userNI] %[userSID] %[userCID] %[userI4] and
	 * %[line:...] (values ADC-escaped; the command text is already ADC) and sends each line as is.
	 */
	private boolean runAdcUserCommand(final HubSession session, UserCommand uc, String targetNick,
			Map<String, String> lineValues) {
		if (!(session.hub instanceof AdcHubConnection) || session.status != DCClientStatus.CONNECTED
				|| uc == null || uc.command == null) {
			return false;
		}
		final AdcHubConnection hub = (AdcHubConnection) session.hub;
		DCUser target = targetNick != null ? hub.userByNick(targetNick) : null;
		String myNick = session.myuser != null && session.myuser.nick != null ? session.myuser.nick : "";
		Map<String, String> values = new java.util.LinkedHashMap<>();
		values.put("myNI", com.phinvader.libjdcpp.adc.AdcCommand.escape(myNick));
		values.put("mySID", hub.sid() != null ? hub.sid() : "");
		values.put("myCID", hub.cid());
		String tNick = target != null && target.nick != null ? target.nick : (targetNick != null ? targetNick : "");
		values.put("userNI", com.phinvader.libjdcpp.adc.AdcCommand.escape(tNick));
		values.put("nick", com.phinvader.libjdcpp.adc.AdcCommand.escape(tNick)); // NMDC-style menus on bridged hubs
		values.put("userSID", target != null && target.sid != null ? target.sid : "");
		values.put("userCID", target != null && target.cid != null ? target.cid : "");
		values.put("userI4", target != null && target.ip != null ? target.ip : "");
		String cmd = uc.command;
		if (lineValues != null) {
			for (Map.Entry<String, String> e : lineValues.entrySet()) {
				cmd = cmd.replace("%[line:" + e.getKey() + "]",
						com.phinvader.libjdcpp.adc.AdcCommand.escape(e.getValue() != null ? e.getValue() : ""));
			}
		}
		for (Map.Entry<String, String> e : values.entrySet()) {
			cmd = cmd.replace("%[" + e.getKey() + "]", e.getValue());
		}
		final List<String> lines = new ArrayList<>();
		for (String l : cmd.split("\n")) {
			if (!l.trim().isEmpty()) lines.add(l);
		}
		if (lines.isEmpty()) return false;
		Log.i("andcpp", "Running ADC hub command '" + uc.title + "' for " + tNick + " on " + session.hubIp);
		userCommandExecutor.execute(() -> {
			for (String l : lines) hub.sendHubCommand(l);
		});
		return true;
	}

	/** Commands (and separators) this hub offers in the given context, e.g. UserCommand.CONTEXT_USER. */
	public List<UserCommand> getUserCommands(String hubIp, int contextBit) {
		List<UserCommand> out = new ArrayList<>();
		HubSession session = getSession(hubIp);
		if (session == null || session.status != DCClientStatus.CONNECTED) return out;
		for (UserCommand uc : session.userCommands) {
			if ((uc.context & contextBit) != 0) out.add(uc);
		}
		return out;
	}

	private final java.util.concurrent.ExecutorService userCommandExecutor =
			java.util.concurrent.Executors.newSingleThreadExecutor();

	/** NMDC escaping for text inserted into a command ('|' and '$' are protocol delimiters). */
	private static String escapeNmdc(String s) {
		return s == null ? "" : s.replace("&", "&amp;").replace("|", "&#124;").replace("$", "&#36;");
	}

	/**
	 * Runs a hub command for targetNick: fills in %[nick]/%[userNI], %[mynick]/%[myNI], %[userIP] and the
	 * %[line:...] answers, then sends each command part to the hub (off the UI thread).
	 * Returns false if the hub is not connected.
	 */
	public boolean runUserCommand(String hubIp, UserCommand uc, String targetNick, Map<String, String> lineValues) {
		HubSession adcSession = sessionForUi(hubIp);
		if (adcSession != null && adcSession.protocol.isAdc()) {
			return runAdcUserCommand(adcSession, uc, targetNick, lineValues);
		}
		final HubSession session = getSession(hubIp);
		if (session == null || session.client == null || session.myuser == null
				|| session.status != DCClientStatus.CONNECTED || uc == null || uc.command == null) {
			return false;
		}
		String myNick = session.myuser.nick != null ? session.myuser.nick : "";
		String nick = targetNick != null ? targetNick : "";
		String userIp = "";
		List<DCUser> users = session.client.get_nick_list();
		if (users != null) {
			for (DCUser u : users) {
				if (u != null && nick.equalsIgnoreCase(u.nick) && u.ip != null) { userIp = u.ip; break; }
			}
		}

		final List<String> parts = new ArrayList<>();
		for (String part : uc.command.split("&#124;", -1)) {
			if (part.trim().isEmpty()) continue;
			String m = part.replace("&#36;", "$");
			m = m.replace("%[nick]", nick).replace("%[userNI]", nick)
					.replace("%[mynick]", myNick).replace("%[myNI]", myNick)
					.replace("%[userIP]", userIp);
			if (lineValues != null) {
				for (Map.Entry<String, String> e : lineValues.entrySet()) {
					m = m.replace("%[line:" + e.getKey() + "]", escapeNmdc(e.getValue()));
				}
			}
			m = m.replace("&amp;", "&");
			parts.add(m);
		}
		if (parts.isEmpty()) return false;
		Log.i("andcpp", "Running hub command '" + uc.title + "' for " + nick + " on " + session.hubIp);
		userCommandExecutor.execute(() -> {
			try {
				HubConnection hub = session.hub;
				if (hub == null) return;
				for (String p : parts) {
					hub.sendHubCommand(p);
				}
			} catch (Exception e) {
				Log.e("andcpp", "Failed to send hub command '" + uc.title + "'", e);
			}
		});
		return true;
	}

	/** Sends searches in order, off the UI thread (network on the main thread crashes the app). */
	private final java.util.concurrent.ExecutorService searchSendExecutor =
			java.util.concurrent.Executors.newSingleThreadExecutor();

	public void make_search(String hubKey, String searchtext) {
		final HubSession session = getSession(hubKey);
		if (session != null && session.hub != null) {
			session.search_results.clear();
			Log.d("andcpp", "dcpp_service Making search request to " + session.hubIp + ":" + session.hubPort + ": " + searchtext);
			searchSendExecutor.execute(() -> {
				try {
					HubConnection hub = session.hub;
					if (hub != null) hub.search(session.myuser, searchtext);
				} catch (Exception e) {
					Log.e("andcpp", "Failed to send search to " + session.hubIp, e);
				}
			});
		}
	}

	/** (Re-)sends our user info to the hub ($MyINFO on NMDC). False if the session has no hub link. */
	private boolean sendMyInfo(HubSession s) {
		HubConnection hub = s.hub;
		if (hub == null || s.myuser == null) return false;
		hub.updateMyInfo(s.myuser, s.prefs);
		return true;
	}
	
	
	@Override
	public void onUploadStarted(PeerConnectionHandler handler) {
		// We only want to notify when it actually starts UPLOADING, 
		// but PeerConnectionHandler calls this right before the transfer loop.
		// However, it's already in UPLOADING state then.
		updateUploadNotification(handler);
	}

	@Override
	public void onUploadProgress(PeerConnectionHandler handler, long sent, long total) {
		updateUploadNotification(handler);
	}

	public UploadManager.UploadStats getUploadStats() {
		if (uploadManager == null) {
			UploadManager.UploadStats empty = new UploadManager.UploadStats();
			empty.connectedPeers = new ArrayList<>();
			return empty;
		}
		return uploadManager.getUploadStats();
	}

	// ---- All-time transfer totals (persisted) ----
	private static final String PREF_ALLTIME_DL = "alltime_dl_bytes";
	private static final String PREF_ALLTIME_UL = "alltime_ul_bytes";
	private final Object allTimeLock = new Object();
	/** Session counter values already added to the stored all-time totals. */
	private long dlSavedMark = com.phinvader.libjdcpp.MessageHandler.totalDownloadedBytes.get();
	private long ulSavedMark = 0;

	private long sessionUploadedBytes() {
		return uploadManager != null ? uploadManager.getTotalBytesUploaded() : 0;
	}

	/** Adds bytes moved since the last save to the stored totals (never double counts). */
	private void saveAllTimeTotals() {
		synchronized (allTimeLock) {
			long dl = com.phinvader.libjdcpp.MessageHandler.totalDownloadedBytes.get();
			long ul = sessionUploadedBytes();
			long dDl = dl >= dlSavedMark ? dl - dlSavedMark : dl; // counter restarted: count from 0
			long dUl = ul >= ulSavedMark ? ul - ulSavedMark : ul;
			if (dDl == 0 && dUl == 0) return;
			SharedPreferences p = getSharedPreferences(Constants.PREFS_NAME, 0);
			p.edit()
					.putLong(PREF_ALLTIME_DL, p.getLong(PREF_ALLTIME_DL, 0) + dDl)
					.putLong(PREF_ALLTIME_UL, p.getLong(PREF_ALLTIME_UL, 0) + dUl)
					.apply();
			dlSavedMark = dl;
			ulSavedMark = ul;
		}
	}

	/** {all-time downloaded, all-time uploaded} in bytes, including this session's unsaved part. */
	public long[] getAllTimeTotals() {
		synchronized (allTimeLock) {
			SharedPreferences p = getSharedPreferences(Constants.PREFS_NAME, 0);
			long dl = com.phinvader.libjdcpp.MessageHandler.totalDownloadedBytes.get();
			long ul = sessionUploadedBytes();
			return new long[]{
					p.getLong(PREF_ALLTIME_DL, 0) + Math.max(0, dl - dlSavedMark),
					p.getLong(PREF_ALLTIME_UL, 0) + Math.max(0, ul - ulSavedMark)
			};
		}
	}

	/**
	 * Upload connections that are still open (for the Uploads list). Only connections that identified themselves
	 * (sent $MyNick): with a public port, internet scanners connect too, and those must not show as uploads.
	 */
	public List<PeerConnectionHandler> getActiveUploads() {
		List<PeerConnectionHandler> list = new ArrayList<>();
		if (uploadManager == null) return list;
		for (PeerConnectionHandler h : uploadManager.getActiveUploads()) {
			if (h.isFinished()) continue;
			if (h.getState() == PeerConnectionHandler.ConnectionState.HANDSHAKING) continue;
			String nick = h.getHisNick();
			if (nick == null || nick.isEmpty() || "Unknown".equals(nick)) continue;
			list.add(h);
		}
		return list;
	}

	private void updateUploadNotification(PeerConnectionHandler handler) {
		Integer id = uploadNotificationIds.get(handler);
		if (id == null) {
			id = nextUploadNotificationId.incrementAndGet();
			uploadNotificationIds.put(handler, id);
		}

		long sent = handler.getBytesSent();
		long total = handler.getTotalSize();
		int progress = total > 0 ? (int) (((double) sent / total) * 100) : 0;
		
		FileSize sentFs = new FileSize(sent);
		FileSize totalFs = new FileSize(total);

		String contentTitle = "Uploading to " + handler.getHisNick();
		String contentText = String.format(java.util.Locale.US, "%s (%.2f %s / %.2f %s)",
				handler.getFileName(), sentFs.fileSize, sentFs.unit, totalFs.fileSize, totalFs.unit);
		boolean indeterminate = total <= 0;

		if (handler.getState() == PeerConnectionHandler.ConnectionState.QUEUED) {
			int pos = uploadManager.getQueuePosition(handler);
			contentTitle = "Upload Queued for " + handler.getHisNick();
			contentText = String.format(java.util.Locale.US, "%s - Position: %d",
					handler.getFileName(), pos);
			progress = 0;
			indeterminate = true;
		}

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_launcher)
				.setContentTitle(contentTitle)
				.setContentText(contentText)
				.setOngoing(true)
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.setProgress(100, progress, indeterminate);

		if (mNotifyManager2 != null) {
			mNotifyManager2.notify(id, builder.build());
		}
	}

	@Override
	public void onUploadFinished(PeerConnectionHandler handler) {
		Integer id = uploadNotificationIds.remove(handler);
		if (id == null) return;

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_launcher)
				.setContentTitle("Upload Complete")
				.setContentText("Finished uploading " + handler.getFileName() + " to " + handler.getHisNick())
				.setOngoing(false)
				.setAutoCancel(true);

		if (mNotifyManager2 != null) {
			mNotifyManager2.notify(id, builder.build());
		}
	}

	@Override
	public void onUploadError(PeerConnectionHandler handler, String error) {
		Integer id = uploadNotificationIds.remove(handler);
		if (id == null) return;

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_launcher)
				.setContentTitle("Upload Failed")
				.setContentText("Error uploading " + handler.getFileName() + ": " + error)
				.setOngoing(false)
				.setAutoCancel(true);

		if (mNotifyManager2 != null) {
			mNotifyManager2.notify(id, builder.build());
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (runningInstance == this) runningInstance = null;
		saveDownloadsStateNow();
		saveAllTimeTotals();
		connectivityExecutor.shutdownNow();
		final ConnectivityDetector detector = connectivityDetector;
		if (detector != null) {
			// Remove our router port mapping (network I/O: not on the main thread).
			new Thread(detector::removeMapping, "UPnP-cleanup").start();
		}
		searchReplyExecutor.shutdownNow();
		searchSendExecutor.shutdownNow();
		userCommandExecutor.shutdownNow();
		synchronized (this) {
			if (searchUdpSocket != null) {
				searchUdpSocket.close();
				searchUdpSocket = null;
			}
		}

		if (wifiOnlyPrefListener != null) {
			getSharedPreferences(Constants.PREFS_NAME, 0)
					.unregisterOnSharedPreferenceChangeListener(wifiOnlyPrefListener);
			wifiOnlyPrefListener = null;
		}

		PIAPortForwardingManager.getInstance(this).stopPortForwarding();
		
		cancelTick();
		// Let's shutdown connection(if any) if app get's unexpectedly killed.
		if (keepAliveTimer != null) {
			keepAliveTimer.cancel();
			keepAliveTimer = null;
		}
		if (networkReceiver != null) {
			unregisterReceiver(networkReceiver);
			networkReceiver = null;
		}
		networkEventHandler.removeCallbacks(networkEventRunnable);
		if (physicalNetworkCallback != null) {
			try {
				ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
				if (cm != null) cm.unregisterNetworkCallback(physicalNetworkCallback);
			} catch (Exception ignored) {
			}
			physicalNetworkCallback = null;
		}
		if (wakeLock != null && wakeLock.isHeld()) {
			wakeLock.release();
			wakeLock = null;
		}
		if (wifiLock != null && wifiLock.isHeld()) {
			wifiLock.release();
			wifiLock = null;
		}
		if (uploadManager != null) {
			uploadManager.stopListening();
		}

		// 1. Iterate through all active/running downloads and call pause_download() on each of them
		synchronized (initiated_downloads) {
			for (DownloadObject obj : initiated_downloads) {
				if (!obj.is_finished() && !obj.isPaused) {
					try {
						obj.pause_download();
					} catch (Exception e) {
						Log.e("andcpp", "Error pausing download during onDestroy", e);
					}
				}
			}
		}

		// 2. Explicitly close connection streams / sockets and wait for normal_worker and any download threads with bounded safe timeouts (e.g., 2000ms / 3000ms)
		synchronized (initiated_downloads) {
			for (DownloadObject obj : initiated_downloads) {
				if (obj.download_status != null) {
					if (obj.download_status.connectionHandler != null) {
						try {
							obj.download_status.connectionHandler.close();
						} catch (Exception e) {
							Log.e("andcpp", "Error closing download connection handler", e);
						}
					}
					if (obj.download_status.thread != null) {
						try {
							obj.download_status.thread.interrupt();
						} catch (Exception ignored) {}
					}
				}
			}
		}

		synchronized (normalWorkers) {
			for (Thread t : normalWorkers) {
				if (t != null) {
					t.interrupt();
				}
			}
			for (Thread t : normalWorkers) {
				if (t != null) {
					try {
						t.join(1000);
					} catch (InterruptedException ignored) {}
				}
			}
			normalWorkers.clear();
		}

		// Also check/wait for any specific thread associated with active downloads with bounded timeout (2000ms)
		synchronized (initiated_downloads) {
			for (DownloadObject obj : initiated_downloads) {
				if (obj.download_status != null && obj.download_status.thread != null) {
					try {
						obj.download_status.thread.join(2000);
					} catch (Exception ignored) {}
				}
			}
		}

		// 3. Save download state and actual partial file length (File.length()) synchronously using editor.commit() within the bounded timeout window.
		synchronized (initiated_downloads) {
			for (DownloadObject obj : initiated_downloads) {
				if (!obj.is_finished()) {
					File f = new File(obj.local_file);
					if (f.exists()) {
						obj.pendingStartOffset = f.length();
						if (obj.download_status != null) {
							obj.download_status.downloadedSize = f.length();
							obj.download_status.startOffset = f.length();
						}
					}
				}
			}
		}
		saveDownloadsState();

		shutdown();
		Log.d("andcpp", "destroyed!");
	}

	private final Object saveLock = new Object();
	private final java.util.concurrent.atomic.AtomicBoolean saveScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
	private final java.util.concurrent.ScheduledExecutorService saveExecutor =
			Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "DownloadStateSaver");
				t.setDaemon(true);
				return t;
			});

	/** Coalesces bursts of save requests into one write about 2 seconds later, off the calling thread. */
	private void saveDownloadsState() {
		if (saveScheduled.compareAndSet(false, true)) {
			try {
				saveExecutor.schedule(() -> {
					saveScheduled.set(false);
					saveDownloadsStateNow();
				}, 2, java.util.concurrent.TimeUnit.SECONDS);
			} catch (Exception e) {
				saveScheduled.set(false);
				saveDownloadsStateNow();
			}
		}
	}

	private static volatile DCPPService runningInstance = null;

	/** The service while it runs (used by the updater to save state before installing). */
	public static DCPPService getRunningInstance() {
		return runningInstance;
	}

	/** Called just before an app update is installed (the installer kills the process without onDestroy). */
	public void prepareForUpdate() {
		Log.i("andcpp", "[UPDATE] saving downloads state before update");
		saveDownloadsStateNow();
	}

	/** False until restoreDownloadsState() has loaded the saved queue: saving before then would overwrite it with less. */
	private volatile boolean downloadsStateRestored = false;

	private void saveDownloadsStateNow() {
		synchronized (saveLock) {
		try {
			SharedPreferences prefs = getSharedPreferences("dcpp_downloads_state", Context.MODE_PRIVATE);
			SharedPreferences.Editor editor = prefs.edit();
			if (!downloadsStateRestored) {
				Log.d("andcpp", "Downloads state not restored yet; not saving over it");
				return;
			}
			editor.clear(); // drop entries of a longer previous list
			
			List<DownloadItem> queue = get_download_queue();
			org.json.JSONArray backup = new org.json.JSONArray();
			int count = 0;
			for (DownloadItem item : queue) {
				if (item instanceof DownloadObject) {
					DownloadObject obj = (DownloadObject) item;
					// Save active, queued, or incomplete (not fully finished or completed with partial size)
					if (!obj.is_finished() || obj.bytes_done() < obj.total_bytes()) {
						editor.putString("hubIp_" + count, obj.hubIp);
						editor.putString("target_nick_" + count, obj.target_nick);
						editor.putString("local_file_" + count, obj.local_file);
						editor.putString("remote_file_" + count, obj.remote_file);
						editor.putLong("file_size_" + count, obj.file_size);
						editor.putString("group_label_" + count, obj.groupLabel);
						editor.putString("group_name_" + count, obj.groupName);
						editor.putLong("bytes_done_" + count, obj.bytes_done());
						editor.putBoolean("is_paused_" + count, obj.isPaused);
						backup.put(toBackupJson(obj));
						count++;
					}
				} else if (item instanceof FolderDownloadItem) {
					FolderDownloadItem folderItem = (FolderDownloadItem) item;
					for (DownloadObject obj : folderItem.downloads) {
						if (!obj.is_finished() || obj.bytes_done() < obj.total_bytes()) {
							editor.putString("hubIp_" + count, obj.hubIp);
							editor.putString("target_nick_" + count, obj.target_nick);
							editor.putString("local_file_" + count, obj.local_file);
							editor.putString("remote_file_" + count, obj.remote_file);
							editor.putLong("file_size_" + count, obj.file_size);
							editor.putString("group_label_" + count, obj.groupLabel);
							editor.putString("group_name_" + count, obj.groupName);
							editor.putLong("bytes_done_" + count, obj.bytes_done());
							editor.putBoolean("is_paused_" + count, obj.isPaused);
							backup.put(toBackupJson(obj));
							count++;
						}
					}
				}
			}
			editor.putInt("download_count", count);
			editor.commit();
			writeDownloadsBackup(backup);
			Log.d("andcpp", "Saved " + count + " active/queued/incomplete downloads state.");
		} catch (Exception e) {
			Log.e("andcpp", "Error saving downloads state", e);
		}
		}
	}

	public void retryPendingDownloads() {
		reapStaleClaims();
		logQueueSnapshot();
		if (initiated_downloads == null) return;
		synchronized (initiated_downloads) {
			for (DownloadObject obj : initiated_downloads) {
				if (obj.isFileList()) continue;
				if (obj.permanentlyFailed) continue; // peer says the file is not available
				if (isActiveWorkerObject(obj)) continue; // a worker owns it right now
				if (obj.waitReason != null && System.currentTimeMillis() < obj.nextRetryAt) continue; // still backing off
				if (obj.isCancelled || obj.isPaused
						|| (obj.download_status != null
						&& obj.download_status.status == DCConstants.DownloadStatus.COMPLETED)) {
					continue;
				}
				DCConstants.DownloadStatus status = obj.get_status();
				if (status == DCConstants.DownloadStatus.INTERUPTED
						|| status == DCConstants.DownloadStatus.FAILED
						|| status == DCConstants.DownloadStatus.QUEUED
						|| status == DCConstants.DownloadStatus.WAITING_FOR_PEER
						|| obj.download_status == null) {
					HubSession s = getSession(obj.hubIp);
					if (s != null && s.status == DCClientStatus.CONNECTED) {
						try {
							if (normal_queue != null && !normal_queue.contains(obj)) {
								normal_queue.put(obj);
							}
						} catch (InterruptedException e) {
							Log.e("andcpp", "Interrupted while re-queueing pending download", e);
						}
					}
				}
			}
		}
		if (normal_queue != null && !normal_queue.isEmpty()) {
			startNormalWorkersIfNeeded();
		}
	}

	// ---- Download queue backup that survives uninstall / clean install ----
	// Android deletes the app's private storage (and so the saved queue) on uninstall. The queue is also written to
	// the app's folder in shared storage (Download/Android-DC++/.downloads_state), next to the partly downloaded
	// files, and read back from there after a reinstall so those downloads continue where they stopped.

	private static final String DOWNLOADS_BACKUP_NAME = ".downloads_state";

	/**
	 * False until we know the backup file holds nothing we still need: after a clean install it can only be read once
	 * storage permission is granted, and writing the (still empty) queue over it before then would lose it.
	 */
	private volatile boolean downloadsBackupChecked = false;

	private File downloadsBackupFile() {
		return new File(SettingsBackup.backupDirectory(this), DOWNLOADS_BACKUP_NAME);
	}

	private boolean hasStoragePermission() {
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
				|| checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
				== android.content.pm.PackageManager.PERMISSION_GRANTED;
	}

	private static org.json.JSONObject toBackupJson(DownloadObject obj) throws org.json.JSONException {
		org.json.JSONObject j = new org.json.JSONObject();
		j.put("hubIp", obj.hubIp);
		j.put("nick", obj.target_nick);
		j.put("local", obj.local_file);
		j.put("remote", obj.remote_file);
		j.put("size", obj.file_size);
		j.put("groupLabel", obj.groupLabel);
		j.put("groupName", obj.groupName);
		j.put("done", obj.bytes_done());
		j.put("paused", obj.isPaused);
		return j;
	}

	private void writeDownloadsBackup(org.json.JSONArray list) {
		if (!downloadsBackupChecked || !hasStoragePermission()) return;
		try {
			File f = downloadsBackupFile();
			if (list.length() == 0) {
				if (f.exists()) f.delete();
				return;
			}
			org.json.JSONObject root = new org.json.JSONObject();
			root.put("v", 1);
			root.put("downloads", list);
			File tmp = new File(f.getPath() + ".tmp");
			try (java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
				os.write(root.toString().getBytes("UTF-8"));
			}
			if (!tmp.renameTo(f)) {
				f.delete();
				tmp.renameTo(f);
			}
		} catch (Exception e) {
			Log.w("andcpp", "Could not write the downloads backup: " + e.getMessage());
		}
	}

	/** One saved download, from the app's own saved state or from the backup file. */
	private static final class SavedDownload {
		String hubIp, nick, localFile, remoteFile, groupLabel, groupName;
		long fileSize, bytesDone;
	}

	private static String optStr(org.json.JSONObject j, String key) {
		return j.isNull(key) ? null : j.optString(key, null);
	}

	private List<SavedDownload> readDownloadsBackup() throws Exception {
		List<SavedDownload> out = new ArrayList<>();
		File f = downloadsBackupFile();
		if (!f.isFile()) return out;
		byte[] data = new byte[(int) Math.min(f.length(), 32L * 1024 * 1024)];
		try (java.io.InputStream in = new java.io.FileInputStream(f)) {
			int off = 0, n;
			while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
		}
		org.json.JSONArray arr = new org.json.JSONObject(new String(data, "UTF-8")).optJSONArray("downloads");
		if (arr == null) return out;
		for (int i = 0; i < arr.length(); i++) {
			org.json.JSONObject j = arr.optJSONObject(i);
			if (j == null) continue;
			SavedDownload d = new SavedDownload();
			d.hubIp = optStr(j, "hubIp");
			d.nick = optStr(j, "nick");
			d.localFile = optStr(j, "local");
			d.remoteFile = optStr(j, "remote");
			d.groupLabel = optStr(j, "groupLabel");
			d.groupName = optStr(j, "groupName");
			d.fileSize = j.optLong("size", 0);
			d.bytesDone = j.optLong("done", 0);
			out.add(d);
		}
		return out;
	}

	private List<SavedDownload> readDownloadsPrefs() {
		SharedPreferences prefs = getSharedPreferences("dcpp_downloads_state", Context.MODE_PRIVATE);
		int count = prefs.getInt("download_count", 0);
		List<SavedDownload> out = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			SavedDownload d = new SavedDownload();
			d.hubIp = prefs.getString("hubIp_" + i, null);
			d.nick = prefs.getString("target_nick_" + i, null);
			d.localFile = prefs.getString("local_file_" + i, null);
			d.remoteFile = prefs.getString("remote_file_" + i, null);
			d.fileSize = prefs.getLong("file_size_" + i, 0);
			d.groupLabel = prefs.getString("group_label_" + i, null);
			d.groupName = prefs.getString("group_name_" + i, null);
			d.bytesDone = prefs.getLong("bytes_done_" + i, 0);
			out.add(d);
		}
		return out;
	}

	/** Adds the saved downloads (paused) to the list; returns how many were added. */
	private int addSavedDownloads(List<SavedDownload> saved) {
		int added = 0;
		for (SavedDownload d : saved) {
			if (d.localFile == null || d.remoteFile == null || d.nick == null) continue;
			File f = new File(d.localFile);
			long existingLength = f.exists() ? Math.max(f.length(), d.bytesDone) : d.bytesDone;

			final String targetHubIp = (d.hubIp != null) ? d.hubIp : (!sessions.isEmpty() ? sessions.keySet().iterator().next() : null);

			synchronized (initiated_downloads) {
				boolean exists = false;
				for (DownloadObject existing : initiated_downloads) {
					if (existing.local_file.equals(d.localFile) && !existing.is_finished()) {
						exists = true;
						break;
					}
				}
				if (!exists) {
					final DownloadObject obj = new DownloadObject(targetHubIp, d.nick, d.localFile,
							d.remoteFile, DCPPService.this, d.groupLabel, d.groupName);
					obj.set_total_bytes(d.fileSize);
					obj.pendingStartOffset = existingLength;
					// Restored paused; resumeRestoredDownloadsSoon() resumes it via the normal unpause path (resume_download()).
					obj.isPaused = true;
					if (obj.mBuilder2 != null) {
						obj.mBuilder2.setContentText("Download Paused").setOngoing(false);
					}
					initiated_downloads.add(obj);
					added++;
				}
			}
		}
		if (added > 0) manageWakeLock();
		return added;
	}

	/** Resumes the restored (paused) downloads after 10 seconds, giving the hub time to connect. */
	private void resumeRestoredDownloadsSoon() {
		new Handler(Looper.getMainLooper()).postDelayed(() -> {
			synchronized (initiated_downloads) {
				for (DownloadObject obj : initiated_downloads) {
					if (obj.isPaused && !obj.is_finished()) {
						obj.resume_download();
					}
				}
			}
			retryPendingDownloads();
		}, 10000);
	}

	private void restoreDownloadsState() {
		List<SavedDownload> saved = readDownloadsPrefs();
		if (saved.isEmpty()) {
			// Nothing in the app's own storage: a clean install, or simply an empty queue. Try the backup file.
			downloadsStateRestored = true;
			restoreDownloadsFromBackupIfNeeded();
			return;
		}

		boolean restoreSuccessful = false;
		try {
			Log.d("andcpp", "Restoring " + saved.size() + " downloads state...");
			addSavedDownloads(saved);
			restoreSuccessful = true;
		} catch (Exception e) {
			Log.e("andcpp", "Error restoring downloads state", e);
		}

		// Keep the saved state as it is: the next save rewrites it. (It used to be cleared here, so a kill in the
		// seconds before that save - e.g. an app update installed right after start - lost the whole queue.)
		// If the restore failed, keep not saving so the next start can try again with the full saved list.
		if (restoreSuccessful) {
			downloadsStateRestored = true;
			downloadsBackupChecked = true; // the app's own state is the current copy; the backup just follows it
			saveDownloadsState(); // (re)write the backup file
		}

		resumeRestoredDownloadsSoon();
	}

	/**
	 * After a clean install, brings back the download queue from the backup file in shared storage.
	 * Needs storage permission; MainActivity calls this again once the permission is granted.
	 */
	public void restoreDownloadsFromBackupIfNeeded() {
		if (downloadsBackupChecked) return;
		if (!hasStoragePermission()) {
			Log.d("andcpp", "Downloads backup: no storage permission yet; will check once it is granted");
			return;
		}
		new Thread(() -> {
			final List<SavedDownload> saved;
			try {
				saved = readDownloadsBackup();
			} catch (Exception e) {
				Log.w("andcpp", "Could not read the downloads backup: " + e.getMessage());
				return; // leave the file alone: maybe a later start can read it
			}
			new Handler(Looper.getMainLooper()).post(() -> {
				if (downloadsBackupChecked) return;
				int added;
				try {
					added = addSavedDownloads(saved);
				} catch (Exception e) {
					Log.e("andcpp", "Error restoring downloads from the backup", e);
					return; // leave the backup untouched so a later start can try again
				}
				downloadsBackupChecked = true;
				saveDownloadsState(); // into the app's own state too, and refresh the backup
				if (added > 0) {
					Log.i("andcpp", "Restored " + added + " downloads from " + downloadsBackupFile().getPath() + " (after a reinstall)");
					Toast.makeText(DCPPService.this, "Restored " + added
							+ " unfinished downloads; they continue once a hub is connected", Toast.LENGTH_LONG).show();
					resumeRestoredDownloadsSoon();
				}
			});
		}, "RestoreDownloadsBackup").start();
	}

	public void resetAllDownloads() {
		// pause_download() blocks up to 3s per item, so never run this on the UI thread.
		new Thread(this::resetAllDownloadsBlocking, "ResetAllDownloads").start();
	}

	private void resetAllDownloadsBlocking() {
		// 1. Stop every running download, remembering which ones the reset paused
		//    (downloads the user paused by hand stay paused).
		List<DownloadObject> toResume = new ArrayList<>();
		List<DownloadObject> snapshot;
		synchronized (initiated_downloads) {
			snapshot = new ArrayList<>(initiated_downloads);
		}
		for (DownloadObject obj : snapshot) {
			if (obj.isFileList() || obj.isCancelled || obj.is_finished() || obj.isPaused) continue;
			toResume.add(obj);
			try {
				obj.pause_download();
			} catch (Exception e) {
				Log.e("andcpp", "Error pausing download during resetAllDownloads", e);
			}
		}

		// 2. Close connection streams / sockets and threads
		synchronized (initiated_downloads) {
			for (DownloadObject obj : initiated_downloads) {
				if (obj.download_status != null) {
					if (obj.download_status.connectionHandler != null) {
						try {
							obj.download_status.connectionHandler.close();
						} catch (Exception e) {
							Log.e("andcpp", "Error closing download connection handler during resetAllDownloads", e);
						}
					}
					if (obj.download_status.thread != null) {
						try {
							obj.download_status.thread.interrupt();
						} catch (Exception ignored) {}
					}
				}
			}
		}

		synchronized (normalWorkers) {
			for (Thread t : normalWorkers) {
				if (t != null) t.interrupt();
			}
		}

		// 3. Save download state with actual file lengths synchronously
		synchronized (initiated_downloads) {
			for (DownloadObject obj : initiated_downloads) {
				if (!obj.is_finished()) {
					File f = new File(obj.local_file);
					if (f.exists()) {
						obj.pendingStartOffset = f.length();
						if (obj.download_status != null) {
							obj.download_status.downloadedSize = f.length();
							obj.download_status.startOffset = f.length();
						}
					}
				}
			}
		}
		saveDownloadsStateNow();

		// 4. Give the interrupted workers a moment to exit and release their claims,
		//    then resume everything the reset paused.
		try {
			Thread.sleep(2000);
		} catch (InterruptedException ignored) {}

		new Handler(Looper.getMainLooper()).post(() -> {
			for (DownloadObject obj : toResume) {
				if (obj.isCancelled || obj.is_finished()) continue;
				obj.resume_download();
			}
			retryPendingDownloads();
			Log.d("andcpp", "resetAllDownloads resumed " + toResume.size() + " downloads.");
		});
		// Safety net in case a worker was still releasing its claim on the first pass.
		new Handler(Looper.getMainLooper()).postDelayed(this::retryPendingDownloads, 5000);
	}

	
	
	
	
}
