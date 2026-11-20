package com.phinmadvader.andcpp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class PrivateChatFragment extends Fragment {
    private static final String ARG_HUB_IP = "arg_hub_ip";
    private static final String ARG_TARGET_NICK = "arg_target_nick";

    private String hubIp;
    private String targetNick;

    private ListView messagesListView;
    private EditText chatInputText;
    private Button chatSendButton;
    private Button getFileListButton;
    private ChatMessagesAdapter adapter;
    private final List<ChatMessage> messageList = new ArrayList<>();

    private MainActivity mainActivity;

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

    public static PrivateChatFragment newInstance(String hubIp, String targetNick) {
        PrivateChatFragment fragment = new PrivateChatFragment();
        Bundle args = new Bundle();
        args.putString(ARG_HUB_IP, hubIp);
        args.putString(ARG_TARGET_NICK, targetNick);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            hubIp = getArguments().getString(ARG_HUB_IP);
            targetNick = getArguments().getString(ARG_TARGET_NICK);
        }
        if (getActivity() instanceof MainActivity) {
            mainActivity = (MainActivity) getActivity();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_private_chat, container, false);

        messagesListView = (ListView) view.findViewById(R.id.chat_messages_list);
        chatInputText = (EditText) view.findViewById(R.id.chat_input_text);
        chatSendButton = (Button) view.findViewById(R.id.chat_send_button);
        getFileListButton = (Button) view.findViewById(R.id.chat_get_file_list_button);

        adapter = new ChatMessagesAdapter(getActivity(), messageList);
        messagesListView.setAdapter(adapter);

        chatSendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage();
            }
        });

        chatInputText.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    sendMessage();
                    return true;
                }
                return false;
            }
        });

        if (getFileListButton != null) {
            getFileListButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    triggerGetFileList();
                }
            });
        }

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() != null) {
            IntentFilter filter = new IntentFilter("com.phinmadvader.andcpp.PRIVATE_MESSAGE_RECEIVED");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                getActivity().registerReceiver(pmReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                getActivity().registerReceiver(pmReceiver, filter);
            }
        }
        loadMessages();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getActivity() != null) {
            try {
                getActivity().unregisterReceiver(pmReceiver);
            } catch (Exception ignored) {}
        }
    }

    public void updateTarget(String newHubIp, String newNick) {
        this.hubIp = newHubIp;
        this.targetNick = newNick;
        loadMessages();
    }

    public String getTargetNick() {
        return targetNick;
    }

    public String getHubIp() {
        return hubIp;
    }

    private void loadMessages() {
        if (mainActivity == null || mainActivity.mService == null || targetNick == null) return;
        List<ChatMessage> fresh = mainActivity.mService.get_private_messages(hubIp, targetNick);
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

        if (mainActivity == null || mainActivity.mService == null) {
            Toast.makeText(getActivity(), "Not connected to service.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean sent = mainActivity.mService.send_private_message(hubIp, targetNick, text);
        if (sent) {
            chatInputText.setText("");
            loadMessages();
        } else {
            Toast.makeText(getActivity(), "Cannot send message: Not connected to hub.", Toast.LENGTH_SHORT).show();
        }
    }

    private void triggerGetFileList() {
        if (mainActivity == null || mainActivity.mService == null || hubIp == null || targetNick == null) {
            Toast.makeText(getActivity(), "Not connected to hub.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(getActivity(), "Requesting file list of " + targetNick + "...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                mainActivity.mService.get_file_list(hubIp, targetNick);
            }
        }).start();
    }
}
