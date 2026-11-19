package com.phinvader.libjdcpp;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * This is the Socket message handler class. Every TCP Connection should be
 * encapsulated within such a Message Handler class. The
 * 
 * @author phinfinity
 * 
 */
public class MessageHandler {
	private Socket tcp_scoket;
	private Semaphore update_sem;
	public OutputStream os;
	private final ArrayBlockingQueue<DCMessage> message_queue;
	Thread input_handler_thread;
	private long dump_bytes_limit = 0;
	private String dump_stream_file = null; // Once this is set to not null
											// start dumping the stream to file
	private boolean dump_append = false;
	private long dump_bytes = 0;

	DCDownloader.DownloadQueueEntity downloadEntity = null;
	public void setDownloadEntity(DCDownloader.DownloadQueueEntity entity) {
		this.downloadEntity = entity;
	}

	/**
	 * Start dumping the rest of the input stream to a file Stop after dumping
	 * no_bytes of data.
	 * 
	 * @param file_name
	 * @param no_bytes
	 */
	public void dump_remaining_stream(String file_name, long no_bytes, boolean append) {
		dump_stream_file = file_name;
		dump_bytes_limit = no_bytes;
		dump_append = append;
	}

	public void dump_remaining_stream(String file_name, long no_bytes) {
		dump_remaining_stream(file_name, no_bytes, false);
	}

	/**
	 * Get number of bytes dumped to file so far.
	 * 
	 * @return
	 */
	public long get_dumped_bytes() {
		return dump_bytes;
	}

	public long get_filesize() {
		return dump_bytes_limit;
	}

	/**
	 * Create a new Message Handler with the Socket s. It spawns a thread to
	 * listen on the Message Queue and buffers incoming messages concurrently
	 * 
	 * @param s
	 */
	public MessageHandler(final Socket s) {
		tcp_scoket = s;
		message_queue = new ArrayBlockingQueue<DCMessage>(
				DCConstants.max_message_queue_size);
		Runnable input_handler = new Runnable() {
			@Override
			public void run() {
				try {
					InputStream in = s.getInputStream();
					BufferedInputStream bin = new BufferedInputStream(in);
					java.io.ByteArrayOutputStream messageBuffer = new java.io.ByteArrayOutputStream();
					int buf_read_sz = 0;
					while (true) {
						if (dump_stream_file != null) break;
						int b = bin.read();
						if (b == -1) {
							buf_read_sz = -1;
							break;
						}
						if (b == '|') {
							addMessage(DCMessage.parse_message(messageBuffer.toByteArray()));
							messageBuffer.reset();
						} else {
							messageBuffer.write(b);
						}
					}
					if (buf_read_sz != -1 && dump_stream_file != null) {
						dump_bytes = 0;
						FileOutputStream output_file = null;
						try {
							output_file = new FileOutputStream(
									dump_stream_file, dump_append);

							byte[] initial = messageBuffer.toByteArray();
							if (initial.length > 0) {
								output_file.write(initial);
								dump_bytes += initial.length;
								messageBuffer.reset();
							}

							byte[] write_buffer = new byte[DCConstants.io_buffer_size];
							while (true) {
								int s = bin.read(write_buffer);
								if (s == -1)
									break;
								output_file.write(write_buffer, 0, s);
								output_file.flush(); // Ensure writes are flushed immediately to persistent storage
								dump_bytes += s;
								if (downloadEntity != null) {
									downloadEntity.downloadedSize = downloadEntity.startOffset + dump_bytes;
								}

								if (dump_bytes >= dump_bytes_limit || Thread.currentThread().isInterrupted())
									break;
							}
							output_file.getFD().sync(); // Force sync file descriptor
						} finally {
							if (output_file != null) {
								try { output_file.close(); } catch (IOException ignored) {}
							}
						}
					}
					bin.close();
					addMessage(DCMessage.parse_message("$HubQuit".getBytes()));
				} catch (Exception e) { // IOException InterruptedException
					try {
						addMessage(DCMessage.parse_message(("$HubQuit " + e
								.toString()).getBytes()));
						// s.close();
						// TODO remove this stacktrace. Added only for debugging
						// purposes
						e.printStackTrace();
					} catch (InterruptedException e1) {
					}
				}
			}
		};
		input_handler_thread = new Thread(input_handler);
		input_handler_thread.start();

		// Just get the output stream
		try {
			os = s.getOutputStream();
		} catch (IOException e) {
			try {
				addMessage(DCMessage.parse_message("$HubQuit".getBytes()));
			} catch (InterruptedException e1) {
			}
		}
	}

	/**
	 * Interrupts the input thread and tries to close the socket connection
	 */
	public void close() {
		try {
			if (tcp_scoket != null)
				tcp_scoket.close();
		} catch (IOException e) {
		}
		if (input_handler_thread != null)
			input_handler_thread.interrupt();
	}

	/**
	 * This is used to obtain from the Message queue the next message packet
	 * available or blocks if none exist. (Uses java inbuilt blocking queue)
	 * 
	 * @return
	 * @throws InterruptedException
	 */
	public DCMessage getNextMessage() throws InterruptedException {
		return message_queue.take();
	}

	public DCMessage getNextMessage(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
		return message_queue.poll(timeout, unit);
	}

	public DCMessage checkNextMessage() {
		return message_queue.peek();
	}

	public boolean isEmpty() {
		return message_queue.isEmpty();
	}

