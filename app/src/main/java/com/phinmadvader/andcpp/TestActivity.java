package com.phinmadvader.andcpp;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.phinvader.libjdcpp.HubConnectionProbe;

/** Minimal screen for proving an NMDC hub can be reached and entered. */
public class TestActivity extends Activity {
    private EditText address;
    private EditText nickname;
    private Button testButton;
    private TextView output;
    private AsyncTask<Void, String, HubConnectionProbe.Result> task;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtils.applyTheme(this);
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView heading = new TextView(this);
        heading.setText("Android DC++ Hub Connection Test");
        heading.setTextSize(22);
        root.addView(heading);

        TextView addressLabel = new TextView(this);
        addressLabel.setText("Hub address (IP or hostname, optional :port; default 411)");
        root.addView(addressLabel);
        address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("hub.example.net:411");
        root.addView(address);

        TextView nickLabel = new TextView(this);
        nickLabel.setText("Nickname");
        root.addView(nickLabel);
        nickname = new EditText(this);
        nickname.setSingleLine(true);
        nickname.setText("AndroidDCppTest");
        root.addView(nickname);

        testButton = new Button(this);
        testButton.setText("Test hub connection");
        root.addView(testButton);
        testButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { startTest(); }
        });

        Button backToApp = new Button(this);
        backToApp.setText("Return to Android DC++");
        root.addView(backToApp);
        backToApp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                finish();
            }
        });

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setText("Enter a hub address and nickname, then run the test.\n\nThe test stops after the initial hub-entry exchange. It does not load the user list or start downloads.\n");
        output.setTextIsSelectable(true);
        scroll.addView(output);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(scroll, scrollParams);
        setContentView(root);
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

    @Override protected void onDestroy() {
        if (task != null) task.cancel(true);
        super.onDestroy();
    }
}
