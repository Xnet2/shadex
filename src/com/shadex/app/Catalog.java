package com.shadex.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The buttons. An id is a built-in name or "app:<package>". Each style has its own list, edited
 * in the app; a style's fixed buttons (e.g. iOS connectivity) are never in its list.
 */
final class Catalog {
    private Catalog() {}

    /** Each style keeps its own list (iOS also reads the list from before styles existed). */
    static String key(Context c) {
        return "tiles_" + Prefs.style(c);
    }

    static String defaults(String style) {
        switch (style) {
            case Look.ANDROID:
                return "wifi,bt,dnd,flashlight,rotation,airplane,location,cell,hotspot,screenshot";
            case Look.XIAOMI:
                return "bt,airplane,dnd,flashlight,rotation,screenshot,location,hotspot,calculator,camera,lock";
            default:
                return "rotation,screenshot,flashlight,timer,calculator,camera,hotspot,location,lock,system";
        }
    }

    static final String[] BUILT_IN = {
            "wifi", "bt", "cell", "airplane", "dnd",
            "rotation", "screenshot", "flashlight", "timer", "alarm", "calculator",
            "camera", "qr", "hotspot", "location", "nfc", "cast", "darkmode", "battery", "lock",
            "settings", "system"};

    /** Buttons a style always shows in its own fixed place, so they are not in the list. */
    static boolean fixed(String style, String id) {
        switch (style) {
            case Look.ANDROID:
                return false;
            case Look.XIAOMI:
                return id.equals("wifi") || id.equals("cell");
            default:
                return id.equals("wifi") || id.equals("bt") || id.equals("cell")
                        || id.equals("airplane") || id.equals("dnd");
        }
    }

    /** The current style's list, exactly as the user arranged it. */
    static List<String> load(Context c) {
        String style = Prefs.style(c);
        android.content.SharedPreferences sp = Prefs.get(c);
        String s = sp.getString(key(c), null);
        if (s == null && Look.IOS.equals(style)) s = sp.getString("tiles", null);
        if (s == null) s = defaults(style);
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(s)) return out;
        for (String id : s.split(",")) {
            // Skip buttons that no longer exist (e.g. from an older version).
            boolean known = isApp(id) || java.util.Arrays.asList(BUILT_IN).contains(id);
            if (known && !fixed(style, id) && !out.contains(id)) out.add(id);
        }
        return out;
    }

    static void save(Context c, List<String> ids) {
        Prefs.get(c).edit().putString(key(c), TextUtils.join(",", ids)).apply();
    }

    static void reset(Context c) {
        Prefs.get(c).edit().remove(key(c)).apply();
    }

    static boolean isApp(String id) {
        return id.startsWith("app:");
    }

    static String pkg(String id) {
        return id.substring(4);
    }

    static CharSequence label(Context c, String id) {
        if (isApp(id)) {
            PackageManager pm = c.getPackageManager();
            try {
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg(id), 0));
            } catch (Exception e) {
                return pkg(id);
            }
        }
        switch (id) {
            case "wifi": return "Wi-Fi";
            case "bt": return "Bluetooth";
            case "cell": return "נתונים ניידים";
            case "airplane": return "מצב טיסה";
            case "dnd": return "נא לא להפריע";
            case "rotation": return "נעילת סיבוב מסך";
            case "screenshot": return "צילום מסך";
            case "flashlight": return "פנס";
            case "timer": return "טיימר";
            case "alarm": return "שעון מעורר";
            case "calculator": return "מחשבון";
            case "camera": return "מצלמה";
            case "qr": return "סורק QR";
            case "hotspot": return "נקודה חמה";
            case "location": return "מיקום";
            case "nfc": return "NFC";
            case "cast": return "שיקוף מסך";
            case "darkmode": return "מצב כהה";
            case "battery": return "חיסכון בסוללה";
            case "lock": return "נעילת מסך";
            case "settings": return "הגדרות";
            case "system": return "החלונית המקורית";
        }
        return id;
    }

    static int icon(String id) {
        switch (id) {
            case "wifi": return R.drawable.ic_wifi;
            case "bt": return R.drawable.ic_bt;
            case "cell": return R.drawable.ic_cell;
            case "airplane": return R.drawable.ic_airplane;
            case "dnd": return R.drawable.ic_moon;
            case "rotation": return R.drawable.ic_rotation;
            case "screenshot": return R.drawable.ic_screenshot;
            case "flashlight": return R.drawable.ic_flash;
            case "timer": return R.drawable.ic_timer;
            case "alarm": return R.drawable.ic_alarm;
            case "calculator": return R.drawable.ic_calc;
            case "camera": return R.drawable.ic_camera;
            case "qr": return R.drawable.ic_qr;
            case "hotspot": return R.drawable.ic_hotspot;
            case "location": return R.drawable.ic_location;
            case "nfc": return R.drawable.ic_nfc;
            case "cast": return R.drawable.ic_cast;
            case "darkmode": return R.drawable.ic_darkmode;
            case "battery": return R.drawable.ic_leaf;
            case "lock": return R.drawable.ic_lock;
            case "settings": return R.drawable.ic_cog;
            case "system": return R.drawable.ic_grid;
        }
        return R.drawable.ic_apps;
    }

    /** Buttons that are on or off (the rest just do something). */
    static boolean stateful(String id) {
        switch (id) {
            case "wifi":
            case "bt":
            case "cell":
            case "airplane":
            case "dnd":
            case "rotation":
            case "location":
            case "flashlight":
                return true;
        }
        return false;
    }
}
