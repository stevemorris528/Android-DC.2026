package com.phinvader.libjdcpp;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

import com.phinvader.libjdcpp.DCConstants.DownloadStatus;

public class DCDownloader {
	
	public static class DownloadQueueEntity{
		public DCUser target_user;
		public DCUser my_user;
		public String remote_filename;
		public String local_filename;
		public MessageHandler connectionHandler;
		public DownloadQueueEntity(DCUser target_user, DCUser my_user,
				String remote_filename, String local_filename) {
			super();
			this.target_user = target_user;
			this.my_user = my_user;
			this.remote_filename = remote_filename;
			this.local_filename = local_filename;
		}

		public Thread thread;
		public long downloadedSize = 0;
		public long expectedDownloadSize = 0;
		public long startOffset = 0;
		public DCConstants.DownloadStatus status;
		/** Set when the peer said the file will never be served (e.g. "File Not Available"); do not auto-retry. */
		public volatile boolean permanentError = false;
		/**
		 * True if the latest request for this download was $RevConnectToMe (the peer connects to us via its own
		 * $ConnectToMe), false if it was our $ConnectToMe (the peer connects straight to our listening port).
		 * A $ConnectToMe from the peer only answers a download that asked with $RevConnectToMe.
		 */
		public volatile boolean revConnectSent = false;
		/** ADC downloads: the client-to-client connection, so pause / cancel / stall restarts can close it. */
		public volatile java.io.Closeable adcConnection = null;
	}

	private HashMap<String, ArrayList<DownloadQueueEntity>> downloadQ;

	public synchronized void initDownloadQ() {
		if (this.downloadQ == null) {
			this.downloadQ = new HashMap<String, ArrayList<DownloadQueueEntity>>();
		}
	}

	public synchronized DownloadQueueEntity addDownloadEntity(String nick , DownloadQueueEntity entity) {
		if (downloadQ == null) initDownloadQ();
		ArrayList<DownloadQueueEntity> list = downloadQ.get(nick);
		if(list == null){
			list = new ArrayList<DCDownloader.DownloadQueueEntity>();
			downloadQ.put(nick, list);
		}
		int sizeBefore = list.size();
		String remoteFile = entity != null ? entity.remote_filename : "null";
		// Avoid stacking duplicate pending requests for the same file
		if (entity != null) {
			for (DownloadQueueEntity e : list) {
				if (e.remote_filename != null && e.remote_filename.equals(entity.remote_filename)) {
					Log.d("andcpp", "[DLTRACE] DCDownloader.addDownloadEntity REUSED existing entity: nick=" + nick + ", remoteFile=" + remoteFile + ", listSizeBefore=" + sizeBefore + ", listSizeAfter=" + list.size());
					return e;
				}
			}
			list.add(entity);
		}
		Log.d("andcpp", "[DLTRACE] DCDownloader.addDownloadEntity ADDED new entity: nick=" + nick + ", remoteFile=" + remoteFile + ", listSizeBefore=" + sizeBefore + ", listSizeAfter=" + list.size());
		return entity;
	}

	public synchronized DownloadQueueEntity getDownloadEntity(String nick) {
		if(downloadQ == null) {
			Log.d("andcpp", "[DLTRACE] DCDownloader.getDownloadEntity: nick=" + nick + ", downloadQ is null");
			return null;
		}
		ArrayList<DownloadQueueEntity> list = downloadQ.get(nick);
		int sizeBefore = (list != null ? list.size() : 0);
		if(list == null || list.isEmpty()) {
			Log.d("andcpp", "[DLTRACE] DCDownloader.getDownloadEntity: nick=" + nick + ", list empty or null, sizeBefore=" + sizeBefore);
			return null;
		}
		DownloadQueueEntity ret = list.get(0);
		list.remove(ret);
		ret.status = DownloadStatus.INITIATED; // claimed by a connection: no longer pending
		Log.d("andcpp", "[DLTRACE] DCDownloader.getDownloadEntity: nick=" + nick + ", retrieved entity remoteFile=" + (ret != null ? ret.remote_filename : "null") + ", listSizeBefore=" + sizeBefore + ", listSizeAfter=" + list.size());
		return ret;
	}