	/**
	 * (Do Not use this outside class as public member unless explicitly needed.
	 * messages added are not sent over the socket)
	 * 
	 * This function is used to add messages to the queue. This is typically
	 * only used by the parallel thread listening on the socket and handling
	 * messages. As soon as a message is received this function will be called
	 * 
	 * NOTE: This function will block calling thread if the Mes$Lock
	 * EXTENDEDPROTOCOLABCABCABCABCABCABC Pk=py-dchub-0.2.4--|$HubName
	 * SuperNova|sage Queue is full and will remain blocked until the message
	 * Queue is free. DCConstants.max_message_queue_size specifies the Maximum
	 * Queue size
	 * 
	 * if updates requested on a semaphore , semaphore will be released one
	 * permit
	 * 
	 * @param msg
	 * @throws InterruptedException
	 */
	public void addMessage(DCMessage msg) throws InterruptedException {
		message_queue.put(msg);
		if (update_sem != null)
			update_sem.release();
	}

	/**
	 * This function is a means to get updates from the MessageHandler when a
	 * new message is received. As soon as a message is received the semaphore
	 * registered will be released a single permit to wake up threads waiting on
	 * them. If a semaphore has already been registered it will be discarded and
	 * replaced with the new one. The old semaphore will no longer receive
	 * permits
	 * 
	 * @see MessageHandler#discardupdates()
	 * @param sem_lock
	 */
	public void requestupdates(Semaphore sem_lock) {
		update_sem = sem_lock;
	}

	/**
	 * $Lock EXTENDEDPROTOCOLABCABCABCABCABCABC Pk=py-dchub-0.2.4--|$HubName
	 * SuperNova| This function is the opposite of
	 * {@link MessageHandler#requestupdates(Semaphore)}. If a semaphore is
	 * registered it will be discarded for future updates.
	 */
	public void discardupdates() {
		update_sem = null;
	}

	public synchronized void send_msg(String s) {
		s += "|";
		try {
			if (os != null) {
				os.write(s.getBytes("ISO-8859-1"));
				os.flush();
			}
		} catch (IOException e) {
			try {
				addMessage(DCMessage.parse_message(("$HubQuit " + e.toString())
						.getBytes()));
			} catch (InterruptedException e1) {
			}
		}
	}

	public void send_supports(List<String> sup) {
		String msg = "$Supports ";
		for (String s : sup) {
			msg += s + " ";
		}
		send_msg(msg);
	}

	public void send_key(byte[] key) {
		String msg = "$Key ";
		try {
			msg += new String(key, "ISO-8859-1");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		send_msg(msg);
	}

	public void send_validatenick(String nick) {
		String msg = "$ValidateNick " + nick;
		send_msg(msg);
	}

	public void send_mypass(String password) {
		String msg = "$MyPass " + password;
		send_msg(msg);
	}

	public void send_version() {
		send_msg("$Version 1,0091");
	}

	public void send_getnicklist() {
		send_msg("$GetNickList");
	}

	public void send_mynick(DCUser user) {
		send_msg("$MyNick " + user.nick);
	}

	public void send_lock() {
		String s = "$Lock " + DCConstants.default_lock + " Pk="
				+ DCConstants.default_pk;
		send_msg(s);
	}

	public void send_direction(boolean download) {
		int random_v = (int) (Math.random() * 65535);
		String dir = "Download";
		if (!download)
			dir = "Upload";
		send_msg("$Direction " + dir + " " + random_v);
	}

	public void send_revconnect(DCUser mynick, DCUser connecting_nick) {
		send_msg("$RevConnectToMe " + mynick.nick + " " + connecting_nick.nick);
	}

	public void send_connecttome(DCUser mynick, String host, int port) {
		send_msg("$ConnectToMe " + mynick.nick + " " + host + ":"
				+ Integer.toString(port));
	}

	public void send_search(String searchString, DCUser myuser) {
		send_msg("$Search Hub:" + myuser.nick + " " + searchString);
	}

	public void send_myinfo(DCUser user, DCPreferences prefs) {
		DCUser myuser = new DCUser(user);
		boolean isActive = (prefs != null) ? prefs.active : myuser.active;
		if (myuser.description == null)
			myuser.description = "";
		
		// LibreTorrent/Modern client style tag: <++ V:0.8.7,M:A,H:1/0/0,S:3>
		// M:A for Active, M:P for Passive
		if (myuser.tag == null) {
			myuser.tag = "<++ V:" + DCConstants.version_short + ","
					+ (isActive ? "M:A" : "M:P") + "," + "H:1/0/0,S:3>";
		}
		if (myuser.connection_speed == null || myuser.connection_speed.equals("1")) {
			myuser.connection_speed = "Cable";
		}
		
		// Speed ID: 1 for Active, 4 for Passive in some hubs
		if (myuser.speed_id == 0) {
		    myuser.speed_id = (byte) (isActive ? 1 : 4);
		}

		if (myuser.email == null) {
			myuser.email = "";
		}
		String msg = "$MyINFO $ALL ";
		msg += myuser.nick + " ";
		msg += (myuser.description.trim().isEmpty() ? "" : myuser.description.trim() + " ");
		msg += myuser.tag + "$ $";
		msg += myuser.connection_speed;
		msg += (char) myuser.speed_id;
		msg += "$" + myuser.email + "$";
		msg += Long.toString(myuser.share_size) + "$";
		send_msg(msg);
	}
}
