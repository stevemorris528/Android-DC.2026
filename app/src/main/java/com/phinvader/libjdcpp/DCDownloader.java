package com.phinvader.libjdcpp;

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
		public volatile long downloadedSize = 0;
		public volatile long expectedDownloadSize = 0;
		public volatile long startOffset = 0;
		public volatile DCConstants.DownloadStatus status;

	}

	public HashMap<String, ArrayList<DownloadQueueEntity>> downloadQ;

	public void initDownloadQ() {
		this.downloadQ = new HashMap<String, ArrayList<DownloadQueueEntity>>();
	}

	public DownloadQueueEntity addDownloadEntity(String nick , DownloadQueueEntity entity) {
		if (downloadQ == null) initDownloadQ();
		ArrayList<DownloadQueueEntity> list = downloadQ.get(nick);
		if(list == null){
			list = new ArrayList<DCDownloader.DownloadQueueEntity>();
			downloadQ.put(nick, list);
		}
		// Avoid stacking duplicate pending requests for the same file
		for (DownloadQueueEntity e : list) {
			if (e.remote_filename.equals(entity.remote_filename)) {
				return e;
			}
		}
		list.add(entity);
		return entity;
	}

	public DownloadQueueEntity getDownloadEntity(String nick) {
		if(downloadQ == null)
			return null;
		ArrayList<DownloadQueueEntity> list = downloadQ.get(nick);
		if(list == null || list.isEmpty())
			return null;
		DownloadQueueEntity ret = list.get(0);
		list.remove(ret);
		return ret;
	}


	public boolean hasAnyDownloadEntities() {
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
		try {
			// At this point Handler has been associated with a socket
			// MyNick and Lock have been sent.
			// Send Direction and Key

			handler.send_direction(true);
			handler.send_key(DCFunctions.convert_lock_to_key(rlock.lock_s
					.getBytes()));

			// Notify target_user about file to download
			handler.send_msg("$Get " + fname + "$" + (entity.startOffset + 1));
			
			entity.status = DownloadStatus.INITIATED;

			// Wait (bounded) for the peer's answer to $Get, skipping handshake chatter.
			DCMessage msg2 = null;
			long startTime = System.currentTimeMillis();
			while (System.currentTimeMillis() - startTime < 20000) {
				if (Thread.currentThread().isInterrupted())
					throw new InterruptedException("download thread interrupted");
				DCMessage m = handler.getNextMessage(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
				if (m == null || m.command == null) continue;
				if ("HubQuit".equals(m.command) || "FileLength".equals(m.command)
						|| "MaxedOut".equals(m.command) || "Error".equals(m.command)) {
					msg2 = m;
					break;
				}
			}

			if (msg2 == null || !"FileLength".equals(msg2.command)) {
				entity.status = DownloadStatus.FAILED;
				DCLogger.Log("Download failed: Expected FileLength, got " + (msg2 == null ? "no answer (timeout)"
						: msg2.command + ("Error".equals(msg2.command) ? " " + msg2.msg_s : "")));
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

			// Switch the reader to raw file mode BEFORE asking for data, so no file byte is parsed as protocol.
			handler.dump_remaining_stream(save_file_name, msg2.file_length, entity.startOffset > 0);

			// Request Start Download
			handler.send_msg("$Send");
			entity.status = DownloadStatus.DOWNLOADING;
			while (true) {
				if (Thread.currentThread().isInterrupted())
					throw new InterruptedException("download thread interrupted");
				DCMessage msg = handler.getNextMessage(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
				if (msg != null && msg.command != null && msg.command.equals("HubQuit"))
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
			// Stopped from outside (pause, stall restart, shutdown): never leave an "active" status behind.
			markStoppedIfActive(entity);
			handler.close();
			DCLogger.Log("Download interrupted: " + save_file_name);
			return false;
		}
		return true;
	}

	/** Turns a leftover INITIATED/STARTED/DOWNLOADING status into INTERUPTED so the next attempt is not blocked. */
	public static void markStoppedIfActive(DownloadQueueEntity entity) {
		if (entity == null) return;
		DownloadStatus st = entity.status;
		if (st == DownloadStatus.INITIATED || st == DownloadStatus.STARTED || st == DownloadStatus.DOWNLOADING) {
			entity.status = DownloadStatus.INTERUPTED;
		}
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
