package com.phinmadvader.andcpp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WelcomeFragment extends Fragment {
    private TextView statusText;
    private Button btnGoToHubs;
    private Button btnConnectNow;
    private MainActivity mainActivity;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mainActivity = (MainActivity) getActivity();
        View view = inflater.inflate(R.layout.welcome_layout, container, false);

        statusText = view.findViewById(R.id.welcome_status_text);
        btnGoToHubs = view.findViewById(R.id.btn_go_to_hubs);
        btnConnectNow = view.findViewById(R.id.btn_connect_now);

        btnGoToHubs.setOnClickListener(v -> {
            if (mainActivity.tab_page_adapter != null) {
                int pos = mainActivity.tab_page_adapter.get_tab_position(TabPagerAdapter.TAB_HUBLIST);
                if (pos != -1) {
                    mainActivity.get_view_pager().setCurrentItem(pos);
                }
            }
        });

        btnConnectNow.setOnClickListener(v -> {
            if (mainActivity.tab_page_adapter != null) {
                int pos = mainActivity.tab_page_adapter.get_tab_position(TabPagerAdapter.TAB_LOGININFO);
                if (pos != -1) {
                    mainActivity.get_view_pager().setCurrentItem(pos);
                }
            }
        });

        refreshStatus();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStatus();
    }

    public void refreshStatus() {
        if (mainActivity == null || statusText == null) return;

        int activeHubs = 0;
        String firstHubName = "";
        if (mainActivity.mService != null) {
            List<DCPPService.HubSession> sessions = mainActivity.mService.getActiveSessions();
            for (DCPPService.HubSession s : sessions) {
                if (s.status == DCPPService.DCClientStatus.CONNECTED) {
                    activeHubs++;
                    if (firstHubName.isEmpty()) firstHubName = s.hubIp;
                }
            }
        }

        SharedPreferences prefs = mainActivity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> favorites = prefs.getStringSet("favorite_hubs", new HashSet<String>());

        if (activeHubs > 0) {
            statusText.setText("Connected to " + (activeHubs == 1 ? firstHubName : activeHubs + " hubs"));
            btnGoToHubs.setVisibility(View.VISIBLE);
            btnGoToHubs.setText("Open " + (activeHubs == 1 ? firstHubName : "Hubs"));
            btnConnectNow.setText("Connect to another Hub");
        } else if (!favorites.isEmpty()) {
            statusText.setText("You have " + favorites.size() + " saved hubs.");
            btnGoToHubs.setVisibility(View.VISIBLE);
            btnGoToHubs.setText("View Saved Hubs");
            btnConnectNow.setText("Connect to new Hub");
        } else {
            statusText.setText("No hubs added yet.");
            btnGoToHubs.setVisibility(View.GONE);
            btnConnectNow.setText("Add and Connect to a Hub");
        }
    }
}
