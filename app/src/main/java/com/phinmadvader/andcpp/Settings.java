package com.phinmadvader.andcpp;

import java.io.File;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

public class Settings extends Activity{
	
	private EditText inputDirectoryTextView;
	
	@Override
	public void onCreate(Bundle savedInstanceState){
		ThemeUtils.applyTheme(this);
		super.onCreate(savedInstanceState);

		if (getActionBar() != null) getActionBar().setTitle("Settings");
		buildSettingsScreen();
	}

	// ---- Settings screen: grouped cards in the app's theme (surface colour, accent headings) ----

	/** Value lines under each row, refreshed whenever a dialog closes (see onWindowFocusChanged). */
	private final java.util.List<Runnable> summaryUpdaters = new java.util.ArrayList<>();

	private int dp(float v) {
		return Math.round(v * getResources().getDisplayMetrics().density);
	}

	private int themeColor(int attr, int fallback) {
		android.util.TypedValue tv = new android.util.TypedValue();
		if (getTheme().resolveAttribute(attr, tv, true)) {
			return tv.resourceId != 0 ? getResources().getColor(tv.resourceId) : tv.data;
		}
		return fallback;
	}

	private interface Summary {
		String get();
	}

	private void buildSettingsScreen() {
		boolean dark = ThemeUtils.isDarkMode(this);
		int bg = themeColor(R.attr.appBackgroundColor, dark ? 0xFF121212 : 0xFFF5F5F5);

		android.widget.ScrollView scroll = new android.widget.ScrollView(this);
		scroll.setBackgroundColor(bg);
		scroll.setFillViewport(true);
		android.widget.LinearLayout root = new android.widget.LinearLayout(this);
		root.setOrientation(android.widget.LinearLayout.VERTICAL);
		root.setPadding(dp(16), dp(8), dp(16), dp(24));
		scroll.addView(root);

		final com.phinmadvader.andcpp.pia.PIAPortForwardingManager pia =
				com.phinmadvader.andcpp.pia.PIAPortForwardingManager.getInstance(this);

		android.widget.LinearLayout card = addSection(root, "Appearance");
		addRow(card, "Theme", () -> ThemeUtils.isDarkMode(this) ? "Dark mode" : "Light mode", this::changeThemeMode);
		addRow(card, "Accent colour", this::accentName, this::changeAccentColor);

		card = addSection(root, "Transfers");
		addRow(card, "Downloads folder", () -> getDCDownloadsDirectory(this), this::changeDownloadsDirectory);
		addRow(card, "Upload slots", () -> getUploadSlots(this) + " at a time", this::changeUploadSlots);

		card = addSection(root, "Hubs");
		addRow(card, "Auto-reconnect saved hubs", () -> getAutoReconnectHubs(this) ? "On at startup" : "Off",
				this::changeAutoReconnectHubs);

		card = addSection(root, "Connection");
		addRow(card, "VPN forwarded port", () -> {
			int p = getVpnForwardedPort(this);
			return p > 0 ? "Port " + p : "Not set (for active mode on a VPN)";
		}, this::changeVpnForwardedPort);
		addRow(card, "PiA port forwarding", () -> {
			if (!pia.isEnabled()) return "Off";
			String port = pia.getCurrentPort();
			return port != null ? "On, port " + port : "On (automatic)";
		}, this::changePiaPortForwarding);
		addRow(card, "PiA login", () -> pia.hasCredentials() ? "Saved: " + pia.getUsername() : "Not set",
				this::changePiaLogin);

		card = addSection(root, "About");
		addRow(card, "Check for updates", () -> "Installed build " + BuildConfig.BUILD_NUMBER,
				() -> UpdateChecker.checkNow(true));

		setContentView(scroll);
		refreshSummaries();
	}

