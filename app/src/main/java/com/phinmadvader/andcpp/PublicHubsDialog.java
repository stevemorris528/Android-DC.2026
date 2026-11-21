package com.phinmadvader.andcpp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import com.phinvader.libjdcpp.hub.HubAddress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PublicHubsDialog {
    private static final String TAG = "PublicHubsDialog";
    public static final String DEFAULT_HUBLIST_URL = "http://hublist.pwiam.com/hublist.xml.bz2";
    private static final String PREF_HUBLIST_URLS = "saved_hublist_urls";
    private static final String PREF_SELECTED_HUBLIST_URL = "selected_hublist_url";

    public static class PublicHub {
        public String name = "";
        public String address = "";
        public String description = "";
        public int users = 0;
        public long shared = 0;
        public String country = "";
    }

    private final MainActivity activity;
    private AlertDialog dialog;
    private ProgressBar progressBar;
    private TextView statusText;
    private ListView listView;
    private Spinner urlSpinner;
    private ArrayAdapter<String> spinnerAdapter;
    private PublicHubAdapter adapter;
    private DownloadHubListTask downloadTask;

    private static List<PublicHub> cachedHubs = null;
    private static String cachedForUrl = null;

    public PublicHubsDialog(MainActivity activity) {
        this.activity = activity;
    }

    public void show() {
        LayoutInflater inflater = LayoutInflater.from(activity);
        View dialogView = inflater.inflate(R.layout.public_hubs_dialog, null);

        progressBar = dialogView.findViewById(R.id.public_hubs_progress);
        statusText = dialogView.findViewById(R.id.public_hubs_status_text);
        listView = dialogView.findViewById(R.id.public_hubs_list);
        urlSpinner = dialogView.findViewById(R.id.public_hubs_url_spinner);
        Button btnRefresh = dialogView.findViewById(R.id.public_hubs_btn_refresh);
        Button btnAddUrl = dialogView.findViewById(R.id.public_hubs_btn_add_url);
        Button btnEditUrls = dialogView.findViewById(R.id.public_hubs_btn_edit_urls);

        adapter = new PublicHubAdapter(activity);
        listView.setAdapter(adapter);

        setupUrlSpinner();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            PublicHub hub = (PublicHub) adapter.getItem(position);
            if (hub != null) {
                showHubActionDialog(hub);
            }
        });

        btnRefresh.setOnClickListener(v -> loadHubList(true));
        btnAddUrl.setOnClickListener(v -> showAddUrlDialog());
        btnEditUrls.setOnClickListener(v -> showEditUrlsDialog());

        dialog = new AlertDialog.Builder(activity)
                .setTitle("Public Hubs")
                .setView(dialogView)
                .setNegativeButton("Close", (d, which) -> {
                    if (downloadTask != null) {
                        downloadTask.cancel(true);
                    }
                })
                .create();

        dialog.setOnDismissListener(d -> {
            if (downloadTask != null) {
                downloadTask.cancel(true);
            }
        });

        dialog.show();

        String selectedUrl = getSelectedUrl();
        if (cachedHubs != null && !cachedHubs.isEmpty() && selectedUrl.equals(cachedForUrl)) {
            adapter.setHubs(cachedHubs);
        } else {
            loadHubList(false);
        }
    }

    private List<String> getSavedUrls() {
        SharedPreferences prefs = activity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String savedString = prefs.getString(PREF_HUBLIST_URLS, "");
        List<String> list = new ArrayList<>();
        if (!savedString.isEmpty()) {
            String[] split = savedString.split("\n");
            for (String s : split) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty() && !list.contains(trimmed)) {
                    list.add(trimmed);
                }
            }
        }
        if (list.isEmpty()) {
            list.add(DEFAULT_HUBLIST_URL);
            saveUrls(list);
        }
        return list;
    }

    private void saveUrls(List<String> urls) {
        SharedPreferences prefs = activity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(urls.get(i).trim());
        }
        prefs.edit().putString(PREF_HUBLIST_URLS, sb.toString()).apply();
    }

    private String getSelectedUrl() {
        SharedPreferences prefs = activity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String selected = prefs.getString(PREF_SELECTED_HUBLIST_URL, "");
        List<String> urls = getSavedUrls();
        if (selected.isEmpty() || !urls.contains(selected)) {
            return urls.get(0);
        }
        return selected;
    }

    private void setSelectedUrl(String url) {
        SharedPreferences prefs = activity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_SELECTED_HUBLIST_URL, url).apply();
    }

    private void setupUrlSpinner() {
        final List<String> urls = getSavedUrls();
        String currentSelected = getSelectedUrl();

        spinnerAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, urls);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        urlSpinner.setAdapter(spinnerAdapter);

        int selectedIndex = urls.indexOf(currentSelected);
        if (selectedIndex != -1) {
            urlSpinner.setSelection(selectedIndex);
        }

        urlSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String chosen = urls.get(position);
                String previouslySelected = getSelectedUrl();
                if (!chosen.equals(previouslySelected)) {
                    setSelectedUrl(chosen);
                    loadHubList(false);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void showAddUrlDialog() {
        final EditText input = new EditText(activity);
        input.setHint("http://example.com/hublist.xml.bz2");
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);

        new AlertDialog.Builder(activity)
                .setTitle("Add Hublist URL")
                .setView(input)
                .setPositiveButton("Add", (d, which) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "http://" + url;
                    }
                    List<String> urls = getSavedUrls();
                    if (!urls.contains(url)) {
                        urls.add(url);
                        saveUrls(urls);
                        setSelectedUrl(url);
                        setupUrlSpinner();
                        loadHubList(true);
                        Toast.makeText(activity, "URL added", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(activity, "URL already exists", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditUrlsDialog() {
        final List<String> urls = getSavedUrls();
        final String[] items = urls.toArray(new String[0]);

        new AlertDialog.Builder(activity)
                .setTitle("Select URL to Edit or Remove")
                .setItems(items, (d, which) -> {
                    final String chosenUrl = items[which];
                    showEditSingleUrlDialog(chosenUrl);
                })
                .setNegativeButton("Done", null)
                .show();
    }

    private void showEditSingleUrlDialog(final String originalUrl) {
        final EditText input = new EditText(activity);
        input.setText(originalUrl);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);

        new AlertDialog.Builder(activity)
                .setTitle("Edit Hublist URL")
                .setView(input)
                .setPositiveButton("Save", (d, which) -> {
                    String newUrl = input.getText().toString().trim();
                    if (newUrl.isEmpty()) return;
                    if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                        newUrl = "http://" + newUrl;
                    }
                    List<String> urls = getSavedUrls();
                    int index = urls.indexOf(originalUrl);
                    if (index != -1) {
                        urls.set(index, newUrl);
                    }
                    saveUrls(urls);
                    if (getSelectedUrl().equals(originalUrl)) {
                        setSelectedUrl(newUrl);
                    }
                    setupUrlSpinner();
                    loadHubList(true);
                    Toast.makeText(activity, "URL updated", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Delete", (d, which) -> {
                    List<String> urls = getSavedUrls();
                    if (urls.size() <= 1) {
                        Toast.makeText(activity, "Cannot delete the only URL", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    urls.remove(originalUrl);
                    saveUrls(urls);
                    if (getSelectedUrl().equals(originalUrl)) {
                        setSelectedUrl(urls.get(0));
                    }
                    setupUrlSpinner();
                    loadHubList(true);
                    Toast.makeText(activity, "URL deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadHubList(boolean forceRefresh) {
        String targetUrl = getSelectedUrl();

        if (!forceRefresh && cachedHubs != null && !cachedHubs.isEmpty() && targetUrl.equals(cachedForUrl)) {
            adapter.setHubs(cachedHubs);
            listView.setVisibility(View.VISIBLE);
            statusText.setVisibility(View.GONE);
            progressBar.setVisibility(View.GONE);
            return;
        }

        if (downloadTask != null) {
            downloadTask.cancel(true);
        }

        progressBar.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.VISIBLE);
        statusText.setText("Downloading public hublist...");
        listView.setVisibility(View.GONE);

        downloadTask = new DownloadHubListTask();
        downloadTask.execute(targetUrl);
    }

    private void showHubActionDialog(final PublicHub hub) {
        final String cleanAddress = cleanAddress(hub.address);
        String[] options = new String[] {
                "Connect to Hub",
                "Add to Favorite Hubs",
                "Copy Address"
        };

        new AlertDialog.Builder(activity)
                .setTitle(hub.name != null && !hub.name.isEmpty() ? hub.name : cleanAddress)
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: // Connect
                            connectToHub(cleanAddress);
                            if (dialog != null && dialog.isShowing()) {
                                dialog.dismiss();
                            }
                            break;
                        case 1: // Add to Favorite Hubs
                            addToFavorites(cleanAddress);
                            break;
                        case 2: // Copy Address
                            copyToClipboard(cleanAddress);
                            break;
                    }
                })
                .show();
    }

    private void connectToHub(String cleanAddress) {
        SharedPreferences prefs = activity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String nick = prefs.getString(Constants.SETTINGS_NICK_KEY, "");
        if (nick.isEmpty()) {
            Toast.makeText(activity, "Please set a nickname in Login tab first", Toast.LENGTH_SHORT).show();
            activity.switchToLoginTab();
            return;
        }

        HubAddress addr;
        try {
            addr = HubAddress.parse(cleanAddress);
        } catch (IllegalArgumentException e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (activity.startBackgroundService(nick, addr, true)) {
            activity.switchToHubsTab();
        }
    }

    private void addToFavorites(String cleanAddress) {
        if (HubAddress.tryParse(cleanAddress) == null) {
            Toast.makeText(activity, "Invalid hub address: " + cleanAddress, Toast.LENGTH_SHORT).show();
            return;
        }
        if (HubListFragment.saveHub(activity, cleanAddress)) {
            Toast.makeText(activity, "Added to Favorite Hubs", Toast.LENGTH_SHORT).show();
            activity.refreshHubList();
        } else {
            Toast.makeText(activity, "Already in Favorite Hubs", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("Hub Address", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(activity, "Address copied to clipboard", Toast.LENGTH_SHORT).show();
    }

    /**
     * Normalises a hub list address to the form the app saves: NMDC hubs ({@code dchub://}) lose the scheme
     * as before ({@code host:port}); ADC, ADCS and NMDCS hubs keep theirs ({@code adcs://host:port}), so
     * they are no longer mistaken for NMDC hubs.
     */
    public static String cleanAddress(String rawAddress) {
        if (rawAddress == null) return "";
        HubAddress parsed = HubAddress.tryParse(rawAddress);
        if (parsed != null) return parsed.id();
        // Unparseable (e.g. an ADC address without a port): show it as listed. Only dchub:// is stripped;
        // an adc:// scheme stays, so it can't be saved or connected to as an NMDC hub.
        String addr = rawAddress.trim();
        if (addr.startsWith("dchub://")) {
            addr = addr.substring("dchub://".length());
        }
        if (addr.endsWith("/")) {
            addr = addr.substring(0, addr.length() - 1);
        }
        return addr;
    }

    public static String formatSize(long size) {
        if (size <= 0) return "0 B";
        final String[] units = new String[] { "B", "KB", "MB", "GB", "TB", "PB" };
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private class DownloadHubListTask extends AsyncTask<String, String, List<PublicHub>> {
        private String targetUrl = "";
        private String errorMessage = null;

        @Override
        protected List<PublicHub> doInBackground(String... params) {
            targetUrl = params[0];
            // Clear cachedHubs if re-downloading
            cachedHubs = null;
            cachedForUrl = null;
            List<PublicHub> hubs = new ArrayList<>();
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                URL url = new URL(targetUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android DC++)");

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    errorMessage = "Server returned HTTP " + responseCode;
                    return null;
                }

                in = new BufferedInputStream(conn.getInputStream());

                InputStream xmlStream;
                if (targetUrl.toLowerCase().contains(".bz2")) {
                    xmlStream = new BZip2CompressorInputStream(in);
                } else {
                    xmlStream = in;
                }

                XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                factory.setNamespaceAware(false);
                XmlPullParser parser = factory.newPullParser();
                parser.setInput(xmlStream, null);

                int eventType = parser.getEventType();
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (isCancelled()) {
                        return null;
                    }

                    if (eventType == XmlPullParser.START_TAG) {
                        String tagName = parser.getName();
                        if ("Hub".equalsIgnoreCase(tagName)) {
                            PublicHub hub = new PublicHub();
                            hub.name = getAttr(parser, "Name");
                            hub.address = getAttr(parser, "Address");
                            hub.description = getAttr(parser, "Description");
                            hub.country = getAttr(parser, "Country");

                            String usersStr = getAttr(parser, "Users");
                            if (usersStr != null && !usersStr.isEmpty()) {
                                try {
                                    hub.users = Integer.parseInt(usersStr.trim());
                                } catch (NumberFormatException ignored) {}
                            }

                            String sharedStr = getAttr(parser, "Shared");
                            if (sharedStr != null && !sharedStr.isEmpty()) {
                                try {
                                    hub.shared = Long.parseLong(sharedStr.trim());
                                } catch (NumberFormatException ignored) {}
                            }

                            if (!hub.address.isEmpty()) {
                                hubs.add(hub);
                            }
                        }
                    }
                    eventType = parser.next();
                }

                // Sort by user count descending
                Collections.sort(hubs, (h1, h2) -> Integer.compare(h2.users, h1.users));

                return hubs;
            } catch (Exception e) {
                Log.e(TAG, "Error downloading or parsing hublist from " + targetUrl, e);
                errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
                return null;
            } finally {
                if (in != null) {
                    try { in.close(); } catch (Exception ignored) {}
                }
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
            }
        }

        private String getAttr(XmlPullParser parser, String name) {
            String val = parser.getAttributeValue(null, name);
            if (val == null) {
                for (int i = 0; i < parser.getAttributeCount(); i++) {
                    if (name.equalsIgnoreCase(parser.getAttributeName(i))) {
                        return parser.getAttributeValue(i);
                    }
                }
            }
            return val != null ? val : "";
        }

        @Override
        protected void onPostExecute(List<PublicHub> result) {
            progressBar.setVisibility(View.GONE);
            if (result != null && !result.isEmpty()) {
                cachedHubs = result;
                cachedForUrl = targetUrl;
                statusText.setVisibility(View.GONE);
                listView.setVisibility(View.VISIBLE);
                adapter.setHubs(result);
            } else {
                statusText.setVisibility(View.VISIBLE);
                statusText.setText("Failed to load hubs" + (errorMessage != null ? ":\n" + errorMessage : ""));
                listView.setVisibility(View.GONE);
            }
        }
    }

    private static class PublicHubAdapter extends BaseAdapter {
        private final Context context;
        private List<PublicHub> hubs = new ArrayList<>();

        public PublicHubAdapter(Context context) {
            this.context = context;
        }

        public void setHubs(List<PublicHub> hubs) {
            this.hubs = hubs != null ? hubs : new ArrayList<>();
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return hubs.size();
        }

        @Override
        public Object getItem(int position) {
            return hubs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.public_hub_list_item, parent, false);
            }

            PublicHub hub = hubs.get(position);

            TextView nameView = convertView.findViewById(R.id.public_hub_name);
            TextView usersView = convertView.findViewById(R.id.public_hub_users);
            TextView addressView = convertView.findViewById(R.id.public_hub_address);
            TextView descView = convertView.findViewById(R.id.public_hub_description);
            TextView sharedView = convertView.findViewById(R.id.public_hub_shared);

            nameView.setText(hub.name != null && !hub.name.isEmpty() ? hub.name : cleanAddress(hub.address));
            usersView.setText(hub.users + " users");
            addressView.setText(cleanAddress(hub.address));

            if (hub.description != null && !hub.description.isEmpty()) {
                descView.setVisibility(View.VISIBLE);
                descView.setText(hub.description);
            } else {
                descView.setVisibility(View.GONE);
            }

            StringBuilder meta = new StringBuilder();
            if (hub.shared > 0) {
                meta.append("Shared: ").append(formatSize(hub.shared));
            }
            if (hub.country != null && !hub.country.isEmpty()) {
                if (meta.length() > 0) meta.append(" | ");
                meta.append("Country: ").append(hub.country);
            }

            if (meta.length() > 0) {
                sharedView.setVisibility(View.VISIBLE);
                sharedView.setText(meta.toString());
            } else {
                sharedView.setVisibility(View.GONE);
            }

            return convertView;
        }
    }
}
