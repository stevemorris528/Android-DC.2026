package com.phinmadvader.andcpp;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Build j: records why the previous run of the app ended and SHOWS it on the phone.
 * - uncaught Java exceptions (full stack trace, written just before the process dies);
 * - on Android 11+, the system's own exit record (crash, low memory, killed, ANR, ...).
 * On every start it shows a toast and a notification with the previous exit reason
 * and where the log was saved (or why saving failed). The log is written to three places:
 *   1. Download/Android-DC++/last_exit.txt
 *   2. Android/data/com.phinmadvader.andcpp/files/last_exit.txt
 *   3. the app's internal storage (always works; summary is also in the notification)
 * No download behaviour is changed.
 */
public class MyApplication extends Application {

    private static final String PREFS = "exit_report";
    private static final String KEY_LAST_EXIT_TS = "last_exit_ts";
    private static final String EXIT_CHANNEL_ID = "exit_report_channel";
    private static final int EXIT_NOTIFICATION_ID = 9911;

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashRecorder();
        startTraceLogger();
        SettingsBackup.init(this); // keep a copy of saved hubs/settings that survives a clean install
        UpdateChecker.start(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    reportPreviousExit();
                } catch (Throwable t) {
                    showResult("Exit report failed: " + t, "Exit report failed: " + stackOf(t));
                }
            }
        }, "ExitReporter").start();
    }

    // ---- Build l: copy this app's own log lines (tags andcpp, libjdcpp, crashes) to a file ----
    private static final long TRACE_MAX_BYTES = 3L * 1024 * 1024;

    private File traceFile() {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Android-DC++");
            if (dir.isDirectory() || dir.mkdirs()) {
                File f = new File(dir, "dltrace.txt");
                if (f.exists() || f.createNewFile()) return f;
            }
        } catch (Throwable ignored) {
        }
        File ext = getExternalFilesDir(null);
        return new File(ext != null ? ext : getFilesDir(), "dltrace.txt");
    }

    private void startTraceLogger() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Process proc = null;
                try {
                    File out = traceFile();
                    FileWriter fw = new FileWriter(out, true);
                    fw.write("\n===== trace logger started " + stamp(System.currentTimeMillis())
                            + " pid " + android.os.Process.myPid() + " =====\n");
                    fw.flush();
                    scan(out);
                    proc = Runtime.getRuntime().exec(new String[]{
                            "logcat", "-v", "time", "andcpp:V", "libjdcpp:V", "AndroidRuntime:E", "*:S"});
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(proc.getInputStream()), 8192);
                    String line;
                    long size = out.length();
                    while ((line = br.readLine()) != null) {
                        fw.write(line);
                        fw.write('\n');
                        fw.flush();
                        size += line.length() + 1;
                        if (size > TRACE_MAX_BYTES) {
                            fw.close();
                            File old = new File(out.getParentFile(), "dltrace_old.txt");
                            old.delete();
                            out.renameTo(old);
                            out = traceFile();
                            fw = new FileWriter(out, false);
                            fw.write("===== continued " + stamp(System.currentTimeMillis()) + " (older lines in dltrace_old.txt) =====\n");
                            fw.flush();
                            size = out.length();
                            scan(out);
                            scan(old);
                        }
                    }
                    fw.write("===== trace logger stopped " + stamp(System.currentTimeMillis()) + " =====\n");
                    fw.close();
                } catch (Throwable ignored) {
                } finally {
                    if (proc != null) {
                        try {
                            proc.destroy();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "TraceLogger");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void scan(File f) {
        try {
            MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
        } catch (Throwable ignored) {
        }
    }

    private File crashFile() {
        return new File(getFilesDir(), "last_crash.txt");
    }

    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    FileWriter fw = new FileWriter(crashFile(), false);
                    fw.write("CRASH at " + stamp(System.currentTimeMillis()) + " on thread " + t.getName() + "\n" + stackOf(e) + "\n");
                    fw.close();
                } catch (Throwable ignored) {
                }
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
    }

    private static String stackOf(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String stamp(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    private static String reasonName(int r) {
        if (Build.VERSION.SDK_INT < 30) return String.valueOf(r);
        switch (r) {
            case ApplicationExitInfo.REASON_ANR: return "ANR (app not responding)";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH (Java exception)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE (killed by system for resource use)";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF (app exited itself)";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY (killed to free memory)";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER (killed by system, e.g. power saving / vendor)";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED (killed by a signal)";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED (stopped by user)";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_UNKNOWN: return "UNKNOWN";
            default: return String.valueOf(r);
        }
    }

    private void reportPreviousExit() throws Exception {
        StringBuilder sb = new StringBuilder();
        String summary = null; // one short line for toast / notification title

        sb.append("=== App start ").append(stamp(System.currentTimeMillis()))
                .append(" (Android ").append(Build.VERSION.RELEASE).append(", ").append(Build.MANUFACTURER)
                .append(" ").append(Build.MODEL).append(") ===\n");

        File cf = crashFile();
        boolean hadCrash = cf.exists() && cf.length() > 0;
        if (hadCrash) {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(cf));
            String line;
            String first = null;   // exception line (2nd line of the crash file)
            int n = 0;
            sb.append("Saved crash from the previous run:\n");
            while ((line = br.readLine()) != null) {
                sb.append("  ").append(line).append('\n');
                n++;
                if (n == 2) first = line;
            }
            br.close();
            summary = "Previous run CRASHED: " + (first != null ? first.trim() : "?");
        } else {
            sb.append("No Java crash was recorded by the app.\n");
        }

        if (Build.VERSION.SDK_INT >= 30) {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            long lastTs = prefs.getLong(KEY_LAST_EXIT_TS, 0L);
            long newest = lastTs;
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(getPackageName(), 0, 6);
            boolean any = false;
            for (ApplicationExitInfo i : list) {
                if (i.getTimestamp() <= lastTs) continue;
                newest = Math.max(newest, i.getTimestamp());
                String one = "ended " + stamp(i.getTimestamp()) + ": " + reasonName(i.getReason())
                        + (i.getDescription() != null ? " \"" + i.getDescription() + "\"" : "");
                if (!any && summary == null) summary = "Previous run " + one; // list is newest first
                any = true;
                sb.append("Previous process ").append(one)
                        .append(", pid ").append(i.getPid())
                        .append(", status ").append(i.getStatus())
                        .append(", importance ").append(i.getImportance())
                        .append(", pss ").append(i.getPss()).append(" KB")
                        .append('\n');
                try {
                    InputStream in = i.getTraceInputStream();
                    if (in != null) {
                        byte[] buf = new byte[6000];
                        int n = in.read(buf);
                        in.close();
                        if (n > 0) sb.append("  trace: ").append(new String(buf, 0, n, "UTF-8").replace("\n", "\n  ")).append('\n');
                    }
                } catch (Throwable ignored) {
                }
            }
            if (!any) sb.append("Android recorded no new exit for this app since the last start.\n");
            if (summary == null) summary = "Previous exit: Android recorded no new exit since last start";
            prefs.edit().putLong(KEY_LAST_EXIT_TS, newest).apply();
        } else {
            sb.append("(Android exit records need Android 11 or newer.)\n");
            if (summary == null) summary = "Previous exit: no crash recorded (exit records need Android 11+)";
        }
        sb.append('\n');

        // Write to three places; record what happened for each.
        StringBuilder where = new StringBuilder();
        boolean anyOk = false;
        File dl = null;
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Android-DC++");
            dl = new File(dir, "last_exit.txt");
        } catch (Throwable ignored) {
        }
        String r1 = writeReport(dl, sb.toString());
        String r2 = writeReport(getExternalFilesDir(null) != null ? new File(getExternalFilesDir(null), "last_exit.txt") : null, sb.toString());
        String r3 = writeReport(new File(getFilesDir(), "last_exit.txt"), sb.toString());
        where.append("Download: ").append(r1).append('\n')
                .append("App files: ").append(r2).append('\n')
                .append("Internal: ").append(r3);
        anyOk = r1.startsWith("saved") || r2.startsWith("saved") || r3.startsWith("saved");

        if (r1.startsWith("saved") && dl != null) {
            try {
                MediaScannerConnection.scanFile(this, new String[]{dl.getAbsolutePath()}, null, null);
            } catch (Throwable ignored) {
            }
        }
        if (hadCrash && anyOk) cf.delete();

        showResult(summary, summary + "\n\n" + where);
    }

    /** Writes text (newest first, keeping older entries) and returns "saved <path>" or "FAILED <path>: reason". */
    private static String writeReport(File out, String text) {
        if (out == null) return "FAILED: folder not available";
        try {
            File dir = out.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return "FAILED " + out.getAbsolutePath() + ": could not create folder";
            }
            String existing = "";
            if (out.exists() && out.length() > 0 && out.length() < 40000) {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(out));
                StringBuilder ex = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) ex.append(line).append('\n');
                br.close();
                existing = ex.toString();
            }
            FileWriter fw = new FileWriter(out, false);
            fw.write(text);
            fw.write(existing);
            fw.close();
            return "saved " + out.getAbsolutePath();
        } catch (Throwable t) {
            return "FAILED " + out.getAbsolutePath() + ": " + t;
        }
    }

    /** Shows a long toast with the short line, and a notification with the full text. */
    private void showResult(final String shortText, final String longText) {
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(MyApplication.this, shortText, Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    NotificationChannel ch = new NotificationChannel(EXIT_CHANNEL_ID, "Previous exit report",
                            NotificationManager.IMPORTANCE_DEFAULT);
                    nm.createNotificationChannel(ch);
                }
            }
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, EXIT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Previous exit report")
                    .setContentText(shortText)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(longText))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            NotificationManagerCompat.from(this).notify(EXIT_NOTIFICATION_ID, b.build());
        } catch (Throwable ignored) {
        }
    }
}
