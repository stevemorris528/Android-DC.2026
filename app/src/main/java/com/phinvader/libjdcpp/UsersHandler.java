package com.phinvader.libjdcpp;

import java.util.HashSet;
import java.util.Set;

public class UsersHandler implements DCCommand {

	/**
	 * Gets the list of all NICKS registered on the current server identified by
	 * param: handler
	 * 
	 * To use UserHandler: class MyUserHandler extends UserHandler implements
	 * DCCallback
	 * 
	 * @author madhavan
	 */
	public final Set<DCUser> nick_q = new HashSet<DCUser>();

	/**
	 * Add a user to the list-of-online-users Updated when there is a MYINFO
	 * Message from the server
	 * 
	 * @param nick
	 * @throws InterruptedException
	 */

	public void addNick(DCUser nick) {
		if (nick == null) return;
		synchronized (nick_q) {
			nick_q.add(nick);
		}
	}

	/**
	 * Delete a user from the list-of-online-users This is updated when there is
	 * a QUIT message from the server
	 * 
	 * @param nick
	 */

	public void deleteNick(String nick) {
		if (nick == null) return;
		DCUser user = new DCUser();
		user.nick = nick;
		synchronized (nick_q) {
			nick_q.remove(user);
		}
	}

	/**
	 * DCCommand interface override Should subscribe to MyInfo and Quit to the
	 * router.
	 * 
	 * @param msg
	 */

	@Override
	public void onCommand(DCMessage msg) {
		String command = msg.command;
		if (command == null) return;
		if (command.equals("Quit")) {
			String nick_to_delete = msg.quit_s;
			if (nick_to_delete == null) {
				String quit_command = msg.toString();
				if (quit_command.contains(":")) {
					String[] quit_arr = quit_command.split(":");
					if (quit_arr.length > 1)
						nick_to_delete = quit_arr[1].trim();
				} else {
					// Fallback for $Quit <nick>
					int spaceIndex = quit_command.indexOf(' ');
					if (spaceIndex != -1) {
						nick_to_delete = quit_command.substring(spaceIndex + 1).trim();
					}
				}
			}
			if (nick_to_delete != null)
				deleteNick(nick_to_delete);
		} else if (command.equals("MyINFO")) {
			if (msg.myinfo != null)
				addNick(msg.myinfo);
		}
	}

}