	public synchronized DownloadQueueEntity getDownloadEntityCaseInsensitive(String nick) {
		if (downloadQ == null || nick == null) return null;
		String matchedKey = null;
		for (String key : downloadQ.keySet()) {
			if (key != null && key.equalsIgnoreCase(nick)) {
				matchedKey = key;
				break;
			}
		}
		if (matchedKey == null) return null;
		ArrayList<DownloadQueueEntity> list = downloadQ.get(matchedKey);
		int sizeBefore = (list != null ? list.size() : 0);
		if (list == null || list.isEmpty()) return null;
		DownloadQueueEntity ret = list.get(0);
		list.remove(ret);
		ret.status = DownloadStatus.INITIATED; // claimed by a connection: no longer pending
		Log.d("andcpp", "[DLTRACE] DCDownloader.getDownloadEntityCaseInsensitive: nick=" + nick + (matchedKey.equals(nick) ? "" : " (matched key " + matchedKey + ")") + ", retrieved entity remoteFile=" + (ret != null ? ret.remote_filename : "null") + ", listSizeBefore=" + sizeBefore + ", listSizeAfter=" + list.size());
		return ret;
	}

	/**
	 * For an incoming $ConnectToMe from {@code nick}: claims the first pending download from that peer that we
	 * requested with $RevConnectToMe, or returns null. Downloads requested with our own $ConnectToMe are left
	 * alone: the peer's $ConnectToMe is then its own download request, to be served as an upload. (Taking the
	 * connection for our download used to starve the peer's download whenever ours was pending.)
	 */
	public synchronized DownloadQueueEntity claimRevConnectAnswer(String nick) {
		if (downloadQ == null || nick == null) return null;
		for (java.util.Map.Entry<String, ArrayList<DownloadQueueEntity>> en : downloadQ.entrySet()) {
			if (en.getKey() == null || !en.getKey().equalsIgnoreCase(nick) || en.getValue() == null) continue;
			for (DownloadQueueEntity e : en.getValue()) {
				if (e.revConnectSent) {
					en.getValue().remove(e);
					e.status = DownloadStatus.INITIATED; // claimed by a connection: no longer pending
					Log.d("andcpp", "[DLTRACE] DCDownloader.claimRevConnectAnswer: nick=" + nick
							+ ", claimed remoteFile=" + e.remote_filename + ", left=" + en.getValue().size());
					return e;
				}
			}
		}
		return null;
	}

	public synchronized boolean isEntityPending(String nick, DownloadQueueEntity entity) {
		if (downloadQ == null || entity == null || nick == null) return false;
		ArrayList<DownloadQueueEntity> list = downloadQ.get(nick);
		if (list == null) return false;
		return list.contains(entity);
	}

