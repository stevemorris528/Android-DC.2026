package com.phinmadvader.andcpp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update from the project's GitHub releases.
 *
 * On app start and every CHECK_INTERVAL_MILLIS the newest .apk attached to any (non-draft) release is looked up.
 * A release asset that hasn't been seen before is downloaded and its versionCode (= build number, see
 * app/build.gradle) is read from the file itself, so releases need no special naming. If it is newer than the
 * installed app, the user is asked to install it (dialog when the app is open, notification otherwise).
 * After the update is installed, UpdateReceiver (MY_PACKAGE_REPLACED) restarts the app.
 */
public final class UpdateChecker {

    private static final String TAG = "andcpp";
    private static final String RELEASES_URL =
            "https://api.github.com/repos/stevemorris528/Android-DC.2026/releases?per_page=15";
    private static final long CHECK_INTERVAL_MILLIS = 30 * 60000L; // every half hour (testing)
    private static final long FIRST_CHECK_DELAY_MILLIS = 15000L;

    private static final String PREFS = "app_update";
    private static final String KEY_ASSET_ID = "asset_id";
    private static final String KEY_ASSET_UPDATED = "asset_updated";
    private static final String KEY_ASSET_VERSION = "asset_version";

    static final String CHANNEL_ID = "app_update_channel";
    static final int NOTIFICATION_ID = 9921;
    public static final String EXTRA_SHOW_UPDATE = "show_update";

    private static Context appContext;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean started = false;
    private static volatile boolean checking = false;

    /** Downloaded and verified update waiting for the user, or null. */
    private static volatile File pendingApk = null;
    private static volatile long pendingVersion = 0;
    private static volatile String pendingName = null;
    /** Build the user said "Later" to; asked again at the next periodic check. */
    private static volatile long dismissedVersion = 0;

    private static Activity foreground = null;
    private static AlertDialog dialog = null;

    private UpdateChecker() {}

