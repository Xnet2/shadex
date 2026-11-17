package com.shadex.app;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private Prefs() {}

    static final String MODE = "mode";            // split | cc | nc
    static final String CC_LEFT = "cc_left";
    static final String CC_ZONE = "cc_zone";      // percent of the top edge that opens Control Center
    static final String STRIP_DP = "strip_dp";    // 0 = status bar height
    static final String HAPTIC = "haptic";
    static final String BLUR = "blur";
    static final String HIDE_LANDSCAPE = "hide_landscape";
    static final String SHOW_ZONE = "show_zone";
    static final String THEME = "theme";          // light | dark | system
    static final String DOCK = "dock";            // package names, comma separated
    static final String HOME_LABELS = "home_labels";
    static final String HOME_COLS = "home_cols";          // 4 | 5
    static final String HOME_ICON = "home_icon";          // 0 small, 1 medium, 2 large
    static final String HOME_HIDDEN = "home_hidden";      // package names, comma separated
    static final String HOME_MIDDLE = "home_middle";      // what a swipe down mid-screen does: panel | search
    static final String HOME_DOCK_BG = "home_dock_bg";
    static final String STYLE = "style";          // ios | android | xiaomi

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("prefs", Context.MODE_PRIVATE);
    }

    static String mode(Context c) { return get(c).getString(MODE, "split"); }
    static boolean ccLeft(Context c) { return get(c).getBoolean(CC_LEFT, false); }
    static int ccZone(Context c) { return get(c).getInt(CC_ZONE, 40); }
    static int stripDp(Context c) { return get(c).getInt(STRIP_DP, 0); }
    static boolean haptic(Context c) { return get(c).getBoolean(HAPTIC, true); }
    static boolean blur(Context c) { return get(c).getBoolean(BLUR, true); }
    static boolean hideLandscape(Context c) { return get(c).getBoolean(HIDE_LANDSCAPE, true); }
    static boolean showZone(Context c) { return get(c).getBoolean(SHOW_ZONE, false); }
    static String theme(Context c) { return get(c).getString(THEME, "light"); }
    static boolean homeLabels(Context c) { return get(c).getBoolean(HOME_LABELS, true); }
    static int homeCols(Context c) { return get(c).getInt(HOME_COLS, 4); }
    static int homeIcon(Context c) { return get(c).getInt(HOME_ICON, 1); }
    static String homeHidden(Context c) { return get(c).getString(HOME_HIDDEN, ""); }
    /** "search" or "panel"; unset means the style's own (iOS search, others the panel). */
    static String homeMiddle(Context c) {
        String v = get(c).getString(HOME_MIDDLE, null);
        return v != null ? v : Look.IOS.equals(style(c)) ? "search" : "panel";
    }
    static boolean homeDockBg(Context c) { return get(c).getBoolean(HOME_DOCK_BG, true); }
    static String style(Context c) { return get(c).getString(STYLE, "ios"); }
}
