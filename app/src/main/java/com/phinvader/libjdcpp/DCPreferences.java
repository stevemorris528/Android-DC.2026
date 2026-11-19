package com.phinvader.libjdcpp;

public class DCPreferences {

	public String nick; // DC Nick
	public long share_size; //
	public String server_ip; // IP address of the server
	public String password = null; // Password for registered nick if required
	public int upload_port = 4111;
	public boolean active = true;
	public String self_ip;
	public static boolean nat_mapped = false;
	public static String external_ip_detected = null;

	public DCPreferences(String nick, long share_size, String server_ip) {
		super();
		this.nick = nick;
		this.share_size = share_size;
		this.server_ip = server_ip;
	}

	public DCPreferences(String nick, long share_size, String server_ip, boolean active) {
		this(nick, share_size, server_ip);
		this.active = active;
	}

	public DCPreferences(String nick, long share_size, String server_ip, boolean active, int upload_port) {
		this(nick, share_size, server_ip, active);
		this.upload_port = upload_port;
	}

	public int getUploadPort() {
		return upload_port;
	}

	/**
	 * Save preferences to an encoded format for storage.
	 * 
	 * @return
	 * @see DCPreferences#load_preferences(String)
	 */
	public String save_preferences() {
		// TODO auto-generated block
		return null;
	}

	/**
	 * Load preferences from a single string encoded format
	 * 
	 * @param pref_string
	 * @see DCPreferences#save_preferences()
	 */
	public void load_preferences(String pref_string) {
		// TODO auto-generated block
	}

	public String get_self_ip() {
		return self_ip;
	}

	public void set_self_ip(String self_ip) {
		this.self_ip = self_ip;
	}

	// Following are the Getters and Setters for the params in this class
	public String getNick() {
		return nick;
	}

	public void setNick(String nick) {
		this.nick = nick;
	}

	public long getShare_size() {
		return share_size;
	}

	public void setShare_size(long share_size) {
		this.share_size = share_size;
	}

	public String getServer_ip() {
		return server_ip;
	}

	public void setServer_ip(String server_ip) {
		this.server_ip = server_ip;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

}
