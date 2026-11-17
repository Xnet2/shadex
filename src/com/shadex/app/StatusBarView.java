package com.shadex.app;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/**
 * Thin status bar fixed at the top of the panel, drawn like the phone's own: time and the icons of
 * apps with notifications on one side; Wi-Fi, Bluetooth, battery and its percentage on the other.
 */
final class StatusBarView extends LinearLayout implements Panel.Live {
    private final Context c;
    private final Look look;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final TextView time, pct;
    private final LinearLayout notifIcons;
    private final ImageView wifi, bt, battery;

    private final Runnable updater = this::update;
    private final Runnable onNotif = () -> {
        h.removeCallbacks(updater);
        h.postDelayed(updater, 150);
    };
    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            update();
        }
    };

    StatusBarView(Context c, Look look) {
        super(c);
        this.c = c;
        this.look = look;
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0);

        time = Ui.line(c, 13.5f, look.text, 500);
        addView(time);
        notifIcons = new LinearLayout(c);
        notifIcons.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams nl = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        nl.setMarginStart(Ui.dp(c, 8));
        addView(notifIcons, nl);

        wifi = glyph(R.drawable.ic_wifi, 14);
        bt = glyph(R.drawable.ic_bt, 14);
        // Battery lying on its side, then the percentage (the phone's order).
        battery = glyph(R.drawable.ic_battery, 17);
        battery.setRotation(90);
        pct = Ui.line(c, 12.5f, look.text, 500);
        LayoutParams pl = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        pl.setMarginStart(Ui.dp(c, 2));
        addView(pct, pl);
        update();
    }

    private ImageView glyph(int res, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(look.text));
        int s = Ui.dp(c, sizeDp);
        LayoutParams lp = new LayoutParams(s, s);
        lp.setMarginStart(Ui.dp(c, 5));
        addView(v, lp);
        return v;
    }

    private void update() {
        time.setText(DateFormat.getTimeFormat(c).format(new java.util.Date()));
        wifi.setVisibility(Sys.wifiOn(c) ? VISIBLE : GONE);
        boolean btOn = Sys.btOn();
        bt.setVisibility(btOn ? VISIBLE : GONE);
        if (btOn) bt.setImageResource(Sys.btInfo().connected ? R.drawable.ic_bt_connected : R.drawable.ic_bt);
        pct.setText(Sys.battery(c) + "%");
        battery.setImageResource(Sys.charging(c) ? R.drawable.ic_battery_charging : R.drawable.ic_battery);

        notifIcons.removeAllViews();
        NotifService s = NotifService.instance;
        if (s == null) return;
        StatusBarNotification[] arr;
        try {
            arr = s.getActiveNotifications();
        } catch (Throwable t) {
            return;
        }
        if (arr == null) return;
        Set<String> seen = new HashSet<>();
        int sz = Ui.dp(c, 13);
        for (StatusBarNotification sbn : arr) {
            if (notifIcons.getChildCount() >= 5) break;
            String pkg = sbn.getPackageName();
            if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
            Icon small = sbn.getNotification().getSmallIcon();
            if (small == null) continue;
            Drawable d;
            try {
                d = small.loadDrawable(c);
            } catch (Throwable t) {
                d = null;
            }
            if (d == null) continue;
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(d);
            iv.setImageTintList(ColorStateList.valueOf(look.text));
            LayoutParams lp = new LayoutParams(sz, sz);
            lp.setMarginEnd(Ui.dp(c, 4));
            notifIcons.addView(iv, lp);
        }
    }

    private boolean live;

    /** Starts listening to the system: only while the panel is open. */
    @Override
    public void resume() {
        if (live) return;
        live = true;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        c.registerReceiver(rx, f);
        NotifService.listeners.add(onNotif);
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        pause();
    }

    /** Stops listening: the panel closed. */
    @Override
    public void pause() {
        if (!live) return;
        live = false;
        try {
            c.unregisterReceiver(rx);
        } catch (Exception ignored) {
        }
        NotifService.listeners.remove(onNotif);
        h.removeCallbacksAndMessages(null);
    }
}
