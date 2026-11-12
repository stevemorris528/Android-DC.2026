package com.phinmadvader.andcpp;

import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.phinvader.libjdcpp.HubConnectionProbe;

/** Fragment for proving an NMDC hub can be reached and entered. */
public class HubTestFragment extends Fragment {
    private EditText address;
    private EditText nickname;
    private Button testButton;
    private TextView output;
    private AsyncTask<Void, String, HubConnectionProbe.Result> task;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView heading = new TextView(getActivity());
        heading.setText("Android DC++ Hub Connection Test");
        heading.setTextSize(22);
        root.addView(heading);

        TextView addressLabel = new TextView(getActivity());
        addressLabel.setText("Hub address (IP or hostname, optional :port; default 411)");
        root.addView(addressLabel);
        address = new EditText(getActivity());
        address.setSingleLine(true);
        address.setHint("hub.example.net:411");
        root.addView(address);

        TextView nickLabel = new TextView(getActivity());
        nickLabel.setText("Nickname");
        root.addView(nickLabel);
        nickname = new EditText(getActivity());
        nickname.setSingleLine(true);
        nickname.setText("AndroidDCppTest");
        root.addView(nickname);

        testButton = new Button(getActivity());
        testButton.setText("Test hub connection");
        root.addView(testButton);
        testButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { startTest(); }
        });

        ScrollView scroll = new ScrollView(getActivity());
        output = new TextView(getActivity());
        output.setText("Enter a hub address and nickname, then run the test.\n\nThe test stops after the initial hub-entry exchange. It does not load the user list or start downloads.\n");
        output.setTextIsSelectable(true);
        scroll.addView(output);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(scroll, scrollParams);
        
        return root;
    }

    private void startTest() {
        if (task != null) return;
        final String rawAddress = address.getText().toString().trim();
        final String nick = nickname.getText().toString().trim();
        if (rawAddress.length() == 0 || nick.length() == 0 || nick.indexOf(' ') >= 0) {
            output.setText("Please enter a hub address and a nickname without spaces.");
            return;
        }
        String host = rawAddress;
        int port = 411;
        int colon = rawAddress.lastIndexOf(':');
        if (colon > 0 && rawAddress.indexOf(':') == colon) {
            host = rawAddress.substring(0, colon).trim();
            try { port = Integer.parseInt(rawAddress.substring(colon + 1).trim()); }
            catch (NumberFormatException e) {
                output.setText("Invalid port number.");
                return;
            }
        }
        if (port < 1 || port > 65535 || host.length() == 0) {
            output.setText("Please check the hub address and port.");
            return;
        }
        final String targetHost = host;
        final int targetPort = port;
        output.setText("Starting test...\n");
        testButton.setEnabled(false);
        task = new AsyncTask<Void, String, HubConnectionProbe.Result>() {
            @Override protected HubConnectionProbe.Result doInBackground(Void... ignored) {
                return HubConnectionProbe.run(targetHost, targetPort, nick,
                    new HubConnectionProbe.Listener() {
                        @Override public void onStatus(String status) { publishProgress(status); }
                    });
            }
            @Override protected void onProgressUpdate(String... values) {
                output.append(values[0] + "\n");
            }
            @Override protected void onPostExecute(HubConnectionProbe.Result result) {
                output.append("\n" + result.message + "\n");
                if (result.lastHubMessage != null && result.lastHubMessage.length() > 0)
                    output.append("Last hub response: " + result.lastHubMessage + "\n");
                task = null;
                testButton.setEnabled(true);
            }
        }.execute();
    }

    @Override
    public void onDestroy() {
        if (task != null) task.cancel(true);
        super.onDestroy();
    }
}
