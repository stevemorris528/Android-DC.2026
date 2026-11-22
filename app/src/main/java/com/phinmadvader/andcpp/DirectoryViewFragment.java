package com.phinmadvader.andcpp;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
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

public class DirectoryViewFragment extends Fragment {
	private MainActivity mainActivity;
	private FileListManager filelist_manager; // has all depth info
	public boolean is_ready = false;

	private ListView fileListView;
	private ArrayAdapter<DCFileList> filesAdapter; // adapter adapted on
													// curFiles
	private List<DCFileList> curFiles; // adapter list being displayed
	private List<DCFileList> fileList; // reference to file list, same items as
										// curFiles
	private int depth = 0; // current depth at FileList
	
	public final static String NUM_ARG_KEY = "dirfrag_num_arg";


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        depth = getArguments() != null ? getArguments().getInt(NUM_ARG_KEY) : 0;
    }

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		mainActivity = (MainActivity) getActivity();
		filelist_manager = mainActivity.get_filelist_manager();

		curFiles = new ArrayList<DCFileList>();
		filesAdapter = new FileListAdapter(mainActivity,
				android.R.layout.simple_list_item_1, curFiles);
		View rootview = inflater.inflate(R.layout.file_list, container, false);
		fileListView = (ListView) rootview.findViewById(R.id.listView);
		fileListView.setAdapter(filesAdapter);
		
		fileListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
			@Override
			public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
				if (fileList == null || position < 0 || position >= fileList.size()) return false;
				final DCFileList selectedFile = fileList.get(position);
				// Work out the path and owner now: if another file list finishes loading while the dialog is
				// open, the shared folder stack and chosenNick change under us (that crashed the app).
				final String nickAtPress = mainActivity.chosenNick;
				StringBuilder dirPath = new StringBuilder();
				java.util.List<DCFileList> stack = filelist_manager.filelist_stack;
				for (int j = 1; j <= depth && j < stack.size(); j++) dirPath.append(stack.get(j).name).append('\\');
				final String remotePathAtPress = dirPath + selectedFile.name;

				if (selectedFile.isDirectory()) {
					new android.app.AlertDialog.Builder(getActivity())
							.setTitle("Download Folder")
							.setMessage("Do you want to download the entire folder '" + selectedFile.name + "' and all its contents?")
							.setPositiveButton("Download", (dialog, which) -> {
								if (mainActivity.mService != null && mainActivity.mService.canDownloadFrom(nickAtPress)) {
									String remotePath = remotePathAtPress;

									String localPath = Settings.getDCDownloadsDirectory(getActivity()) + "/" + selectedFile.name;
									
									// Folder Download with statistics
									String groupLabel = "folder_" + System.currentTimeMillis();
									int[] stats = {0};
									long[] sizeStats = {0};
									countFolderStats(selectedFile, stats, sizeStats);
									
									if (mainActivity.mService != null) {
										mainActivity.mService.startFolderDownload(groupLabel, selectedFile.name, stats[0], sizeStats[0]);
									}
									
									downloadDirectoryRecursive(nickAtPress, localPath, remotePath, selectedFile, groupLabel, selectedFile.name);
									Toast.makeText(getActivity(), "Started folder download: " + selectedFile.name + " (" + stats[0] + " files)", Toast.LENGTH_SHORT).show();
								} else {
									Toast.makeText(getActivity(), "Cannot start download while disconnected.", Toast.LENGTH_SHORT).show();
								}
							})
							.setNegativeButton("Cancel", null)
							.show();
					return true;
				}

				// Individual file: same kind of box as a folder.
				DCPPService.FileSize fs = new DCPPService.FileSize(selectedFile.size);
				final String localPath = Settings.getDCDownloadsDirectory(getActivity()) + "/" + selectedFile.name;
				new android.app.AlertDialog.Builder(getActivity())
						.setTitle("Download File")
						.setMessage(String.format(java.util.Locale.US, "Do you want to download the file '%s' (%.2f %s)?",
								selectedFile.name, fs.fileSize, fs.unit))
						.setPositiveButton("Download", (dialog, which) -> {
							if (mainActivity.mService != null && mainActivity.mService.canDownloadFrom(nickAtPress)) {
								mainActivity.mService.download_file(nickAtPress, localPath, remotePathAtPress, selectedFile.size);
								Toast.makeText(getActivity(), "Started download: " + selectedFile.name, Toast.LENGTH_SHORT).show();
							} else {
								Toast.makeText(getActivity(), "Cannot start download while disconnected.", Toast.LENGTH_SHORT).show();
							}
						})
						.setNegativeButton("Cancel", null)
						.show();
				return true;
			}
		});

		fileListView
				.setOnItemClickListener(new AdapterView.OnItemClickListener() {
					@Override
					public void onItemClick(AdapterView<?> adapterView,
							View view, int i, long l) {
						if (fileList == null || i < 0 || i >= fileList.size()) return;
						DCFileList selectedFile = fileList.get(i);
						if (selectedFile.isDirectory()) {
							filelist_manager.openDirectory(depth,
									selectedFile);
						} else {
							// Check if file already exists in downloads to allow reopening
							String localPath = Settings.getDCDownloadsDirectory(getActivity()) + "/" + selectedFile.name;
							File localFile = new File(localPath);
							// Open it only if it's complete; a partial file is resumed by downloading again.
							if (localFile.isFile() && (selectedFile.size <= 0 || localFile.length() == selectedFile.size)) {
								mainActivity.openFile(localFile);
								return;
							}

							if (mainActivity == null || mainActivity.mService == null || !mainActivity.mService.canDownloadFrom(mainActivity.chosenNick)) {
								Toast.makeText(getActivity(), "Cannot start download while disconnected.", Toast.LENGTH_SHORT).show();
								return;
							}
							
							// Download File
							String path = "";
							for (int j = 1; j <= depth && j < filelist_manager.filelist_stack.size(); j++)
								path += filelist_manager.filelist_stack.get(j).name + "\\";
							path +=  selectedFile.name;
							Log.e("andcpp", path);

							ConfirmDownloadFragment cdf = ConfirmDownloadFragment.newInstance(
									mainActivity.chosenNick,
									Settings.getDCDownloadsDirectory(getActivity()) + "/" + selectedFile.name,
									path,
									selectedFile.size,
									selectedFile.name
							);
							cdf.show(getFragmentManager(), "ConfirmDownload");
						}
					}
				});

		curFiles.clear();
		if (filelist_manager != null && filelist_manager.filelist_stack != null 
				&& depth < filelist_manager.filelist_stack.size()) {
			DCFileList currentDir = filelist_manager.filelist_stack.get(depth);
			if (currentDir != null && currentDir.children != null) {
				fileList = new ArrayList<>(currentDir.children);
				Collections.sort(fileList);
				curFiles.addAll(fileList);
			}
		}
		if (filesAdapter != null) {
			filesAdapter.notifyDataSetChanged();
		}

		is_ready = true;
		return rootview;
	}

	private void countFolderStats(DCFileList dir, int[] count, long[] size) {
		if (dir.children != null) {
			for (DCFileList child : dir.children) {
				if (child.isDirectory()) {
					countFolderStats(child, count, size);
				} else {
					count[0]++;
					size[0] += child.size;
				}
			}
		}
	}

	private void downloadDirectoryRecursive(String nick, String localPath, String remotePath, DCFileList dir, String groupLabel, String groupName) {
		File localDir = new File(localPath);
		if (!localDir.exists()) {
			localDir.mkdirs();
		}

		if (dir.children != null) {
			for (DCFileList child : dir.children) {
				String childRemotePath = remotePath + "\\" + child.name;
				String childLocalPath = localPath + "/" + child.name;
				if (child.isDirectory()) {
					downloadDirectoryRecursive(nick, childLocalPath, childRemotePath, child, groupLabel, groupName);
				} else {
					if (mainActivity.mService != null) {
						mainActivity.mService.download_file(nick, childLocalPath, childRemotePath, child.size, groupLabel, groupName);
					}
				}
			}
		}
	}

    static DirectoryViewFragment newInstance(int num) {
        DirectoryViewFragment f = new DirectoryViewFragment();

        Bundle args = new Bundle();
        args.putInt(NUM_ARG_KEY, num);
        f.setArguments(args);

        return f;
    }

}