package com.phinvader.libjdcpp;

/**
 * This class is a universal container for all DC messages which are transfered
 * over TCP. This includes the data of a file download
 * 
 * @author phinfinity
 * 
 */
public class DCMessage {
	public String msg_s; // When there is no command and it is just a text
							// message
	public String command;
	public String lock_s; // Lock string in $Lock Command
	public String key_s; // Key string in $Lock command or the key from $Key
	public String hubname_s; // HubName given by $HubName command
	public String[] supports; // List of features supported (by $Supports
								// command
	public String hello_s;
	public DCUser myinfo;
	public DCUser hisinfo;
	public String quit_s;
	public String error_s;
	public String host_name; // ConnectToMe
	public int port_number; // ConnectToMe
	public String connect_nick; // ConnectToMe
	public String revconnect_from; // RevConnectToMe
	public String revconnect_to; // RevConnectToMe
	public boolean dir_download; // If direction is download in a $Direction
									// message
	public int dir_no;
	public long file_length;
	public String file_path;
	public long file_size;
	public String file_signature;
	public String get_filename; // Get
	public long startOffset; // Get/ADCGET
	public long bytesToTransfer; // ADCGET
	public String search_term; // Search
	public String search_ip_port; // Search
	public boolean is_tthl; // ADCGET tthl
	public String to_user; // $To: PM recipient
	public String pm_from; // $To: PM sender nick
	public String pm_text; // $To: PM message body

	/**
	 * Parses a byte[] array to produce a DCMessage Object.
	 * 
	 * sets {@link DCMessage#command} to be the command of recieved message.
	 * Depending on the message recieved more fields are populated. If parsing
	 * message was unsuccessful it defaults to a text message and is set in
	 * msg_s , and command is set to null. List of handled messages: (so far)
	 * <ul>
	 * <li>$Lock</li>
	 * <li>$Key</li>
	 * <li>$HubName</li>
	 * <li>$Supports</li>
	 * <li>$Hello</li>
	 * <li>$MyINFO</li>
	 * <li>$Quit</li>
	 * <li>$HubQuit - this is not part of the protocol but added as a way to
	 * notify when the hub socket gets cut.</li>
	 * <li></li>
	 * <li></li>
	 * </ul>
	 * 
	 * 
	 * @param input
	 * @return
	 * @throws DCMessageParseException
	 */
	public static DCMessage parse_message(byte[] input) {
		DCMessage ret = new DCMessage();
		ret.parse(input);
		return ret;
	}

