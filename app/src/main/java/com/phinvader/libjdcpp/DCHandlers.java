package com.phinvader.libjdcpp;

import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public class DCHandlers {

	public static interface Downloader {
		public String remote_filename = null;
		public String local_filename = null;

	}

	public interface UploadHandlerCallback {
		/**
		 * Called when we connected out after a $ConnectToMe, exchanged $MyNick/$Lock, and found no pending
		 * download for that peer: the SAME socket and MessageHandler continue as an upload connection.
		 */
		void handleHandshakenUploadConnection(Socket s, MessageHandler h, String hisNick, String hisLock);
	}

	private static volatile UploadHandlerCallback uploadCallback = null;

	public static void setUploadCallback(UploadHandlerCallback callback) {
		uploadCallback = callback;
	}

	/**
	 * DC++-type uploaders give a free or granted slot to a queued user by connecting to it themselves
	 * ($ConnectToMe / $RevConnectToMe), usually while our download from them is parked between attempts, so no
	 * request of ours is pending. This lets such a connection ($Direction Upload) carry that download.
	 */
	public interface PeerConnectionAdopter {
		/** True if we have an unfinished download from this peer that could use a connection now. */
		boolean hasResumableDownloadFrom(String peerNick);

		/** Prepares that download for this connection and returns its entity, or null if there is none. */
		DCDownloader.DownloadQueueEntity adoptPeerConnection(String peerNick);
	}

	private static volatile PeerConnectionAdopter connectionAdopter = null;

	public static void setConnectionAdopter(PeerConnectionAdopter adopter) {
		connectionAdopter = adopter;
	}

	/**
	 * The only consumer of $ConnectToMe. In NMDC msg.connect_nick is the RECIPIENT (our own nick), so the peer is
	 * identified from its $MyNick during the handshake, never from connect_nick.
	 */
	public static class MainDownloadHandler implements DCCommand {

		private DCUser my_user;
		private DCClient client;
		private DCDownloader downloaderManager;

		public MainDownloadHandler(DCUser my_user, DCDownloader downloader,
				DCClient client) {
			super();
			this.my_user = my_user;
			this.client = client;
			this.downloaderManager = downloader;
		}

		@Override
		public void onCommand(final DCMessage msg) {
			// Blocking connect/handshake must never run on the hub message router thread.
			new Thread(new Runnable() {
				@Override
				public void run() {
					handleConnectToMe(msg);
				}
			}, "ConnectToMe-handler").start();
		}

		private void handleConnectToMe(DCMessage msg) {
			String ourNick = (my_user != null && my_user.nick != null) ? my_user.nick : "unknown";
			android.util.Log.d("andcpp", "[DLTRACE] ConnectToMe handler: connect_nick=" + msg.connect_nick
					+ " (recipient, ours=" + ourNick + "), peer=" + msg.host_name + ":" + msg.port_number);
			DCLogger.Log("in ConnectToMe handler: " + msg.toString());

			Socket s = null;
			MessageHandler h = null;
			try {
				s = new Socket();
				s.connect(new java.net.InetSocketAddress(msg.host_name, msg.port_number), 15000);
				s.setSoTimeout(30000);
				h = new MessageHandler(s);

				// We connected out: we speak first ($MyNick, $Lock).
				h.send_mynick(my_user);
				h.send_lock();

				DCMessage hisNick = null;
				DCMessage rlock = null;
				long startTime = System.currentTimeMillis();
				while ((hisNick == null || rlock == null) && System.currentTimeMillis() - startTime < 20000) {
					DCMessage m = h.getNextMessage(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
					if (m == null) continue;
					if ("HubQuit".equals(m.command)) break;
					if ("MyNick".equals(m.command)) hisNick = m;
					if ("Lock".equals(m.command)) rlock = m;
				}

				if (hisNick == null || hisNick.hisinfo == null || hisNick.hisinfo.nick == null || rlock == null) {
					android.util.Log.w("andcpp", "[DLTRACE] ConnectToMe handshake incomplete with " + msg.host_name + ":" + msg.port_number);
					h.close();
					return;
				}

				String peerNick = hisNick.hisinfo.nick;
				// A peer that answers our $RevConnectToMe says "$Direction Upload". One that connected because IT
				// wants to download from us says "Download" (or sends its request). With downloads pending both
				// ways (we're passive towards this peer), taking every connection for our download starved the
				// peer: its file list / downloads from us only ever got "Connecting" / "Timed out". So look at
				// the peer's $Direction first, as the incoming-connection path does.
				// A peer that grants us a slot connects for our download while it is parked (nothing pending): it
				// says "$Direction Upload" too, so look at the direction when we have any unfinished download from it.
				java.util.List<DCMessage> early = new java.util.ArrayList<DCMessage>();
				boolean peerWantsToDownload = false;
				boolean peerSaidUpload = false;
				boolean pending = downloaderManager.hasPendingFor(peerNick);
				PeerConnectionAdopter adopter = connectionAdopter;
				boolean resumable = !pending && adopter != null && adopter.hasResumableDownloadFrom(peerNick);
				if (pending || resumable) {
					long dirDeadline = System.currentTimeMillis() + 5000;
					while (System.currentTimeMillis() < dirDeadline) {
						DCMessage m = h.getNextMessage(500, java.util.concurrent.TimeUnit.MILLISECONDS);
						if (m == null) continue;
						if ("HubQuit".equals(m.command)) throw new java.io.IOException("Peer closed connection during handshake");
						early.add(m);
						if ("Direction".equals(m.command)) {
							peerWantsToDownload = m.dir_download;
							peerSaidUpload = !m.dir_download;
							break;
						}
						if ("Get".equals(m.command) || "ADCGET".equals(m.command)) {
							peerWantsToDownload = true;
							break;
						}
					}
				}
				DCDownloader.DownloadQueueEntity entity = null;
				if (!peerWantsToDownload && pending) {
					entity = downloaderManager.claimRevConnectAnswer(peerNick);
					if (entity == null && peerSaidUpload) {
						// Our $ConnectToMe is pending, but the peer connected for it through its own $ConnectToMe
						// (e.g. it answered after we parked and asked again, or it grants a slot): still ours.
						entity = downloaderManager.getDownloadEntityCaseInsensitive(peerNick);
					}
				}
				if (entity == null && peerSaidUpload && adopter != null) {
					entity = adopter.adoptPeerConnection(peerNick); // a parked download (e.g. after "no slots")
				}
				if (peerWantsToDownload) {
					android.util.Log.d("andcpp", "[DLTRACE] ConnectToMe: " + peerNick + " wants to download from us:"
							+ " serving it as an upload (our download from " + peerNick + " stays queued)");
				}
				// Give the messages read above back to whoever handles the connection now (same order).
				for (DCMessage m : early) h.addMessage(m);
				android.util.Log.d("andcpp", "[DLTRACE] ConnectToMe: peer=" + peerNick + ", direction="
						+ (peerSaidUpload ? "Upload" : peerWantsToDownload ? "Download" : "unknown")
						+ ", pending=" + pending + ", resumable=" + resumable + ", downloadFound=" + (entity != null));

				if (entity != null) {
					client.startDownloadingFile(entity, client, rlock, h);
					return;
				}

				UploadHandlerCallback cb = uploadCallback;
				if (cb != null) {
					// Hand over the same, already handshaken connection instead of closing and reconnecting.
					cb.handleHandshakenUploadConnection(s, h, peerNick, rlock.lock_s);
				} else {
					h.close();
				}
			} catch (Exception e) {
				DCLogger.Log("ERROR - ConnectToMe connection failed: " + e.toString());
				if (h != null) {
					h.close();
				} else if (s != null) {
					try { s.close(); } catch (Exception ignored) {}
				}
			}
		}
	}

	public static class UnsubscriptionHandler implements Runnable {

		private DCClient client;
		private DCCommand handler;
		private int timeout;

		public UnsubscriptionHandler(DCClient client, DCCommand handler,
				int timeout) {
			super();
			this.client = client;
			this.handler = handler;
			this.timeout = timeout;
		}

		@Override
		public void run() {
			try {
				Thread.sleep(timeout);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			client.unsetPassiveDownloadHandler(handler);

		}

	}

	public static class BoardMessageHandler implements DCCommand {

		List<DCMessage> listOfMessages = new ArrayList<DCMessage>();

		@Override
		public void onCommand(DCMessage msg) {
			listOfMessages.add(msg);
		}

		public List<DCMessage> getLatestMessages() {
			return listOfMessages;
		}

		public List<DCMessage> getLatestMessages(int limit) {
			int lowerBound = listOfMessages.size() - 1 - limit;
			int upperBound = listOfMessages.size() - 1;
			if (limit > listOfMessages.size()) {
				lowerBound = 0;
			}
			//TODO: bugfix, subList() threw IllegalArgumentException on empty list or of some sort
			return listOfMessages.subList(lowerBound, upperBound);

		}

		public String toString() {
			int size = listOfMessages.size();
			String logString = "Size : "
					+ Integer.toString(listOfMessages.size());
			if (size > 5) {
				logString += "..." + listOfMessages.get(size - 4) + ","
						+ listOfMessages.get(size - 3) + ","
						+ listOfMessages.get(size - 2) + ","
						+ listOfMessages.get(size - 1);
			}
			return logString;
		}

	}

}