	private String accentName() {
		String c = ThemeUtils.getColorAccent(this);
		if (ThemeUtils.COLOR_BLUE.equals(c)) return "Blue";
		if (ThemeUtils.COLOR_GREEN.equals(c)) return "Green";
		if (ThemeUtils.COLOR_ORANGE.equals(c)) return "Orange";
		if (ThemeUtils.COLOR_PURPLE.equals(c)) return "Purple";
		return "Teal (default)";
	}

	/** Adds an accent-coloured section heading and returns the rounded card that holds its rows. */
	private android.widget.LinearLayout addSection(android.widget.LinearLayout root, String title) {
		android.widget.TextView heading = new android.widget.TextView(this);
		heading.setText(title.toUpperCase(java.util.Locale.US));
		heading.setTextSize(12);
		if (android.os.Build.VERSION.SDK_INT >= 21) heading.setLetterSpacing(0.08f);
		heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
		heading.setTextColor(ThemeUtils.getAccentColor(this));
		heading.setPadding(dp(8), dp(20), dp(8), dp(8));
		root.addView(heading);

		boolean dark = ThemeUtils.isDarkMode(this);
		android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
		shape.setColor(themeColor(R.attr.appSurfaceColor, dark ? 0xFF1E1E1E : 0xFFFFFFFF));
		shape.setCornerRadius(dp(16));
		shape.setStroke(dp(1), themeColor(R.attr.appDividerColor, dark ? 0xFF333333 : 0xFFEEEEEE));

		android.widget.LinearLayout card = new android.widget.LinearLayout(this);
		card.setOrientation(android.widget.LinearLayout.VERTICAL);
		card.setBackgroundDrawable(shape);
		if (android.os.Build.VERSION.SDK_INT >= 21) {
			card.setClipToOutline(true); // row ripples stay inside the rounded corners
			card.setElevation(dp(1));
		}
		root.addView(card);
		return card;
	}