	/**
	 * Parses a byte[] array to populate DCMessage
	 * 
	 * @param input
	 * @throws DCMessageParseExceptionus
	 * @see {@link DCMessage#parse_message(byte[])}
	 */
	public void parse(byte[] input) {

		boolean parse_success = true;
		try {

			if (input[0] == '$') {
				int beg = DCFunctions.find_next(input, 1, ' ');
				if (beg == -1)
					beg = input.length;
				command = new String(input, 1, beg - 1);
				if (command.equals("Lock")) {
					int poffset = beg + 1, coffset;
					coffset = DCFunctions.find_next(input, poffset, ' ');
					lock_s = new String(input, poffset, coffset - poffset);
					poffset = coffset + 1;
					coffset = DCFunctions.find_next(input, poffset, ' ');
					String[] key_split = new String(input, poffset, coffset
							- poffset).split("=");
					if (key_split.length != 2
							|| !key_split[0].toLowerCase().equals("pk"))
						parse_success = false;
					else
						key_s = key_split[1];

				} else if (command.equals("Key")) {
					key_s = new String(input, beg + 1, input.length - beg - 1);
				} else if (command.equals("SR")) {
					// Search Result
					String value = new String(input);
					String[] values = value.split(" ");
					String usernick = values[1];
					String signature = values[values.length - 2];
					String path = "";
					for (int i = 2; i < values.length - 2; i++) {
						path += values[i];
						if (i != values.length - 3)
							path += " ";
					}
					String[] path_size_raw = path.split("");
					hisinfo = new DCUser();
					hisinfo.nick = usernick;
					file_path = path_size_raw[0];
					file_size = Long.parseLong(path_size_raw[1]);
					file_signature = signature;

				} else if (command.equals("HubName")) {
					hubname_s = new String(input, beg + 1, input.length - beg
							- 1);
				} else if (command.equals("Supports")) {
					String sup_s = new String(input, beg + 1, input.length
							- beg - 1);
					supports = sup_s.split(" ");
				} else if (command.equals("Hello")) {
					hello_s = new String(input, beg + 1, input.length - beg - 1);
				} else if (command.equals("MyINFO")) {
					myinfo = new DCUser();
					DCFunctions.find_next(input, beg, '$');
					int offsets[] = new int[6];
					int seglength[] = new int[6];
					int loffset = DCFunctions.find_next(input, beg + 1, '$');
					// $ALL
					offsets[0] = loffset;
					loffset = DCFunctions.find_next(input, loffset, ' ');
					seglength[0] = loffset - offsets[0];
					loffset++;
					// Nick Name
					offsets[1] = loffset;
					loffset = DCFunctions.find_next(input, loffset, ' ');
					seglength[1] = loffset - offsets[1];
					loffset++;
					// Interest String
					offsets[2] = loffset;
					loffset = DCFunctions.find_next(input, loffset, '$');
					seglength[2] = loffset - offsets[2];
					loffset++;
					// Speed specifier
					loffset = DCFunctions.find_next(input, loffset, '$') + 1; // skipping
																				// blankspace
					offsets[3] = loffset;
					loffset = DCFunctions.find_next(input, loffset, '$');
					seglength[3] = loffset - offsets[3];
					loffset++;
					// email
					offsets[4] = loffset;
					loffset = DCFunctions.find_next(input, loffset, '$');
					seglength[4] = loffset - offsets[4];
					loffset++;
					// Share Size
					offsets[5] = loffset;
					loffset = DCFunctions.find_next(input, loffset, '$');
					seglength[5] = loffset - offsets[5];
					if (new String(input, offsets[0], seglength[0])
							.equals("$ALL")) {
						myinfo.nick = new String(input, offsets[1],
								seglength[1]);
						myinfo.description = new String(input, offsets[2],
								seglength[2]);
						int desc_split_offset = myinfo.description
								.lastIndexOf('<');
						// Split for the tag
						if (desc_split_offset != -1) {
							myinfo.tag = myinfo.description
									.substring(desc_split_offset);
							myinfo.description = myinfo.description.substring(
									0, desc_split_offset);
						} else {
							myinfo.tag = "";
						}
						if (myinfo.tag.contains("M:A"))
							myinfo.active = true;
						else
							myinfo.active = false;
						myinfo.connection_speed = new String(input, offsets[3],
								seglength[3] - 1);
						myinfo.speed_id = input[offsets[3] + seglength[3] - 1];
						myinfo.email = new String(input, offsets[4],
								seglength[4]);
						myinfo.share_size = Long.parseLong(new String(input,
								offsets[5], seglength[5]));
					}

					else {
						parse_success = false;
					}
					// } else if (command.equals("ValidateNick")) {
					// } else if (command.equals("Version")) {
					// } else if (command.equals("GetNickList")) {
				} else if (command.equals("FileLength")) {
					String val = new String(input, beg + 1, input.length - beg - 1);
					if (val.contains("|")) {
						val = val.substring(0, val.indexOf('|'));
					}
					val = val.trim();
					if (val.isEmpty()) {
						file_length = 0;
					} else {
						try {
							file_length = Long.parseLong(val);
						} catch (NumberFormatException e) {
							file_length = 0;
						}
					}
				} else if (command.equals("MyNick")) {
					hisinfo = new DCUser();
					hisinfo.nick = new String(input, beg + 1, input.length
							- beg - 1);
				} else if (command.equals("UserIP")) {
					msg_s = new String(input, beg + 1, input.length - beg - 1);
					parse_success = true;
				} else if (command.equals("Direction")) {
					String dir_str[] = new String(input, beg + 1, input.length
							- beg - 1).split("\\s");
					dir_download = true;
					if (!dir_str[0].equals("Download"))
						dir_download = false;
					dir_no = Integer.parseInt(dir_str[1]);
				} else if (command.equals("ConnectToMe")) {
					String conn_str = new String(input, beg + 1, input.length
							- beg - 1);
					String[] parts = conn_str.split("\\s");
					if (parts.length >= 2) {
						connect_nick = parts[0];
						host_name = parts[1];
						if (host_name.contains(":")) {
							port_number = Integer.parseInt(host_name.split(":")[1]);
							host_name = host_name.split(":")[0];
						} else {
							port_number = 411; // Default
						}
					}
				} else if (command.equals("RevConnectToMe")) {
					String conn_str = new String(input, beg + 1, input.length - beg - 1);
					String[] parts = conn_str.split("\\s");
					revconnect_from = parts[0];
					revconnect_to = parts[1];
				} else if (command.equals("Search")) {
					String searchStr = new String(input, beg + 1, input.length - beg - 1);
					int firstSpace = searchStr.indexOf(' ');
					if (firstSpace != -1) {
						search_ip_port = searchStr.substring(0, firstSpace);
						search_term = searchStr.substring(firstSpace + 1);
					}
				} else if (command.equals("HubQuit")) {
					if (input.length - beg - 1 > 0)
						quit_s = new String(input, beg + 1, input.length - beg
								- 1);
					else
						quit_s = "";
					// return just command as HubQuit
				} else if (command.equals("Quit")) {
					if (input.length > beg + 1) {
						quit_s = new String(input, beg + 1, input.length - beg - 1);
					} else {
						quit_s = "";
					}
				} else if (command.equals("MaxedOut")) {
					parse_success = true;
				} else if (command.equals("Error")) {
					if (input.length - beg - 1 > 0)
						error_s = new String(input, beg + 1, input.length - beg - 1);
					else
						error_s = "";
					parse_success = true;
				} else if (command.equals("Get")) {
					String getStr = new String(input, beg, input.length - beg).trim();
					if (getStr.contains("|")) {
						getStr = getStr.substring(0, getStr.indexOf('|'));
					}
					int sep = getStr.lastIndexOf('$');
					if (sep != -1) {
						get_filename = getStr.substring(0, sep);
						try {
							String offsetStr = getStr.substring(sep + 1);
							startOffset = Long.parseLong(offsetStr.trim());
						} catch (NumberFormatException e) {
							startOffset = 1;
						}
					} else {
						get_filename = getStr;
						startOffset = 1;
					}
					parse_success = true;
				} else if (command.equals("ADCGET")) {
					String val = new String(input, beg, input.length - beg).trim();
					String[] parts = val.split(" ");
					if (parts.length >= 4) {
						if (parts[0].equals("file")) {
							get_filename = parts[1];
							try {
								startOffset = Long.parseLong(parts[2]) + 1; // ADC is 0-based
								bytesToTransfer = Long.parseLong(parts[3]);
								parse_success = true;
							} catch (NumberFormatException e) {
								parse_success = false;
							}
						} else if (parts[0].equals("tthl")) {
							get_filename = parts[1];
							try {
								startOffset = Long.parseLong(parts[2]);
								bytesToTransfer = Long.parseLong(parts[3]);
								is_tthl = true;
								parse_success = true;
							} catch (NumberFormatException e) {
								parse_success = false;
							}
						}
					}
				} else if (command.equals("Send")) {
					parse_success = true;
				} else if (command.equals("GetPass")) {
					parse_success = true;
				} else if (command.equals("BadPass")) {
					parse_success = true;
				} else if (command.equals("LogedIn")) {
					parse_success = true;
				} else if (command.startsWith("To:")) {
					command = "To:";
					String fullStr = new String(input, "ISO-8859-1");
					// Format: $To: <Recipient> From: <Sender> $<Sender> <Message>
					// Or: $To: <Recipient> From: <Sender> $<Sender> message
					int fromIdx = fullStr.indexOf(" From: ");
					if (fromIdx != -1) {
						to_user = fullStr.substring(4, fromIdx).trim();
						String afterFrom = fullStr.substring(fromIdx + 7).trim();
						int dollarIdx = afterFrom.indexOf('$');
						if (dollarIdx != -1) {
							pm_from = afterFrom.substring(0, dollarIdx).trim();
							String payload = afterFrom.substring(dollarIdx + 1).trim();
							// Payload often starts with <SenderNick> text
							if (payload.startsWith("<") && payload.indexOf('>') != -1) {
								int closeBracket = payload.indexOf('>');
								pm_text = payload.substring(closeBracket + 1).trim();
							} else {
								pm_text = payload;
							}
						} else {
							int spaceIdx = afterFrom.indexOf(' ');
							if (spaceIdx != -1) {
								pm_from = afterFrom.substring(0, spaceIdx).trim();
								pm_text = afterFrom.substring(spaceIdx + 1).trim();
							} else {
								pm_from = afterFrom;
								pm_text = "";
							}
						}
					} else {
						// Simple fallback if no " From: "
						String rem = fullStr.substring(4).trim();
						int spaceIdx = rem.indexOf(' ');
						if (spaceIdx != -1) {
							to_user = rem.substring(0, spaceIdx).trim();
							pm_text = rem.substring(spaceIdx + 1).trim();
						} else {
							to_user = rem;
							pm_text = "";
						}
					}
					parse_success = true;
				} else {
					parse_success = false;
				}
			} else if (input[0] == '<') {
				command = "BoardMessage";
				msg_s = new String(input);
			} else {
				parse_success = false;
			}
			if (!parse_success) {
				command = null;
				msg_s = new String(input);
			}
		} catch (Exception e) {
			// TODO Remove This debugging piece
			command = null;
			msg_s = "ParseFail : " + e.toString();
			msg_s += "\n For:" + new String(input);
		}
	}

