package com.phinmadvader.andcpp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a copy of the settings (saved hubs, nicks, hub passwords, port, download folder, ...) in shared storage
 * (Download/Android-DC++/.settings_backup) and restores it after a clean install. Android deletes the app's private
 * storage on uninstall; the shared Download folder is kept.
 *
 * PIA keys are left out: PIAPortForwardingManager keeps its own backup (.pia_settings).
 * Hub passwords and the ADC PID are scrambled, not strongly encrypted: no key can survive an uninstall.
 */
public final class SettingsBackup {
    private static final String TAG = "andcpp";
    static final String BACKUP_NAME = ".settings_backup";
    private static final String SETTINGS_PREFS = "settingsSharedPreferences";
    /** Separate file so the marker is neither backed up nor seen by the settings listener. */
    private static final String STATE_PREFS = "settings_backup_state";
    private static final String KEY_CHECKED = "checked";
    private static final byte[] SCRAMBLE = "AnDCpp-hub-passwords-v1".getBytes(Charset.forName("UTF-8"));

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Context appContext;
    private static boolean saveScheduled = false;
    // Held strongly: SharedPreferences keeps its listeners only weakly.
    private static final SharedPreferences.OnSharedPreferenceChangeListener listener = (sp, key) -> scheduleSave();
    private static final Runnable saveRunnable = () -> {
        saveScheduled = false;
        saveNow();
    };

    private SettingsBackup() {}

    /** Call once from Application.onCreate: writes the backup whenever a setting changes. */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
        appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener);
        appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener);
    }

    /**
     * The backup folder: always the default Download/Android-DC++ (not the user's chosen download folder, which is
     * itself a setting and unknown after a reinstall).
     */
    public static File backupDirectory(Context c) {
        File dir;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Android-DC++");
        } else {
            File ext = c.getExternalFilesDir(null);
            dir = ext != null ? new File(ext, "Android-DC++") : new File(Constants.dcDirectory);
        }
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static boolean hasStoragePermission(Context c) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isChecked(Context c) {
        return c.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CHECKED, false);
    }

    private static void setChecked(Context c) {
        c.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CHECKED, true).commit();
    }

    private static boolean excluded(String key) {
        return key == null || key.startsWith("pia_");
    }

    private static boolean isSecret(String key) {
        // The ADC PID is a secret too: whoever has it can claim our CID.
        return key.startsWith("favorite_passwords_") || key.equals(AdcIdentityStore.KEY_PID);
    }

    private static String scramble(String s) {
        byte[] b = s.getBytes(Charset.forName("UTF-8"));
        for (int i = 0; i < b.length; i++) b[i] ^= SCRAMBLE[i % SCRAMBLE.length];
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static String unscramble(String s) {
        byte[] b = Base64.decode(s, Base64.NO_WRAP);
        for (int i = 0; i < b.length; i++) b[i] ^= SCRAMBLE[i % SCRAMBLE.length];
        return new String(b, Charset.forName("UTF-8"));
    }

    private static void scheduleSave() {
        if (saveScheduled) return;
        saveScheduled = true;
        handler.postDelayed(saveRunnable, 2000);
    }

    private static JSONObject dump(SharedPreferences prefs) throws Exception {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            if (excluded(key) || v == null) continue;
            JSONObject item = new JSONObject();
            if (v instanceof String) {
                item.put("t", "s");
                item.put("v", isSecret(key) ? scramble((String) v) : v);
                if (isSecret(key)) item.put("x", true);
            } else if (v instanceof Boolean) {
                item.put("t", "b").put("v", v);
            } else if (v instanceof Integer) {
                item.put("t", "i").put("v", v);
            } else if (v instanceof Long) {
                item.put("t", "l").put("v", v);
            } else if (v instanceof Float) {
                item.put("t", "f").put("v", ((Float) v).doubleValue());
            } else if (v instanceof Set) {
                JSONArray arr = new JSONArray();
                for (Object o : (Set<?>) v) arr.put(String.valueOf(o));
                item.put("t", "set").put("v", arr);
            } else {
                continue;
            }
            out.put(key, item);
        }
        return out;
    }

    /** Writes the backup file now (only once any earlier backup has been restored or found absent). */
    public static void saveNow() {
        Context c = appContext;
        if (c == null || !isChecked(c) || !hasStoragePermission(c)) return;
        try {
            JSONObject root = new JSONObject();
            root.put("v", 1);
            root.put("main", dump(c.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)));
            root.put("settings", dump(c.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)));
            File f = new File(backupDirectory(c), BACKUP_NAME);
            File tmp = new File(f.getPath() + ".tmp");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(root.toString().getBytes("UTF-8"));
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
            Log.d(TAG, "Settings backup written to " + f.getPath());
        } catch (Exception e) {
            Log.w(TAG, "Could not write the settings backup: " + e.getMessage());
        }
    }

    private static void load(SharedPreferences prefs, JSONObject data) throws Exception {
        SharedPreferences.Editor ed = prefs.edit();
        // Drop the fresh install's defaults (but keep PIA keys: they have their own backup).
        for (String key : prefs.getAll().keySet()) {
            if (!excluded(key)) ed.remove(key);
        }
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (excluded(key)) continue;
            JSONObject item = data.optJSONObject(key);
            if (item == null) continue;
            switch (item.optString("t")) {
                case "s":
                    String s = item.optString("v", "");
                    ed.putString(key, item.optBoolean("x", false) ? unscramble(s) : s);
                    break;
                case "b": ed.putBoolean(key, item.optBoolean("v")); break;
                case "i": ed.putInt(key, item.optInt("v")); break;
                case "l": ed.putLong(key, item.optLong("v")); break;
                case "f": ed.putFloat(key, (float) item.optDouble("v")); break;
                case "set":
                    JSONArray arr = item.optJSONArray("v");
                    Set<String> set = new HashSet<>();
                    if (arr != null) for (int i = 0; i < arr.length(); i++) set.add(arr.optString(i));
                    ed.putStringSet(key, set);
                    break;
                default:
                    break;
            }
        }
        ed.commit();
    }

    /**
     * On the first start after an install, restores the settings from the backup file. Needs storage permission:
     * call again once it is granted. Returns true when settings were restored (the UI should then be rebuilt).
     */
    public static boolean restoreIfNeeded(Context context) {
        Context c = context.getApplicationContext();
        if (appContext == null) appContext = c;
        if (isChecked(c)) return false;
        if (!hasStoragePermission(c)) {
            Log.d(TAG, "Settings backup: no storage permission yet; will check once it is granted");
            return false;
        }
        File f = new File(backupDirectory(c), BACKUP_NAME);
        if (!f.isFile()) {
            setChecked(c); // nothing to bring back: start backing up from now on
            scheduleSave();
            return false;
        }
        try {
            byte[] data = new byte[(int) Math.min(f.length(), 8L * 1024 * 1024)];
            try (InputStream in = new FileInputStream(f)) {
                int off = 0, n;
                while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
            }
            JSONObject root = new JSONObject(new String(data, "UTF-8"));
            JSONObject main = root.optJSONObject("main");
            JSONObject settings = root.optJSONObject("settings");
            if (main != null) load(c.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE), main);
            if (settings != null) load(c.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE), settings);
            setChecked(c);
            Log.i(TAG, "Restored settings and saved hubs from " + f.getPath() + " (after a reinstall)");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not restore the settings backup: " + e.getMessage());
            return false; // leave the file alone; a later start can try again
        }
    }
}
