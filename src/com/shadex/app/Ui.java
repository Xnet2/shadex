package com.shadex.app;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rect(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    static TextView text(Context c, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, weight, false));
        return t;
    }

    static TextView line(Context c, float sp, int color, int weight) {
        TextView t = text(c, sp, color, weight);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static void haptic(View v) {
        if (Prefs.haptic(v.getContext())) {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
    }

    /** iOS-like shrink while the finger is down. */
    static void press(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(110)
                            .setInterpolator(null).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(new OvershootInterpolator(2.2f)).start();
                    break;
            }
            return false;
        });
    }

    static void onTap(View v, Runnable click) {
        v.setOnClickListener(x -> {
            haptic(x);
            click.run();
        });
    }

    static void onHold(View v, Runnable hold) {
        v.setOnLongClickListener(x -> {
            haptic(x);
            hold.run();
            return true;
        });
    }

    static int statusBarHeight(Context c) {
        return sysDimen(c, "status_bar_height", 24);
    }

    static int navBarHeight(Context c) {
        return sysDimen(c, "navigation_bar_height", 0);
    }

    private static int sysDimen(Context c, String name, int fallbackDp) {
        Resources r = c.getResources();
        int id = r.getIdentifier(name, "dimen", "android");
        return id > 0 ? r.getDimensionPixelSize(id) : dp(c, fallbackDp);
    }
}