	public String toString() {
		String desc = command;
		if (desc == null)
			return msg_s;
		desc += " : ";
		if (command.equals("Lock")) {
			desc += lock_s;
			desc += " pk : " + key_s;
		} else if (command.equals("Key")) {
			desc += key_s;
		} else if (command.equals("HubName")) {
			desc += hubname_s;
		} else if (command.equals("Supports")) {
			for (String s : supports)
				desc += s + " ";
		} else if (command.equals("Hello")) {
			desc += hello_s;
		} else if (command.equals("MyINFO")) {
			desc += myinfo.nick;
		} else if (command.equals("HubQuit")) {
			desc += quit_s;
		} else if (command.equals("Quit")) {
			desc += quit_s;
		} else if (command.equals("ConnectToMe")) {
			desc += connect_nick + " " + host_name + ":" + port_number;
		} else if (command.equals("Direction")) {
			String s = "Download";
			if (!dir_download)
				s = "Upload";
			s += " " + dir_no;
			desc += s;
		} else if (command.equals("FileLength")) {
			desc += Long.toString(file_length);
		} else if (command.equals("SR")) {
			desc += "@" + hisinfo.nick + " " + file_path + " : "
					+ file_signature;
		} else if (command.equals("BoardMessage")) {
			desc += msg_s;
		}

		return desc;
	}

}
