package com.shadex.app;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

/**
 * Colour palette for the panel: a style (iOS, Android, Xiaomi) in light or dark.
 * iOS is monochrome glass; Android uses the phone's Material You colours; Xiaomi is HyperOS
 * white glass with blue for whatever is switched on.
 */
final class Look {
    static final String IOS = "ios", ANDROID = "android", XIAOMI = "xiaomi";

    final String style;
    final boolean dark;
    /** sheet: tint over the blurred screen; sheetSolid: used when no blurred image is available. */
    int sheet, sheetSolid;
    /** module: button/platter background when off; stroke: its hairline edge (0 = none). */
    int module, stroke, icon;
    /** circleOff: round button when off. */
    int circleOff, circleIcon;
    /** activeBg / activeIcon: any button that is switched on (text on it uses activeIcon too). */
    int activeBg, activeIcon;
    int text, sub;
    int track, fill, iconOnFill, iconOnTrack;
    int card, cardText, cardSub, pill;
    int handle, dotOn, dotOff, roundBtn, roundBtnIcon;
    final float moduleElevation = 0;

    boolean is(String s) {
        return style.equals(s);
    }

    private Look(Context c, String style, boolean dark) {
        this.style = style;
        this.dark = dark;
        handle = dark ? 0x99FFFFFF : 0x59000000;
        dotOn = dark ? 0xFFFFFFFF : 0xFF1C1C1E;
        dotOff = dark ? 0x4DFFFFFF : 0x33000000;
        if (ANDROID.equals(style)) android(c);
        else if (XIAOMI.equals(style)) xiaomi();
        else ios();
    }

    private void ios() {
        if (dark) {
            sheet = 0x99101012;
            sheetSolid = 0xEB161618;
            module = 0x2EFFFFFF;
            stroke = 0x1FFFFFFF;
            icon = 0xFFFFFFFF;
            circleOff = 0x38FFFFFF;
            circleIcon = 0xFFFFFFFF;
            activeBg = 0xFFFFFFFF;
            activeIcon = 0xFF000000;
            text = 0xFFFFFFFF;
            sub = 0x99EBEBF5;
            track = 0x2EFFFFFF;
            fill = 0xFFFFFFFF;
            iconOnFill = 0xFF8E8E93;
            iconOnTrack = 0xFFFFFFFF;
            card = 0xD9262628;
            cardText = 0xFFFFFFFF;
            cardSub = 0x99EBEBF5;
            pill = 0x33FFFFFF;
            roundBtn = 0x33FFFFFF;
            roundBtnIcon = 0xFFFFFFFF;
        } else {
            sheet = 0x80F2F2F7;
            sheetSolid = 0xE8F2F2F7;
            module = 0x99FFFFFF;
            stroke = 0xB3FFFFFF;
            icon = 0xFF1C1C1E;
            circleOff = 0xE6FFFFFF;
            circleIcon = 0xFF1C1C1E;
            activeBg = 0xFF1C1C1E;
            activeIcon = 0xFFFFFFFF;
            text = 0xFF000000;
            sub = 0x993C3C43;
            track = 0xB3FFFFFF;
            fill = 0xFF1C1C1E;
            iconOnFill = 0xFFFFFFFF;
            iconOnTrack = 0xFF3A3A3C;
            card = 0xD9FFFFFF;
            cardText = 0xFF000000;
            cardSub = 0x993C3C43;
            pill = 0x14000000;
            roundBtn = 0xB3FFFFFF;
            roundBtnIcon = 0xFF1C1C1E;
        }
    }

