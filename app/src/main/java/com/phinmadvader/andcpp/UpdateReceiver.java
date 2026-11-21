package com.phinmadvader.andcpp;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

/**
 * Restarts the app after it was updated (the installer kills the old process and nothing starts the new one).
 * Opens MainActivity, which restores hubs and downloads as on a normal start. Android 10+ may block an activity
 * start from the background, so a "tap to reopen" notification is posted as well; MainActivity removes it.
 */
public class UpdateReceiver extends BroadcastReceiver {

    static final int RESTART_NOTIFICATION_ID = 9922;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        Log.i("andcpp", "[UPDATE] app updated to build " + BuildConfig.BUILD_NUMBER + "; restarting");

        Intent open = new Intent(context, MainActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                UpdateChecker.ensureChannel(context);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
                PendingIntent pi = PendingIntent.getActivity(context, RESTART_NOTIFICATION_ID, open, flags);
                nm.notify(RESTART_NOTIFICATION_ID, new NotificationCompat.Builder(context, UpdateChecker.CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle("Android DC++ updated to build " + BuildConfig.BUILD_NUMBER)
                        .setContentText("Tap to reopen if the app did not restart by itself.")
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build());
            }
        } catch (Exception e) {
            Log.w("andcpp", "[UPDATE] could not post restart notification", e);
        }

        try {
            context.startActivity(open);
        } catch (Exception e) {
            Log.w("andcpp", "[UPDATE] could not reopen the app after the update", e);
        }
    }
}
