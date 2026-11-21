package com.phinmadvader.andcpp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.phinvader.libjdcpp.tls.SelfSignedCertificate;

import java.security.SecureRandom;

/**
 * Our own TLS certificate (RSA-2048, self-signed, CN = our ADC CID), used for secure hubs and, later, encrypted
 * transfers. Its keyprint is what other clients will know us by, so it must survive a reinstall: it is kept in
 * the main SharedPreferences, which {@link SettingsBackup} copies (the private key scrambled, like passwords).
 * Generating it takes a moment on old phones: never call {@link #get(Context)} for the first time on the UI
 * thread (use {@link #peekKeyprint(Context)} there).
 */
public final class ClientCertificateStore {
    /** Base64 PKCS#8 private key. Secret. */
    static final String KEY_PRIVATE_KEY = "tls_key";
    /** Base64 DER certificate. */
    static final String KEY_CERTIFICATE = "tls_cert";
    /** Our keyprint, cached for the UI. */
    static final String KEY_KEYPRINT = "tls_kp";

    private static SelfSignedCertificate cached;
    private static String cachedFrom; // the stored certificate the cache was built from

    private ClientCertificateStore() {}

    public static synchronized SelfSignedCertificate get(Context context) {
        SharedPreferences prefs = prefs(context);
        String key = prefs.getString(KEY_PRIVATE_KEY, null);
        String cert = prefs.getString(KEY_CERTIFICATE, null);
        if (key != null && cert != null) {
            // Re-read when the stored one changed (e.g. restored from the settings backup after a reinstall).
            if (cached != null && cert.equals(cachedFrom)) return cached;
            try {
                cached = SelfSignedCertificate.load(Base64.decode(key, Base64.NO_WRAP), Base64.decode(cert, Base64.NO_WRAP));
                cachedFrom = cert;
                if (!cached.keyprint().equals(prefs.getString(KEY_KEYPRINT, null))) {
                    prefs.edit().putString(KEY_KEYPRINT, cached.keyprint()).apply();
                }
                return cached;
            } catch (Exception e) {
                Log.w("andcpp", "[ADCS] Stored client certificate is unusable; making a new one", e);
            }
        }
        try {
            long t0 = System.currentTimeMillis();
            SelfSignedCertificate made = SelfSignedCertificate.generate(
                    AdcIdentityStore.cid(context), 20, new SecureRandom());
            String certB64 = Base64.encodeToString(made.encodedCertificate(), Base64.NO_WRAP);
            prefs.edit()
                    .putString(KEY_PRIVATE_KEY, Base64.encodeToString(made.encodedKey(), Base64.NO_WRAP))
                    .putString(KEY_CERTIFICATE, certB64)
                    .putString(KEY_KEYPRINT, made.keyprint())
                    .commit();
            cached = made;
            cachedFrom = certB64;
            Log.i("andcpp", "[ADCS] Generated client certificate in " + (System.currentTimeMillis() - t0)
                    + " ms, keyprint " + made.keyprint());
            return made;
        } catch (Exception e) {
            Log.e("andcpp", "[ADCS] Could not generate a client certificate", e);
            return null;
        }
    }

    /** Our keyprint if the certificate exists already, else null. Cheap: safe on the UI thread. */
    public static String peekKeyprint(Context context) {
        return prefs(context).getString(KEY_KEYPRINT, null);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
