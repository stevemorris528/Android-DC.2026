package com.phinmadvader.andcpp;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SearchView;
import android.widget.SearchView.OnQueryTextListener;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

import com.phinvader.libjdcpp.DCCommand;
import com.phinvader.libjdcpp.DCFileList;
import com.phinvader.libjdcpp.DCMessage;
import com.phinvader.libjdcpp.hub.HubAddress;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import android.webkit.MimeTypeMap;
import android.net.Uri;
import android.support.v4.content.FileProvider;

public class MainActivity extends FragmentActivity implements
		OnQueryTextListener, DCCommand {

	DCPPService mService;
	boolean mBound = false;

	private Poller poller;
	private SearchView searchview;
	private MenuItem searchmenuitem;
	
	private Intent serviceIntent;
	private AlertDialog myProgressDialog;
	private boolean hasAutoReconnected = false;
	private myBroadcastReceiver myBR = null;
	/**
	 * TODO: make tab_page_adapter private? or leave as it is...
	 * tab_page_adapters is public so that filelistmanager can update its pages
	 */
	public TabPagerAdapter tab_page_adapter;
	private ViewPager view_pager;
	public FileListManager filelist_manager;
	public PrivateChatManager privatechat_manager;
	private LoginFragment login_fragment;
	private HubListFragment hublist_fragment;

	public HubListFragment get_hublist_fragment() {
		return hublist_fragment;
	}

	public void openChatForHub(String hubIp) {
		if (tab_page_adapter == null) return;

		if (messageboard_fragment != null) {
			messageboard_fragment.setHubIp(hubIp);
			tab_page_adapter.add_tab(TabPagerAdapter.TAB_HUBCHAT,
					messageboard_fragment, "Chat", false);
		}

		if (userlist_fragment != null) {
			userlist_fragment.setHubIp(hubIp);
			tab_page_adapter.add_tab(TabPagerAdapter.TAB_USERLIST,
					userlist_fragment, "Users", false);
		}

		if (mService != null) {
			mService.setBoard_message_handler(hubIp, this);
			mService.setUser_handler(hubIp, this);
			mService.setBoard_message_handler(null, this);
			mService.setUser_handler(null, this);
		}

		tab_page_adapter.notifyDataSetChanged();

		int chatPos = tab_page_adapter.get_tab_position(TabPagerAdapter.TAB_HUBCHAT);
		if (chatPos != -1 && view_pager != null) {
			view_pager.post(() -> {
				view_pager.setCurrentItem(chatPos, true);
				ActionBar ab = getActionBar();
				if (ab != null && chatPos < ab.getTabCount()) {
					ab.setSelectedNavigationItem(chatPos);
				}
			});
		}
	}

	public void openUsersForHub(String hubIp) {
		if (tab_page_adapter == null) return;

		if (userlist_fragment != null) {
			userlist_fragment.setHubIp(hubIp);
			tab_page_adapter.add_tab(TabPagerAdapter.TAB_USERLIST,
					userlist_fragment, "Users", false);
		}

		tab_page_adapter.notifyDataSetChanged();

		int userPos = tab_page_adapter.get_tab_position(TabPagerAdapter.TAB_USERLIST);
		if (userPos != -1 && view_pager != null) {
			view_pager.post(() -> {
				view_pager.setCurrentItem(userPos, true);
				ActionBar ab = getActionBar();
				if (ab != null && userPos < ab.getTabCount()) {
					ab.setSelectedNavigationItem(userPos);
				}
			});
		}
	}
	public void refreshWelcomeFragment() {
		if (tab_page_adapter != null) {
			Fragment frag = tab_page_adapter.get_tab_fragment(TabPagerAdapter.TAB_WELCOME);
			if (frag instanceof WelcomeFragment) {
				((WelcomeFragment) frag).refreshStatus();
			}
		}
	}

	public ViewPager get_view_pager() {
		return view_pager;
	}

	private MessageBoardFragment messageboard_fragment;
	private UserListFragment userlist_fragment;
	private DownloadListFragment downloadlist_fragment;
	private TotalsFragment totals_fragment;
	private SearchResultsFragment searchresult_fragment;

	boolean exitFlag = false;
	Toast myToast;
	
	// FileList stack variables
	// TODO: This ought to be nested in some filelist management class
	// (filelistfragment? sth new?) and not strewn around randomly as presently
	public DCFileList rootfileList;
	public String chosenNick;

	public FileListManager get_filelist_manager() {
		return filelist_manager;
	}

	public PrivateChatManager get_privatechat_manager() {
		return privatechat_manager;
	}

	private final ServiceConnection mConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			DCPPService.LocalBinder binder = (DCPPService.LocalBinder) service;
			mService = binder.getService();
			mBound = true;
			Log.d("andcpp", "mservice is bound");

			// Rebuild our shared file list on every app open (cheap: hashes are cached).
			mService.refreshMyFileListSoon();
			// The menu may have been built before the service was bound: re-check the search icon.
			invalidateOptionsMenu();

			refreshWelcomeFragment();

			// Refresh UI state from active sessions
			List<DCPPService.HubSession> sessions = mService.getActiveSessions();
			for (DCPPService.HubSession session : sessions) {
				if (session.status == DCPPService.DCClientStatus.CONNECTED) {
					onConnectUIChanges(session.hubIp);
				}
			}

			// Auto-reconnect saved hubs on startup if enabled
			checkAutoReconnectSavedHubs();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			mBound = false;
			finish();
			Log.d("andcpp", "mservice is got disconnected");
		}
	};

	@Override
	public void onCommand(final DCMessage msg) {
		// These all cause UI changes, so force run on UI thread by default
		if (msg == null || msg.command == null) return; // unparsed message: switch(null) would crash
		runOnUiThread(() -> {
			switch (msg.command) {
				case "MyINFO":
					if (userlist_fragment != null && userlist_fragment.is_ready)
						userlist_fragment.addOneNick(msg);
					break;
				case "Quit":
					if (userlist_fragment != null && userlist_fragment.is_ready)
						userlist_fragment.delNick(msg);
					break;
				case "SR":
					if (searchresult_fragment != null)
						searchresult_fragment.add_search_result(msg);
					break;
				case "BoardMessage":
					if (messageboard_fragment != null
							&& messageboard_fragment.is_ready)
						messageboard_fragment.add_msg(msg.hubname_s, msg.msg_s);
					break;
				case "To:":
					if (msg.pm_from != null) {
						open_private_chat(msg.hubname_s, msg.pm_from);
					}
					break;
				case "CLOSE_UI":
					// Only finish if all sessions are closed? 
					// For now let's just log it.
					Log.d("andcpp", "Session closed");
					break;
			}
		});
	}

	public SharedPreferences getprefs() {
		return getSharedPreferences(Constants.PREFS_NAME, 0);
	}

	private boolean currentIsDark;
	private String currentAccent;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		SettingsBackup.restoreIfNeeded(this); // after a clean install (if storage permission is already granted)
		initDefaultPreferences();
		currentIsDark = ThemeUtils.isDarkMode(this);
		currentAccent = ThemeUtils.getColorAccent(this);
		ThemeUtils.applyTheme(this);
		super.onCreate(savedInstanceState);

		// Request Storage Permissions for Android 6.0+
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
					!= PackageManager.PERMISSION_GRANTED) {
				ActivityCompat.requestPermissions(this,
						new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
			}
			
			// Request Battery Optimization Ignore for background connection stability
			android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
			if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
				Intent intent = new Intent();
				intent.setAction(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
				intent.setData(android.net.Uri.parse("package:" + getPackageName()));
				startActivity(intent);
			}
		}

		bindService(new Intent(this, DCPPService.class), mConnection,
				Context.BIND_AUTO_CREATE);
		setContentView(R.layout.main_activity);
		ActionBar actionBar = getActionBar();
		if (actionBar != null) {
			actionBar.setDisplayShowHomeEnabled(false);
			actionBar.setDisplayShowTitleEnabled(false);
			actionBar.setDisplayShowCustomEnabled(true);
			actionBar.setCustomView(R.layout.action_bar_title);
			actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);
		}
		view_pager = findViewById(R.id.pager_root);
		view_pager.setOffscreenPageLimit(32); // TODO : FIXTHIS
		// ^ this is a hack, basically without this fragments keep getting
		// recreated which is painful for us to maintain state, moreover
		// saveinstance doesn't seem to work on loginframgnet, resulting in
		// loginfragment being reset to a state without a disconnect button
		// which is problematic

		filelist_manager = new FileListManager(this);
		privatechat_manager = new PrivateChatManager(this);
		tab_page_adapter = new TabPagerAdapter(getSupportFragmentManager(),
				getActionBar(), view_pager, filelist_manager, privatechat_manager); // attaches and
																// manages
																// view_pager

		WelcomeFragment welcome_fragment = new WelcomeFragment();
		login_fragment = new LoginFragment();
		hublist_fragment = new HubListFragment();
		messageboard_fragment = new MessageBoardFragment();
		userlist_fragment = new UserListFragment();
		downloadlist_fragment = new DownloadListFragment();
		totals_fragment = new TotalsFragment();
		searchresult_fragment = new SearchResultsFragment();

		tab_page_adapter.add_tab(TabPagerAdapter.TAB_WELCOME,
				welcome_fragment, "Welcome", true);

		tab_page_adapter.add_tab(TabPagerAdapter.TAB_LOGININFO,
				login_fragment, "Login", false);

		// Add Hub List tab
		tab_page_adapter.add_tab(TabPagerAdapter.TAB_HUBLIST,
				hublist_fragment, "Hubs", false);

		// Always show Saved File Lists tab
		tab_page_adapter.add_tab(TabPagerAdapter.TAB_SAVED_FILELISTS,
				new SavedFileListsFragment(), "Saved", false);

		// Always make Downloads tab visible
		tab_page_adapter.add_tab(TabPagerAdapter.TAB_DOWNLOADS,
				downloadlist_fragment, "Downloads", false);

		// Totals tab (all-time and session DL/UL), right after Downloads
		tab_page_adapter.add_tab(TabPagerAdapter.TAB_TOTALS,
				totals_fragment, "Totals", false);

		// Always make Hub Users tab visible
		tab_page_adapter.add_tab(TabPagerAdapter.TAB_USERLIST,
				userlist_fragment, "Users", false);

		// Always make Hub Chat tab visible
		tab_page_adapter.add_tab(TabPagerAdapter.TAB_HUBCHAT,
				messageboard_fragment, "Chat", false);

		tab_page_adapter.notifyDataSetChanged();

		// Check and restore last opened file list (do not auto-restore on restart to prevent crash if file list is invalid or removed)
		// String lastFileListNick = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
		// 		.getString("last_open_filelist_nick", null);
		// if (lastFileListNick != null && !lastFileListNick.isEmpty()) {
		// 	open_cached_file_list(lastFileListNick);
		// }

		// Ensure we start at the Welcome tab unless a file list was reopened
		view_pager.post(new Runnable() {
			@Override
			public void run() {
				if (tab_page_adapter != null) {
					int welcomePos = tab_page_adapter.get_tab_position(TabPagerAdapter.TAB_WELCOME);
					if (welcomePos != -1) {
						view_pager.setCurrentItem(welcomePos, false);
						ActionBar ab = getActionBar();
						if (ab != null && welcomePos < ab.getTabCount()) {
							ab.setSelectedNavigationItem(welcomePos);
						}
					}
				}
			}
		});

		Log.d("andcpp", "CREATION");
		clearUpdateNotifications();
		UpdateChecker.checkNow(false); // app opened: the process (kept alive by the service) may be hours old
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		Log.d("andcpp", "MainActivity Saving state");
	}

	@Override
	protected void onDestroy() {
		tab_page_adapter = null;
		login_fragment = null;
		hublist_fragment = null;
		messageboard_fragment = null;
		userlist_fragment = null;
		filelist_manager = null;
		searchresult_fragment = null;
		chosenNick = null;
		rootfileList = null;
		if (view_pager != null) {
			view_pager.removeAllViews();
		}
		if (myBR != null) {
			try {
				unregisterReceiver(myBR);
			} catch (Exception ignored) {}
			myBR = null;
		}
		if (mBound) {
			unbindService(mConnection);
			mBound = false;
		}
		super.onDestroy();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.connect, menu);
		searchmenuitem = menu.findItem(R.id.action_search);
		if (searchmenuitem != null) {
			searchview = (SearchView) searchmenuitem.getActionView();
			if (searchview != null) {
				searchview.setSubmitButtonEnabled(true);
				searchview.setOnQueryTextListener(this);
				searchview.setQueryHint("Search all connected hubs");
			}
			updateSearchMenuVisibility();
		}
		return true;
	}

	private boolean isAnyHubConnected() {
		if (mService == null) return false;
		for (DCPPService.HubSession s : mService.getActiveSessions()) {
			if (s != null && s.status == DCPPService.DCClientStatus.CONNECTED) return true;
		}
		return false;
	}

	/** The search icon is shown while at least one hub is connected. */
	private void updateSearchMenuVisibility() {
		if (searchmenuitem != null) {
			searchmenuitem.setVisible(isAnyHubConnected());
		}
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		MenuItem wifiOnly = menu.findItem(R.id.action_wifi_only);
		if (wifiOnly != null) {
			wifiOnly.setChecked(Settings.getWifiOnlyDownloads(this));
		}		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		int id = item.getItemId();
		if (id == R.id.action_public_hubs) {
			showPublicHubsDialog();
			return true;
		} else if (id == R.id.action_about) {
			showAboutDialog();
			return true;
		} else if (id == R.id.action_wifi_only) {
			boolean wifiOnly = !item.isChecked();
			item.setChecked(wifiOnly);
			Settings.putWifiOnlyDownloads(wifiOnly, this); // the service reacts via its preference listener
			Toast.makeText(this,
					wifiOnly ? "Downloads and uploads only over Wi-Fi" : "Downloads and uploads allowed on Wi-Fi and mobile data",
					Toast.LENGTH_SHORT).show();
			return true;
		} else if (id == R.id.action_settings) {
			Intent settingsIntent = new Intent(this, Settings.class);
			startActivity(settingsIntent);
			return true;
		} else if (id == R.id.action_license) {
			showLicenseDialog();
			return true;
		} else if (id == R.id.action_search) {
			if (searchmenuitem != null) {
				searchmenuitem.expandActionView();
				if (searchview != null) {
					searchview.setIconified(false);
					searchview.requestFocus();
				}
			}
			return true;
		} else if (id == R.id.action_shutdown) {
			shutdownAppCompletely();
			return true;
		} else {
			Log.i("andcpp", "menu item " + id + "click not implemented.");
			return true;
		}
	}

	private void showAboutDialog() {
		String aboutHtml = "<p><b>Android DC++</b></p>"
				+ "<p>An open-source Android DC++ client based on the older AnDCpp project, with further development, improvements, and new features.</p>"
				+ "<p><b>Original Developers &amp; Credits</b><br/>"
				+ "&#8226; Anish Shankar<br/>"
				+ "&#8226; Lakshminarayanan Srinivasan<br/>"
				+ "&#8226; Madhavan Malolan</p>"
				+ "<p>Full credit to the original developers for their open-source contribution. This project builds upon their original work.</p>"
				+ "<p>Original project: AnDCpp</p>"
				+ "<p>Developed further by the Android DC++ project.</p>"
				+ "<p>GitHub Source Code: <a href=\"https://github.com/stevemorris528/Android-DC.2026/blob/master/README.md\">&#x2B07;&#xFE0F;</a></p>"
				// Filled in by Gradle on every build (see app/build.gradle)
				+ "<p>Build " + BuildConfig.BUILD_NUMBER + " &middot; " + BuildConfig.BUILD_DATE + "</p>";

		TextView textView = new TextView(this);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			textView.setText(Html.fromHtml(aboutHtml, Html.FROM_HTML_MODE_LEGACY));
		} else {
			textView.setText(Html.fromHtml(aboutHtml));
		}
		textView.setMovementMethod(LinkMovementMethod.getInstance());
		int padding = (int) (18 * getResources().getDisplayMetrics().density);
		textView.setPadding(padding, padding / 2, padding, padding / 2);
		textView.setTextSize(14);
		textView.setLineSpacing(0, 1.2f);

		ScrollView scrollView = new ScrollView(this);
		scrollView.addView(textView);

		new AlertDialog.Builder(this)
				.setIcon(R.drawable.ic_launcher)
				.setTitle("About Android DC++ 2026")
				.setView(scrollView)
				.setPositiveButton("OK", null)
				.show();
	}

	private void showLicenseDialog() {
		String licenseText = "GNU GENERAL PUBLIC LICENSE\n" +
				"Version 3, 29 June 2007\n\n" +
				"Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>\n" +
				"Everyone is permitted to copy and distribute verbatim copies\n" +
				"of this license document, but changing it is not allowed.\n\n" +
				"Preamble\n\n" +
				"The GNU General Public License is a free, copyleft license for\n" +
				"software and other kinds of works.\n\n" +
				"This application is licensed under the GNU GPLv3.\n" +
				"You can redistribute it and/or modify it under the terms of the\n" +
				"GNU General Public License as published by the Free Software Foundation,\n" +
				"either version 3 of the License, or (at your option) any later version.\n\n" +
				"For details, visit: https://www.gnu.org/licenses/gpl-3.0.html";

		TextView textView = new TextView(this);
		textView.setText(licenseText);
		int padding = (int) (18 * getResources().getDisplayMetrics().density);
		textView.setPadding(padding, padding / 2, padding, padding / 2);
		textView.setTextSize(13);
		textView.setLineSpacing(0, 1.2f);

		ScrollView scrollView = new ScrollView(this);
		scrollView.addView(textView);

		new AlertDialog.Builder(this)
				.setIcon(R.drawable.ic_launcher)
				.setTitle("GNU GPLv3 License")
				.setView(scrollView)
				.setPositiveButton("OK", null)
				.show();
	}

	private void showPublicHubsDialog() {
		new PublicHubsDialog(this).show();
	}

	public void switchToLoginTab() {
		if (tab_page_adapter != null && view_pager != null) {
			int pos = tab_page_adapter.get_tab_position(TabPagerAdapter.TAB_LOGININFO);
			if (pos != -1) {
				view_pager.post(() -> {
					view_pager.setCurrentItem(pos, true);
					ActionBar ab = getActionBar();
					if (ab != null && pos < ab.getTabCount()) {
						ab.setSelectedNavigationItem(pos);
					}
				});
			}
		}
	}

	public void switchToHubsTab() {
		if (tab_page_adapter != null && view_pager != null) {
			int pos = tab_page_adapter.get_tab_position(TabPagerAdapter.TAB_HUBLIST);
			if (pos != -1) {
				view_pager.post(() -> {
					view_pager.setCurrentItem(pos, true);
					ActionBar ab = getActionBar();
					if (ab != null && pos < ab.getTabCount()) {
						ab.setSelectedNavigationItem(pos);
					}
				});
			}
		}
	}

	public void refreshHubList() {
		if (hublist_fragment != null) {
			hublist_fragment.refresh();
		}
		refreshWelcomeFragment();
	}

	private boolean shuttingDown = false;

	public void shutdownAppCompletely() {
		if (shuttingDown) return; // Shutdown tapped twice
		shuttingDown = true;

		// Pausing downloads, closing connections and saving state can take several seconds. It used to run
		// on the UI thread, so the app just froze; now a window explains the wait while it runs in the background.
		final AlertDialog closing = showClosingDialog();
		final DCPPService service = mService;
		new Thread(() -> {
			// Don't stop the service or finish until pausing/saving has completely finished.
			if (service != null) {
				try {
					service.onDestroy();
				} catch (Exception e) {
					Log.e("andcpp", "Error while shutting down transfers", e);
				}
			}
			runOnUiThread(() -> {
				try {
					if (closing.isShowing()) closing.dismiss();
				} catch (Exception ignored) {}
				stopBackgroundService();
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
					finishAffinity();
				} else {
					finish();
				}
			});
		}, "AppShutdown").start();
	}

	/** "Closing transfers please wait :-)" with a spinner in the accent colour; can't be dismissed. */
	private AlertDialog showClosingDialog() {
		float d = getResources().getDisplayMetrics().density;
		android.widget.LinearLayout row = new android.widget.LinearLayout(this);
		row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
		row.setGravity(android.view.Gravity.CENTER_VERTICAL);
		int pad = Math.round(24 * d);
		row.setPadding(pad, pad, pad, pad);

		android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
		spinner.setIndeterminate(true);
		if (Build.VERSION.SDK_INT >= 21) {
			spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ThemeUtils.getAccentColor(this)));
		}
		row.addView(spinner, new android.widget.LinearLayout.LayoutParams(Math.round(36 * d), Math.round(36 * d)));

		TextView text = new TextView(this);
		text.setText("Closing transfers please wait :-)");
		text.setTextSize(16);
		android.util.TypedValue tv = new android.util.TypedValue();
		if (getTheme().resolveAttribute(R.attr.appTextColorPrimary, tv, true)) {
			text.setTextColor(tv.resourceId != 0 ? getResources().getColor(tv.resourceId) : tv.data);
		}
		text.setPadding(Math.round(20 * d), 0, 0, 0);
		row.addView(text);

		AlertDialog dialog = new AlertDialog.Builder(this)
				.setView(row)
				.setCancelable(false)
				.create();
		dialog.setCanceledOnTouchOutside(false);
		dialog.show();
		return dialog;
	}

	public void open_cached_file_list(String nick) {
		DCFileList fileList = null;
		
		java.io.File fileListDir = new java.io.File(Settings.getDCDownloadsDirectory(this), "FileLists");
		java.io.File saved_file_list = new java.io.File(fileListDir, nick + ".xml");
		if (saved_file_list.exists()) {
			fileList = DCFileList.parseXML(saved_file_list);
		}

		if (fileList != null) {
			MainActivity.this.rootfileList = fileList;
			MainActivity.this.chosenNick = nick;
			if (filelist_manager != null) {
				filelist_manager.refreshToNewFileList();
			}
			if (tab_page_adapter != null) {
				tab_page_adapter.notifyDataSetChanged();
				tab_page_adapter.add_tab(TabPagerAdapter.TAB_FILELIST,
						null, fileListTabTitle(nick));
				tab_page_adapter.move_to_last_page();
			}
			try {
				getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
						.edit()
						.putString("last_open_filelist_nick", nick)
						.putBoolean("last_open_filelist_offline", true)
						.apply();
			} catch (Exception ignored) {}
		} else {
			Toast.makeText(this, "Failed to load saved file list.", Toast.LENGTH_SHORT).show();
		}
	}
	
	/** The app is open again: drop the "updated, tap to reopen" notice. */
	private void clearUpdateNotifications() {
		NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (nm != null) nm.cancel(UpdateReceiver.RESTART_NOTIFICATION_ID);
	}

	@Override
	protected void onPause() {
		UpdateChecker.setForeground(null);
		if (poller != null) {
			poller.stop();
			poller = null;
		}
		super.onPause();
	}

	@Override
	protected void onResume() {
		super.onResume();
		boolean darkNow = ThemeUtils.isDarkMode(this);
		String accentNow = ThemeUtils.getColorAccent(this);
		if (darkNow != currentIsDark || !accentNow.equals(currentAccent)) {
			recreate();
			return;
		}
		UpdateChecker.setForeground(this);
		poller = new Poller(this); // start poller, will take care of service
									// connect/disconnect updates
	}

	@Override
	public boolean onQueryTextChange(String newText) {
		// Nothing to do here , not possible auto-complete
		return false;
	}

	@Override
	public boolean onQueryTextSubmit(String query) {
		// '|' and '$' are NMDC delimiters: they would corrupt the $Search command.
		query = query == null ? "" : query.replace('|', ' ').replace('$', ' ').trim();
		if (query.isEmpty()) return true;
		if (!isAnyHubConnected()) {
			Toast.makeText(this, "Connect to a hub to search", Toast.LENGTH_SHORT).show();
			return true;
		}
		if (searchview != null) {
			searchview.setQuery("", false);
			searchview.clearFocus();
		}
		if (searchmenuitem != null) {
			searchmenuitem.collapseActionView();
		}

		if (searchresult_fragment != null && tab_page_adapter != null) {
			searchresult_fragment.clear_search_results();
			tab_page_adapter.add_tab(TabPagerAdapter.TAB_SEARCHLIST,
					searchresult_fragment, "Search Results", true);
		}
		if (mService != null) {
			// Multi-hub search: send to first session or all? 
			// For now, let's send to all connected.
			for (DCPPService.HubSession session : mService.getActiveSessions()) {
				if (session != null && session.status == DCPPService.DCClientStatus.CONNECTED && session.hubIp != null) {
					mService.make_search(session.hubIp, query);
				}
			}
		}
		return true;
	}

	public void startBackgroundService(String nick, String ip, String port) {
		startBackgroundService(nick, ip, port, true);
	}

	/**
	 * Connects to a hub given by its parsed address. Hubs whose protocol this build can't speak yet
	 * (ADC, ADCS, NMDCS) are refused with a message instead of being treated as NMDC.
	 * Returns true if the connection was started.
	 */
	public boolean startBackgroundService(String nick, HubAddress addr, boolean showDialog) {
		if (addr == null) {
			Toast.makeText(this, "Invalid hub address", Toast.LENGTH_LONG).show();
			return false;
		}
		if (!addr.isSupported()) {
			Toast.makeText(this, addr.host + ":" + addr.port + ": " + addr.unsupportedReason(), Toast.LENGTH_LONG).show();
			return false;
		}
		startBackgroundService(nick, addr.host, String.valueOf(addr.port), showDialog);
		return true;
	}

	public void startBackgroundService(String nick, String ip, String port, boolean showDialog) {
		if (myBR == null) {
			myBR = new myBroadcastReceiver();
			IntentFilter intFil = new IntentFilter("ACTION_DO_SOMETHING");
			intFil.addCategory(Intent.CATEGORY_DEFAULT);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				registerReceiver(myBR, intFil, Context.RECEIVER_NOT_EXPORTED);
			} else {
				registerReceiver(myBR, intFil);
			}
		}
		
		if (showDialog) {
			if (myProgressDialog != null && myProgressDialog.isShowing()) {
				myProgressDialog.dismiss();
			}
			myProgressDialog = new AlertDialog.Builder(this)
					.setMessage("Connecting...")
					.setCancelable(false)
					.create();
			myProgressDialog.show();

			new Handler(Looper.getMainLooper()).postDelayed(() -> {
				if (myProgressDialog != null && myProgressDialog.isShowing())
					myProgressDialog.dismiss();
			}, 10000); // 10 second safety timeout
		}
		
		serviceIntent = new Intent(this, DCPPService.class);
		serviceIntent.putExtra("nick", nick);
		serviceIntent.putExtra("ip", ip);
		serviceIntent.putExtra("port", port);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			startForegroundService(serviceIntent);
		} else {
			startService(serviceIntent);
		}
	}

	private void initDefaultPreferences() {
		SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
		if (!prefs.contains("initialized_default_hubs")) {
			Set<String> favorites = new HashSet<>(prefs.getStringSet("favorite_hubs", new HashSet<String>()));
			favorites.add(Constants.DEFAULT_FAVORITE_HUB);
			SharedPreferences.Editor editor = prefs.edit();
			editor.putStringSet("favorite_hubs", favorites);
			String currentIp = prefs.getString(Constants.SETTINGS_IP_KEY, "");
			if (currentIp.isEmpty()) {
				editor.putString(Constants.SETTINGS_IP_KEY, Constants.DEFAULT_FAVORITE_HUB);
			}
			editor.putBoolean("initialized_default_hubs", true);
			editor.apply();
		}
		// One-time repair: "Edit Nick & Password" used to save a hub's details without saving the hub itself,
		// so the hub vanished from the list after a restart. Bring back every hub that has saved details.
		if (!prefs.getBoolean("repaired_hubs_with_details", false)) {
			List<String> withPort = new ArrayList<>();
			List<String> hostOnly = new ArrayList<>();
			for (String key : prefs.getAll().keySet()) {
				String id = null;
				if (key.startsWith("favorite_nicks_")) id = key.substring("favorite_nicks_".length());
				else if (key.startsWith("favorite_passwords_")) id = key.substring("favorite_passwords_".length());
				if (id == null || id.trim().isEmpty()) continue;
				if (id.contains(":")) { if (!withPort.contains(id)) withPort.add(id); }
				else if (!hostOnly.contains(id)) hostOnly.add(id);
			}
			for (String id : withPort) HubListFragment.saveHub(this, id);
			for (String id : hostOnly) HubListFragment.saveHub(this, id); // skipped when host:port is saved
			prefs.edit().putBoolean("repaired_hubs_with_details", true).apply();
		}
	}

	/**
	 * Nick to use for a saved hub: the one saved with "Edit Nick & Password" for that hub,
	 * otherwise the Login tab nick. Returns "" if neither is set.
	 */
	public String resolveNickForHub(HubAddress addr) {
		SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
		for (String k : addr.prefKeys()) {
			if (k == null) continue;
			String n = prefs.getString("favorite_nicks_" + k, "");
			if (n != null && !n.trim().isEmpty()) return n.trim();
		}
		String global = prefs.getString(Constants.SETTINGS_NICK_KEY, "");
		return global != null ? global.trim() : "";
	}

	private void checkAutoReconnectSavedHubs() {
		Log.i("andcpp", "Auto-reconnect check: alreadyDone=" + hasAutoReconnected
				+ " enabled=" + Settings.getAutoReconnectHubs(this));
		if (hasAutoReconnected) return;
		hasAutoReconnected = true;

		if (!Settings.getAutoReconnectHubs(this)) return;

		SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);

		Set<String> favorites = prefs.getStringSet("favorite_hubs", new HashSet<String>());
		Log.i("andcpp", "Auto-reconnect saved hubs: " + favorites);
		if (favorites == null || favorites.isEmpty()) return;

		// Connect only once active/passive is known, so each hub is told the right mode from the start
		// (no passive login followed by a switch to active). Never waits more than 30 s.
		final List<String> favoritesList = new ArrayList<>(favorites);
		if (mService != null && !mService.isConnectivityDecided()) {
			Toast.makeText(this, "Checking connection mode before reconnecting hubs…", Toast.LENGTH_SHORT).show();
			mService.whenConnectivityDecided(() -> reconnectSavedHubs(favoritesList), 30000);
		} else {
			reconnectSavedHubs(favoritesList);
		}
	}

	/** Connects to each saved hub that isn't connected yet (auto-reconnect on startup). */
	private void reconnectSavedHubs(List<String> favoritesList) {
		for (String hubStr : favoritesList) {
			HubAddress addr = HubAddress.tryParse(hubStr);
			if (addr == null) {
				Log.w("andcpp", "Auto-reconnect: skipping invalid hub address " + hubStr);
				continue;
			}
			if (!addr.isSupported()) { // e.g. a saved ADC hub: wait for ADC support, don't try it as NMDC
				Log.i("andcpp", "Auto-reconnect: skipping " + hubStr + ": " + addr.unsupportedReason());
				continue;
			}

			String hubKey = addr.host + ":" + addr.port;
			if (mService != null && mService.getSession(hubKey) != null) {
				Log.i("andcpp", "Auto-reconnect: " + hubKey + " already has a session");
				continue;
			}

			String nick = resolveNickForHub(addr);
			if (nick.isEmpty()) { // no nick saved for this hub or on the Login tab
				Log.w("andcpp", "Auto-reconnect: no nick for " + hubStr);
				continue;
			}
			Log.i("andcpp", "Auto-reconnect: connecting to " + hubKey + " as " + nick);

			startBackgroundService(nick, addr, false);
		}
	}

	public void shutdownSession(String hubIp) {
		if (mService != null) {
			mService.shutdownSession(hubIp);
		}
		if (hublist_fragment != null) {
			hublist_fragment.refresh();
		}

		refreshWelcomeFragment();
	}

	public void stopBackgroundService() {
		if (mService != null) {
			mService.shutdown();
		}
		// Also stop the service intent to let the system know it can be destroyed
		if (serviceIntent != null) {
			stopService(serviceIntent);
		} else {
			stopService(new Intent(this, DCPPService.class));
		}
	}

	/**
	 * Called by Poller or BroadcastReceiver when background dcppservice connects.
	 * Only UI changes to be carried out (called on UI Thread)
	 */
	public void onConnectUIChanges(String hubIp) {
		Log.d("andcpp", "Running Connect UI changes for " + hubIp);
		if (mService != null) {
			mService.setBoard_message_handler(hubIp, this);
			mService.setUser_handler(hubIp, this);
			mService.setSearch_handler(hubIp, this);
			mService.setPrivate_message_handler(hubIp, this);
			// Also register handlers for general / null hubIp to ensure callbacks reach fragments even if hubIp differs slightly
			mService.setBoard_message_handler(null, this);
			mService.setUser_handler(null, this);
			mService.setPrivate_message_handler(null, this);
		}

		if (tab_page_adapter == null) return;

		if (searchmenuitem != null) {
			searchmenuitem.setVisible(true); // connected: search is available
		}
		if (login_fragment != null) {
			login_fragment.setViewState(true);
		}
		
		// Add tabs if they aren't already visible (do not automatically jump/switch to them)
		if (messageboard_fragment != null) {
			messageboard_fragment.setHubIp(hubIp);
		}
		tab_page_adapter.add_tab(TabPagerAdapter.TAB_HUBCHAT,
				messageboard_fragment, "Chat", false);
				
		if (userlist_fragment != null) {
			userlist_fragment.setHubIp(hubIp);
		}
		tab_page_adapter.add_tab(TabPagerAdapter.TAB_USERLIST,
				userlist_fragment, "Users", false);
		
		if (downloadlist_fragment != null) {
			tab_page_adapter.add_tab(TabPagerAdapter.TAB_DOWNLOADS,
					downloadlist_fragment, "Downloads", false);
		}
		if (totals_fragment != null) {
			tab_page_adapter.add_tab(TabPagerAdapter.TAB_TOTALS,
					totals_fragment, "Totals", false);
		}
		// Build k: tabs above were added with notify=false; tell the pager or it crashes
		// ("PagerAdapter changed the adapter's contents without calling notifyDataSetChanged").
		tab_page_adapter.notifyDataSetChanged();
		
		// Refresh saved lists when service connects
		Fragment savedFrag = tab_page_adapter.get_tab_fragment(TabPagerAdapter.TAB_SAVED_FILELISTS);
		if (savedFrag instanceof SavedFileListsFragment) {
			((SavedFileListsFragment) savedFrag).refreshList();
		}
		
		if (messageboard_fragment != null) {
			messageboard_fragment.refresh();
		}
		if (userlist_fragment != null) {
			userlist_fragment.clearNicklistConstraints();
		}
		
		if (hublist_fragment != null) {
			hublist_fragment.refresh();
		}

		refreshWelcomeFragment();
	}

	public void onConnectUIChanges() {
		onConnectUIChanges("Hub");
	}

	/**
	 * Called by Poller Change UI to disconnected state will be called on UI
	 * thread
	 */
	public void onDisonnectUIChanges() {
		Log.d("andcpp", "Running disconnect UI changes");
		if (tab_page_adapter == null) return;
		updateSearchMenuVisibility(); // stays visible while another hub is still connected
		if (login_fragment != null) {
			login_fragment.setViewState(false);
		}
		tab_page_adapter.hide_tab(TabPagerAdapter.TAB_HUBCHAT);
		tab_page_adapter.hide_tab(TabPagerAdapter.TAB_USERLIST);
		tab_page_adapter.hide_tab(TabPagerAdapter.TAB_SEARCHLIST);
	}

	public void open_private_chat(String hubIp, String targetNick) {
		if (privatechat_manager != null && tab_page_adapter != null) {
			privatechat_manager.openChat(hubIp, targetNick);
			tab_page_adapter.add_tab(TabPagerAdapter.TAB_PRIVATECHAT,
					null, "PM: " + targetNick);
			tab_page_adapter.move_to_last_page();
		}
	}

	public void open_file_list(final String hubIp, DCUserComparable user) {
		final String nick = user.nick;
		if (mService == null || mService.get_status(hubIp) != DCPPService.DCClientStatus.CONNECTED) {
			Toast.makeText(this, "Not connected to hub. Checking for cached list...", Toast.LENGTH_SHORT).show();
			open_cached_file_list(nick);
			return;
		}
		Toast.makeText(this, "Fetching file list of " + nick,
				Toast.LENGTH_SHORT).show();
		Thread fileDownloadThread = new Thread(() -> {
			DCFileList fileList = mService.get_file_list(hubIp, nick);

			if (fileList == null) {
				// Try loading from cache if download fails
				fileList = mService.get_cached_file_list(nick);
				if (fileList != null) {
					final DCFileList cachedList = fileList;
					runOnUiThread(() -> {
						Toast.makeText(MainActivity.this,
								"Download failed, opening cached version of " + nick,
								Toast.LENGTH_LONG).show();
						display_file_list(cachedList, nick, true);
					});
					return;
				}
				runOnUiThread(() -> Toast.makeText(MainActivity.this,
						"Slot not available and no cached list found.", Toast.LENGTH_LONG)
						.show());
				return;
			}
			
			final DCFileList finalFileList = fileList;
			runOnUiThread(() -> {
				Toast.makeText(MainActivity.this,
						"User FileList download done",
						Toast.LENGTH_LONG).show();
				display_file_list(finalFileList, nick, false);
			});
		});
		fileDownloadThread.start();
	}

	/** True if a user with this nick is currently online on any connected hub. */
	private boolean isUserOnline(String nick) {
		if (mService == null || nick == null) return false;
		for (DCPPService.HubSession s : mService.getActiveSessions()) {
			if (s.status != DCPPService.DCClientStatus.CONNECTED || s.client == null) continue;
			java.util.List<com.phinvader.libjdcpp.DCUser> users = s.client.get_nick_list();
			if (users == null) continue;
			for (com.phinvader.libjdcpp.DCUser u : users) {
				if (u != null && nick.equalsIgnoreCase(u.nick)) return true;
			}
		}
		return false;
	}

	/** Tab title for a file list: based on whether the user is really online, not on where the list came from. */
	private String fileListTabTitle(String nick) {
		if (UploadManager.MY_FILE_LIST_NAME.equals(nick)) return nick; // our own share
		return isUserOnline(nick) ? nick + "'s FileList" : nick + " (Offline)";
	}

	private void display_file_list(DCFileList fileList, String nick, boolean offline) {
		MainActivity.this.rootfileList = fileList;
		MainActivity.this.chosenNick = nick;
		if (filelist_manager != null) {
			filelist_manager.refreshToNewFileList();
		}
		if (tab_page_adapter != null) {
			tab_page_adapter.notifyDataSetChanged();
			tab_page_adapter.add_tab(TabPagerAdapter.TAB_FILELIST,
					null, fileListTabTitle(nick));
		}
		try {
			getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
					.edit()
					.putString("last_open_filelist_nick", nick)
					.putBoolean("last_open_filelist_offline", offline)
					.apply();
		} catch (Exception ignored) {}
	}
	
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) 
	{  
	    if (keyCode == KeyEvent.KEYCODE_BACK)
	    {
	    	int currentItem = view_pager != null ? view_pager.getCurrentItem() : 0;
	    	if(currentItem > 0)
	    		 view_pager.setCurrentItem(currentItem - 1);
	    	
	    	else if(exitFlag){
	    		// in case the user opens the app again before activity is destroyed, 
	    		// flag will be false so it will exit when back is pressed once and we don't want that!
	    		exitFlag = false;
	    		
	    		//canceling the toast looks nice!
	    		if (myToast != null)
	    			myToast.cancel();
	    		
	    		super.onBackPressed();
	    		return true;
	    	}
	    	
	    	else {
	    		myToast = Toast.makeText(getApplicationContext(), "Please press back again to exit", Toast.LENGTH_SHORT);
	    		myToast.show();
	    		exitFlag = true;
	    		
	    		new Handler().postDelayed(() -> exitFlag=false, 2000);
	    	}
	    	// Must return true if we handles the event!
	    	return true;
	    }
	    return false; 
	}

	public void openFile(File file) {
		if (file == null || !file.exists()) {
			Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show();
			return;
		}

		Intent intent = new Intent(Intent.ACTION_VIEW);
		MimeTypeMap mime = MimeTypeMap.getSingleton();
		String fileName = file.getName();
		int lastDot = fileName.lastIndexOf('.');
		String ext = lastDot != -1 ? fileName.substring(lastDot + 1) : "";
		String type = mime.getMimeTypeFromExtension(ext.toLowerCase());
		if (type == null) type = "*/*";

		Uri contentUri;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			contentUri = FileProvider.getUriForFile(this,
					"com.phinmadvader.andcpp.fileprovider", file);
			intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		} else {
			contentUri = Uri.fromFile(file);
		}

		intent.setDataAndType(contentUri, type);
		try {
			startActivity(intent);
		} catch (Exception e) {
			Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show();
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		if (requestCode == 1) {
			if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				Log.d("andcpp", "Storage permission granted");
				// Saved hubs and settings from before a clean install: restore, then rebuild the screens with them.
				boolean settingsRestored = SettingsBackup.restoreIfNeeded(this);
				if (settingsRestored) {
					Toast.makeText(this, "Restored saved hubs and settings", Toast.LENGTH_LONG).show();
					if (mService != null) mService.redetectConnectivity("settings restored");
				}
				// Files from a previous install are only visible now: rebuild our file list,
				// and bring back the PIA login backup (survives clean installs), then re-check active mode.
				if (mService != null) {
					mService.refreshMyFileListSoon();
					mService.restoreDownloadsFromBackupIfNeeded(); // unfinished downloads from before a clean install
					if (com.phinmadvader.andcpp.pia.PIAPortForwardingManager.getInstance(this).restoreFromBackupIfNeeded()) {
						mService.redetectConnectivity("PIA login restored");
					}
				}
				if (settingsRestored) {
					// Start the screen afresh (like opening the app) so the restored hub list, nick etc. show and
					// saved hubs auto-reconnect. recreate() kept the old pages' saved state, so the list stayed empty.
					Intent restart = new Intent(this, MainActivity.class);
					restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
					startActivity(restart);
					finish();
					overridePendingTransition(0, 0);
				}
			} else {
				Toast.makeText(this, "Storage permission is required to download files.", Toast.LENGTH_LONG).show();
			}
		}
	}

	public class myBroadcastReceiver extends BroadcastReceiver{

		@Override
		public void onReceive(Context context, Intent intent) {
			// TODO Auto-generated method stub
			Log.d("andcpp", "Broadcast Received");
			Bundle inBun = intent.getExtras();
			if (inBun != null) {
				String hubIp = inBun.getString("hubIp");
				boolean stopServiceFlag = inBun.getBoolean("stopServiceFlag", false);
				if (inBun.getBoolean("stopProgressDialogFlag")) {
					if (myProgressDialog != null && myProgressDialog.isShowing())
						myProgressDialog.dismiss();
				}
				
				if (hubIp != null && mService != null && mService.get_status(hubIp) == DCPPService.DCClientStatus.CONNECTED) {
					onConnectUIChanges(hubIp);
				} else if (stopServiceFlag) {
					boolean anyConnected = false;
					if (mService != null) {
						for (DCPPService.HubSession s : mService.getActiveSessions()) {
							if (s.status == DCPPService.DCClientStatus.CONNECTED) {
								anyConnected = true;
								break;
							}
						}
					}
					if (!anyConnected) {
						onDisonnectUIChanges();
					}
				}
				if (hublist_fragment != null) {
					hublist_fragment.refresh();
				}
				refreshWelcomeFragment();
			}
		}	
	}

	
}
