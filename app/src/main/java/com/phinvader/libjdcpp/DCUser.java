package com.phinvader.libjdcpp;


/**
 * The only compulsory field here is nick. Rest all may be null. Mostly decided
 * by the $MyInfo command as specified at:
 * http://wiki.gusari.org/index.php?title=$MyINFO
 * 
 * @author phinfinity
 * 
 */
public class DCUser {
	public String nick; // filled by myinfo
	public String ip;
	public long share_size; // filled by myinfo
	public String description; // filled by myinfo
	public String tag; // filled by myinfo
	public String email; // filled by myinfo
	public String connection_speed; // filled by myinfo
	public byte speed_id; // filled by myinfo
	public boolean active; // filled by tag from myfino
	public boolean op;

	// ---- ADC (null / 0 on NMDC hubs). NI, DE, SS, I4 and the op bit of CT also fill the fields above. ----
	/** Session ID on this hub (4 chars). Changes every login: never store it. */
	public String sid;
	/** Client ID (39-char Base32), the same on every hub. */
	public String cid;
	/** Client type bits: 1 bot, 2 registered, 4 operator, 8 super user, 16 owner, 32 hub. */
	public int ct;
	public int slots;
	public int freeSlots;
	public long sharedFiles;
	/** VE: client name and version. */
	public String client;
	/** SU: supported features (TCP4, UDP4, ADC0, ADCS, ...). */
	public java.util.Set<String> features = new java.util.HashSet<String>();
	/** KP: keyprint of the user's TLS certificate, or null. */
	public String kp;
	public int udp4;
	public boolean away;

	public DCUser(DCUser original) {
		nick = original.nick;
		ip = original.ip;
		share_size = original.share_size;
		description = original.description;
		tag = original.tag;
		email = original.email;
		connection_speed = original.connection_speed;
		speed_id = original.speed_id;
		active = original.active;
		op = original.op;
		sid = original.sid;
		cid = original.cid;
		ct = original.ct;
		slots = original.slots;
		freeSlots = original.freeSlots;
		sharedFiles = original.sharedFiles;
		client = original.client;
		features = new java.util.HashSet<String>(original.features);
		kp = original.kp;
		udp4 = original.udp4;
		away = original.away;
	}
	public DCUser() {
	}
	public String toString() {
		String ret = nick;
		ret += " Tag:" + tag;
		ret += " share_size:" + share_size;
		return ret;
	}

	@Override
	public boolean equals(Object obj) {
		// Only comparing Nick Names
		if (obj == null)
			return false;
		if (obj == this)
			return true;
		if (!(obj instanceof DCUser))
			return false;
		return nick.equals(((DCUser) obj).nick);
	}
	@Override
	public int hashCode() {
		// Only comparing Nick Names
		return nick.hashCode();
	}

}
