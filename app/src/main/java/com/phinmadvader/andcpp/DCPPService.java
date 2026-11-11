package com.phinmadvader.andcpp;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import android.app.Service;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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

	public static enum DCClientStatus {
		DISCONNECTED, INVALIDIP, CONNECTED, CONNECTING, RECONNECTING
	}

	public class HubSession {
		public String hubIp;
		public int hubPort;
		public String hubName;
		public DCClientStatus status = DCClientStatus.DISCONNECTED;
		public DCClient client = null;
		public DCCommand board_message_handler = null;
		public DCCommand user_handler = null;
		public DCCommand search_handler = null;
		public DCPreferences prefs;
		public DCUser myuser;
		public LinkedBlockingQueue<DCMessage> search_results = new LinkedBlockingQueue<>();
		public int reconnectAttempts = 0;
		public long lastReconnectAttempt = 0;

		public HubSession(String ip, int port) {
			this.hubIp = ip;
			this.hubPort = port;
		}

		public void shutdown() {
			status = DCClientStatus.DISCONNECTED;
			if (client != null) {
				client.close();
			}
		}
	}

	private Map<String, HubSession> sessions = new ConcurrentHashMap<>();

	private String getSessionKey(String ip, int port) {
		return ip + ":" + port;
	}

	public HubSession getSession(String hubKey) {
		if (hubKey == null) return null;
		HubSession session = sessions.get(hubKey);
		if (session == null && !hubKey.contains(":")) {
			// Fallback search by IP if key doesn't have port
			for (Map.Entry<String, HubSession> entry : sessions.entrySet()) {
				if (entry.getValue().hubIp.equals(hubKey)) {
					return entry.getValue();
				}
			}
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

		public DCDownloader.DownloadQueueEntity download_status = null;
		private boolean isPaused = false;
		private boolean isCancelled = false;
		public String groupLabel = null;
		public String groupName = null;

		public String getLocalFilePath() {
			return local_file;
		}

		public void pause_download() {
			isPaused = true;
			try {
				if (download_status != null && !is_finished_internal()) {
					download_status.status = DCConstants.DownloadStatus.INTERUPTED;
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
				if (mNotifyManager2 != null) {
					mBuilder2.setContentText("Download Paused")
							.setOngoing(false);
					mNotifyManager2.notify(currentID, mBuilder2.build());
				}
			} catch (Exception e) {
				Log.e("andcpp", "Error in pause_download", e);
			}
		}

		public void resume_download() {
			try {
				if (isPaused || (download_status != null && (download_status.status == DCConstants.DownloadStatus.INTERUPTED || download_status.status == DCConstants.DownloadStatus.FAILED))) {
					isPaused = false;
					if (download_status != null) {
						this.pendingStartOffset = download_status.downloadedSize;
						download_status.status = DCConstants.DownloadStatus.QUEUED;
					}
					synchronized (initiated_downloads) {
						initiated_downloads.remove(this);
					}
					try {
						normal_queue.put(this);
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

			mBuilder2 = new NotificationCompat.Builder(parent, CHANNEL_ID);
			this.initialize_download_connection();
		}

		public void initialize_download_connection() {

			String title = this.file_name;
			if (remote_file.equalsIgnoreCase("files.xml")) {
				title = "Fetching File List: " + target_nick;
			}

			this.mBuilder2.setContentTitle(title)
					.setContentText("Download Queued")
					.setSmallIcon(R.drawable.ic_launcher);
			
			mNotifyManager2 = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			if (mNotifyManager2 != null && groupLabel == null) {
				mNotifyManager2.notify(this.currentID, mBuilder2.build());
			}
			updateFolderStatus(this, DCConstants.DownloadStatus.QUEUED);

			HubSession session = getSession(hubIp);
			if (session == null) return;

			DCUser my_user = new DCUser();
			DCUser his_user = new DCUser();
			my_user.nick = session.prefs.getNick();
			my_user.ip = session.prefs.server_ip;
			his_user.nick = target_nick;
			// initialize_download_tracking();
			if (session.prefs.active && uploadManager != null && uploadManager.isListening()) {
				activeRc = new DCClient.ActiveDownloadConnection(his_user, my_user,
						session.prefs, local_file, remote_file, session.client);
			} else {
				myrc = new DCClient.PassiveDownloadConnection(his_user, my_user,
						session.prefs, local_file, remote_file, session.client);
			}

		}

		public void start_download() throws InterruptedException {
			if (isCancelled || isPaused) return;
			updateFolderStatus(this, DCConstants.DownloadStatus.STARTED);
			if (download_status == null)
				set_start_download_time();

			HubSession session = getSession(hubIp);
			if (session == null || session.client == null) {
				isCancelled = true;
				return;
			}

			DCDownloader.DownloadQueueEntity entity;
			if (activeRc != null) {
				int port = uploadManager != null ? uploadManager.getPort() : session.prefs.upload_port;
				entity = session.client.startActiveDownload(activeRc,
						(int) Constants.DOWNLOAD_TIMEOUT_MILLIS, port);
			} else {
				entity = session.client.startPassiveDownload(myrc,
						(int) Constants.DOWNLOAD_TIMEOUT_MILLIS);
			}

			if (entity.status == null || entity.status == DCConstants.DownloadStatus.UNDEFINED) {
				entity.status = DCConstants.DownloadStatus.QUEUED;
			}

			this.download_status = entity;
			this.download_status.startOffset = this.pendingStartOffset;
			if (this.download_status.downloadedSize < this.pendingStartOffset)
				this.download_status.downloadedSize = this.pendingStartOffset;
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
			final ArrayBlockingQueue<DownloadObject> q,
			final List<DownloadObject> initiated_list) {
		return new Runnable() {

			@Override
			public void run() {

				try {
					while (true) {
						DownloadObject work = q.take();
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

						while (true) {
							if (work.isCancelled || work.isPaused) break;
							updateFolderStatus(work, work.get_status());
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

								break;

							} else if (work.is_finished()) {
								work.mBuilder2.setOngoing(false);
								if (mNotifyManager2 != null && !work.isCancelled)
									mNotifyManager2.notify(work.currentID, work.mBuilder2.build());
								break;
							} else if (work.download_status != null && work.download_status.status != DCConstants.DownloadStatus.DOWNLOADING) {
								if (work.millis_elapsed() > Constants.DOWNLOAD_TIMEOUT_MILLIS) {
									work.cancel_download();
								}
							}
							
							try {
								Thread.sleep(500);
							} catch (InterruptedException e) {
								break;
							}
						}
									break;
							}
							FileSize fs = new FileSize(work.avg_speed());
							long total = work.total_bytes();
							long done = work.bytes_done();

							String progressStr = "";
							if (total > 0) {
								int p = (int) (((double) done / total) * 1000);
								work.mBuilder2.setProgress(1000, p, false);

								FileSize doneFs = new FileSize(done);
								FileSize totalFs = new FileSize(total);
								progressStr = String.format(java.util.Locale.US, " (%.2f %s / %.2f %s)",
										doneFs.fileSize, doneFs.unit, totalFs.fileSize, totalFs.unit);
							} else {
								work.mBuilder2.setProgress(0, 0, true);
							}

							if (work.download_status.status == DCConstants.DownloadStatus.QUEUED) {
								work.mBuilder2.setContentText("Queued: Waiting for slot...");
							} else {
								work.mBuilder2.setContentText(
										"Speed: "
												+ String.format(java.util.Locale.US, "%.2f",
												fs.fileSize) + " "
												+ fs.unit + "/s" + progressStr);
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
						} else if (work.download_status.status == DCConstants.DownloadStatus.COMPLETED) {
							// Do something here.
							Log.i("andcpp",
									"Download " + work.getFileName()
											+ " completed.");
							updateFolderProgress(work);

						} else if (work.download_status.status == DCConstants.DownloadStatus.INTERUPTED) {
							// Stay in queue, handled by resume
						} else {
							// Do Something here.
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

							// Auto-retry logic for file lists or specific files if desired
							// For now, just ensure it doesn't stay in a broken state
							if (work.remote_file.equalsIgnoreCase("files.xml")) {
								// We could re-queue it here if we wanted
							}
						}
					}
				} catch (InterruptedException e) {
					return;
				}
			}
		};
	}

	private ArrayBlockingQueue<DownloadObject> normal_queue;
	private ArrayList<DownloadObject> initiated_downloads;
	private Thread normal_worker;

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
		String hubIp = null;
		if (!sessions.isEmpty()) {
			hubIp = sessions.keySet().iterator().next();
		}
		download_file(hubIp, nick, local_file, remote_file, file_size, groupLabel, groupName);
	}

	public void download_file(final String hubIp, final String nick, final String local_file,
			final String remote_file, final long file_size, final String groupLabel, final String groupName) {

		downloadEnqueueExecutor.execute(() -> {
			HubSession session = getSession(hubIp);
			if (session == null || session.client == null || session.prefs == null) {
				Log.e("andcpp", "Session not fully initialized for hub " + hubIp + ", cannot start download");
				return;
			}
			final DownloadObject obj = new DownloadObject(hubIp, nick, local_file,
					remote_file, this, groupLabel, groupName);
			obj.set_total_bytes(file_size);
			synchronized (initiated_downloads) {
				initiated_downloads.add(obj);
				manageWakeLock();
			}
			try {
				if (normal_queue != null)
					normal_queue.put(obj);
			} catch (InterruptedException e) {
				Log.e("andcpp", "Interrupted while adding to queue", e);
			}
		});

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
		List<DownloadObject> all = new ArrayList<DownloadObject>();
		if (normal_queue != null) {
			for (DownloadObject o : normal_queue)
				all.add(o);
		}
		if (initiated_downloads != null) {
			synchronized (initiated_downloads) {
				all.addAll(initiated_downloads);
			}
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
						obj.mBuilder2.setProgress(1000, p, false);
						FileSize doneFs = new FileSize(done);
						FileSize totalFs = new FileSize(total);
						progressStr = String.format(java.util.Locale.US, " (%.2f %s / %.2f %s)",
								doneFs.fileSize, doneFs.unit, totalFs.fileSize, totalFs.unit);
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
				if (mNotifyManager2 != null) {
					obj.mBuilder2.setOngoing(false);
					mNotifyManager2.notify(obj.currentID, obj.mBuilder2.build());
				}
				String s = "null";
				if (obj.download_status != null && obj.download_status.status != null)
					s = obj.download_status.status.name();
				Log.d("andcpp", "download_file_list: Got download status as : " + s);
				return null;
			}
		} catch (InterruptedException e) {
			Log.d("andcpp", "DCPPService: Got an interrupted exception");
			return null;
		} catch (Exception e) {
			Log.d("andcpp", "DCPPService: Got an unknown exception in get_file_list", e);
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

	public List<String> get_saved_file_lists() {
		File file_list_dir = getFileListDir();
		File[] files = file_list_dir.listFiles();
		List<String> nicks = new ArrayList<>();
		if (files != null) {
			for (File f : files) {
				if (f.isFile() && f.getName().endsWith(".xml")) {
					String name = f.getName();
					nicks.add(name.substring(0, name.length() - 4));
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
				if (session.search_handler != null)
					session.search_handler.onCommand(dcMessage);
				session.search_results.add(dcMessage);
			}
		}
	}
	
	private class MyIncomingSearchHandler implements DCCommand {
		private String hubKey;
		public MyIncomingSearchHandler(String hubKey) { this.hubKey = hubKey; }
		@Override
		public void onCommand(DCMessage msg) {
			// Hub sent a search request. If we have the file, we reply with $SR
			if (msg.search_ip_port == null || msg.search_term == null) return;
			HubSession session = getSession(hubKey);
			if (session == null || session.client == null) return;

			Log.d("andcpp", "Received search request for: " + msg.search_term);

			// Check if we have the file
			// Search term format: F?T?0?1?searchterm
			String term = msg.search_term;
			if (term.startsWith("F?T?0?1?")) {
				term = term.substring(8);
			}
			term = term.replace('$', ' '); // libjdcpp replaces space with $ in searchForFile, so we might receive it back

			File foundFile = uploadManager.resolveSharedFile(term);
			if (foundFile != null && foundFile.exists() && foundFile.isFile()) {
				Log.d("andcpp", "Found file for search: " + foundFile.getAbsolutePath());
				try {
					String tth = com.phinmadvader.andcpp.util.TTH.calculateTTH(foundFile);
					String ourIp = session.client.getSocket().getLocalAddress().getHostAddress();
					int ourPort = uploadManager.getPort();
					
					int freeSlots = uploadManager.getFreeSlots();
					int maxSlots = uploadManager.getMaxSlots();
					String slotStr = freeSlots + "/" + maxSlots;

					String sr;
					if (session.prefs.active && uploadManager.isListening()) {
						String hubReportedIp = com.phinvader.libjdcpp.DCPreferences.get_self_ip();
						if (hubReportedIp != null && !hubReportedIp.isEmpty()) {
							ourIp = hubReportedIp;
						}
						ourPort = uploadManager.getPort();
						sr = "$SR " + session.myuser.nick + " Share\\" + foundFile.getName() + "\u0005" + foundFile.length() + " " + slotStr + " Hub:" + session.hubIp + " (" + ourIp + ":" + ourPort + ")\u0005TTH:" + tth;
					} else {
						// Passive search response or listener not ready
						sr = "$SR " + session.myuser.nick + " Share\\" + foundFile.getName() + "\u0005" + foundFile.length() + " " + slotStr + " Hub:" + session.hubIp + "\u0005TTH:" + tth;
					}
					session.client.sendRaw(sr);
				} catch (Exception e) {
					Log.e("andcpp", "Failed to send SR", e);
				}
			}
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

	private class MyHubQuitHandler implements DCCommand {
		private String hubKey;
		public MyHubQuitHandler(String hubKey) { this.hubKey = hubKey; }
		@Override
		public void onCommand(DCMessage dcMessage) {
			Log.d("andcpp", "Hub connection lost for " + hubKey + ": " + dcMessage.quit_s);
			HubSession session = getSession(hubKey);
			if (session != null) {
				handleConnectionFailure(hubKey, session, dcMessage.quit_s);
			}
		}
	}
	
	private class MyConnectToMeHandler implements DCCommand {
		private String hubKey;
		public MyConnectToMeHandler(String hubKey) { this.hubKey = hubKey; }
		@Override
		public void onCommand(DCMessage msg) {
			Log.d("andcpp", "Received ConnectToMe from " + msg.connect_nick + " at " + msg.host_name + ":" + msg.port_number);
			// This happens when WE are passive and an ACTIVE peer wants to download from us.
			// OR when we are active but the hub is proxying.
			new Thread(() -> {
				try {
					java.net.Socket s = new java.net.Socket();
					s.connect(new java.net.InetSocketAddress(msg.host_name, msg.port_number), 15000);
					Log.d("andcpp", "Connecting to peer for upload: " + msg.connect_nick + " (" + msg.host_name + ":" + msg.port_number + ")");
					uploadManager.handlePassiveUploadConnection(s);
				} catch (Exception e) {
					Log.e("andcpp", "Failed to connect to peer for upload: " + msg.host_name, e);
				}
			}).start();
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
					if (session.prefs.active && uploadManager.isListening()) {
						String ourIp = session.client.getSocket().getLocalAddress().getHostAddress();
						String hubReportedIp = com.phinvader.libjdcpp.DCPreferences.get_self_ip();
						if (hubReportedIp != null && !hubReportedIp.isEmpty()) {
							ourIp = hubReportedIp;
						}
						int ourPort = uploadManager.getPort();
						String connectStr = "$ConnectToMe " + msg.revconnect_from + " " + ourIp + ":" + ourPort;
						Log.d("andcpp", "Sending " + connectStr + " in response to RevConnectToMe (Active Mode)");
						try {
							session.client.sendRaw(connectStr);
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
			
			String[] parts = data.trim().split(" ");
			String detectedIp = null;
			if (parts.length >= 2) {
				String nick = parts[0];
				String ip = parts[1];
				HubSession session = getSession(hubKey);
				if (session != null && session.myuser != null && nick.equals(session.myuser.nick)) {
					detectedIp = ip;
				}
			} else if (parts.length == 1) {
				detectedIp = parts[0];
			}

			if (detectedIp != null && !detectedIp.isEmpty()) {
				Log.d("andcpp", "Hub reported our IP as: " + detectedIp);
				com.phinvader.libjdcpp.DCPreferences.self_ip = detectedIp;
				
				HubSession session = getSession(hubKey);
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
					
					// Re-send MyINFO and send $IP, some hubs need this to confirm Active status
					session.client.sendMyInfo(session.myuser, session.prefs);
					session.client.sendRaw("$IP " + detectedIp);
					Log.d("andcpp", "Sent $MyINFO and $IP to hub to confirm Active status");
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

	public List<DCUser> get_nick_list(String hubIp) {
		HubSession session = getSession(hubIp);
		if (session == null && hubIp != null && !hubIp.contains(":")) {
			for (HubSession s : sessions.values()) {
				if (s.hubIp.equals(hubIp)) {
					session = s;
					break;
				}
			}
		}
		if (session == null && !sessions.isEmpty()) {
			session = sessions.values().iterator().next();
		}
		return session != null && session.client != null ? session.client.get_nick_list() : new ArrayList<>();
	}

	public List<String> get_board_messages(String hubIp) {
		ArrayList<String> ret = new ArrayList<String>();
		HubSession session = getSession(hubIp);
		if (session == null && hubIp != null && !hubIp.contains(":")) {
			for (HubSession s : sessions.values()) {
				if (s.hubIp.equals(hubIp)) {
					session = s;
					break;
				}
			}
		}
		if (session == null && !sessions.isEmpty()) {
			session = sessions.values().iterator().next();
		}
		if (session != null && session.client != null) {
			for(DCMessage msg: session.client.getBoardMessages()) {
				ret.add(msg.msg_s);
			}
		}
		return ret;
	}

	public List<String> get_board_messages() {
		return get_board_messages(null);
	}

	public DCPPService() {
		super();
		initiated_downloads = new ArrayList<DownloadObject>();
		normal_queue = new ArrayBlockingQueue<DownloadObject>(
				Constants.MAX_DOWNLOAD_Q);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		if (initiated_downloads == null)
			initiated_downloads = new ArrayList<DownloadObject>();
		if (normal_queue == null)
			normal_queue = new ArrayBlockingQueue<DownloadObject>(Constants.MAX_DOWNLOAD_Q);
		mNotifyManager2 = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		
		        uploadManager = new UploadManager(this);
        // Do not start listening yet if PIA is enabled. Wait for onPortBound.
        if (!com.phinmadvader.andcpp.pia.PIAPortForwardingManager.getInstance(this).isEnabled()) {
            int savedPort = Settings.getListeningPort(this);
            uploadManager.startListening(savedPort);
        }
        
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
		
		com.phinmadvader.andcpp.pia.PIAPortForwardingManager.getInstance(this).setListener(new com.phinmadvader.andcpp.pia.PIAPortForwardingManager.PortForwardingListener() {
			@Override
			public void onPortBound(String port) {
				handlePIAPortBound(port);
			}

			@Override
			public void onPortError() {
				Log.e("andcpp", "PIA Port Binding failed.");
			}
		});

		networkReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
					ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
					NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
					if (activeNetwork != null && activeNetwork.isConnected()) {
						Log.d("andcpp", "Network connected, triggering reconnection logic");
						triggerReconnections();
					}
				}
			}
		};
		registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
	}

	private void triggerReconnections() {
		com.phinmadvader.andcpp.pia.PIAPortForwardingManager piaManager = com.phinmadvader.andcpp.pia.PIAPortForwardingManager.getInstance(this);
		if (piaManager.isEnabled()) {
			piaManager.triggerRebind();
		}

		for (Map.Entry<String, HubSession> entry : sessions.entrySet()) {
			HubSession session = entry.getValue();
			if (session.status == DCClientStatus.RECONNECTING || session.status == DCClientStatus.DISCONNECTED || session.status == DCClientStatus.INVALIDIP) {
				session.reconnectAttempts = 0; // Reset backoff
				connectToHub(entry.getKey(), session);
			}
		}
	}

	private void handlePIAPortBound(String portStr) {
		int port = Integer.parseInt(portStr);
		Log.i("andcpp", "PIA Port bound (" + port + "), restarting UploadManager and updating active sessions.");
		
		if (uploadManager != null) {
			uploadManager.startListening(port);
		}

		for (HubSession session : sessions.values()) {
			if (session.status == DCClientStatus.CONNECTED && session.client != null && session.prefs != null && session.prefs.active) {
				session.prefs.upload_port = port;
				session.myuser.tag = null; // force tag regeneration
				try {
					session.client.sendMyInfo(session.myuser, session.prefs);
				} catch (Exception e) {
					Log.e("andcpp", "Failed to send updated MyINFO for PIA port on hub " + session.hubIp, e);
				}
			}
		}
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

	private void startKeepAliveTimer() {
		if (keepAliveTimer != null) keepAliveTimer.cancel();
		// A Handler on the main looper or AlarmManager is better than Timer, but we hold a PARTIAL_WAKE_LOCK.
		keepAliveTimer = new Timer();
		keepAliveTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				try {
					for (HubSession session : sessions.values()) {
						if (session.status == DCClientStatus.CONNECTED && session.client != null) {
							try {
								// Send a keep-alive (empty command ending in |) to prevent socket timeouts
								session.client.sendRaw("");
							} catch (Exception e) {
								Log.e("andcpp", "Failed to send keep-alive to " + session.hubIp);
							}
						}
					}
					if (uploadManager != null) {
						uploadManager.cleanupZombieConnections();
					}
				} catch (Exception e) {
					Log.e("andcpp", "Exception in keepAliveTimer task", e);
				}
			}
		}, 60000, 60000); // Every 60 seconds
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

		String hubKey = ip + ":" + port;

		// Establish foreground status to satisfy Android 8.0+ requirements
		ensureForeground(ip);

		if (!sessions.containsKey(hubKey)) {
			final HubSession session = new HubSession(ip, port);
			sessions.put(hubKey, session);
			session.status = DCClientStatus.CONNECTING;
			
			// Load preferences
			SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, 0);
			boolean activeMode = Settings.getActiveMode(this);
			int uploadPort = Settings.getListeningPort(this);

			if (uploadManager == null) {
				uploadManager = new UploadManager(DCPPService.this);
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
			session.prefs = new DCPreferences(nick, sharedSize, ip, activeMode, uploadPort);
			session.myuser = new DCUser();
			session.myuser.nick = nick;
			session.myuser.active = activeMode;

			connectToHub(hubKey, session);
		} else {
			Log.e("andcpp", "Already connecting/connected to " + hubKey);
			mySendBroadcast(ip, false, true);
		}

		return START_STICKY;
	}

	private void connectToHub(final String hubKey, final HubSession session) {
		session.status = DCClientStatus.CONNECTING;
		new Thread(() -> {
			try {
				if (session.client != null) {
					session.client.close();
				}
				session.client = new DCClient();
				session.client.connect(session.hubIp, session.hubPort, session.prefs);
				
				session.client.bootstrap(session.myuser);
				session.client.setCustomUserChangeHandler(new MyUserHandler(hubKey));
				session.client.setCustomBoardMessageHandler(new MyBoardMessageHandler(hubKey));
				session.client.setCustomSearchHandler(new MySearchHandler(hubKey));
				session.client.setCustomRawHandler("HubName", dcMessage -> {
					if (dcMessage.hubname_s != null) {
						session.hubName = dcMessage.hubname_s;
						mySendBroadcast(session.hubIp, false, true);
					}
				});
				session.client.setCustomRawHandler("Search", new MyIncomingSearchHandler(hubKey));
				session.client.setCustomRawHandler("HubQuit", new MyHubQuitHandler(hubKey));
				session.client.setCustomRawHandler("RevConnectToMe", new MyRevConnectHandler(hubKey));
				session.client.setCustomRawHandler("ConnectToMe", new MyConnectToMeHandler(hubKey));
				session.client.setCustomRawHandler("UserIP", new MyUserIPHandler(hubKey));
				session.client.setCustomRawHandler("MyINFO", new MyUserInfoHandler(hubKey));
				session.client.InitiateDefaultRouting();
				
				session.status = DCClientStatus.CONNECTED;
				session.reconnectAttempts = 0; // Reset on success
				ensureForeground(session.hubIp);
				mySendBroadcast(session.hubIp, false, true);
				manageWakeLock();

				if (normal_worker == null || !normal_worker.isAlive()) {
					normal_worker = new Thread(generate_worker(normal_queue,
							initiated_downloads));
					normal_worker.start();
				}
			} catch (Exception e) {
				Log.e("andcpp", "Connection failed for " + session.hubIp + ":" + session.hubPort, e);
				handleConnectionFailure(hubKey, session, e.getMessage() != null ? e.getMessage() : e.toString());
			}
		}).start();
	}
	
	private void handleConnectionFailure(final String hubKey, final HubSession session, String message) {
		session.reconnectAttempts++;
		
		session.status = DCClientStatus.RECONNECTING;
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
					if (normal_worker != null)
						normal_worker.interrupt();
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

	public void make_search(String hubKey, String searchtext) {
		HubSession session = getSession(hubKey);
		if (session != null && session.client != null) {
			session.search_results.clear();
			Log.d("andcpp", "dcpp_service Making search request to " + session.hubIp + ":" + session.hubPort + ": " + searchtext);
			session.client.searchForFile(searchtext, session.myuser, 1);
		}
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
		return uploadManager.getUploadStats();
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
		saveDownloadsState();
		
		PIAPortForwardingManager.getInstance(this).stopPortForwarding();
		
		// Let's shutdown connection(if any) if app get's unexpectedly killed.
		if (keepAliveTimer != null) {
			keepAliveTimer.cancel();
			keepAliveTimer = null;
		}
		if (networkReceiver != null) {
			unregisterReceiver(networkReceiver);
			networkReceiver = null;
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
		shutdown();
		Log.d("andcpp", "destroyed!");
	}

	private void saveDownloadsState() {
		try {
			SharedPreferences prefs = getSharedPreferences("dcpp_downloads_state", Context.MODE_PRIVATE);
			SharedPreferences.Editor editor = prefs.edit();
			
			List<DownloadItem> queue = get_download_queue();
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
							count++;
						}
					}
				}
			}
			editor.putInt("download_count", count);
			editor.apply();
			Log.d("andcpp", "Saved " + count + " active/queued/incomplete downloads state.");
		} catch (Exception e) {
			Log.e("andcpp", "Error saving downloads state", e);
		}
	}

	private void restoreDownloadsState() {
		try {
			SharedPreferences prefs = getSharedPreferences("dcpp_downloads_state", Context.MODE_PRIVATE);
			int count = prefs.getInt("download_count", 0);
			if (count <= 0) return;

			Log.d("andcpp", "Restoring " + count + " downloads state...");
			for (int i = 0; i < count; i++) {
				String hubIp = prefs.getString("hubIp_" + i, null);
				String nick = prefs.getString("target_nick_" + i, null);
				String localFile = prefs.getString("local_file_" + i, null);
				String remoteFile = prefs.getString("remote_file_" + i, null);
				long fileSize = prefs.getLong("file_size_" + i, 0);
				String groupLabel = prefs.getString("group_label_" + i, null);
				String groupName = prefs.getString("group_name_" + i, null);
				long bytesDone = prefs.getLong("bytes_done_" + i, 0);
				boolean isPaused = prefs.getBoolean("is_paused_" + i, false);

				if (localFile != null && remoteFile != null && nick != null) {
					// Check if file exists or can be resumed
					File f = new File(localFile);
					long existingLength = f.exists() ? f.length() : bytesDone;

					// If we have hubIp == null or not in sessions, use first available or default
					final String targetHubIp;
					if (hubIp == null || getSession(hubIp) == null) {
						if (!sessions.isEmpty()) {
							targetHubIp = sessions.keySet().iterator().next();
						} else {
							targetHubIp = hubIp;
						}
					} else {
						targetHubIp = hubIp;
					}

					if (targetHubIp != null) {
						downloadEnqueueExecutor.execute(() -> {
							HubSession session = getSession(targetHubIp);
							if (session == null || session.client == null || session.prefs == null) {
								Log.e("andcpp", "Session not fully initialized for restored download hub " + targetHubIp);
								return;
							}
							final DownloadObject obj = new DownloadObject(targetHubIp, nick, localFile,
									remoteFile, this, groupLabel, groupName);
							obj.set_total_bytes(fileSize);
							obj.pendingStartOffset = existingLength;
							if (isPaused) {
								obj.isPaused = true;
							}
							synchronized (initiated_downloads) {
								initiated_downloads.add(obj);
								manageWakeLock();
							}
							if (!isPaused) {
								try {
									if (normal_queue != null)
										normal_queue.put(obj);
								} catch (InterruptedException e) {
									Log.e("andcpp", "Interrupted while adding restored download to queue", e);
								}
							}
						});
					}
				}
			}
			// Clear prefs after restore so we don't duplicate on subsequent restarts unless saved again
			prefs.edit().clear().apply();
		} catch (Exception e) {
			Log.e("andcpp", "Error restoring downloads state", e);
		}
	}

	
	
	
	
}