	public synchronized boolean hasPendingFor(String nick) {
		if (downloadQ == null || nick == null) return false;
		for (java.util.Map.Entry<String, ArrayList<DownloadQueueEntity>> en : downloadQ.entrySet()) {
			if (en.getKey() != null && en.getKey().equalsIgnoreCase(nick)
					&& en.getValue() != null && !en.getValue().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	public synchronized boolean removeDownloadEntity(String nick, DownloadQueueEntity entity) {
		if (downloadQ == null || nick == null || entity == null) return false;
		boolean removed = false;
		for (java.util.Map.Entry<String, ArrayList<DownloadQueueEntity>> en : downloadQ.entrySet()) {
			if (en.getKey() != null && en.getKey().equalsIgnoreCase(nick) && en.getValue() != null) {
				removed |= en.getValue().remove(entity);
			}
		}
		return removed;
	}

	public synchronized boolean hasAnyDownloadEntities() {
		if (downloadQ == null) return false;
		for (ArrayList<DownloadQueueEntity> list : downloadQ.values()) {
			if (list != null && !list.isEmpty()) return true;
		}
		return false;
	}



	/**
	 * 
	 * @param nick
	 *            - Nick to download from
	 * @param s
	 *            - Socket connection
	 * @param remote_filename
	 *            - Path to the file on remote user, from where to be downloaded
	 * @param local_filename
	 *            - Path where the downloaded file should be saved
	 * @return
	 */
	//
	private boolean download_file(DownloadQueueEntity entity, DCMessage rlock,
			MessageHandler handler) {
		// Using REVCONNECT
		String fname = entity.remote_filename;
		String save_file_name = entity.local_filename;
		entity.connectionHandler = handler;
		entity.status = DownloadStatus.INITIATED; // covers the handshake window for re-signal guards
		try {
			// At this point Handler has been associated with a socket
			// MyNick and Lock have been sent.
			// Send Direction and Key

			handler.send_direction(true);
			handler.send_key(DCFunctions.convert_lock_to_key(rlock.lock_s
					.getBytes()));

			// The local file or its folder may have been deleted since the download was queued (e.g. the share
			// folder was cleaned): recreate the folder, and never resume past what is really on disk, or the rest
			// of the file would be written into a new empty file and the result would be corrupt.
			java.io.File localFile = new java.io.File(save_file_name);
			java.io.File localDir = localFile.getParentFile();
			if (localDir != null && !localDir.isDirectory() && !localDir.mkdirs()) {
				Log.w("andcpp", "[DLTRACE] could not create folder " + localDir.getPath());
			}
			long onDisk = localFile.isFile() ? localFile.length() : 0;
			if (entity.startOffset > onDisk) {
				Log.w("andcpp", "[DLTRACE] " + localFile.getName() + ": only " + onDisk + " bytes on disk, not "
						+ entity.startOffset + "; downloading from " + onDisk);
				entity.startOffset = onDisk;
				entity.downloadedSize = onDisk;
			}

			// Notify target_user about file to download
			handler.send_msg("$Get " + fname + "$" + (entity.startOffset + 1));
			
			DCMessage msg2 = null;
			long startTime = System.currentTimeMillis();
			while (System.currentTimeMillis() - startTime < 20000) {
				DCMessage m = handler.getNextMessage(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
				if (m == null || m.command == null) continue;
				if ("HubQuit".equals(m.command)) {
					msg2 = m;
					break;
				}
				if ("FileLength".equals(m.command) || "MaxedOut".equals(m.command) || "Error".equals(m.command)) {
					msg2 = m;
					break;
				}
			}

			if (msg2 == null) {
				entity.status = DownloadStatus.FAILED;
				DCLogger.Log("Download failed: No response received from peer");
				handler.close();
				return false;
			}

			if ("MaxedOut".equals(msg2.command)) {
				entity.status = DownloadStatus.WAITING_FOR_PEER;
				DCLogger.Log("Download remote queued / maxed out from peer " + (entity.target_user != null ? entity.target_user.nick : "unknown"));
				handler.close();
				return false;
			}

			if ("Error".equals(msg2.command)) {
				entity.status = DownloadStatus.FAILED;
				if (msg2.error_s != null && msg2.error_s.toLowerCase(java.util.Locale.ROOT).contains("not available")) {
					entity.permanentError = true;
				}
				DCLogger.Log("Download failed with error from peer: " + msg2.error_s);
				handler.close();
				return false;
			}

			if (!"FileLength".equals(msg2.command)) {
				entity.status = DownloadStatus.FAILED;
				DCLogger.Log("Download failed: Expected FileLength, got " + msg2.command);
				handler.close();
				return false;
			}

			// Update filesize
			entity.expectedDownloadSize = msg2.file_length + entity.startOffset;

			// Associate ENTITY to HANDLER to monitor progress
			// Done esp. for downloadedSize
			handler.setDownloadEntity(entity);

			// Download Started
			entity.status = DownloadStatus.STARTED;

			// Switch stream to raw mode BEFORE sending $Send
			handler.dump_remaining_stream(save_file_name, msg2.file_length, entity.startOffset > 0);

			// Request Start Download
			handler.send_msg("$Send");
			while (true) {
				entity.status = DownloadStatus.DOWNLOADING;
				DCMessage msg = handler.getNextMessage();
				if (msg.command != null && msg.command.equals("HubQuit"))
					break;
			}

			// Download COMPLETE
			long dumped = handler.get_dumped_bytes();
			long expected = handler.get_filesize();
			if (expected > 0 && dumped >= expected) {
				entity.status = DownloadStatus.COMPLETED;
			} else if (Thread.currentThread().isInterrupted()) {
				entity.status = DownloadStatus.INTERUPTED;
			} else if (dumped > 0) {
				// We transferred data but connection ended prematurely: treat as INTERUPTED to allow resume
				entity.status = DownloadStatus.INTERUPTED;
			} else {
				entity.status = DownloadStatus.FAILED;
			}
			handler.close();
			DCLogger.Log("Download Complete" + save_file_name
					+ entity.status.toString());

		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		return true;
	}

	/**
	 * Download Manager will do the downloading in a separate thread to keep the
	 * UI Responsive
	 * 
	 * @author madhavan
	 * 
	 */

	public class downloadManager implements Runnable {

		private DCMessage rlock;
		private MessageHandler messageHandler;
		private DownloadQueueEntity requestEntity;

		public downloadManager(DownloadQueueEntity requestEntity,
				DCMessage rlock,
				MessageHandler handler) {
			super();
			this.rlock = rlock;
			this.messageHandler = handler;
			this.requestEntity = requestEntity;
		}

		@Override
		public void run() {
			download_file(requestEntity, rlock, messageHandler);

		}

	}

}
