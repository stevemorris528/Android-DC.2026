package com.phinmadvader.andcpp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.phinvader.libjdcpp.DCMessage;
import com.phinvader.libjdcpp.DCUser;

/**
 * Created by invader on 8/11/13.
 */
public class UserListFragment extends Fragment {
	private MainActivity mainActivity;
	private ListView userListView;
	private TextView connectedUserCount;
	private UserListAdapter userListAdapter;

	// Thread-safe map of lowercase nick -> DCUserComparable
	private final Map<String, DCUserComparable> nickMap = new ConcurrentHashMap<>();
	private final List<DCUserComparable> filteredList = new ArrayList<>();
	private final Object filterLock = new Object();

	public boolean is_ready = false;
	private String currentHubIp = null;
	private String filterText = "";

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private static final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
	private volatile boolean filterUpdatePending = false;

	private final Runnable debounceFilterRunnable = () -> {
		filterUpdatePending = false;
		applyFilter();
	};

	public void setHubIp(String hubIp) {
		this.currentHubIp = hubIp;
		if (is_ready) {
			clearNicklistConstraints();
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		if (is_ready && nickMap.isEmpty()) {
			clearNicklistConstraints();
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		mainHandler.removeCallbacks(debounceFilterRunnable);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		this.mainActivity = (MainActivity) getActivity();
		View rootView = inflater.inflate(R.layout.userlist_layout, container,
				false);

		userListAdapter = new UserListAdapter(mainActivity,
				android.R.layout.simple_list_item_1, filteredList);
		userListAdapter.setOnUserMenuClickListener(new UserListAdapter.OnUserMenuClickListener() {
			@Override
			public void onUserMenuClick(View anchorView, DCUserComparable user) {
				showUserContextMenu(anchorView, user);
			}
		});

		userListView = (ListView) rootView.findViewById(R.id.listView);
		userListView.setAdapter(userListAdapter);
		userListView
				.setOnItemClickListener(new AdapterView.OnItemClickListener() {
					@Override
					public void onItemClick(AdapterView<?> adapterView,
							View view, int i, long l) {
						synchronized (filterLock) {
							if (i >= 0 && i < filteredList.size()) {
								DCUserComparable user = filteredList.get(i);
								showUserContextMenu(view, user);
							}
						}
					}
				});
		userListView
				.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
					@Override
					public boolean onItemLongClick(AdapterView<?> adapterView,
							View view, int i, long l) {
						synchronized (filterLock) {
							if (i >= 0 && i < filteredList.size()) {
								DCUserComparable user = filteredList.get(i);
								showUserContextMenu(view, user);
								return true;
							}
						}
						return false;
					}
				});

		EditText filterInput = (EditText) rootView.findViewById(R.id.filterInput);
		if (filterInput != null) {
			filterInput.setVisibility(View.GONE);
		}

		connectedUserCount = (TextView) rootView.findViewById(R.id.textView2);

		is_ready = true;
		clearNicklistConstraints();
		return rootView;
	}

