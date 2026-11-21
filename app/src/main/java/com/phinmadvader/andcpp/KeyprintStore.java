package com.phinmadvader.andcpp;

import android.content.Context;
import android.content.SharedPreferences;

import com.phinvader.libjdcpp.hub.HubAddress;

/**
 * Trusted keyprints of secure hubs (NMDCS / ADCS), per hub. A keyprint given in the hub's address
 * ({@code ?kp=SHA256/...}) always wins; otherwise the first keyprint we see is remembered ("trust on first
 * use") and a later, different one is refused until the user trusts it. Kept in the main preferences, so
 * the settings backup carries them across a reinstall.
 */
public final class KeyprintStore {
    private static final String PREFIX = "hub_kp_";

    private KeyprintStore() {}

    /** The trusted keyprint for this hub, or null if none is known yet. */
    public static String get(Context c, HubAddress hub) {
        String v = prefs(c).getString(PREFIX + hub.prefKey(), null);
        return v == null || v.isEmpty() ? null : v;
    }

    public static void put(Context c, HubAddress hub, String keyprint) {
        if (keyprint == null) return;
        prefs(c).edit().putString(PREFIX + hub.prefKey(), keyprint).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
