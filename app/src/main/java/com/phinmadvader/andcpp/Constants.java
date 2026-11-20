package com.phinmadvader.andcpp;

import android.os.Environment;

/**
 * Created by invader on 8/11/13.
 */
public class Constants {
    public static String dcDirectory = "/sdcard/Android-DC++"; // Fallback, better to use Context.getExternalFilesDir
    public static String dcConfDirectory = ".androiddcpp";
    public static String dcFileListDirectory = "filelists";
    public static String PREFS_NAME = "ANDROIDDCPPSETTINGS";
    public static String SETTINGS_IP_KEY = "dcipaddr";
    public static String DEFAULT_FAVORITE_HUB = "bigchill.servemp3.com:1069";
    public static String SETTINGS_NICK_KEY = "dcnick";
    public static String SETTINGS_ACTIVE_KEY = "dcactive";
    public static String SETTINGS_PORT_KEY = "dcport";
    public static String SETTINGS_UPLOAD_SLOTS_KEY = "dcuploadslots";
    public static String SETTINGS_AUTO_RECONNECT_HUBS_KEY = "dcautoreconnecthubs";
    public static String SETTINGS_WIFI_ONLY_DOWNLOADS_KEY = "dcwifionlydownloads";    public static int DEFAULT_PORT = 4111;
    public static int DEFAULT_UPLOAD_SLOTS = 2;
    public static int DEFAULT_DOWNLOAD_WORKERS = 3;
    public static String SETTINGS_DOWNLOAD_WORKERS_KEY = "dc_download_workers";
    public static int MAX_DOWNLOAD_Q = 1024;
    public static int DOWNLOAD_TIMEOUT_MILLIS = 60000;
    public static int DOWNLOAD_CONNECT_DEADLINE_MILLIS = 90000; // 90 seconds connect deadline
    public static int DOWNLOAD_HANDSHAKE_DEADLINE_MILLIS = 60000; // max time a claimed connection may sit in INITIATED/STARTED
    public static int DOWNLOAD_RESIGNAL_INTERVAL_MILLIS = 30000; // re-send $ConnectToMe/$RevConnectToMe while still pending
    public static int DOWNLOAD_UPDATE_INTERVAL_MILLIS = 1000; // Updates all download activities every 1s
    public static int POLLING_CLASS_TIME_IN_MILLIS = 5000; // Interval frequency for poll updates
}
