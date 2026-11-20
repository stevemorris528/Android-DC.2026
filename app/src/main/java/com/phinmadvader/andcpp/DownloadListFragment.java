package com.phinmadvader.andcpp;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;

import com.phinmadvader.andcpp.DCPPService.DownloadItem;
import com.phinmadvader.andcpp.DCPPService.DownloadObject;
import com.phinmadvader.andcpp.DCPPService.FileSize;
import com.phinvader.libjdcpp.DCConstants;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.support.v4.content.FileProvider;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DownloadListFragment extends Fragment {
    private MainActivity mainActivity;
    private ListView downloadListView;
    private DownloadListAdapter adapter;
    private List<DownloadItem> downloadList = new ArrayList<>();
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;
    public boolean is_ready = false;

    private View uploadStatsLayout;
    private android.widget.TextView txtUploadSpeed;
    private android.widget.TextView txtUploadStats;
    private android.widget.TextView txtDownloadSpeed;
    private android.widget.TextView txtDlTotals;
    private android.widget.TextView txtUlTotals;
    private android.widget.TextView txtNoUploads;
    private android.widget.LinearLayout uploadRowsContainer;
    /** Reused row views (download_row_element), one per open upload connection. */
    private final List<View> uploadRows = new ArrayList<>();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mainActivity = (MainActivity) getActivity();
        View root = inflater.inflate(R.layout.download_list_layout, container, false);
        
        downloadListView = (ListView) root.findViewById(R.id.downloadListView);
        adapter = new DownloadListAdapter(mainActivity, downloadList);
        downloadListView.setAdapter(adapter);

        uploadStatsLayout = root.findViewById(R.id.uploadStatsLayout);
        txtUploadSpeed = (android.widget.TextView) root.findViewById(R.id.txtUploadSpeed);
        txtUploadStats = (android.widget.TextView) root.findViewById(R.id.txtUploadStats);
        uploadRowsContainer = (android.widget.LinearLayout) root.findViewById(R.id.uploadRowsContainer);
        // The pager rebuilds this tab's view when tabs change (e.g. a file list opens to queue downloads).
        // Rows made for the previous view are no longer on screen: forget them so new ones are created.
        uploadRows.clear();
        txtDownloadSpeed = (android.widget.TextView) root.findViewById(R.id.txtDownloadSpeed);
        txtDlTotals = (android.widget.TextView) root.findViewById(R.id.txtDlTotals);
        txtUlTotals = (android.widget.TextView) root.findViewById(R.id.txtUlTotals);
        txtNoUploads = (android.widget.TextView) root.findViewById(R.id.txtNoUploads);

        // DL totals and speed use the same colour as the download progress bars (theme accent).
        int dlColor = ThemeUtils.getAccentColor(mainActivity);
        txtDlTotals.setTextColor(dlColor);
        txtDownloadSpeed.setTextColor(dlColor);

        Button btnClear = (Button) root.findViewById(R.id.btnClearFinished);
        btnClear.setOnClickListener(v -> {
            if (mainActivity.mService != null) {
                mainActivity.mService.clear_finished_downloads();
                updateList();
            }
        });

        downloadListView.setOnItemClickListener((parent, view, position, id) -> {
            DownloadItem download = downloadList.get(position);
            DCConstants.DownloadStatus status = download.get_status();
            if (status == DCConstants.DownloadStatus.COMPLETED) {
                openDownloadedFile(download);
            } else {
                Toast.makeText(getActivity(), "Long-press for options", Toast.LENGTH_SHORT).show();
            }
        });

        downloadListView.setOnItemLongClickListener((parent, view, position, id) -> {
            DownloadItem download = downloadList.get(position);
            showPauseResumeDialog(download);
            return true;
        });

        is_ready = true;
        return root;
    }

    private void updateList() {
        if (mainActivity == null || mainActivity.mService == null || !is_ready) return;
        
        List<DownloadItem> newList = mainActivity.mService.get_download_queue();
        downloadList.clear();
        downloadList.addAll(newList);
        adapter.notifyDataSetChanged();

        try {
            updateUploadStats();
        } catch (Exception e) {
            // Never let a panel problem hide it silently: log it and keep the panel on screen.
            android.util.Log.e("andcpp", "Transfers panel update failed", e);
        }
    }

    private void updateUploadStats() {
        UploadManager.UploadStats stats = mainActivity.mService.getUploadStats();
        List<PeerConnectionHandler> uploads = mainActivity.mService.getActiveUploads();

        // Total download speed: sum of everything currently downloading (folders sum their own files).
        double downloadSpeed = 0;
        for (DownloadItem item : downloadList) {
            if (item.isFolder() || item.get_status() == DCConstants.DownloadStatus.DOWNLOADING) {
                downloadSpeed += item.avg_speed();
            }
        }
        long totalDownloaded = com.phinvader.libjdcpp.MessageHandler.totalDownloadedBytes.get();
        long[] allTime = mainActivity.mService.getAllTimeTotals();

        // Always shown, so speeds, totals and running uploads are visible at a glance.
        uploadStatsLayout.setVisibility(View.VISIBLE);
        txtNoUploads.setVisibility(uploads.isEmpty() ? View.VISIBLE : View.GONE);

        // Header: total speeds, like a row's speed text.
        FileSize dlSpeedFs = new FileSize(downloadSpeed);
        txtDownloadSpeed.setText(String.format(java.util.Locale.US, "⬇ %.2f %s/s", dlSpeedFs.fileSize, dlSpeedFs.unit));
        FileSize speedFs = new FileSize(stats.currentSpeed);
        txtUploadSpeed.setText(String.format(java.util.Locale.US, "⬆ %.2f %s/s", speedFs.fileSize, speedFs.unit));

        // Totals: this session, then all time in brackets (colours come from the layout: DL teal, UL warm yellow).
        txtDlTotals.setText("DL: " + formatSize(totalDownloaded) + "  (" + formatSize(allTime[0]) + ")");
        txtUlTotals.setText("UL: " + formatSize(stats.totalBytes) + "  (" + formatSize(allTime[1]) + ")");

        FileSize totalFs = new FileSize(stats.totalBytes);
        String summary = String.format(java.util.Locale.US, "Active: %d | Queued: %d | Total: %.2f %s | Done: %d | Failed: %d",
                stats.activeCount, stats.queueLength, totalFs.fileSize, totalFs.unit, stats.completedCount, stats.failedCount);
        int errors = stats.handshakeFailures + stats.connectionErrors;
        if (errors > 0) {
            summary += " | Errors: " + errors;
        }
        txtUploadStats.setText(summary);

        // One row per open upload connection, reusing views so the list doesn't flicker every second.
        LayoutInflater inflater = LayoutInflater.from(mainActivity);
        while (uploadRows.size() < uploads.size()) {
            View row = inflater.inflate(R.layout.download_row_element, uploadRowsContainer, false);
            // Upload rows: warm yellow progress bar and speed (download rows stay teal).
            tintProgress((android.widget.ProgressBar) row.findViewById(R.id.download_progress), COLOR_UL);
            ((android.widget.TextView) row.findViewById(R.id.download_speed)).setTextColor(COLOR_UL);
            if (!uploadRows.isEmpty()) {
                View divider = new View(mainActivity);
                divider.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(getResources().getDisplayMetrics().density))));
                divider.setBackgroundColor(themeColor(R.attr.appDividerColor));
                uploadRowsContainer.addView(divider);
            }
            uploadRowsContainer.addView(row);
            uploadRows.add(row);
        }
        while (uploadRows.size() > uploads.size()) {
            View row = uploadRows.remove(uploadRows.size() - 1);
            int idx = uploadRowsContainer.indexOfChild(row);
            uploadRowsContainer.removeView(row);
            if (idx > 0) uploadRowsContainer.removeViewAt(idx - 1); // its divider
        }
        for (int i = 0; i < uploads.size(); i++) {
            bindUploadRow(uploadRows.get(i), uploads.get(i));
        }
    }

    /** Upload colour (warm yellow). The download colour is the theme accent, see ThemeUtils.getAccentColor(). */
    private static final int COLOR_UL = 0xFFF9A825;

    private static String formatSize(long bytes) {
        FileSize fs = new FileSize(bytes);
        return String.format(java.util.Locale.US, "%.2f %s", fs.fileSize, fs.unit);
    }

    private static void appendColored(android.text.SpannableStringBuilder sb, String text, int color) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), start, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Tints a row's progress bar (determinate and spinning) in the given colour. */
    private static void tintProgress(android.widget.ProgressBar bar, int color) {
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            android.content.res.ColorStateList csl = android.content.res.ColorStateList.valueOf(color);
            bar.setProgressTintList(csl);
            bar.setIndeterminateTintList(csl);
        } else {
            if (bar.getProgressDrawable() != null) {
                bar.getProgressDrawable().mutate().setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
            }
            if (bar.getIndeterminateDrawable() != null) {
                bar.getIndeterminateDrawable().mutate().setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        }
    }

    private int themeColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (mainActivity.getTheme().resolveAttribute(attr, tv, true)) {
            return tv.resourceId != 0 ? getResources().getColor(tv.resourceId) : tv.data;
        }
        return 0x22888888;
    }

    /** Fills a download_row_element row with one upload, in the same format as a download row. */
    private void bindUploadRow(View row, PeerConnectionHandler up) {
        android.widget.TextView filename = (android.widget.TextView) row.findViewById(R.id.download_filename);
        android.widget.TextView statusText = (android.widget.TextView) row.findViewById(R.id.download_status_text);
        android.widget.ProgressBar progress = (android.widget.ProgressBar) row.findViewById(R.id.download_progress);
        android.widget.TextView speed = (android.widget.TextView) row.findViewById(R.id.download_speed);

        PeerConnectionHandler.ConnectionState state = up.getState();
        String statusName;
        switch (state) {
            case HANDSHAKING: statusName = "Connecting"; break;
            case CONNECTED:
            case REQUESTING: statusName = "Waiting for request"; break;
            case QUEUED: statusName = "Queued"; break;
            case UPLOADING: statusName = "Uploading"; break;
            case COMPLETED: statusName = "Completed"; break;
            case FAILED: statusName = "Failed"; break;
            default: statusName = state.name(); break;
        }

        String name = up.getFileName();
        boolean hasFile = name != null && !name.equals("None");
        filename.setText(hasFile ? name : "Connection from " + up.getHisNick());

        long total = up.getTotalSize();
        long done = up.getBytesSent();
        String progressStr = "";
        if (hasFile && total > 0) {
            FileSize doneFs = new FileSize(done);
            FileSize totalFs = new FileSize(total);
            progressStr = String.format(java.util.Locale.US, " | %.1f%% (%.2f %s / %.2f %s)",
                    (double) done / total * 100.0, doneFs.fileSize, doneFs.unit, totalFs.fileSize, totalFs.unit);
        }
        statusText.setText("To: " + up.getHisNick() + " | " + statusName + progressStr);

        if (state == PeerConnectionHandler.ConnectionState.UPLOADING && total > 0) {
            progress.setIndeterminate(false);
            progress.setMax(1000);
            progress.setProgress((int) ((double) done / total * 1000));
        } else if (state == PeerConnectionHandler.ConnectionState.HANDSHAKING) {
            progress.setIndeterminate(true);
        } else {
            progress.setIndeterminate(false);
            progress.setMax(1000);
            progress.setProgress(hasFile && total > 0 ? (int) ((double) done / total * 1000) : 0);
        }

        if (state == PeerConnectionHandler.ConnectionState.UPLOADING) {
            double curSpeed = up.getSpeed();
            FileSize fs = new FileSize(curSpeed);
            String etaStr = "";
            if (curSpeed > 0 && total > done) {
                long remainingSecs = (long) ((total - done) / curSpeed);
                if (remainingSecs >= 3600) {
                    etaStr = String.format(java.util.Locale.US, " | %02d:%02d:%02d",
                            remainingSecs / 3600, (remainingSecs % 3600) / 60, remainingSecs % 60);
                } else {
                    etaStr = String.format(java.util.Locale.US, " | %02d:%02d", remainingSecs / 60, remainingSecs % 60);
                }
            }
            speed.setText(String.format(java.util.Locale.US, "⬆ %.2f %s/s%s", fs.fileSize, fs.unit, etaStr));
        } else {
            speed.setText(statusName);
        }
        speed.setVisibility(View.VISIBLE);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateList();
                handler.postDelayed(this, 1000); // Update every second
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

    private void openDownloadedFile(DownloadItem download) {
        if (mainActivity != null) {
            mainActivity.openFile(new File(download.getLocalFilePath()));
        }
    }

    private void showPauseResumeDialog(final DownloadItem download) {
        if (mainActivity == null || mainActivity.mService == null) return;

        DCConstants.DownloadStatus status = download.get_status();
        String title = download.getFileName() + (download.isFolder() ? " (Folder)" : "");
        
        long total = download.total_bytes();
        long done = download.bytes_done();
        FileSize doneFs = new FileSize(done);
        FileSize totalFs = new FileSize(total);
        String progressInfo;
        if (total > 0) {
            double percent = ((double) done / total) * 100.0;
            progressInfo = String.format(java.util.Locale.US, "\nProgress: %.1f%% (%.2f %s / %.2f %s)",
                    percent, doneFs.fileSize, doneFs.unit, totalFs.fileSize, totalFs.unit);
        } else {
            progressInfo = String.format(java.util.Locale.US, "\nProgress: %.2f %s done",
                    doneFs.fileSize, doneFs.unit);
        }

        String statusName = status.name();
        if (status == DCConstants.DownloadStatus.INTERUPTED) statusName = "Paused";
        else if (DCPPService.WAIT_REASON_WIFI.equals(download.getWaitReason())) statusName = "Waiting for Wi-Fi";

        // Combine progress and status into the title since setMessage and setItems are mutually exclusive
        String fullTitle = title + " (" + statusName + ")" + progressInfo;
        
        final List<String> options = new ArrayList<>();
        if (status == DCConstants.DownloadStatus.DOWNLOADING || status == DCConstants.DownloadStatus.STARTED || 
            status == DCConstants.DownloadStatus.INITIATED || status == DCConstants.DownloadStatus.QUEUED
            || status == DCConstants.DownloadStatus.WAITING_FOR_PEER) {
            options.add("Pause");
        } else if (status == DCConstants.DownloadStatus.INTERUPTED || status == DCConstants.DownloadStatus.FAILED) {
            options.add("Resume");
        }
        
        if (status == DCConstants.DownloadStatus.COMPLETED) {
            options.add("Open File");
        }
        
        options.add("Remove Download");
        options.add("Delete File");

        new android.app.AlertDialog.Builder(getActivity())
                .setTitle(fullTitle)
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    String selected = options.get(which);
                    if (selected.equals("Pause")) {
                        download.pause_download();
                        Toast.makeText(getActivity(), "Pausing...", Toast.LENGTH_SHORT).show();
                    } else if (selected.equals("Resume")) {
                        download.resume_download();
                        Toast.makeText(getActivity(), "Resuming...", Toast.LENGTH_SHORT).show();
                    } else if (selected.equals("Open File")) {
                        openDownloadedFile(download);
                    } else if (selected.equals("Remove Download")) {
                        download.cancel_download();
                        Toast.makeText(getActivity(), "Removed from list", Toast.LENGTH_SHORT).show();
                    } else if (selected.equals("Delete File")) {
                        confirmDelete(download);
                    }
                    updateList();
                })
                .setNegativeButton("Close", (dialog, which) -> updateList())
                .show();
    }

    private void confirmDelete(final DownloadItem download) {
        new android.app.AlertDialog.Builder(getActivity())
                .setTitle("Confirm Delete")
                .setMessage("Are you sure you want to remove the download and delete the file(s) for " + download.getFileName() + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    if (download.delete_download()) {
                        Toast.makeText(getActivity(), "Deleted " + download.getFileName(), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getActivity(), "Removed " + download.getFileName(), Toast.LENGTH_SHORT).show();
                    }
                    updateList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