    /** Called once from MyApplication.onCreate: first check shortly after start, then every half hour. */
    public static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        appContext = ctx.getApplicationContext();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkNow(false);
                main.postDelayed(this, CHECK_INTERVAL_MILLIS);
            }
        }, FIRST_CHECK_DELAY_MILLIS);
    }

    /** Runs a check in the background. userRequested = toast the result even when there is no update. */
    public static void checkNow(final boolean userRequested) {
        if (appContext == null) return;
        if (checking) {
            if (userRequested) Toast.makeText(appContext, "Already checking for updates...", Toast.LENGTH_SHORT).show();
            return;
        }
        if (userRequested) Toast.makeText(appContext, "Checking for updates...", Toast.LENGTH_SHORT).show();
        checking = true;
        dismissedVersion = 0; // a periodic check asks again
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    result = doCheck();
                } catch (Throwable t) {
                    result = "Update check failed: " + t.getMessage();
                    Log.w(TAG, "[UPDATE] check failed", t);
                } finally {
                    checking = false;
                }
                Log.i(TAG, "[UPDATE] " + result);
                if (pendingApk != null) {
                    main.post(UpdateChecker::offerUpdate);
                } else if (userRequested) {
                    final String msg = result;
                    main.post(() -> Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show());
                }
            }
        }, "UpdateCheck").start();
    }

    private static long installedVersion() {
        try {
            PackageInfo pi = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return BuildConfig.BUILD_NUMBER;
        }
    }

    private static long archiveVersion(File apk) {
        PackageInfo pi = appContext.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (pi == null || !appContext.getPackageName().equals(pi.packageName)) return -1;
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    private static File updateFile() {
        File dir = new File(appContext.getExternalFilesDir(null), "updates");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, "update.apk");
    }

    private static String doCheck() throws Exception {
        long installed = installedVersion();
        pendingApk = null; // re-decided below; an update installed meanwhile must not be offered again
        JSONArray releases = new JSONArray(httpGetString(RELEASES_URL));

        // Newest .apk asset across all published releases (pre-releases included, drafts skipped).
        JSONObject best = null;
        String bestRelease = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject rel = releases.getJSONObject(i);
            if (rel.optBoolean("draft")) continue;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets == null) continue;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject a = assets.getJSONObject(j);
                if (!a.optString("name").toLowerCase().endsWith(".apk")) continue;
                if (best == null || a.optString("updated_at").compareTo(best.optString("updated_at")) > 0) {
                    best = a;
                    bestRelease = rel.optString("name", rel.optString("tag_name"));
                }
            }
        }
        if (best == null) return "no APK found in the GitHub releases";

        long assetId = best.optLong("id");
        String updatedAt = best.optString("updated_at");
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        File apk = updateFile();

        // Already looked at this exact upload: no need to download it again.
        if (prefs.getLong(KEY_ASSET_ID, -1) == assetId && updatedAt.equals(prefs.getString(KEY_ASSET_UPDATED, ""))) {
            long known = prefs.getLong(KEY_ASSET_VERSION, -1);
            if (known <= installed) {
                return "up to date (installed build " + installed + ", latest release build " + known + ")";
            }
            if (apk.isFile() && archiveVersion(apk) == known) {
                setPending(apk, known, bestRelease);
                return "update ready: build " + known + " (installed " + installed + ")";
            }
        }

        File tmp = new File(apk.getParentFile(), "update.apk.part");
        Log.i(TAG, "[UPDATE] downloading " + best.optString("name") + " from release \"" + bestRelease + "\"");
        download(best.getString("browser_download_url"), tmp);
        long version = archiveVersion(tmp);
        prefs.edit().putLong(KEY_ASSET_ID, assetId).putString(KEY_ASSET_UPDATED, updatedAt)
                .putLong(KEY_ASSET_VERSION, version).apply();
        if (version <= installed) {
            tmp.delete();
            return version < 0 ? "latest release APK is not this app or is damaged"
                    : "up to date (installed build " + installed + ", latest release build " + version + ")";
        }
        apk.delete();
        if (!tmp.renameTo(apk)) throw new Exception("could not save the downloaded update");
        setPending(apk, version, bestRelease);
        return "update ready: build " + version + " (installed " + installed + ")";
    }

    private static void setPending(File apk, long version, String name) {
        pendingApk = apk;
        pendingVersion = version;
        pendingName = name;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "AndroidDCpp-updater");
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new Exception("HTTP " + code + " for " + url);
        }
        return c;
    }

    private static String httpGetString(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
    }

    // ---------------- UI ----------------

    /** MainActivity.onResume / onPause report whether the app is on screen. */
    public static void setForeground(Activity a) {
        foreground = a;
        if (a == null) {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            dialog = null;
        } else {
            offerUpdate();
        }
    }

    private static void offerUpdate() {
        if (pendingApk == null || !pendingApk.isFile()) return;
        if (pendingVersion <= installedVersion()) { pendingApk = null; return; }
        if (pendingVersion == dismissedVersion) return;
        Activity a = foreground;
        if (a == null || a.isFinishing()) {
            postNotification();
            return;
        }
        if (dialog != null && dialog.isShowing()) return;
        cancelNotification();
        final long version = pendingVersion;
        dialog = new AlertDialog.Builder(a)
                .setTitle("Update available")
                .setMessage("Build " + version + " is available"
                        + (pendingName != null && !pendingName.isEmpty() ? " (\"" + pendingName + "\")" : "")
                        + ".\nYou have build " + installedVersion() + ".\n\nInstall it now? The app restarts after the update"
                        + " and downloads continue where they stopped.")
                .setPositiveButton("Install", (d, w) -> install(a))
                .setNegativeButton("Later", (d, w) -> dismissedVersion = version)
                .setOnCancelListener(d -> dismissedVersion = version)
                .show();
    }

    private static void install(Activity a) {
        File apk = pendingApk;
        if (apk == null || !apk.isFile()) return;
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Allow \"Install unknown apps\" for this app, then come back to install the update",
                    Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            return; // asked again in onResume
        }
        // Save download progress now: installing kills the process without onDestroy.
        DCPPService svc = DCPPService.getRunningInstance();
        if (svc != null) {
            try {
                svc.prepareForUpdate();
            } catch (Exception e) {
                Log.w(TAG, "[UPDATE] could not save state before update", e);
            }
        }
        Uri uri;
        if (Build.VERSION.SDK_INT >= 24) {
            uri = FileProvider.getUriForFile(a, "com.phinmadvader.andcpp.fileprovider", apk);
        } else {
            uri = Uri.fromFile(apk);
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.i(TAG, "[UPDATE] starting installer for build " + pendingVersion);
        a.startActivity(i);
    }

    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "App updates",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    private static void postNotification() {
        NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureChannel(nm);
        Intent open = new Intent(appContext, MainActivity.class);
        open.putExtra(EXTRA_SHOW_UPDATE, true);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(appContext, NOTIFICATION_ID, open, flags);
        nm.notify(NOTIFICATION_ID, new NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Android DC++ update available")
                .setContentText("Build " + pendingVersion + " is ready. Tap to install.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    private static void cancelNotification() {
        NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }

    static void ensureChannel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) ensureChannel(nm);
    }
}
