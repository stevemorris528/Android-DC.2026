package com.phinmadvader.andcpp;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.phinmadvader.andcpp.DCPPService.FileSize;

/** "Network Stats" tab: all-time and this-session transfer totals plus upload statistics. */
public class TotalsFragment extends Fragment {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;

    private TextView allTimeDl;
    private TextView allTimeUl;
    private TextView sessionDl;
    private TextView sessionUl;
    private TextView uploadStats;
    private TextView downloadStats;
    private TextView connectionMode;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.totals_layout, container, false);
        allTimeDl = (TextView) root.findViewById(R.id.totals_alltime_dl);
        allTimeUl = (TextView) root.findViewById(R.id.totals_alltime_ul);
        sessionDl = (TextView) root.findViewById(R.id.totals_session_dl);
        sessionUl = (TextView) root.findViewById(R.id.totals_session_ul);
        uploadStats = (TextView) root.findViewById(R.id.totals_upload_stats);
        downloadStats = (TextView) root.findViewById(R.id.totals_download_stats);
        connectionMode = (TextView) root.findViewById(R.id.totals_connection_mode);

        // DL figures in the same colour as the download progress bars (theme accent).
        int dlColor = ThemeUtils.getAccentColor(getActivity());
        allTimeDl.setTextColor(dlColor);
        sessionDl.setTextColor(dlColor);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    update();
                } catch (Exception e) {
                    android.util.Log.e("andcpp", "Network Stats tab update failed", e);
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(updateRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }
    }

    private static String formatSize(long bytes) {
        FileSize fs = new FileSize(bytes);
        return String.format(java.util.Locale.US, "%.2f %s", fs.fileSize, fs.unit);
    }

    private void update() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || activity.mService == null || allTimeDl == null) return;
        DCPPService service = activity.mService;

        long[] allTime = service.getAllTimeTotals();
        UploadManager.UploadStats stats = service.getUploadStats();
        long sessionDownloaded = com.phinvader.libjdcpp.MessageHandler.totalDownloadedBytes.get();

        if (connectionMode != null) {
            connectionMode.setText(ThemeUtils.formatConnectionMode("",
                    service.getConnectionModeDescription(), service.isConnectionActive()));
        }
        allTimeDl.setText("DL: " + formatSize(allTime[0]));
        allTimeUl.setText("UL: " + formatSize(allTime[1]));
        sessionDl.setText("DL: " + formatSize(sessionDownloaded));
        sessionUl.setText("UL: " + formatSize(stats.totalBytes));

        uploadStats.setText(String.format(java.util.Locale.US,
                "Uploads\nCompleted: %d\nFailed: %d\nActive: %d\nQueued: %d\nHandshake failures: %d\nConnection errors: %d",
                stats.completedCount, stats.failedCount, stats.activeCount, stats.queueLength,
                stats.handshakeFailures, stats.connectionErrors));
        if (downloadStats != null) {
            int[] dl = service.getDownloadCounts();
            downloadStats.setText(String.format(java.util.Locale.US,
                    "Downloads\nCompleted: %d\nFailed: %d\nActive: %d\nQueued: %d\nPaused: %d",
                    dl[0], dl[1], dl[2], dl[3], dl[4]));
        }
    }
}
