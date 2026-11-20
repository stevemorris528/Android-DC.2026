package com.phinmadvader.andcpp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.phinvader.libjdcpp.DCFileList;
import com.phinvader.libjdcpp.DCMessage;

public class SearchResultsFragment extends Fragment {
	private MainActivity mainActivity;
	public boolean is_ready = false;

	private ListView fileListView;
	private ArrayAdapter<DCFileList> filesAdapter; // adapter adapted on
													// curFiles
	private List<DCFileList> curFiles; // adapter list being displayed
	private List<DCMessage> searchResults;
	private android.widget.TextView titleView;

	/** Header shows how many results there are, e.g. "Search Results (12)". */
	private void updateTitle() {
		if (titleView != null) {
			int n = searchResults != null ? searchResults.size() : 0;
			titleView.setText(n > 0 ? "Search Results (" + n + ")" : "Search Results");
		}
	}

	public SearchResultsFragment() {
		super();
		curFiles = new ArrayList<DCFileList>();
		searchResults = new ArrayList<DCMessage>();
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		mainActivity = (MainActivity) getActivity();
		ArrayAdapter<DCFileList> myFileAdapter = new FileListAdapter(
				mainActivity, android.R.layout.simple_list_item_1, curFiles);
		View rootview = inflater.inflate(R.layout.search_results_layout, container, false);
		fileListView = (ListView) rootview.findViewById(R.id.listView);
		titleView = (android.widget.TextView) rootview.findViewById(R.id.txtSearchResultsTitle);
		rootview.findViewById(R.id.btnClearSearchResults).setOnClickListener(v -> {
			clear_search_results();
			Toast.makeText(getActivity(), "Search results cleared", Toast.LENGTH_SHORT).show();
		});
		fileListView.setAdapter(myFileAdapter);
		fileListView
				.setOnItemClickListener(new AdapterView.OnItemClickListener() {
					@Override
					public void onItemClick(AdapterView<?> adapterView,
							View view, int i, long l) {
						
						if (i < 0 || i >= searchResults.size()) return;
						
						DCMessage selected_search_result = searchResults.get(i);
						String path = selected_search_result.file_path;
						long file_size = selected_search_result.file_size;
						String nick = selected_search_result.hisinfo.nick;
						if (mainActivity == null || mainActivity.mService == null || path == null || nick == null) return;

						// The hub this result came from (set by the service); fall back to any connected hub.
						String hubIp = selected_search_result.hubname_s;
						if (hubIp == null || mainActivity.mService.get_status(hubIp) != DCPPService.DCClientStatus.CONNECTED) {
							hubIp = null;
							for (DCPPService.HubSession s : mainActivity.mService.getActiveSessions()) {
								if (s.status == DCPPService.DCClientStatus.CONNECTED) { hubIp = s.hubIp; break; }
							}
						}
						if (hubIp == null) {
							Toast.makeText(getActivity(), "Cannot start download while disconnected.", Toast.LENGTH_SHORT).show();
							return;
						}

						// Folder result: open that user's file list to download the folder from there.
						if (selected_search_result.is_dir_result) {
							Toast.makeText(getActivity(), "Opening " + nick + "'s file list", Toast.LENGTH_SHORT).show();
							mainActivity.open_file_list(hubIp, new DCUserComparable(nick));
							return;
						}

						String[] file_parts = path.split("\\\\");
						String filename = file_parts[file_parts.length - 1];

						// Check if file already exists in downloads to allow reopening
						String localPath = Settings.getDCDownloadsDirectory(getActivity()) + "/" + filename;
						File localFile = new File(localPath);
						if (localFile.exists()) {
							mainActivity.openFile(localFile);
							return;
						}

						ConfirmDownloadFragment cdf = ConfirmDownloadFragment.newInstance(
								hubIp,
								nick,
								localPath,
								path,
								file_size,
								filename
						);
						cdf.show(getFragmentManager(), "ConfirmDownload");
						
				
					}
				});
		this.filesAdapter = myFileAdapter;
		is_ready = true;
		updateTitle();
		return rootview;
	}

	/**
	 * Adds a new search result entry.
	 * This must be called on UI thread.
	 * 
	 * @param sr
	 */
	public void add_search_result(DCMessage sr) {
		if (sr == null || sr.file_path == null || sr.hisinfo == null) return;
		Runnable action = () -> {
			try {
				// The same user answers once per hub we're on: show each result only once.
				if (searchResults != null) {
					for (DCMessage existing : searchResults) {
						if (existing.hisinfo != null && sr.hisinfo.nick != null
								&& sr.hisinfo.nick.equals(existing.hisinfo.nick)
								&& sr.file_path.equals(existing.file_path)) {
							return;
						}
					}
				}
				String[] file_parts = sr.file_path.split("\\\\");
				String name = file_parts.length > 0 ? file_parts[file_parts.length - 1] : sr.file_path;
				if (sr.is_dir_result) name = "[Folder] " + name;
				name = (sr.hisinfo.nick != null ? sr.hisinfo.nick : "Unknown") + ": " + name;

				DCFileList entry = new DCFileList(name, sr.file_size);

				if (curFiles != null) curFiles.add(entry);
				if (searchResults != null) searchResults.add(sr);
				if (is_ready && filesAdapter != null)
					filesAdapter.notifyDataSetChanged();
				updateTitle();
			} catch (Exception e) {
				Log.e("andcpp", "Error adding search result", e);
			}
		};

		if (mainActivity != null) {
			mainActivity.runOnUiThread(action);
		} else if (getActivity() != null) {
			getActivity().runOnUiThread(action);
		}
	}

	/**
	 * Clears all search results
	 * Must call on UI thread
	 */
	public void clear_search_results() {
		Runnable action = () -> {
			try {
				if (curFiles != null) curFiles.clear();
				if (searchResults != null) searchResults.clear();
				if (is_ready && filesAdapter != null)
					filesAdapter.notifyDataSetChanged();
				updateTitle();
			} catch (Exception e) {
				Log.e("andcpp", "Error clearing search results", e);
			}
		};

		if (mainActivity != null) {
			mainActivity.runOnUiThread(action);
		} else if (getActivity() != null) {
			getActivity().runOnUiThread(action);
		}
	}

}