    /** Material You: the phone's own wallpaper-based colours (Android 12+), Google blue before. */
    private void android(Context c) {
        int a100 = sys(c, android.R.color.system_accent1_100, 0xFFD3E3FD);
        int a200 = sys(c, android.R.color.system_accent1_200, 0xFFA8C7FA);
        int a600 = sys(c, android.R.color.system_accent1_600, 0xFF0B57D0);
        int a900 = sys(c, android.R.color.system_accent1_900, 0xFF041E49);
        int n10 = sys(c, android.R.color.system_neutral1_10, 0xFFFDFBFF);
        int n50 = sys(c, android.R.color.system_neutral1_50, 0xFFF1F0F4);
        int n100 = sys(c, android.R.color.system_neutral1_100, 0xFFE3E2E6);
        int n700 = sys(c, android.R.color.system_neutral1_700, 0xFF46464A);
        int n800 = sys(c, android.R.color.system_neutral1_800, 0xFF2F3033);
        int n900 = sys(c, android.R.color.system_neutral1_900, 0xFF1B1B1F);
        int v100 = sys(c, android.R.color.system_neutral2_100, 0xFFE1E2EC);
        int v300 = sys(c, android.R.color.system_neutral2_300, 0xFFC4C6D0);
        int v700 = sys(c, android.R.color.system_neutral2_700, 0xFF44474F);
        stroke = 0;
        if (dark) {
            sheet = (n900 & 0xFFFFFF) | 0xD9000000;
            sheetSolid = (n900 & 0xFFFFFF) | 0xFA000000;
            module = n800;
            icon = n100;
            circleOff = n800;
            circleIcon = n100;
            activeBg = a200;
            activeIcon = a900;
            text = n50;
            sub = v300;
            track = n800;
            fill = a200;
            iconOnFill = a900;
            iconOnTrack = n100;
            card = n800;
            cardText = n50;
            cardSub = v300;
            pill = n700;
            roundBtn = n800;
            roundBtnIcon = n100;
        } else {
            sheet = (n50 & 0xFFFFFF) | 0xE0000000;
            sheetSolid = (n50 & 0xFFFFFF) | 0xFA000000;
            module = v100;
            icon = n900;
            circleOff = v100;
            circleIcon = n900;
            activeBg = a600;
            activeIcon = 0xFFFFFFFF;
            text = n900;
            sub = v700;
            track = a100;
            fill = a600;
            iconOnFill = 0xFFFFFFFF;
            iconOnTrack = n900;
            card = n10;
            cardText = n900;
            cardSub = v700;
            pill = v100;
            roundBtn = v100;
            roundBtnIcon = n900;
        }
    }

    /** HyperOS: white/dark glass, blue for active things. */
    private void xiaomi() {
        int blue = 0xFF2F7CF6;
        stroke = 0;
        activeBg = blue;
        activeIcon = 0xFFFFFFFF;
        if (dark) {
            sheet = 0x8C000000;
            sheetSolid = 0xF0141414;
            module = 0x29FFFFFF;
            icon = 0xFFFFFFFF;
            circleOff = 0x33FFFFFF;
            circleIcon = 0xFFFFFFFF;
            text = 0xFFFFFFFF;
            sub = 0x99FFFFFF;
            track = 0x33FFFFFF;
            fill = 0xFFFFFFFF;
            iconOnFill = 0xFF555555;
            iconOnTrack = 0xFFFFFFFF;
            card = 0xD92A2A2A;
            cardText = 0xFFFFFFFF;
            cardSub = 0x99FFFFFF;
            pill = 0x33FFFFFF;
            roundBtn = 0x33FFFFFF;
            roundBtnIcon = 0xFFFFFFFF;
        } else {
            sheet = 0x73F2F2F2;
            sheetSolid = 0xF0F2F2F2;
            module = 0xD9FFFFFF;
            icon = 0xFF1F1F1F;
            circleOff = 0xEBFFFFFF;
            circleIcon = 0xFF1F1F1F;
            text = 0xFF000000;
            sub = 0x8C000000;
            track = 0xD9FFFFFF;
            fill = blue;
            iconOnFill = 0xFFFFFFFF;
            iconOnTrack = 0xFF1F1F1F;
            card = 0xF2FFFFFF;
            cardText = 0xFF000000;
            cardSub = 0x8C000000;
            pill = 0x14000000;
            roundBtn = 0xD9FFFFFF;
            roundBtnIcon = 0xFF1F1F1F;
        }
    }

    private static int sys(Context c, int id, int fallback) {
        if (Build.VERSION.SDK_INT < 31) return fallback;
        try {
            return c.getColor(id);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static Look of(Context c) {
        String t = Prefs.theme(c);
        boolean dark;
        if ("dark".equals(t)) dark = true;
        else if ("system".equals(t)) dark = (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        else dark = false;
        return new Look(c, Prefs.style(c), dark);
    }
}
