package com.phinmadvader.andcpp;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Button;

import com.phinmadvader.andcpp.DCPPService.DownloadItem;
import com.phinmadvader.andcpp.DCPPService.DownloadObject;
import com.phinmadvader.andcpp.DCPPService.FileSize;
import com.phinvader.libjdcpp.DCConstants;

import java.util.List;

public class DownloadListAdapter extends ArrayAdapter<DownloadItem> {
    private final Context context;

    public DownloadListAdapter(Context context, List<DownloadItem> objects) {
        super(context, R.layout.download_row_element, objects);
        this.context = context;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            convertView = inflater.inflate(R.layout.download_row_element, parent, false);
        }

        DownloadItem download = getItem(position);
        if (download == null) return convertView;

        TextView filename = (TextView) convertView.findViewById(R.id.download_filename);
        TextView statusText = (TextView) convertView.findViewById(R.id.download_status_text);
        ProgressBar progress = (ProgressBar) convertView.findViewById(R.id.download_progress);
        TextView speed = (TextView) convertView.findViewById(R.id.download_speed);

        filename.setText(download.getFileName() + (download.isFolder() ? " (Folder)" : ""));
        
        DCConstants.DownloadStatus status = download.get_status();
        
        long total = download.total_bytes();
        long done = download.bytes_done();
        FileSize doneFs = new FileSize(done);
        FileSize totalFs = new FileSize(total);
        
        String progressStr;
        if (total > 0) {
            double percent = ((double) done / total) * 100.0;
            progressStr = String.format(java.util.Locale.US, "%.1f%% (%.2f %s / %.2f %s)",
                    percent, doneFs.fileSize, doneFs.unit, totalFs.fileSize, totalFs.unit);
        } else {
            progressStr = String.format(java.util.Locale.US, "%.2f %s done",
                    doneFs.fileSize, doneFs.unit);
        }

        String statusName = status.name();
        if (status == DCConstants.DownloadStatus.DOWNLOADING) statusName = "Downloading";
        else if (status == DCConstants.DownloadStatus.QUEUED) statusName = "Queued";
        else if (status == DCConstants.DownloadStatus.INITIATED || status == DCConstants.DownloadStatus.STARTED) statusName = "Connecting";
        else if (status == DCConstants.DownloadStatus.WAITING_FOR_PEER) {
            String reason = download.getWaitReason();
            if (DCPPService.WAIT_REASON_WIFI.equals(reason)) {
                statusName = "Waiting for Wi-Fi";
            } else {
                statusName = "Waiting for peer" + (reason != null && !reason.isEmpty() ? " (" + reason + ")" : "");
            }
        }
        else if (status == DCConstants.DownloadStatus.COMPLETED) statusName = "Completed";
        else if (status == DCConstants.DownloadStatus.INTERUPTED) statusName = "Paused";
        else if (status == DCConstants.DownloadStatus.FAILED) statusName = "Failed";

        statusText.setText("From: " + download.getTarget_nick() + " | " + statusName + " | " + progressStr);

        if (total > 0) {
            progress.setIndeterminate(false);
            progress.setMax(1000);
            progress.setProgress((int) (((double) done / total) * 1000));
        } else {
            // For file lists or unknown size, use indeterminate if not completed
            if (status == DCConstants.DownloadStatus.COMPLETED) {
                progress.setIndeterminate(false);
                progress.setMax(100);
                progress.setProgress(100);
            } else {
                progress.setIndeterminate(true);
            }
        }

        if (status == DCConstants.DownloadStatus.DOWNLOADING) {
            double curSpeed = download.avg_speed();
            FileSize fs = new FileSize(curSpeed);
            String etaStr = "";
            if (curSpeed > 0 && total > done) {
                long remainingSecs = (long) ((total - done) / curSpeed);
                if (remainingSecs >= 3600) {
                    etaStr = String.format(java.util.Locale.US, " | %02d:%02d:%02d",
                            remainingSecs / 3600, (remainingSecs % 3600) / 60, remainingSecs % 60);
                } else {
                    etaStr = String.format(java.util.Locale.US, " | %02d:%02d",
                            remainingSecs / 60, remainingSecs % 60);
                }
            }
            speed.setText(String.format(java.util.Locale.US, "⬇ %.2f %s/s%s", fs.fileSize, fs.unit, etaStr));
            speed.setVisibility(View.VISIBLE);
        } else if (status == DCConstants.DownloadStatus.COMPLETED) {
            speed.setText("Done");
            speed.setVisibility(View.VISIBLE);
        } else {
            speed.setText(statusName);
            speed.setVisibility(View.VISIBLE);
        }

        return convertView;
    }
}
