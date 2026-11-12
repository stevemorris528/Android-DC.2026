package com.phinmadvader.andcpp;

import java.util.Timer;
import java.util.TimerTask;

import android.util.Log;

import com.phinmadvader.andcpp.DCPPService.DCClientStatus;

/**
 * Created by invader on 15/9/13.
 * 
 * This class does polling activities
 */
public class Poller {
	MainActivity mainActivity;
	private TimerTask task;
	private Timer mTimer;
	Poller(MainActivity mainActivity) {
		this.mainActivity = mainActivity;
		mTimer = new Timer();
		task = new TimerTask() {
			@Override
			public void run() {
				poll();
			}
		};
		mTimer.scheduleAtFixedRate(task, 0, Constants.POLLING_CLASS_TIME_IN_MILLIS);
	}
	
	public void stop() {
		mTimer.cancel();
	}

	// Monitor Service State
	private DCClientStatus currentStatus = DCClientStatus.DISCONNECTED;

	void poll() {
		if (mainActivity == null) return;
		if (mainActivity.mService == null) return;

		mainActivity.runOnUiThread(() -> {
			if (mainActivity.get_hublist_fragment() != null) {
				mainActivity.get_hublist_fragment().refresh();
			}
		});
	}
}
