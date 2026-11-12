package com.phinmadvader.andcpp;

import android.os.Environment;

/**
 * Created by invader on 8/11/13.
 */
public class Constants {
    public static String dcDirectory = "/sdcard/DC++"; // Fallback, better to use Context.getExternalFilesDir
    public static String dcConfDirectory = ".androiddcpp";
    public static String dcFileListDirectory = "filelists";
    public static String PREFS_NAME = "ANDROIDDCPPSETTINGS";
    public static String SETTINGS_IP_KEY = "dcipaddr";
    public static String SETTINGS_NICK_KEY = "dcnick";
    public static String SETTINGS_ACTIVE_KEY = "dcactive";
    public static String SETTINGS_PORT_KEY = "dcport";
    public static String SETTINGS_UPLOAD_SLOTS_KEY = "dcuploadslots";
    public static String SETTINGS_AUTO_RECONNECT_HUBS_KEY = "dcautoreconnecthubs";
    public static int DEFAULT_PORT = 4111;
    public static int DEFAULT_UPLOAD_SLOTS = 3;
    public static int MAX_DOWNLOAD_Q = 1024;
    public static int DOWNLOAD_TIMEOUT_MILLIS = 60000;
    public static int DOWNLOAD_UPDATE_INTERVAL_MILLIS = 1000; // Updates all download activities every 1s
    public static int POLLING_CLASS_TIME_IN_MILLIS = 5000; // Interval frequency for poll updates
}
