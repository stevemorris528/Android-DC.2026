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

public class PrivateChatViewFragment extends Fragment {
    private static final String ARG_INDEX = "arg_index";

    private int chatIndex;
    private MainActivity mainActivity;

    private ListView messagesListView;
    private EditText chatInputText;
    private Button chatSendButton;
    private Button getFileListButton;
    private TextView subtitleText;
    private ChatMessagesAdapter adapter;
    private final List<ChatMessage> messageList = new ArrayList<>();

    private final BroadcastReceiver pmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.phinmadvader.andcpp.PRIVATE_MESSAGE_RECEIVED".equals(intent.getAction())) {
                loadMessages();
            }
        }
    };

    public static PrivateChatViewFragment newInstance(int index) {
        PrivateChatViewFragment fragment = new PrivateChatViewFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_INDEX, index);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            chatIndex = getArguments().getInt(ARG_INDEX, 0);
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
        subtitleText = (TextView) view.findViewById(R.id.chat_subtitle_text);

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

        Button closeButton = (Button) view.findViewById(R.id.chat_close_button);
        if (closeButton != null) {
            closeButton.setVisibility(View.VISIBLE);
            closeButton.setOnClickListener(v -> {
                if (mainActivity != null) mainActivity.close_private_chat(chatIndex);
            });
        }

        updateHeaderInfo();
        loadMessages();
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
        updateHeaderInfo();
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

    private PrivateChatManager.PrivateChatEntry getEntry() {
        if (mainActivity != null && mainActivity.privatechat_manager != null) {
            return mainActivity.privatechat_manager.getEntry(chatIndex);
        }
        return null;
    }

    private void updateHeaderInfo() {
        PrivateChatManager.PrivateChatEntry entry = getEntry();
        if (entry != null && subtitleText != null) {
            String sub = "PM: " + entry.nick;
            if (entry.hubIp != null && !entry.hubIp.isEmpty()) {
                sub += " (" + entry.hubIp + ")";
            }
            subtitleText.setText(sub);
        }
    }

    private void loadMessages() {
        PrivateChatManager.PrivateChatEntry entry = getEntry();
        if (mainActivity == null || mainActivity.mService == null || entry == null) return;
        List<ChatMessage> fresh = mainActivity.mService.get_private_messages(entry.hubIp, entry.nick);
        messageList.clear();
        messageList.addAll(fresh);
        adapter.notifyDataSetChanged();
        if (!messageList.isEmpty()) {
            messagesListView.setSelection(messageList.size() - 1);
        }
    }

    private void sendMessage() {
        PrivateChatManager.PrivateChatEntry entry = getEntry();
        if (chatInputText == null || entry == null) return;
        String text = chatInputText.getText().toString().trim();
        if (text.isEmpty()) return;

        if (mainActivity == null || mainActivity.mService == null) {
            Toast.makeText(getActivity(), "Not connected to service.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean sent = mainActivity.mService.send_private_message(entry.hubIp, entry.nick, text);
        if (sent) {
            chatInputText.setText("");
            loadMessages();
        } else {
            Toast.makeText(getActivity(), "Cannot send message: Not connected to hub.", Toast.LENGTH_SHORT).show();
        }
    }

    private void triggerGetFileList() {
        PrivateChatManager.PrivateChatEntry entry = getEntry();
        if (mainActivity == null || mainActivity.mService == null || entry == null) {
            Toast.makeText(getActivity(), "Not connected to hub.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(getActivity(), "Requesting file list of " + entry.nick + "...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                mainActivity.mService.get_file_list(entry.hubIp, entry.nick);
            }
        }).start();
    }
}