	private void showUserContextMenu(View anchorView, final DCUserComparable user) {
		if (getActivity() == null || user == null) return;

		android.widget.PopupMenu popup = new android.widget.PopupMenu(getActivity(), anchorView);
		popup.getMenu().add(0, 1, 0, "1. Get file list");
		popup.getMenu().add(0, 2, 1, "2. Send private message");

		popup.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(android.view.MenuItem item) {
				switch (item.getItemId()) {
					case 1:
						mainActivity.open_file_list(currentHubIp, user);
						return true;
					case 2:
						mainActivity.open_private_chat(currentHubIp, user.nick);
						return true;
					default:
						return false;
				}
			}
		});

		popup.show();
	}

	private void scheduleFilterUpdate() {
		if (!filterUpdatePending) {
			filterUpdatePending = true;
			mainHandler.postDelayed(debounceFilterRunnable, 300);
		}
	}

	private void applyFilter() {
		workerExecutor.execute(() -> {
			List<DCUserComparable> sorted = new ArrayList<>(nickMap.values());
			Collections.sort(sorted);

			List<DCUserComparable> newFiltered = new ArrayList<>();
			String query = filterText.toLowerCase();
			for (DCUserComparable user : sorted) {
				if (query.isEmpty() || (user.nick != null && user.nick.toLowerCase().contains(query))) {
					newFiltered.add(user);
				}
			}

			mainHandler.post(() -> {
				synchronized (filterLock) {
					filteredList.clear();
					filteredList.addAll(newFiltered);
				}
				if (userListAdapter != null) {
					userListAdapter.notifyDataSetChanged();
				}
				if (connectedUserCount != null) {
					connectedUserCount.setText(String.valueOf(newFiltered.size()));
				}
			});
		});
	}

	public void addOneNick(DCMessage nick_msg) {
		if (nick_msg == null) return;
		if (currentHubIp != null && nick_msg.hubname_s != null) {
			if (!currentHubIp.equals(nick_msg.hubname_s) && !currentHubIp.startsWith(nick_msg.hubname_s) && !nick_msg.hubname_s.startsWith(currentHubIp)) {
				return;
			}
		}
		if (nick_msg.myinfo == null || nick_msg.myinfo.nick == null) return;
		workerExecutor.execute(() -> {
			DCUserComparable u = new DCUserComparable(nick_msg.myinfo);
			nickMap.put(u.nick.toLowerCase(), u);
			scheduleFilterUpdate();
		});
	}

	public void addNick(final List<DCUserComparable> nicks) {
		if (nicks == null || nicks.isEmpty()) return;
		workerExecutor.execute(() -> {
			for (DCUserComparable nick : nicks) {
				if (nick != null && nick.nick != null) {
					nickMap.put(nick.nick.toLowerCase(), nick);
				}
			}
			scheduleFilterUpdate();
		});
	}

	public void delNick(final DCMessage msg) {
		if (msg == null) return;
		if (currentHubIp != null && msg.hubname_s != null) {
			if (!currentHubIp.equals(msg.hubname_s) && !currentHubIp.startsWith(msg.hubname_s) && !msg.hubname_s.startsWith(currentHubIp)) {
				return;
			}
		}
		String nickStr = msg.quit_s;
		if (nickStr == null || nickStr.isEmpty()) return;
		workerExecutor.execute(() -> {
			nickMap.remove(nickStr.toLowerCase());
			scheduleFilterUpdate();
		});
	}

	public void clearNicklistConstraints() {
		if (mainActivity == null || !is_ready) return;
		workerExecutor.execute(() -> {
			nickMap.clear();

			if (mainActivity != null && mainActivity.mService != null) {
				List<DCUser> nicks = null;
				if (currentHubIp != null) {
					nicks = mainActivity.mService.get_nick_list(currentHubIp);
				}
				if ((nicks == null || nicks.isEmpty()) && !mainActivity.mService.getActiveSessions().isEmpty()) {
					for (DCPPService.HubSession s : mainActivity.mService.getActiveSessions()) {
						if (s.status == DCPPService.DCClientStatus.CONNECTED) {
							nicks = mainActivity.mService.get_nick_list(s.hubIp);
							if (currentHubIp == null) currentHubIp = s.hubIp;
							break;
						}
					}
				}

				if (nicks != null) {
					for (DCUser u : nicks) {
						if (u != null && u.nick != null) {
							DCUserComparable cu = new DCUserComparable(u);
							nickMap.put(cu.nick.toLowerCase(), cu);
						}
					}
				}
			}
			applyFilter();
		});
	}

	public void sortNickList(int sortBy) {
		workerExecutor.execute(() -> {
			List<DCUserComparable> sorted = new ArrayList<>(nickMap.values());
			Collections.sort(sorted, new DCUserComparable.ShareSizeCompare());
			mainHandler.post(() -> {
				synchronized (filterLock) {
					filteredList.clear();
					filteredList.addAll(sorted);
				}
				if (userListAdapter != null) {
					userListAdapter.notifyDataSetChanged();
				}
			});
		});
	}

	public void refreshViewFromData() {
		applyFilter();
	}
}
