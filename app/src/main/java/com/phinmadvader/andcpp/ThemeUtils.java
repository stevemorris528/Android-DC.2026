package com.phinmadvader.andcpp;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

public class ThemeUtils {
    public static final String PREF_DARK_MODE = "pref_dark_mode";
    public static final String PREF_COLOR_ACCENT = "pref_color_accent";

    public static final String COLOR_TEAL = "teal";
    public static final String COLOR_BLUE = "blue";
    public static final String COLOR_ORANGE = "orange";
    public static final String COLOR_PURPLE = "purple";
    public static final String COLOR_GREEN = "green";

    public static boolean isDarkMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, 0);
        return prefs.getBoolean(PREF_DARK_MODE, true);
    }

    public static void setDarkMode(Context context, boolean darkMode) {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, 0);
        prefs.edit().putBoolean(PREF_DARK_MODE, darkMode).apply();
    }

    public static String getColorAccent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, 0);
        return prefs.getString(PREF_COLOR_ACCENT, COLOR_GREEN);
    }

    public static void setColorAccent(Context context, String color) {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, 0);
        prefs.edit().putString(PREF_COLOR_ACCENT, color).apply();
    }

    /**
     * The colour progress bars are drawn in (the theme's accent). Used for download speeds/totals so
     * they always match the download progress bars. Before Android 5 bars aren't accent-tinted: teal.
     */
    public static int getAccentColor(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true)) {
                if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                        && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                    return tv.data;
                }
                if (tv.resourceId != 0) {
                    return context.getResources().getColor(tv.resourceId);
                }
            }
        }
        return 0xFF00796B;
    }

    public static void applyTheme(Activity activity) {
        boolean dark = isDarkMode(activity);
        String accent = getColorAccent(activity);

        int themeRes;
        if (dark) {
            switch (accent) {
                case COLOR_BLUE:
                    themeRes = R.style.AppThemeDark_Blue;
                    break;
                case COLOR_ORANGE:
                    themeRes = R.style.AppThemeDark_Orange;
                    break;
                case COLOR_PURPLE:
                    themeRes = R.style.AppThemeDark_Purple;
                    break;
                case COLOR_GREEN:
                    themeRes = R.style.AppThemeDark_Green;
                    break;
                case COLOR_TEAL:
                default:
                    themeRes = R.style.AppThemeDark;
                    break;
            }
        } else {
            switch (accent) {
                case COLOR_BLUE:
                    themeRes = R.style.AppTheme_Blue;
                    break;
                case COLOR_ORANGE:
                    themeRes = R.style.AppTheme_Orange;
                    break;
                case COLOR_PURPLE:
                    themeRes = R.style.AppTheme_Purple;
                    break;
                case COLOR_GREEN:
                    themeRes = R.style.AppTheme_Green;
                    break;
                case COLOR_TEAL:
                default:
                    themeRes = R.style.AppTheme;
                    break;
            }
        }
        activity.setTheme(themeRes);
    }

    /** Blue used for the connection-mode details when active mode is working. */
    public static final int COLOR_MODE_ACTIVE = 0xFF2196F3;

    /**
     * Connection mode text, e.g. "Active (PIA port forwarding, port 47389)": when active mode is working, the
     * details inside the brackets are shown in blue.
     */
    public static CharSequence formatConnectionMode(String prefix, String description, boolean active) {
        String text = prefix + description;
        android.text.SpannableString s = new android.text.SpannableString(text);
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (active && open >= 0 && close > open + 1) {
            s.setSpan(new android.text.style.ForegroundColorSpan(COLOR_MODE_ACTIVE), open + 1, close,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }
}