	/** Adds a row (title + current value) to a card; tapping it runs action. */
	private void addRow(android.widget.LinearLayout card, String title, Summary summary, Runnable action) {
		boolean dark = ThemeUtils.isDarkMode(this);
		if (card.getChildCount() > 0) {
			View divider = new View(this);
			divider.setBackgroundColor(themeColor(R.attr.appDividerColor, dark ? 0xFF333333 : 0xFFEEEEEE));
			android.widget.LinearLayout.LayoutParams lp =
					new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
			lp.leftMargin = dp(16);
			card.addView(divider, lp);
		}

		android.widget.LinearLayout row = new android.widget.LinearLayout(this);
		row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
		row.setGravity(android.view.Gravity.CENTER_VERTICAL);
		row.setPadding(dp(16), dp(14), dp(12), dp(14));
		row.setMinimumHeight(dp(64));
		android.util.TypedValue ripple = new android.util.TypedValue();
		getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
		row.setBackgroundResource(ripple.resourceId);

		android.widget.LinearLayout texts = new android.widget.LinearLayout(this);
		texts.setOrientation(android.widget.LinearLayout.VERTICAL);
		row.addView(texts, new android.widget.LinearLayout.LayoutParams(0,
				android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		android.widget.TextView titleView = new android.widget.TextView(this);
		titleView.setText(title);
		titleView.setTextSize(16);
		titleView.setTextColor(themeColor(R.attr.appTextColorPrimary, dark ? 0xFFEEEEEE : 0xFF212121));
		texts.addView(titleView);

		final android.widget.TextView valueView = new android.widget.TextView(this);
		valueView.setTextSize(13);
		valueView.setTextColor(themeColor(R.attr.appTextColorSecondary, dark ? 0xFFB0B0B0 : 0xFF757575));
		valueView.setPadding(0, dp(2), 0, 0);
		valueView.setMaxLines(2);
		valueView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
		texts.addView(valueView);
		summaryUpdaters.add(() -> {
			String s;
			try {
				s = summary.get();
			} catch (Exception e) {
				s = null;
			}
			valueView.setText(s);
			valueView.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
		});

		android.widget.TextView chevron = new android.widget.TextView(this);
		chevron.setText("›");
		chevron.setTextSize(22);
		chevron.setTextColor(themeColor(R.attr.appTextColorSecondary, dark ? 0xFFB0B0B0 : 0xFF757575));
		chevron.setPadding(dp(12), 0, dp(4), 0);
		row.addView(chevron);

		row.setOnClickListener(v -> action.run());
		card.addView(row);
	}

	private void refreshSummaries() {
		for (Runnable r : summaryUpdaters) r.run();
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) refreshSummaries(); // a settings dialog just closed: show the new values
	}

	@Override
	protected void onResume() {
		super.onResume();
		UpdateChecker.setForeground(this); // an update found by "Check for Updates" is offered here
	}

	@Override
	protected void onPause() {
		UpdateChecker.setForeground(null);
		super.onPause();
	}

	public void changeThemeMode() {
		final String[] modes = {"Light Mode", "Dark Mode"};
		int current = ThemeUtils.isDarkMode(this) ? 1 : 0;
		new AlertDialog.Builder(this)
				.setTitle("Select Theme Mode")
				.setSingleChoiceItems(modes, current, (dialog, which) -> {
					ThemeUtils.setDarkMode(Settings.this, which == 1);
					dialog.dismiss();
					Toast.makeText(Settings.this, "Theme set to " + modes[which], Toast.LENGTH_SHORT).show();
					recreate();
				}).show();
	}

	public void changeAccentColor() {
		final String[] colorNames = {"Teal (Default)", "Blue", "Green", "Orange", "Purple"};
		final String[] colorKeys = {
				ThemeUtils.COLOR_TEAL,
				ThemeUtils.COLOR_BLUE,
				ThemeUtils.COLOR_GREEN,
				ThemeUtils.COLOR_ORANGE,
				ThemeUtils.COLOR_PURPLE
		};
		String currentColor = ThemeUtils.getColorAccent(this);
		int current = 0;
		for (int i = 0; i < colorKeys.length; i++) {
			if (colorKeys[i].equals(currentColor)) {
				current = i;
				break;
			}
		}
		new AlertDialog.Builder(this)
				.setTitle("Select Accent Color")
				.setSingleChoiceItems(colorNames, current, (dialog, which) -> {
					ThemeUtils.setColorAccent(Settings.this, colorKeys[which]);
					dialog.dismiss();
					Toast.makeText(Settings.this, "Accent set to " + colorNames[which], Toast.LENGTH_SHORT).show();
					recreate();
				}).show();
	}
	
	public void changeDownloadsDirectory(){
		DialogInterface.OnClickListener positive;
		positive = new DialogInterface.OnClickListener() {
			
			@Override
			public void onClick(DialogInterface dialog, int which) {
				putDCDownloadsDirectory(inputDirectoryTextView.getText().toString(), getApplicationContext());
				Toast.makeText(getApplicationContext(), "Saved", Toast.LENGTH_LONG).show();
			}
		};
		inputDirectoryTextView	= new EditText(Settings.this);
		inputDirectoryTextView.setHint("Enter the directory here...");
		inputDirectoryTextView.setText(getDCDownloadsDirectory(getApplicationContext()));
		
		DisplayAlert("Enter new Downloads Directory", 
				"Current Directory is "+getDCDownloadsDirectory(getApplicationContext()), 
				"Save", positive, 
				"Cancel", null,
				inputDirectoryTextView);
	}

	public void changeUploadSlots() {
		final EditText input = new EditText(this);
		input.setText(String.valueOf(getUploadSlots(this)));
		DisplayAlert("Change Upload Slots", "Enter max simultaneous uploads:", "Save", (dialog, which) -> {
			try {
				int slots = Integer.parseInt(input.getText().toString());
				if (slots < 1 || slots > 50) throw new NumberFormatException();
				putUploadSlots(slots, Settings.this);
				Toast.makeText(Settings.this, "Slots saved.", Toast.LENGTH_SHORT).show();
			} catch (NumberFormatException e) {
				Toast.makeText(Settings.this, "Invalid number of slots", Toast.LENGTH_SHORT).show();
			}
		}, "Cancel", null, input);
	}

	/** Automatic PIA port forwarding on/off (needs the PIA login below). */
	public void changePiaPortForwarding() {
		final com.phinmadvader.andcpp.pia.PIAPortForwardingManager pia =
				com.phinmadvader.andcpp.pia.PIAPortForwardingManager.getInstance(this);
		final String[] options = {"Off", "On"};
		new AlertDialog.Builder(this)
				.setTitle("PIA Port Forwarding")
				.setSingleChoiceItems(options, pia.isEnabled() ? 1 : 0, (dialog, which) -> {
					boolean on = which == 1;
					pia.setEnabled(on);
					dialog.dismiss();
					if (on && !pia.hasCredentials()) {
						Toast.makeText(Settings.this, "Now enter your PIA login", Toast.LENGTH_LONG).show();
						changePiaLogin();
					} else {
						Toast.makeText(Settings.this, on
								? "PIA port forwarding on: the app gets a port while PIA is connected"
								: "PIA port forwarding off", Toast.LENGTH_SHORT).show();
					}
				}).show();
	}

	/** PIA username/password, used only to get a PIA token for port forwarding. */
	public void changePiaLogin() {
		final com.phinmadvader.andcpp.pia.PIAPortForwardingManager pia =
				com.phinmadvader.andcpp.pia.PIAPortForwardingManager.getInstance(this);
		android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
		layout.setOrientation(android.widget.LinearLayout.VERTICAL);
		int pad = Math.round(16 * getResources().getDisplayMetrics().density);
		layout.setPadding(pad, pad / 2, pad, 0);

		final EditText user = new EditText(this);
		user.setHint("PIA username (p1234567)");
		user.setSingleLine(true);
		// No auto-capitalisation / suggestions: keyboards turn "p1234567" into "P1234567", which PIA rejects.
		user.setInputType(android.text.InputType.TYPE_CLASS_TEXT
				| android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
				| android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		user.setText(pia.getUsername());
		layout.addView(user);

		final EditText pass = new EditText(this);
		pass.setHint("PIA password");
		// Show the saved password (masked as dots) so it's clear it was kept; "Show password" reveals it.
		pass.setText(getSharedPreferences(Constants.PREFS_NAME, 0)
				.getString(com.phinmadvader.andcpp.pia.PIAPortForwardingManager.PREF_PASSWORD, ""));
		pass.setSingleLine(true);
		pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
				| android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		layout.addView(pass);

		// Let the user check what they typed (password fields hide it).
		final android.widget.CheckBox show = new android.widget.CheckBox(this);
		show.setText("Show password");
		show.setOnCheckedChangeListener((b, checked) -> {
			int cursor = pass.getSelectionEnd();
			pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
					| (checked ? android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
					: android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD));
			pass.setSelection(Math.max(0, Math.min(cursor, pass.getText().length())));
		});
		layout.addView(show);

		int savedLen = pia.getSavedPasswordLength();
		String status = savedLen > 0
				? "Password: saved (" + savedLen + " characters)."
				: "Password: not saved yet.";

		DisplayAlert("PIA Login",
				status + "\n\nUsed only to get a PIA token for port forwarding. Stored in this app's private storage "
						+ "on the phone and sent only to PIA.",
				"Save", (dialog, which) -> {
					String u = user.getText().toString().trim();
					String p = pass.getText().toString();
					if (u.isEmpty()) {
						pia.setCredentials("", "");
						Toast.makeText(Settings.this, "PIA login removed", Toast.LENGTH_SHORT).show();
						return;
					}
					if (p.isEmpty()) {
						Toast.makeText(Settings.this, "Enter the PIA password", Toast.LENGTH_SHORT).show();
						return;
					}
					pia.setCredentials(u, p);
					if (!pia.isEnabled()) pia.setEnabled(true);
					Toast.makeText(Settings.this, "PIA login saved (" + p.length() + "-character password). Checking with PIA…",
							Toast.LENGTH_SHORT).show();
					// Check it with PIA right away, so a wrong login is obvious (network: off the UI thread).
					new Thread(() -> {
						final String problem = pia.testLogin();
						runOnUiThread(() -> Toast.makeText(Settings.this,
								problem == null ? "PIA login OK: port forwarding will start" : "PIA: " + problem,
								Toast.LENGTH_LONG).show());
					}, "PIA-login-check").start();
				}, "Cancel", null, layout);
	}

	/** The port your VPN forwards to this phone (shown in the VPN app, e.g. PIA "Port forwarding"). */
	public void changeVpnForwardedPort() {
		final EditText input = new EditText(this);
		input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
		int current = getVpnForwardedPort(this);
		input.setHint("e.g. 47123 (leave empty for none)");
		if (current > 0) input.setText(String.valueOf(current));
		DisplayAlert("VPN Forwarded Port",
				"If your VPN forwards a port to this phone (PIA: Settings > Network > Port Forwarding), enter it here. "
						+ "While the VPN is on, the app listens on this port and goes active. "
						+ "Some VPNs give a new port on every reconnect: update it here when that happens.",
				"Save", (dialog, which) -> {
					String s = input.getText().toString().trim();
					try {
						int port = s.isEmpty() ? 0 : Integer.parseInt(s);
						if (port != 0 && (port < 1024 || port > 65535)) throw new NumberFormatException();
						putVpnForwardedPort(port, Settings.this);
						Toast.makeText(Settings.this, port == 0 ? "VPN port cleared" : "VPN port saved: " + port,
								Toast.LENGTH_SHORT).show();
					} catch (NumberFormatException e) {
						Toast.makeText(Settings.this, "Invalid port (1024-65535)", Toast.LENGTH_SHORT).show();
					}
				}, "Cancel", null, input);
	}

	public void changeAutoReconnectHubs() {
		final String[] options = {"Disabled", "Enabled"};
		int current = getAutoReconnectHubs(this) ? 1 : 0;
		new AlertDialog.Builder(this)
				.setTitle("Auto-Reconnect Saved Hubs on Startup")
				.setSingleChoiceItems(options, current, (dialog, which) -> {
					putAutoReconnectHubs(which == 1, Settings.this);
					Toast.makeText(Settings.this, "Auto-reconnect " + options[which], Toast.LENGTH_SHORT).show();
					dialog.dismiss();
				}).show();
	}

	public String DisplayAlert(String title, //Title for the alert
			String message, //Message for the alert
			String positiveString, //Positive option string
			DialogInterface.OnClickListener positiveOnClick, //Positive on click listener
			String negativeString, //Negative option string
			DialogInterface.OnClickListener negativeOnClick, //Negative on click listener
			View v //Extra view to be added
			){
	//lv.setAdapter();
	
	AlertDialog.Builder myAlertBuilder = new AlertDialog.Builder(Settings.this);
	AlertDialog myAlert = myAlertBuilder
			.setTitle(title)
			.setMessage(message)
			.setPositiveButton(positiveString, positiveOnClick)
			.setNegativeButton(negativeString, negativeOnClick)
			.setCancelable(false)
			.setView(v)
			.create();
			myAlert.show();
	return null;
	}
	
	// Helper functions.
	public static String getDCDownloadsDirectory(Context c) {
		SharedPreferences settingsSharedPreferences = c.getSharedPreferences(
				"settingsSharedPreferences", 0);
		
		String defaultDir;
		if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
			defaultDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/Android-DC++";
		} else {
			defaultDir = c.getExternalFilesDir(null) != null ? 
					c.getExternalFilesDir(null).getAbsolutePath() + "/Android-DC++" :
					Constants.dcDirectory;
		}

		String dcDir = settingsSharedPreferences.getString("dcDirectory", defaultDir);
		// Check if download folder exists , else create
		File folder = new File(dcDir);
		boolean success = true;
		if (!folder.exists()) {
			try {
				success = folder.mkdirs();
			} catch (SecurityException e) {
				Log.e("andcpp", "SecurityException creating directory: " + dcDir, e);
				success = false;
			}
			if (success) {
				Log.d("andcpp", "Created download directory: " + dcDir);
			} else {
				Log.e("andcpp", "Failed to create download directory: " + dcDir);
			}
		}
		return dcDir;
	}
       
