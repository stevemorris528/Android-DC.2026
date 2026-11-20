package com.phinmadvader.andcpp;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

public class ConfirmDownloadFragment extends DialogFragment {

    public static ConfirmDownloadFragment newInstance(String nick, String localFilePath, String remotePath, long fileSize, String fileName) {
        return newInstance(null, nick, localFilePath, remotePath, fileSize, fileName);
    }

    /** hubIp: the hub the user is on (search results come from a specific hub); null = first connected hub. */
    public static ConfirmDownloadFragment newInstance(String hubIp, String nick, String localFilePath, String remotePath, long fileSize, String fileName) {
        ConfirmDownloadFragment f = new ConfirmDownloadFragment();
        Bundle args = new Bundle();
        args.putString("hubIp", hubIp);
        args.putString("nick", nick);
        args.putString("localFilePath", localFilePath);
        args.putString("remotePath", remotePath);
        args.putLong("fileSize", fileSize);
        args.putString("fileName", fileName);
        f.setArguments(args);
        return f;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Bundle args = getArguments();
        final String nick = args != null ? args.getString("nick") : "";
        final String localFilePath = args != null ? args.getString("localFilePath") : "";
        final String remotePath = args != null ? args.getString("remotePath") : "";
        final long fileSize = args != null ? args.getLong("fileSize") : 0;
        final String fileName = args != null ? args.getString("fileName") : "";
        final String hubIp = args != null ? args.getString("hubIp") : null;

        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        LayoutInflater inflater = getActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.download_dialog, null);

        builder.setView(dialogView)
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        MainActivity mainActivity = (MainActivity) getActivity();
                        if (mainActivity != null && mainActivity.mService != null) {
                            if (hubIp != null) {
                                mainActivity.mService.download_file(hubIp, nick,
                                        localFilePath, remotePath, fileSize, null, null);
                            } else {
                                mainActivity.mService.download_file(
                                        nick,
                                        localFilePath, remotePath,
                                        fileSize);
                            }

                            Toast.makeText(getActivity(),
                                    "Download Started",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null);
        
        TextView title = (TextView) dialogView.findViewById(R.id.title);
        title.setText(fileName);
        
        DCPPService.FileSize fs = new DCPPService.FileSize(fileSize);
        String sizeStr = String.format(java.util.Locale.US, "%.2f %s", fs.fileSize, fs.unit);
        
        TextView userView = (TextView) dialogView.findViewById(R.id.user);
        userView.setText("Size: " + sizeStr + "\nFrom: " + nick + "\nTo: " + localFilePath);

        return builder.create();
    }
}
