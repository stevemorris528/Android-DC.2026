package com.phinmadvader.andcpp;

import java.util.List;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MessageBoardFragment extends Fragment {
	private MainActivity mainActivity;
	private TextView msg_board;
	private EditText chatInputText;
	private Button chatSendButton;
	private ScrollView messageScrollView;
	public boolean is_ready = false;
	private String currentHubIp = null;
	/** Verlihub add-on strip and menus (null until the service is bound). */
	private VerlihubPanel verlihubPanel;
	private View verlihubRoot;

	/** Creates the Verlihub add-on panel once the service is there, and points it at this hub. */
	private void updateVerlihub() {
		if (verlihubRoot == null || mainActivity == null || mainActivity.mService == null) return;
		if (verlihubPanel == null) {
			verlihubPanel = new VerlihubPanel(mainActivity, mainActivity.mService.getVerlihub(),
					verlihubRoot.findViewById(R.id.verlihub_bar),
					(Button) verlihubRoot.findViewById(R.id.verlihub_tools_button));
		}
		verlihubPanel.setHubIp(currentHubIp);
	}

	@Override
	public void onDestroyView() {
		if (verlihubPanel != null) verlihubPanel.detach();
		verlihubPanel = null;
		verlihubRoot = null;
		super.onDestroyView();
	}

	public String getHubIp() {
		return currentHubIp;
	}

	public void setHubIp(String hubIp) {
		this.currentHubIp = hubIp;
		if (is_ready) {
			refresh();
		}
	}

	public void refresh() {
		if (msg_board == null || mainActivity == null || mainActivity.mService == null) return;
		updateVerlihub();
		msg_board.setText("");
		List<String> current_messages = mainActivity.mService.get_board_messages(currentHubIp);
		if (current_messages != null) {
			for (String msg : current_messages) {
				msg_board.append(msg + "\n");
			}
		}
		if (messageScrollView != null) {
			messageScrollView.post(() -> messageScrollView.fullScroll(View.FOCUS_DOWN));
		}
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		this.mainActivity = (MainActivity) getActivity();

		View root = inflater.inflate(R.layout.messageboard_layout, container, false);
		msg_board = (TextView) root.findViewById(R.id.msg_board);
		messageScrollView = (ScrollView) root.findViewById(R.id.messageScrollView);
		chatInputText = (EditText) root.findViewById(R.id.chat_input_text);
		chatSendButton = (Button) root.findViewById(R.id.chat_send_button);
		verlihubRoot = root;

		chatSendButton.setOnClickListener(v -> sendMessage());

		chatInputText.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_SEND
					|| (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
				sendMessage();
				return true;
			}
			return false;
		});

		refresh();
		
		// Auto scroll to bottom
		if (messageScrollView != null) {
			messageScrollView.post(() -> messageScrollView.fullScroll(View.FOCUS_DOWN));
		}

		is_ready = true;
		return root;
	}

	private void sendMessage() {
		if (chatInputText == null) return;
		String text = chatInputText.getText().toString().trim();
		if (text.isEmpty()) return;

		if (mainActivity == null || mainActivity.mService == null) {
			Toast.makeText(getActivity(), "Not connected to service.", Toast.LENGTH_SHORT).show();
			return;
		}

		boolean sent = mainActivity.mService.send_chat_message(currentHubIp, text);
		if (sent) {
			chatInputText.setText("");
			if (messageScrollView != null) {
				messageScrollView.post(() -> messageScrollView.fullScroll(View.FOCUS_DOWN));
			}
		} else {
			Toast.makeText(getActivity(), "Cannot send message: Not connected to hub.", Toast.LENGTH_SHORT).show();
		}
	}

	public void add_msg(final String hubIp, final String msg) {
		if (msg == null) return;
		if (currentHubIp != null && hubIp != null) {
			if (!currentHubIp.equals(hubIp) && !currentHubIp.startsWith(hubIp) && !hubIp.startsWith(currentHubIp)) {
				return;
			}
		}
		mainActivity.runOnUiThread(() -> {
			if (msg_board != null) {
				msg_board.append(msg + "\n");
				if (messageScrollView != null) {
					messageScrollView.post(() -> messageScrollView.fullScroll(View.FOCUS_DOWN));
				}
			}
		});
	}

	public void add_msg(final String msg) {
		add_msg(null, msg);
	}
}
