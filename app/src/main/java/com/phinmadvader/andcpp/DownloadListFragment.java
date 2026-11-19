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
    private android.widget.TextView txtUploadPeers;
    private android.widget.TextView txtUploadErrors;

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
        txtUploadPeers = (android.widget.TextView) root.findViewById(R.id.txtUploadPeers);
        txtUploadErrors = (android.widget.TextView) root.findViewById(R.id.txtUploadErrors);

        Button btnClear = (Button) root.findViewById(R.id.btnClearFinished);
        btnClear.setOnClickListener(v -> {
            if (mainActivity.mService != null) {
                mainActivity.mService.clear_finished_downloads();
                updateList();
            }
        });

        Button btnResetAll = (Button) root.findViewById(R.id.btnResetAllDownloads);
        btnResetAll.setOnClickListener(v -> {
            if (mainActivity.mService != null) {
                mainActivity.mService.resetAllDownloads();
                Toast.makeText(getActivity(), "Resetting all downloads...", Toast.LENGTH_SHORT).show();
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

        updateUploadStats();
    }

    private void updateUploadStats() {
        UploadManager.UploadStats stats = mainActivity.mService.getUploadStats();
        if (stats.activeCount > 0 || stats.queueLength > 0 || stats.currentSpeed > 0 || stats.completedCount > 0) {
            uploadStatsLayout.setVisibility(View.VISIBLE);
            
            FileSize speedFs = new FileSize(stats.currentSpeed);
            txtUploadSpeed.setText(String.format(java.util.Locale.US, "Upload Speed: %.2f %s/s ⬆", speedFs.fileSize, speedFs.unit));
            
            FileSize totalFs = new FileSize(stats.totalBytes);
            txtUploadStats.setText(String.format(java.util.Locale.US, "Active: %d | Queued: %d | Total Uploaded: %.2f %s | Success: %d | Failed: %d", 
                stats.activeCount, stats.queueLength, totalFs.fileSize, totalFs.unit, stats.completedCount, stats.failedCount));
            
            if (stats.connectedPeers.isEmpty()) {
                txtUploadPeers.setText("Peers: None");
            } else {
                StringBuilder sb = new StringBuilder("Peers: ");
                for (int i = 0; i < stats.connectedPeers.size(); i++) {
                    sb.append(stats.connectedPeers.get(i));
                    if (i < stats.connectedPeers.size() - 1) sb.append(", ");
                }
                txtUploadPeers.setText(sb.toString());
            }

            txtUploadErrors.setText(String.format(java.util.Locale.US, "Handshake Failures: %d | Connection Errors: %d", 
                stats.handshakeFailures, stats.connectionErrors));
        } else {
            uploadStatsLayout.setVisibility(View.GONE);
        }
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
        
        // Combine progress and status into the title since setMessage and setItems are mutually exclusive
        String fullTitle = title + " (" + statusName + ")" + progressInfo;
        
        final List<String> options = new ArrayList<>();
        if (status == DCConstants.DownloadStatus.DOWNLOADING || status == DCConstants.DownloadStatus.STARTED || 
            status == DCConstants.DownloadStatus.INITIATED || status == DCConstants.DownloadStatus.QUEUED) {
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
