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

        final String[] options = optionsList.toArray(new String[0]);

        new AlertDialog.Builder(getActivity())
                .setTitle(entry.hubIp)
                .setItems(options, (dialog, which) -> {
                    String selected = options[which];
                    switch (selected) {
                        case "Open Hub":
                        case "Open Hub Chat":
                            String targetIp = entry.session != null ? entry.session.hubIp : entry.hubIp.split(":")[0];
                            mainActivity.openChatForHub(targetIp);
                            break;
                        case "Reconnect":
                            if (mainActivity.mService != null && entry.session != null
                                    && mainActivity.mService.reconnectHub(entry.session.hubIp)) {
                                Toast.makeText(getActivity(), "Reconnecting to " + entry.session.hubIp, Toast.LENGTH_SHORT).show();
                                listView.postDelayed(this::refresh, 500);
                            } else {
                                adapter.connectToHub(entry.hubIp);
                            }
                            break;
                        case "Delete Hub":
                            if (mainActivity.mService != null && entry.session != null) {
                                mainActivity.mService.shutdownSession(entry.session.hubIp);
                            }
                            SharedPreferences delPrefs = mainActivity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
                            Set<String> delFavorites = new HashSet<>(delPrefs.getStringSet(FAVORITE_HUBS_PREF, new HashSet<String>()));
                            delFavorites.remove(entry.hubIp);
                            if (entry.session != null) {
                                delFavorites.remove(entry.session.hubIp + ":" + entry.session.hubPort);
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
        String savedNick = prefs.getString("favorite_nicks_" + hubIp, "");
        if (savedNick.isEmpty() && hubIp.contains(":")) {
            savedNick = prefs.getString("favorite_nicks_" + hubIp.split(":")[0], "");
        }
        if (savedNick.isEmpty()) {
            savedNick = prefs.getString(Constants.SETTINGS_NICK_KEY, "");
        }
        nickInput.setText(savedNick);
        layout.addView(nickInput);

        final android.widget.EditText passInput = new android.widget.EditText(getActivity());
        passInput.setHint("Enter hub password");
        passInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String savedPass = prefs.getString("favorite_passwords_" + hubIp, "");
        if (savedPass.isEmpty() && hubIp.contains(":")) {
            savedPass = prefs.getString("favorite_passwords_" + hubIp.split(":")[0], "");
        }
        passInput.setText(savedPass);
        layout.addView(passInput);

        new AlertDialog.Builder(getActivity())
                .setTitle("Edit Nick & Password for " + hubIp)
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String nick = nickInput.getText().toString();
                    String pass = passInput.getText().toString();
                    SharedPreferences.Editor editor = prefs.edit();
                    
                    editor.putString("favorite_nicks_" + hubIp, nick);
                    if (hubIp.contains(":")) {
                        editor.putString("favorite_nicks_" + hubIp.split(":")[0], nick);
                    }

                    editor.putString("favorite_passwords_" + hubIp, pass);
                    if (hubIp.contains(":")) {
                        editor.putString("favorite_passwords_" + hubIp.split(":")[0], pass);
                    }
                    editor.apply();

                    if (mainActivity.mService != null) {
                        DCPPService.HubSession session = mainActivity.mService.getSession(hubIp);
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
        String savedPass = prefs.getString("favorite_passwords_" + hubIp, "");
        if (savedPass.isEmpty() && hubIp.contains(":")) {
            savedPass = prefs.getString("favorite_passwords_" + hubIp.split(":")[0], "");
        }
        input.setText(savedPass);

        new AlertDialog.Builder(getActivity())
                .setTitle("Save Password for " + hubIp)
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String pass = input.getText().toString();
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putString("favorite_passwords_" + hubIp, pass);
                    if (hubIp.contains(":")) {
                        editor.putString("favorite_passwords_" + hubIp.split(":")[0], pass);
                    }
                    editor.apply();
                    if (mainActivity.mService != null) {
                        DCPPService.HubSession session = mainActivity.mService.getSession(hubIp);
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
                String hubIdentifier = session.hubIp + (session.hubPort != 411 ? ":" + session.hubPort : "");
                newEntries.add(new HubEntry(hubIdentifier, session, favorites.contains(session.hubIp) || favorites.contains(hubIdentifier)));
                processedIps.add(session.hubIp);
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
                String hubAddressStr = entry.session.hubIp + ":" + entry.session.hubPort;
                String statusStr = entry.session.status != null ? entry.session.status.toString() : "UNKNOWN";
                
                if (hubNameStr != null && !hubNameStr.trim().isEmpty()) {
                    address.setText(hubNameStr.trim() + favIndicator);
                    status.setText(hubAddressStr + " (" + statusStr + ")");
                } else {
                    address.setText(hubAddressStr + favIndicator);
                    status.setText(statusStr);
                }
                
                int userCount = 0;
                String tag = "N/A";
                if (entry.session.client != null) {
                    List<DCUser> nicks = entry.session.client.get_nick_list();
                    if (nicks != null) userCount = nicks.size();
                    if (entry.session.myuser != null && entry.session.myuser.tag != null) tag = entry.session.myuser.tag;
                }
                info.setText("Users: " + userCount + " | Tag: " + tag);
            } else {
                // Try retrieving saved hub name for offline favorite hubs if present
                SharedPreferences prefs = mainActivity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
                String savedName = prefs.getString("hub_name_" + entry.hubIp, null);
                if (savedName != null && !savedName.trim().isEmpty()) {
                    address.setText(savedName.trim() + favIndicator);
                    status.setText(entry.hubIp + " (OFFLINE)");
                } else {
                    address.setText(entry.hubIp + favIndicator);
                    status.setText("OFFLINE (Favorite)");
                }
                info.setText("");
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
            String ip = hubIp;
            String port = "411";
            String[] parts = hubIp.split(":");
            if (parts.length == 2) {
                ip = parts[0];
                port = parts[1];
            } else if (parts.length > 2) {
                Toast.makeText(mainActivity, "Invalid Hub Address", Toast.LENGTH_SHORT).show();
                return;
            }

            // Prefer the nick saved for this hub ("Edit Nick & Password"), else the Login tab nick.
            String nick = mainActivity.resolveNickForHub(hubIp, ip, port);
            if (nick.isEmpty()) {
                Toast.makeText(mainActivity, "Set a nick for this hub (Edit Nick & Password) or on the Login tab", Toast.LENGTH_LONG).show();
                showEditNickPasswordDialog(hubIp);
                return;
            }

            mainActivity.startBackgroundService(nick, ip, port);
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
