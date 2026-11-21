package com.phinmadvader.andcpp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.phinvader.libjdcpp.hub.HubAddress;

import java.util.HashSet;
import java.util.Set;

/**
 * Which saved hubs connect automatically when the app starts, chosen per hub (a checkbox on each saved hub).
 * Replaces the old single "auto-reconnect saved hubs" setting. Kept in the main preferences, so the settings
 * backup carries it across a reinstall.
 */
final class HubAutoConnect {
    private static final String KEY_HUBS = "autoconnect_hubs";
    private static final String KEY_MIGRATED = "autoconnect_per_hub_v1";

    private HubAutoConnect() {}

    /** Same hub, same key: protocol + host + port (no keyprint), as HubAddress.prefKey(). */
    private static String key(String hubId) {
        HubAddress a = HubAddress.tryParse(hubId);
        return a != null ? a.prefKey().toLowerCase(java.util.Locale.ROOT) : hubId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
    }

    static boolean isOn(Context c, String hubId) {
        if (hubId == null) return false;
        return prefs(c).getStringSet(KEY_HUBS, new HashSet<String>()).contains(key(hubId));
    }

    static void set(Context c, String hubId, boolean on) {
        if (hubId == null) return;
        SharedPreferences p = prefs(c);
        Set<String> s = new HashSet<>(p.getStringSet(KEY_HUBS, new HashSet<String>()));
        boolean changed = on ? s.add(key(hubId)) : s.remove(key(hubId));
        if (changed) {
            p.edit().putStringSet(KEY_HUBS, s).apply();
            Log.i("andcpp", "Auto-connect " + (on ? "on" : "off") + " for " + hubId);
        }
    }

    /**
     * Once: saved hubs take over the old global setting (all on if it was on, all off if it was off), so
     * nothing changes until the user unticks a hub. Call after any settings-backup restore.
     */
    static void migrateIfNeeded(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean(KEY_MIGRATED, false)) return;
        Set<String> s = new HashSet<>(p.getStringSet(KEY_HUBS, new HashSet<String>()));
        boolean oldGlobal = Settings.getAutoReconnectHubs(c);
        if (oldGlobal) {
            for (String hub : p.getStringSet("favorite_hubs", new HashSet<String>())) s.add(key(hub));
        }
        p.edit().putStringSet(KEY_HUBS, s).putBoolean(KEY_MIGRATED, true).apply();
        Log.i("andcpp", "Auto-connect is now per hub; saved hubs start " + (oldGlobal ? "on" : "off")
                + " (the old setting)");
    }
}
