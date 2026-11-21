package com.phinmadvader.andcpp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.phinvader.libjdcpp.adc.AdcIdentity;

/**
 * Keeps our ADC identity (PID, and the CID derived from it) in the main SharedPreferences, so
 * {@link SettingsBackup} carries it across a clean reinstall (the PID is scrambled in the backup, like hub
 * passwords). Always read through {@link #get(Context)} rather than caching it: after a reinstall the backup
 * may be restored after the service started, and the restored PID must win.
 */
public final class AdcIdentityStore {
    /** Base32 PID. Secret: only ever sent to hubs in our own BINF. */
    static final String KEY_PID = "adc_pid";
    /** Base32 CID, kept next to the PID for readability only; always recomputed from the PID. */
    static final String KEY_CID = "cid";

    private AdcIdentityStore() {}

    public static synchronized AdcIdentity get(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String pid = prefs.getString(KEY_PID, null);
        if (pid != null) {
            try {
                AdcIdentity id = AdcIdentity.fromPid(pid);
                if (!id.cid.equals(prefs.getString(KEY_CID, null))) {
                    prefs.edit().putString(KEY_CID, id.cid).apply(); // e.g. old random-UUID "cid" value
                }
                return id;
            } catch (IllegalArgumentException e) {
                Log.w("andcpp", "[ADC] Stored PID is invalid; generating a new identity");
            }
        }
        AdcIdentity id = AdcIdentity.generate();
        // commit(), not apply(): the identity must be on disk before anything is shared with it.
        prefs.edit().putString(KEY_PID, id.pid).putString(KEY_CID, id.cid).commit();
        Log.i("andcpp", "[ADC] Generated new client identity, CID " + id.cid);
        return id;
    }

    /** Our public CID (39-char Base32). */
    public static String cid(Context context) {
        return get(context).cid;
    }
}
