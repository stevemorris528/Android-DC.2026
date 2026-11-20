package com.phinmadvader.andcpp;

import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class PrivateChatActivity extends Activity {
    public static final String EXTRA_HUB_IP = "extra_hub_ip";
    public static final String EXTRA_TARGET_NICK = "extra_target_nick";

    private String hubIp;
    private String targetNick;

    private DCPPService mService;
    private boolean isBound = false;

    private ListView messagesListView;
    private EditText chatInputText;
    private Button chatSendButton;
    private ChatMessagesAdapter adapter;
    private final List<ChatMessage> messageList = new ArrayList<>();

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            DCPPService.LocalBinder binder = (DCPPService.LocalBinder) service;
            mService = binder.getService();
            isBound = true;
            loadMessages();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isBound = false;
            mService = null;
        }
    };

    private final BroadcastReceiver pmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.phinmadvader.andcpp.PRIVATE_MESSAGE_RECEIVED".equals(intent.getAction())) {
                String intentHub = intent.getStringExtra("hubIp");
                String intentNick = intent.getStringExtra("senderNick");
                if (intentNick != null && intentNick.equalsIgnoreCase(targetNick)) {
                    loadMessages();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtils.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_private_chat);

        hubIp = getIntent().getStringExtra(EXTRA_HUB_IP);
        targetNick = getIntent().getStringExtra(EXTRA_TARGET_NICK);
        if (targetNick == null) {
            targetNick = "Unknown User";
        }

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle("PM: " + targetNick);
            if (hubIp != null && !hubIp.isEmpty()) {
                actionBar.setSubtitle(hubIp);
            }
        }

        messagesListView = (ListView) findViewById(R.id.chat_messages_list);
        chatInputText = (EditText) findViewById(R.id.chat_input_text);
        chatSendButton = (Button) findViewById(R.id.chat_send_button);

        adapter = new ChatMessagesAdapter(this, messageList);
        messagesListView.setAdapter(adapter);

        chatSendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage();
            }
        });

        chatInputText.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(android.widget.TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    sendMessage();
                    return true;
                }
                return false;
            }
        });

        bindService(new Intent(this, DCPPService.class), mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String newHubIp = intent.getStringExtra(EXTRA_HUB_IP);
        String newNick = intent.getStringExtra(EXTRA_TARGET_NICK);
        if (newNick != null && !newNick.isEmpty()) {
            this.targetNick = newNick;
            this.hubIp = newHubIp;
            ActionBar actionBar = getActionBar();
            if (actionBar != null) {
                actionBar.setTitle("PM: " + targetNick);
                if (hubIp != null && !hubIp.isEmpty()) {
                    actionBar.setSubtitle(hubIp);
                } else {
                    actionBar.setSubtitle(null);
                }
            }
            loadMessages();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(pmReceiver, new IntentFilter("com.phinmadvader.andcpp.PRIVATE_MESSAGE_RECEIVED"));
        loadMessages();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(pmReceiver);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(mConnection);
            isBound = false;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.pm_chat, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.action_get_file_list) {
            triggerGetFileList();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void triggerGetFileList() {
        if (mService == null || hubIp == null || targetNick == null) {
            Toast.makeText(this, "Not connected to hub.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Requesting file list of " + targetNick + "...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                mService.get_file_list(hubIp, targetNick);
            }
        }).start();
    }

    private void loadMessages() {
        if (mService == null || targetNick == null) return;
        List<ChatMessage> fresh = mService.get_private_messages(hubIp, targetNick);
        messageList.clear();
        messageList.addAll(fresh);
        adapter.notifyDataSetChanged();
        if (!messageList.isEmpty()) {
            messagesListView.setSelection(messageList.size() - 1);
        }
    }

    private void sendMessage() {
        if (chatInputText == null) return;
        String text = chatInputText.getText().toString().trim();
        if (text.isEmpty()) return;

        if (mService == null) {
            Toast.makeText(this, "Not connected to service.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean sent = mService.send_private_message(hubIp, targetNick, text);
        if (sent) {
            chatInputText.setText("");
            loadMessages();
        } else {
            Toast.makeText(this, "Cannot send message: Not connected to hub.", Toast.LENGTH_SHORT).show();
        }
    }
}