    public static void putDCDownloadsDirectory(String dcDir, Context c) {
    	SharedPreferences settingsSharedPreferences = c.getSharedPreferences("settingsSharedPreferences", 0);
    	settingsSharedPreferences.edit().putString("dcDirectory", dcDir).commit();
    }

	public static int getListeningPort(Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		return prefs.getInt(Constants.SETTINGS_PORT_KEY, Constants.DEFAULT_PORT);
	}

	public static void putListeningPort(int port, Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		prefs.edit().putInt(Constants.SETTINGS_PORT_KEY, port).apply();
	}

	public static boolean getActiveMode(Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		// true = active mode allowed: it is auto-detected per network and falls back to passive when other users
		// can't reach us (see ConnectivityDetector). false = always passive.
		return prefs.getBoolean(Constants.SETTINGS_ACTIVE_KEY, true);
	}

	public static void putActiveMode(boolean active, Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		prefs.edit().putBoolean(Constants.SETTINGS_ACTIVE_KEY, active).apply();
	}

	/** Port the VPN provider forwards to this phone (e.g. PIA "port forwarding"); 0 = none. */
	public static int getVpnForwardedPort(Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		return prefs.getInt(Constants.SETTINGS_VPN_PORT_KEY, 0);
	}

	public static void putVpnForwardedPort(int port, Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		prefs.edit().putInt(Constants.SETTINGS_VPN_PORT_KEY, port).commit();
		// Keep the backup (survives a clean install) in step.
		com.phinmadvader.andcpp.pia.PIAPortForwardingManager.getInstance(c).saveBackup();
	}

