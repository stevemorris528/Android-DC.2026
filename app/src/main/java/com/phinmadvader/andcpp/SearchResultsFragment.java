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
		View rootview = inflater.inflate(R.layout.file_list, container, false);
		fileListView = (ListView) rootview.findViewById(R.id.listView);
		fileListView.setAdapter(myFileAdapter);
		fileListView
				.setOnItemClickListener(new AdapterView.OnItemClickListener() {
					@Override
					public void onItemClick(AdapterView<?> adapterView,
							View view, int i, long l) {
						
						if (i < 0 || i >= searchResults.size()) return;
						
						// Download File
						DCMessage selected_search_result = searchResults.get(i);
						String path = selected_search_result.file_path;
						long file_size = selected_search_result.file_size;
						String nick = selected_search_result.hisinfo.nick;

						String[] file_parts = path.split("\\\\");
						String filename = file_parts[file_parts.length - 1];

						Log.e("andcpp", path);

						// Check if file already exists in downloads to allow reopening
						String localPath = Settings.getDCDownloadsDirectory(getActivity()) + "/" + filename;
						File localFile = new File(localPath);
						if (localFile.exists()) {
							mainActivity.openFile(localFile);
							return;
						}

						if (mainActivity == null || mainActivity.mService == null || mainActivity.mService.get_status() != DCPPService.DCClientStatus.CONNECTED) {
							Toast.makeText(getActivity(), "Cannot start download while disconnected.", Toast.LENGTH_SHORT).show();
							return;
						}
						
						// Download File
						ConfirmDownloadFragment cdf = ConfirmDownloadFragment.newInstance(
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
		return rootview;
	}

	/**
	 * Adds a new search result entry.
	 * This must be called on UI thread.
	 * 
	 * @param sr
	 */
	public void add_search_result(DCMessage sr) {
		mainActivity.runOnUiThread(() -> {
			String[] file_parts = sr.file_path.split("\\\\");
			
			String name = file_parts[file_parts.length - 1];
			name = sr.hisinfo.nick + ": " + name;

			DCFileList entry = new DCFileList(name, sr.file_size);

			if (curFiles != null) curFiles.add(entry);
			if (searchResults != null) searchResults.add(sr);
			if (is_ready && filesAdapter != null)
				filesAdapter.notifyDataSetChanged();
		});
	}

	/**
	 * Clears all search results
	 * Must call on UI thread
	 */
	public void clear_search_results() {
		mainActivity.runOnUiThread(() -> {
			if (curFiles != null) curFiles.clear();
			if (searchResults != null) searchResults.clear();
			if(is_ready && filesAdapter != null)
				filesAdapter.notifyDataSetChanged();
		});
	}

}
