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
		public void onCommand(DCMessage msg) {
			DCLogger.Log("in ConnectToMe handler: " + msg.toString());
			String remote_ip = msg.host_name;
			int remote_port = msg.port_number;
			String targetNick = msg.connect_nick;

			DCClient downloader = new DCClient();

			DCMessage hisnick = null;
			DCMessage rlock = null;

			MessageHandler handlerLocal = null;
			try {
				Socket s = new Socket();
				s.connect(new java.net.InetSocketAddress(remote_ip, remote_port), 15000);
				s.setSoTimeout(30000);
				handlerLocal = new MessageHandler(s);

				handlerLocal.send_mynick(my_user);
				handlerLocal.send_lock();
				
				long startTime = System.currentTimeMillis();
				while (hisnick == null || rlock == null) {
					if (System.currentTimeMillis() - startTime > 20000) break;
					DCMessage msg2 = handlerLocal.getNextMessage();
					if (msg2 == null || "HubQuit".equals(msg2.command)) break;
					if ("MyNick".equals(msg2.command)) hisnick = msg2;
					if ("Lock".equals(msg2.command)) rlock = msg2;
				}

			} catch (Exception e) {
				DCLogger.Log("ERROR - Peer connection failed: " + e.toString());
				if (handlerLocal != null) handlerLocal.close();
				return;
			}

			if (hisnick == null || hisnick.hisinfo == null || rlock == null) {
				if (handlerLocal != null) handlerLocal.close();
				return;
			}

			DCDownloader.DownloadQueueEntity downloadEntity = downloaderManager
					.getDownloadEntity(hisnick.hisinfo.nick);
			if (downloadEntity == null) {
				// Try using targetNick from ConnectToMe if hisinfo.nick is not matching or null
				downloadEntity = downloaderManager.getDownloadEntity(targetNick);
			}

			if (downloadEntity == null) {
				handlerLocal.close();
				return;
			}

			client.startDownloadingFile(downloadEntity, client, rlock,
					handlerLocal);
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
