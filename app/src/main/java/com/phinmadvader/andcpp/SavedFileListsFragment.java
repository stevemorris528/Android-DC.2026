package com.phinmadvader.andcpp;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class SavedFileListsFragment extends Fragment {
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private List<String> savedNicks;
    private MainActivity mainActivity;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mainActivity = (MainActivity) getActivity();
        View root = inflater.inflate(R.layout.file_list, container, false);
        listView = (ListView) root.findViewById(R.id.listView);
        savedNicks = new ArrayList<>();
        adapter = new ArrayAdapter<>(getActivity(), android.R.layout.simple_list_item_1, savedNicks);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final String nick = savedNicks.get(position);
                if (mainActivity != null) {
                    mainActivity.open_cached_file_list(nick);
                }
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final String nick = savedNicks.get(position);
                new android.app.AlertDialog.Builder(getActivity())
                        .setTitle("Delete Saved List")
                        .setMessage("Are you sure you want to delete the saved file list for " + nick + "?")
                        .setPositiveButton("Delete", (dialog, which) -> {
                            if (mainActivity != null) {
                                java.io.File fileListDir = new java.io.File(Settings.getDCDownloadsDirectory(mainActivity), "FileLists");
                                java.io.File saved_file_list = new java.io.File(fileListDir, nick + ".xml");
                                if (saved_file_list.exists() && saved_file_list.delete()) {
                                    Toast.makeText(getActivity(), "Deleted " + nick, Toast.LENGTH_SHORT).show();
                                    refreshList();
                                } else {
                                    Toast.makeText(getActivity(), "Failed to delete " + nick, Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            }
        });

        refreshList();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshList();
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (isVisibleToUser) {
            refreshList();
        }
    }

    public void refreshList() {
        if (mainActivity != null) {
            savedNicks.clear();
            
            java.io.File fileListDir = new java.io.File(Settings.getDCDownloadsDirectory(mainActivity), "FileLists");
            java.io.File[] files = fileListDir.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    if (f.isFile() && f.getName().endsWith(".xml")) {
                        String name = f.getName().substring(0, f.getName().length() - 4);
                        if (UploadManager.isLegacyFileListName(name)) continue; // old-build leftover
                        savedNicks.add(name);
                    }
                }
            }
            java.util.Collections.sort(savedNicks);

            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
    }
}
