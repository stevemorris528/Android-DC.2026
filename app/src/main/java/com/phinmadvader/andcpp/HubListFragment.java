package com.phinmadvader.andcpp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import com.phinvader.libjdcpp.DCUser;
import com.phinvader.libjdcpp.hub.HubAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HubListFragment extends Fragment {
    private ListView listView;
    private CheckBox autoReconnectCheckBox;
    private HubListAdapter adapter;
    private MainActivity mainActivity;
    private static final String FAVORITE_HUBS_PREF = "favorite_hubs";

    /**
     * Adds a hub to the saved hubs unless it is already there. Any address form is accepted; it is stored as
     * {@link HubAddress#id()} ("host" / "host:port" for NMDC, "adc://host:port" etc. for other protocols).
     * For NMDC a bare "host" and "host:port" count as the same hub (old behaviour).
     * Returns true when it was added.
     */
    public static boolean saveHub(Context c, String hubId) {
        HubAddress addr = HubAddress.tryParse(hubId);
        if (addr == null) return false;
        hubId = addr.id();
        SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> favorites = new HashSet<>(prefs.getStringSet(FAVORITE_HUBS_PREF, new HashSet<String>()));
        for (String f : favorites) {
            if (f.equals(hubId)) return false;
            HubAddress saved = HubAddress.tryParse(f);
            if (saved == null || saved.protocol != addr.protocol || !saved.host.equalsIgnoreCase(addr.host)) continue;
            if (addr.protocol == HubAddress.Protocol.NMDC && (!saved.portExplicit || !addr.portExplicit)) return false;
            if (saved.port == addr.port) return false;
        }
        favorites.add(hubId);
        prefs.edit().putStringSet(FAVORITE_HUBS_PREF, favorites).apply();
        android.util.Log.i("andcpp", "Saved hub " + hubId);
        return true;
    }

    /** Removes a hub from the saved hubs (every saved form of the same protocol, host and port). */
    public static void removeSavedHub(Context c, String hubId) {
        HubAddress addr = HubAddress.tryParse(hubId);
        if (addr == null) return;
        SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> favorites = new HashSet<>(prefs.getStringSet(FAVORITE_HUBS_PREF, new HashSet<String>()));
        boolean changed = false;
        for (String f : new ArrayList<>(favorites)) {
            if (addr.sameHub(HubAddress.tryParse(f))) {
                favorites.remove(f);
                changed = true;
            }
        }
        if (changed) {
            prefs.edit().putStringSet(FAVORITE_HUBS_PREF, favorites).apply();
            android.util.Log.i("andcpp", "Removed saved hub " + hubId);
        }
    }

    /** Reads a per-hub string pref ("favorite_nicks_", "favorite_passwords_", "hub_name_"); "" if not set. */
    static String getHubPref(SharedPreferences prefs, String prefix, String hubId) {
        HubAddress addr = HubAddress.tryParse(hubId);
        if (addr == null) return prefs.getString(prefix + hubId, "");
        for (String k : addr.prefKeys()) {
            String v = prefs.getString(prefix + k, "");
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    /** Writes a per-hub string pref under the keys {@link HubAddress#prefKeysToWrite()} lists. */
    static void putHubPref(SharedPreferences.Editor editor, String prefix, String hubId, String value) {
        HubAddress addr = HubAddress.tryParse(hubId);
        if (addr == null) {
            editor.putString(prefix + hubId, value);
            return;
        }
        for (String k : addr.prefKeysToWrite()) editor.putString(prefix + k, value);
    }

    /**
     * The running session for a saved hub, or null. Hubs of a protocol the service can't run yet never have
     * one; without this check getSession()'s "any connected hub" fallback would return an unrelated hub.
     */
    private DCPPService.HubSession sessionForHub(String hubId) {
        if (mainActivity.mService == null) return null;
        HubAddress addr = HubAddress.tryParse(hubId);
        if (addr != null && !addr.isSupported()) return null;
        return mainActivity.mService.getSession(hubId);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mainActivity = (MainActivity) getActivity();
        View view = inflater.inflate(R.layout.hub_list_layout, container, false);
        autoReconnectCheckBox = (CheckBox) view.findViewById(R.id.checkbox_auto_reconnect);
        if (autoReconnectCheckBox != null) {
            autoReconnectCheckBox.setChecked(Settings.getAutoReconnectHubs(mainActivity));
            autoReconnectCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                Settings.putAutoReconnectHubs(isChecked, mainActivity);
            });
        }
        listView = (ListView) view.findViewById(R.id.hub_list_view);
        listView.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN || event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                v.performClick();
            }
            return false;
        });
        adapter = new HubListAdapter();
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view1, position, id) -> {
            HubEntry entry = (HubEntry) adapter.getItem(position);
            showHubOptionsDialog(entry);
        });

        listView.setOnItemLongClickListener((parent, view1, position, id) -> {
            HubEntry entry = (HubEntry) adapter.getItem(position);
            showHubOptionsDialog(entry);
            return true;
        });

        return view;
    }

    private void showHubOptionsDialog(HubEntry entry) {
        if (entry == null) return;
        SharedPreferences prefs = mainActivity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> favorites = prefs.getStringSet(FAVORITE_HUBS_PREF, new HashSet<String>());
        boolean isFav = favorites.contains(entry.hubIp);
        boolean isConnected = (entry.session != null && entry.session.status == DCPPService.DCClientStatus.CONNECTED);
        boolean isConnectingOrReconnecting = (entry.session != null && 
                (entry.session.status == DCPPService.DCClientStatus.CONNECTING || entry.session.status == DCPPService.DCClientStatus.RECONNECTING));

        List<String> optionsList = new ArrayList<>();
        if (isConnected) {
            optionsList.add("Open Hub");
            optionsList.add("Reconnect");
            optionsList.add("Delete Hub");
        } else if (isConnectingOrReconnecting) {
            optionsList.add("Open Hub Chat");
            optionsList.add("Reconnect");
            optionsList.add("Delete Hub");
        } else {
            optionsList.add("Connect");
            optionsList.add("Delete Hub");
        }
        if (isFav) {
            optionsList.add("Remove Hub");
        } else {
            optionsList.add("Add to Favorite Hubs");
        }
        optionsList.add("Edit Nick & Password");
        optionsList.add("Hub details");
        if (entry.session != null && entry.session.keyprintProblem != null) {
            optionsList.add(0, "Keyprint warning…");
        }

        final String[] options = optionsList.toArray(new String[0]);

        new AlertDialog.Builder(getActivity())
                .setTitle(entry.hubIp)
                .setItems(options, (dialog, which) -> {
                    String selected = options[which];
                    switch (selected) {
                        case "Open Hub":
                        case "Open Hub Chat":
                            HubAddress openAddr = HubAddress.tryParse(entry.hubIp);
                            String targetIp = entry.session != null ? entry.session.hubIp
                                    : (openAddr != null ? openAddr.host : entry.hubIp);
                            mainActivity.openChatForHub(targetIp);
                            break;
                        case "Reconnect":
                            if (mainActivity.mService != null && entry.session != null
                                    && mainActivity.mService.reconnectHub(entry.session.address().sessionKey())) {
                                Toast.makeText(getActivity(), "Reconnecting to " + entry.session.hubIp, Toast.LENGTH_SHORT).show();
                                listView.postDelayed(this::refresh, 500);
                            } else {
                                adapter.connectToHub(entry.hubIp);
                            }
                            break;
                        case "Delete Hub":
                            if (mainActivity.mService != null && entry.session != null) {
                                mainActivity.mService.shutdownSession(entry.session.address().sessionKey());
                            }
                            SharedPreferences delPrefs = mainActivity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
                            Set<String> delFavorites = new HashSet<>(delPrefs.getStringSet(FAVORITE_HUBS_PREF, new HashSet<String>()));
                            delFavorites.remove(entry.hubIp);
                            if (entry.session != null) {
                                delFavorites.remove(entry.session.hubIp + ":" + entry.session.hubPort);
                                delFavorites.remove(entry.session.address().id());
                            }
                            delPrefs.edit().putStringSet(FAVORITE_HUBS_PREF, delFavorites).apply();
                            refresh();
                            Toast.makeText(getActivity(), "Hub deleted", Toast.LENGTH_SHORT).show();
                            break;
                        case "Connect":
                            adapter.connectToHub(entry.hubIp);
                            break;
                        case "Add to Favorite Hubs":
                        case "Remove Hub":
                            adapter.toggleFavorite(entry.hubIp);
                            refresh();
                            break;
                        case "Save Password":
                            showPasswordDialog(entry.hubIp);
                            break;
                        case "Edit Nick & Password":
                            showEditNickPasswordDialog(entry.hubIp);
                            break;
                        case "Hub details":
                            showHubDetails(entry);
                            break;
                        case "Keyprint warning…":
                            if (entry.session != null && entry.session.keyprintProblem != null) {
                                mainActivity.showKeyprintWarning(entry.session.address().sessionKey(),
                                        entry.session.hubName != null ? entry.session.hubName : entry.hubIp,
                                        entry.session.keyprintProblem, entry.session.pendingNewKeyprint != null);
                            }
                            break;
                    }
                })
                .show();
    }

    /** Protocol, status, software and keyprints of a hub (copyable). */
    private void showHubDetails(HubEntry entry) {
        HubAddress addr = entry.session != null ? entry.session.address() : HubAddress.tryParse(entry.hubIp);
        StringBuilder sb = new StringBuilder();
        sb.append("Address: ").append(addr != null ? addr.toUrl() : entry.hubIp).append('\n');
        sb.append("Protocol: ").append(addr != null ? addr.protocol.label : "?")
                .append(addr != null && addr.protocol.secure ? " (encrypted)" : "").append('\n');
        if (entry.session != null) {
            DCPPService.HubSession s = entry.session;
            if (s.hubName != null) sb.append("Name: ").append(s.hubName).append('\n');
            sb.append("Status: ").append(s.status).append('\n');
            if (s.hubSoftware != null) sb.append("Software: ").append(s.hubSoftware).append('\n');
            if (s.hubKeyprint != null) sb.append("\nHub keyprint (this connection):\n").append(s.hubKeyprint).append('\n');
            if (s.keyprintProblem != null) sb.append("\n⚠ ").append(s.keyprintProblem).append('\n');
        }
        if (addr != null && addr.protocol.secure) {
            if (addr.kp != null) sb.append("\nKeyprint in the address:\n").append(addr.kp).append('\n');
            String remembered = KeyprintStore.get(mainActivity, addr);
            sb.append("\nTrusted keyprint:\n").append(remembered != null ? remembered : "(none yet: remembered on first connection)").append('\n');
        }
        final String text = sb.toString().trim();
        new AlertDialog.Builder(getActivity())
                .setTitle("Hub details")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            mainActivity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Hub details", text));
                        Toast.makeText(getActivity(), "Copied", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showEditNickPasswordDialog(final String hubIp) {
        final android.widget.LinearLayout layout = new android.widget.LinearLayout(getActivity());
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        final android.widget.EditText nickInput = new android.widget.EditText(getActivity());
        nickInput.setHint("Enter nickname for this hub");
        SharedPreferences prefs = mainActivity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String savedNick = getHubPref(prefs, "favorite_nicks_", hubIp);
        if (savedNick.isEmpty()) {
            savedNick = prefs.getString(Constants.SETTINGS_NICK_KEY, "");
        }
        nickInput.setText(savedNick);
        layout.addView(nickInput);

        final android.widget.EditText passInput = new android.widget.EditText(getActivity());
        passInput.setHint("Enter hub password");
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String savedPass = getHubPref(prefs, "favorite_passwords_", hubIp);
        passInput.setText(savedPass);
        layout.addView(passInput);

        new AlertDialog.Builder(getActivity())
                .setTitle("Edit Nick & Password for " + hubIp)
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String nick = nickInput.getText().toString();
                    String pass = passInput.getText().toString();
                    SharedPreferences.Editor editor = prefs.edit();
                    
                    putHubPref(editor, "favorite_nicks_", hubIp, nick);
                    putHubPref(editor, "favorite_passwords_", hubIp, pass);
                    editor.apply();
                    saveHub(mainActivity, hubIp); // details saved for a hub = keep the hub in the list
                    refresh();

                    if (mainActivity.mService != null) {
                        DCPPService.HubSession session = sessionForHub(hubIp);
                        if (session != null) {
                            if (session.prefs != null) {
                                session.prefs.password = pass;
                            }
                            if (session.myuser != null && !nick.isEmpty()) {
                                session.myuser.nick = nick;
                            }
                        }
                    }
                    Toast.makeText(getActivity(), "Saved details for " + hubIp, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showPasswordDialog(final String hubIp) {
        final android.widget.EditText input = new android.widget.EditText(getActivity());
        input.setHint("Enter hub password");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        SharedPreferences prefs = mainActivity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String savedPass = getHubPref(prefs, "favorite_passwords_", hubIp);
        input.setText(savedPass);

        new AlertDialog.Builder(getActivity())
                .setTitle("Save Password for " + hubIp)
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String pass = input.getText().toString();
                    SharedPreferences.Editor editor = prefs.edit();
                    putHubPref(editor, "favorite_passwords_", hubIp, pass);
                    editor.apply();
                    if (mainActivity.mService != null) {
                        DCPPService.HubSession session = sessionForHub(hubIp);
                        if (session != null && session.prefs != null) {
                            session.prefs.password = pass;
                        }
                    }
                    Toast.makeText(getActivity(), "Password saved for " + hubIp, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void refresh() {
        if (autoReconnectCheckBox != null && mainActivity != null) {
            autoReconnectCheckBox.setChecked(Settings.getAutoReconnectHubs(mainActivity));
        }
        if (adapter != null) {
            adapter.updateEntries();
            adapter.notifyDataSetChanged();
        }
    }

    private class HubEntry {
        String hubIp;
        DCPPService.HubSession session;
        boolean isFavorite;

        HubEntry(String hubIp, DCPPService.HubSession session, boolean isFavorite) {
            this.hubIp = hubIp;
            this.session = session;
            this.isFavorite = isFavorite;
        }
    }

    private class HubListAdapter extends BaseAdapter {
        private List<HubEntry> entries = new ArrayList<>();

        private void updateEntries() {
            List<HubEntry> newEntries = new ArrayList<>();
            SharedPreferences prefs = mainActivity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
            Set<String> favorites = prefs.getStringSet(FAVORITE_HUBS_PREF, new HashSet<String>());
            
            List<DCPPService.HubSession> activeSessions = new ArrayList<>();
            if (mainActivity.mService != null) {
                activeSessions = mainActivity.mService.getActiveSessions();
            }

            Set<String> processedIps = new HashSet<>();

            for (DCPPService.HubSession session : activeSessions) {
                // NMDC: "host[:port]" as before; NMDCS: "nmdcs://host:port", matching its saved entry.
                String hubIdentifier = session.address().id();
                boolean nmdc = session.protocol == HubAddress.Protocol.NMDC;
                newEntries.add(new HubEntry(hubIdentifier, session,
                        (nmdc && favorites.contains(session.hubIp)) || favorites.contains(hubIdentifier)));
                if (nmdc) processedIps.add(session.hubIp);
                processedIps.add(hubIdentifier);
            }

            for (String favIp : favorites) {
                if (!processedIps.contains(favIp)) {
                    // Check if favIp contains port already or if we can find matching active session
                    boolean alreadyAdded = false;
                    for (HubEntry e : newEntries) {
                        if (e.hubIp.startsWith(favIp) || favIp.startsWith(e.hubIp)) {
                            alreadyAdded = true;
                            break;
                        }
                    }
                    if (!alreadyAdded) {
                        newEntries.add(new HubEntry(favIp, null, true));
                    }
                }
            }
            this.entries = newEntries;
        }

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getActivity()).inflate(R.layout.hub_list_item, parent, false);
            }
            final HubEntry entry = (HubEntry) getItem(position);
            TextView address = (TextView) convertView.findViewById(R.id.hub_address);
            TextView status = (TextView) convertView.findViewById(R.id.hub_status);
            TextView info = (TextView) convertView.findViewById(R.id.hub_info);

            String favIndicator = entry.isFavorite ? " ✓" : "";

            if (entry.session != null) {
                String hubNameStr = entry.session.hubName;
                String hubAddressStr = entry.session.hubIp + ":" + entry.session.hubPort
                        + (entry.session.protocol.secure ? " 🔒 " + entry.session.protocol.label
                        : entry.session.protocol.isAdc() ? " " + entry.session.protocol.label : "");
                String statusStr = entry.session.keyprintProblem != null
                        ? (entry.session.pendingNewKeyprint != null ? "⚠ KEYPRINT CHANGED" : "⚠ WRONG KEYPRINT")
                        : entry.session.status != null ? entry.session.status.toString() : "UNKNOWN";
                
                if (hubNameStr != null && !hubNameStr.trim().isEmpty()) {
                    address.setText(hubNameStr.trim() + favIndicator);
                    status.setText(hubAddressStr + " (" + statusStr + ")");
                } else {
                    address.setText(hubAddressStr + favIndicator);
                    status.setText(statusStr);
                }
                
                int userCount = 0;
                String tag = "N/A";
                com.phinvader.libjdcpp.hub.HubConnection hubLink = entry.session.hub; // NMDC or ADC
                if (hubLink != null) {
                    List<DCUser> nicks = hubLink.users();
                    if (nicks != null) userCount = nicks.size();
                    if (entry.session.myuser != null && entry.session.myuser.tag != null) tag = entry.session.myuser.tag;
                }
                info.setText("Users: " + userCount + " | Tag: " + tag);
            } else {
                // Try retrieving saved hub name for offline favorite hubs if present
                SharedPreferences prefs = mainActivity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
                HubAddress addr = HubAddress.tryParse(entry.hubIp);
                String savedName = getHubPref(prefs, "hub_name_", entry.hubIp);
                // Protocols this build can't run yet (ADC/ADCS/NMDCS) say so instead of "OFFLINE".
                boolean unsupported = addr != null && !addr.isSupported();
                String state = unsupported ? addr.unsupportedReason() : "OFFLINE";
                if (!savedName.trim().isEmpty()) {
                    address.setText(savedName.trim() + favIndicator);
                    status.setText(entry.hubIp + " (" + state + ")");
                } else {
                    address.setText(entry.hubIp + favIndicator);
                    status.setText(unsupported ? state : "OFFLINE (Favorite)");
                }
                info.setText(addr != null ? "Protocol: " + addr.protocol.label : "");
            }

            // Color the favorite tick green if it's a favorite
            if (entry.isFavorite) {
                android.text.SpannableString spannable = new android.text.SpannableString(address.getText());
                int tickIndex = spannable.toString().lastIndexOf("✓");
                if (tickIndex >= 0) {
                    spannable.setSpan(new android.text.style.ForegroundColorSpan(0xFF4CAF50), tickIndex, tickIndex + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                address.setText(spannable);
            }

            return convertView;
        }

        private void connectToHub(String hubIp) {
            HubAddress addr = HubAddress.tryParse(hubIp);
            if (addr == null) {
                Toast.makeText(mainActivity, "Invalid Hub Address", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!addr.isSupported()) {
                Toast.makeText(mainActivity, hubIp + ": " + addr.unsupportedReason(), Toast.LENGTH_LONG).show();
                return;
            }

            // Prefer the nick saved for this hub ("Edit Nick & Password"), else the Login tab nick.
            String nick = mainActivity.resolveNickForHub(addr);
            if (nick.isEmpty()) {
                Toast.makeText(mainActivity, "Set a nick for this hub (Edit Nick & Password) or on the Login tab", Toast.LENGTH_LONG).show();
                showEditNickPasswordDialog(hubIp);
                return;
            }

            mainActivity.startBackgroundService(nick, addr, true);
        }

        private void toggleFavorite(String hubIp) {
            SharedPreferences prefs = mainActivity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
            Set<String> favorites = new HashSet<>(prefs.getStringSet(FAVORITE_HUBS_PREF, new HashSet<String>()));
            if (favorites.contains(hubIp)) {
                favorites.remove(hubIp);
            } else {
                favorites.add(hubIp);
            }
            prefs.edit().putStringSet(FAVORITE_HUBS_PREF, favorites).apply();
        }
    }
}
