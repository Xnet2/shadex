package com.shadex.app;

import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A page that the panel can scroll-check before a swipe up closes it. */
interface ShadePage {
    ScrollView scroll();
}

/**
 * What every Control Center style shares: reading each button's state, what a tap / long press
 * does, the sliders, Now Playing, and refreshing when the system changes. Styles only lay out.
 */
abstract class ControlsBase extends FrameLayout implements ShadePage, Panel.Live {
    final Panel panel;
    final ShadeService svc;
    final Context c;
    final Look look;
    final PackageManager pm;
    final Handler h = new Handler(Looper.getMainLooper());

    private Sys.Torch torch;
    private MediaSessionManager msm;
    private MediaController controller;
    private Tiles.Media media;
    private boolean hideIdleMedia, warnedWrite;
    /** Shown state right after a tap, until the system confirms it (switching takes a moment). */
    private final Map<String, Boolean> expected = new HashMap<>();
    private final Map<String, Long> expectedUntil = new HashMap<>();

    private final Runnable refresher = this::refresh;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            scheduleRefresh();
        }
    };

    private final ContentObserver obs = new ContentObserver(h) {
        @Override
        public void onChange(boolean self) {
            scheduleRefresh();
        }
    };

    private final MediaController.Callback mediaCb = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            updateMedia();
        }

        @Override
        public void onMetadataChanged(MediaMetadata m) {
            updateMedia();
        }

        @Override
        public void onSessionDestroyed() {
            pickController(null);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsCb =
            this::pickController;

    ControlsBase(Context c, Panel p) {
        super(c);
        this.c = c;
        this.panel = p;
        this.svc = p.svc;
        this.look = p.look;
        this.pm = c.getPackageManager();
        p.live(this);
    }

    /** Re-reads every state into the views. */
    abstract void refresh();

    final void scheduleRefresh() {
        h.removeCallbacks(refresher);
        h.postDelayed(refresher, 60);
    }

    // ------------------------------------------------------------ state

    boolean on(String id) {
        Long until = expectedUntil.get(id);
        if (until != null && SystemClock.uptimeMillis() < until) return expected.get(id);
        switch (id) {
            case "wifi": return Sys.wifiOn(c);
            case "bt": return Sys.btOn();
            case "cell": return Sys.mobileData(c);
            case "airplane": return Sys.airplane(c);
            case "dnd": return Sys.dndOn(c);
            case "rotation": return Sys.rotationLocked(c);
            case "location": return Sys.locationOn(c);
            case "flashlight": return torch != null && torch.on;
        }
        return false;
    }

    private void expect(String id, boolean v) {
        expected.put(id, v);
        expectedUntil.put(id, SystemClock.uptimeMillis() + 2500);
    }

    /** Second line for tiles that have one (Android / Xiaomi). */
    String sub(String id) {
        if ("bt".equals(id) && on(id)) {
            Sys.BtInfo bi = Sys.btInfo();
            if (bi.connected) return bi.battery >= 0 ? "מחובר · " + bi.battery + "%" : "מחובר";
        }
        if (!Catalog.stateful(id)) return "";
        return on(id) ? "מופעל" : "כבוי";
    }

    int icon(String id) {
        if ("bt".equals(id) && Sys.btInfo().connected) return R.drawable.ic_bt_connected;
        return Catalog.icon(id);
    }

    Drawable appIcon(String id) {
        try {
            return pm.getApplicationIcon(Catalog.pkg(id));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** False for an app button whose app is gone. */
    boolean available(String id) {
        return !Catalog.isApp(id) || pm.getLaunchIntentForPackage(Catalog.pkg(id)) != null;
    }

    // ------------------------------------------------------------ actions

    void act(String id) {
        if (Catalog.isApp(id)) {
            svc.launch(pm.getLaunchIntentForPackage(Catalog.pkg(id)));
            return;
        }
        switch (id) {
            case "wifi": {
                boolean v = !on(id);
                if (Sys.setWifi(c, v)) expect(id, v);
                else svc.launch(new Intent(Settings.Panel.ACTION_WIFI));
                break;
            }
            case "bt": {
                boolean v = !on(id);
                if (Sys.setBt(v)) expect(id, v);
                else svc.launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                break;
            }
            case "cell":
                svc.launch(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
                        new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS));
                break;
            case "airplane":
                svc.launch(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
                break;
            case "dnd": {
                boolean v = !on(id);
                if (Sys.setDnd(c, v)) {
                    expect(id, v);
                } else {
                    svc.toast("יש לאשר גישה ל'נא לא להפריע'");
                    svc.launch(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                }
                break;
            }
            case "rotation": {
                boolean v = !on(id);
                if (Sys.setRotationLocked(c, v)) expect(id, v);
                else askWriteSettings();
                break;
            }
            case "screenshot":
                svc.closeThen(() -> svc.performGlobalAction(ShadeService.GLOBAL_ACTION_TAKE_SCREENSHOT));
                break;
            case "flashlight":
                if (torch != null) torch.toggle();
                break;
            case "timer":
                svc.launch(new Intent(AlarmClock.ACTION_SHOW_TIMERS), new Intent(AlarmClock.ACTION_SET_TIMER));
                break;
            case "alarm":
                svc.launch(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
                break;
            case "calculator":
                svc.launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR),
                        pm.getLaunchIntentForPackage("com.sec.android.app.popupcalculator"),
                        pm.getLaunchIntentForPackage("com.miui.calculator"),
                        pm.getLaunchIntentForPackage("com.google.android.calculator"));
                break;
            case "camera":
            case "qr":
                svc.launch(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
                break;
            case "hotspot":
                svc.launch(new Intent("android.settings.TETHER_SETTINGS"),
                        new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                        new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                break;
            case "location":
                svc.launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                break;
            case "nfc":
                svc.launch(new Intent(Settings.Panel.ACTION_NFC), new Intent(Settings.ACTION_NFC_SETTINGS));
                break;
            case "cast":
                svc.launch(new Intent(Settings.ACTION_CAST_SETTINGS));
                break;
            case "darkmode":
                svc.launch(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
                break;
            case "battery":
                svc.launch(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
                        new Intent(Intent.ACTION_POWER_USAGE_SUMMARY));
                break;
            case "lock":
                svc.closeThen(() -> svc.performGlobalAction(ShadeService.GLOBAL_ACTION_LOCK_SCREEN));
                break;
            case "settings":
                svc.launch(new Intent(Settings.ACTION_SETTINGS));
                break;
            case "system":
                svc.closeThen(() -> svc.openSystemShade(ShadeService.GLOBAL_ACTION_QUICK_SETTINGS));
                break;
        }
        refresh();
    }

    void hold(String id) {
        switch (id) {
            case "wifi": svc.launch(new Intent(Settings.Panel.ACTION_WIFI)); break;
            case "bt": svc.launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); break;
            case "cell": svc.launch(new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)); break;
            case "dnd":
                svc.launch(new Intent("android.settings.ZEN_MODE_SETTINGS"),
                        new Intent(Settings.ACTION_SOUND_SETTINGS));
                break;
            case "location": svc.launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); break;
            case "hotspot": act(id); break;
        }
    }

    /** Tap and long press on a view run the button's actions. */
    <V extends android.view.View> V wire(V v, String id) {
        Ui.onTap(v, () -> act(id));
        Ui.onHold(v, () -> hold(id));
        return v;
    }

    void askWriteSettings() {
        if (warnedWrite) return;
        warnedWrite = true;
        svc.toast("יש לאשר 'שינוי הגדרות מערכת' עבור ShadeX");
        svc.launch(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + c.getPackageName())));
    }

    // ------------------------------------------------------------ sliders

    void onBrightness(float v) {
        if (!Sys.setBrightness(c, v)) askWriteSettings();
    }

    void onBrightnessHold() {
        if (!Sys.canWrite(c)) {
            askWriteSettings();
            return;
        }
        boolean auto = !Sys.autoBrightness(c);
        Sys.setAutoBrightness(c, auto);
        svc.toast(auto ? "בהירות אוטומטית: מופעלת" : "בהירות אוטומטית: כבויה");
    }

    String timeText() {
        return DateFormat.getTimeFormat(c).format(new java.util.Date());
    }

    String batteryText() {
        return (Sys.charging(c) ? "⚡ " : "") + Sys.battery(c) + "%";
    }

    // ------------------------------------------------------------ Now Playing

    /** hideIdle: hide the card when nothing is playing (Android, Xiaomi). */
    void bindMedia(Tiles.Media m, boolean hideIdle) {
        media = m;
        hideIdleMedia = hideIdle;
        Ui.onTap(m.prev, () -> {
            if (controller != null) controller.getTransportControls().skipToPrevious();
        });
        Ui.onTap(m.next, () -> {
            if (controller != null) controller.getTransportControls().skipToNext();
        });
        Ui.onTap(m.play, () -> {
            if (controller == null) return;
            if (isPlaying(controller)) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        });
        Ui.onTap(m, () -> {
            if (controller == null) return;
            if (controller.getSessionActivity() != null) svc.send(controller.getSessionActivity());
            else svc.launch(pm.getLaunchIntentForPackage(controller.getPackageName()));
        });
        updateMedia();
    }

    private static boolean isPlaying(MediaController mc) {
        PlaybackState s = mc.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING
                || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    private void pickController(List<MediaController> list) {
        if (list == null && msm != null) {
            try {
                list = msm.getActiveSessions(new ComponentName(c, NotifService.class));
            } catch (SecurityException ignored) {
            }
        }
        MediaController best = null;
        if (list != null) {
            for (MediaController mc : list) {
                if (isPlaying(mc)) {
                    best = mc;
                    break;
                }
            }
            if (best == null && !list.isEmpty()) best = list.get(0);
        }
        if (controller != null) controller.unregisterCallback(mediaCb);
        controller = best;
        if (controller != null) controller.registerCallback(mediaCb, h);
        updateMedia();
    }

    private void updateMedia() {
        if (media == null) return;
        if (controller == null) {
            media.show(null, null, null, false);
            if (hideIdleMedia) media.setVisibility(GONE);
            return;
        }
        media.setVisibility(VISIBLE);
        MediaMetadata md = controller.getMetadata();
        CharSequence title = null, artist = null;
        Bitmap art = null;
        if (md != null) {
            title = md.getText(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) title = md.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            artist = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (artist == null) artist = md.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        media.show(title, artist, art, isPlaying(controller));
    }

    // ------------------------------------------------------------ lifecycle

    private boolean live;

    /** Starts listening to the system: only while the panel is open. */
    @Override
    public void resume() {
        if (live) return;
        live = true;
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
        f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        f.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        f.addAction("android.media.VOLUME_CHANGED_ACTION");
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(LocationManager.MODE_CHANGED_ACTION);
        f.addAction(Intent.ACTION_TIME_TICK);
        c.registerReceiver(rx, f);
        // Only the settings we show: not the whole settings tables (they change all the time).
        android.content.ContentResolver cr = c.getContentResolver();
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, obs);
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, obs);
        cr.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, obs);
        cr.registerContentObserver(Settings.Global.getUriFor("mobile_data"), false, obs);
        torch = new Sys.Torch(c, this::scheduleRefresh);
        msm = c.getSystemService(MediaSessionManager.class);
        ComponentName cn = new ComponentName(c, NotifService.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsCb, cn, h);
            pickController(msm.getActiveSessions(cn));
        } catch (SecurityException e) {
            if (media != null) media.show("נגן מדיה", "דורש 'גישה להתראות'", null, false);
        }
        refresh();
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
        h.removeCallbacksAndMessages(null);
        try {
            c.unregisterReceiver(rx);
        } catch (Exception ignored) {
        }
        c.getContentResolver().unregisterContentObserver(obs);
        if (torch != null) torch.release();
        torch = null;
        if (msm != null) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsCb);
            } catch (Exception ignored) {
            }
        }
        if (controller != null) controller.unregisterCallback(mediaCb);
        controller = null;
    }
}