	public static int getUploadSlots(Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		return prefs.getInt(Constants.SETTINGS_UPLOAD_SLOTS_KEY, Constants.DEFAULT_UPLOAD_SLOTS);
	}

	public static void putUploadSlots(int slots, Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		prefs.edit().putInt(Constants.SETTINGS_UPLOAD_SLOTS_KEY, slots).apply();
	}

	public static boolean getAutoReconnectHubs(Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		return prefs.getBoolean(Constants.SETTINGS_AUTO_RECONNECT_HUBS_KEY, false);
	}

	public static void putAutoReconnectHubs(boolean autoReconnect, Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		prefs.edit().putBoolean(Constants.SETTINGS_AUTO_RECONNECT_HUBS_KEY, autoReconnect).apply();
	}

	public static boolean getWifiOnlyDownloads(Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		return prefs.getBoolean(Constants.SETTINGS_WIFI_ONLY_DOWNLOADS_KEY, false);
	}

	public static void putWifiOnlyDownloads(boolean wifiOnly, Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		prefs.edit().putBoolean(Constants.SETTINGS_WIFI_ONLY_DOWNLOADS_KEY, wifiOnly).apply();
	}

	public static int getDownloadWorkers(Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		return prefs.getInt(Constants.SETTINGS_DOWNLOAD_WORKERS_KEY, Constants.DEFAULT_DOWNLOAD_WORKERS);
	}

	public static void putDownloadWorkers(int workers, Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		prefs.edit().putInt(Constants.SETTINGS_DOWNLOAD_WORKERS_KEY, workers).apply();
	}
}
