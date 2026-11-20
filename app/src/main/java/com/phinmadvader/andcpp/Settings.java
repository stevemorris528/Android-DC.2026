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

		//although it's called file list, it's the exact code needed here, so I'm not going to make a new view.
		setContentView(R.layout.file_list);
		ListView lv = (ListView)findViewById(R.id.listView);
		String[] options = new String[]{
				"Theme: Light / Dark Mode",
				"Accent Color",
				"Change Downloads Directory",
				"Change Listening Port",
				"Change Upload Slots",
				"Connection Mode (Active/Passive)",
				"Auto-Reconnect Saved Hubs on Startup",
				"PIA Port Forwarding",
				"Set PIA Token"
		};
		
		ArrayAdapter<String> myArrayAdapter = new ArrayAdapter<String>(this,
				R.layout.settings_list_element_layout, R.id.elementString, options);
		
		lv.setAdapter(myArrayAdapter);
		lv.setOnItemClickListener(new OnItemClickListener() {

			@Override
			public void onItemClick(AdapterView<?> parentAdapterView, View view, int position,
					long id) {
				Log.d("andcpp", "settings item "+id+" clicked , position "+position);
				switch(position){
				case 0:
					changeThemeMode();
					return;
				case 1:
					changeAccentColor();
					return;
				case 2:
					changeDownloadsDirectory();
					return;
				case 3:
					changeListeningPort();
					return;
				case 4:
					changeUploadSlots();
					return;
				case 5:
					changeConnectionMode();
					return;
				case 6:
					changeAutoReconnectHubs();
					return;
				case 7:
					changePIAPortForwarding();
					return;
				case 8:
					changePIAToken();
					return;
				default:
					Log.d("andcpp", "this is unimplemented");
				}
			}
		});
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

	public void changeListeningPort() {
		final EditText input = new EditText(this);
		input.setText(String.valueOf(getListeningPort(this)));
		DisplayAlert("Change Listening Port", "Enter port (1024-65535):", "Save", (dialog, which) -> {
			try {
				int port = Integer.parseInt(input.getText().toString());
				if (port < 1024 || port > 65535) throw new NumberFormatException();
				putListeningPort(port, Settings.this);
				Toast.makeText(Settings.this, "Port saved. Restart service to apply.", Toast.LENGTH_SHORT).show();
			} catch (NumberFormatException e) {
				Toast.makeText(Settings.this, "Invalid port number", Toast.LENGTH_SHORT).show();
			}
		}, "Cancel", null, input);
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

	public void changeConnectionMode() {
		final String[] modes = {"Passive", "Active"};
		int current = getActiveMode(this) ? 1 : 0;
		new AlertDialog.Builder(this)
				.setTitle("Select Connection Mode")
				.setSingleChoiceItems(modes, current, (dialog, which) -> {
					putActiveMode(which == 1, Settings.this);
					Toast.makeText(Settings.this, "Mode saved. Restart service to apply.", Toast.LENGTH_SHORT).show();
					dialog.dismiss();
				}).show();
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

	public void changePIAPortForwarding() {
		com.phinmadvader.andcpp.pia.PIAPortForwardingManager piaManager = com.phinmadvader.andcpp.pia.PIAPortForwardingManager.getInstance(this);
		boolean current = piaManager.isEnabled();
		String currentPort = piaManager.getCurrentPort();
		String message = "Current PIA Port: " + (currentPort != null ? currentPort : "None");
		
		new AlertDialog.Builder(this)
				.setTitle("Enable PIA Port Forwarding")
				.setMessage(message)
				.setPositiveButton(current ? "Disable" : "Enable", (dialog, which) -> {
					piaManager.setEnabled(!current);
					Toast.makeText(Settings.this, "PIA Port Forwarding " + (!current ? "Enabled" : "Disabled"), Toast.LENGTH_SHORT).show();
				})
				.setNegativeButton("Cancel", null)
				.show();
	}

	public void changePIAToken() {
		SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, 0);
		final EditText input = new EditText(this);
		input.setText(prefs.getString("pia_token", ""));
		DisplayAlert("Change PIA Token", "Enter token:", "Save", (dialog, which) -> {
			prefs.edit().putString("pia_token", input.getText().toString()).apply();
			Toast.makeText(Settings.this, "Token saved.", Toast.LENGTH_SHORT).show();
		}, "Cancel", null, input);
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
		return prefs.getBoolean(Constants.SETTINGS_ACTIVE_KEY, false);
	}

	public static void putActiveMode(boolean active, Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		prefs.edit().putBoolean(Constants.SETTINGS_ACTIVE_KEY, active).apply();
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

	public static int getDownloadWorkers(Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		return prefs.getInt(Constants.SETTINGS_DOWNLOAD_WORKERS_KEY, Constants.DEFAULT_DOWNLOAD_WORKERS);
	}

	public static void putDownloadWorkers(int workers, Context c) {
		SharedPreferences prefs = c.getSharedPreferences(Constants.PREFS_NAME, 0);
		prefs.edit().putInt(Constants.SETTINGS_DOWNLOAD_WORKERS_KEY, workers).apply();
	}
}
