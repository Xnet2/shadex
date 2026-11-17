package com.shadex.app;

import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;

/** Reads and changes system state using only what a normal (non-root) app may touch. */
final class Sys {
    private Sys() {}

    private static ContentResolver cr(Context c) { return c.getContentResolver(); }

    static boolean wifiOn(Context c) {
        WifiManager w = c.getApplicationContext().getSystemService(WifiManager.class);
        return w != null && w.isWifiEnabled();
    }

    /** Works because the app targets API 28; returns false if the system refused. */
    @SuppressWarnings("deprecation")
    static boolean setWifi(Context c, boolean on) {
        try {
            WifiManager w = c.getApplicationContext().getSystemService(WifiManager.class);
            return w != null && w.setWifiEnabled(on);
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    static boolean btOn() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            return a != null && a.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    static boolean setBt(boolean on) {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            return a != null && (on ? a.enable() : a.disable());
        } catch (Throwable t) {
            return false;
        }
    }

    /** A connected Bluetooth device and its battery level (-1 when the device doesn't report it). */
    static final class BtInfo {
        boolean connected;
        int battery = -1;
    }

    /** Uses hidden-but-allowed BluetoothDevice methods (isConnected, getBatteryLevel). */
    @SuppressWarnings("deprecation")
    static BtInfo btInfo() {
        BtInfo info = new BtInfo();
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null || !a.isEnabled()) return info;
            for (android.bluetooth.BluetoothDevice d : a.getBondedDevices()) {
                Object conn = d.getClass().getMethod("isConnected").invoke(d);
                if (!Boolean.TRUE.equals(conn)) continue;
                info.connected = true;
                Object level = d.getClass().getMethod("getBatteryLevel").invoke(d);
                if (level instanceof Integer && (Integer) level >= 0) {
                    info.battery = (Integer) level;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return info;
    }

    static boolean airplane(Context c) {
        return Settings.Global.getInt(cr(c), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
    }

    static boolean mobileData(Context c) {
        try {
            TelephonyManager tm = c.getSystemService(TelephonyManager.class);
            if (tm != null) return tm.isDataEnabled();
        } catch (Throwable ignored) {
        }
        return Settings.Global.getInt(cr(c), "mobile_data", 0) == 1;
    }

    static boolean locationOn(Context c) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        return lm != null && lm.isLocationEnabled();
    }

    static boolean canWrite(Context c) {
        return Settings.System.canWrite(c);
    }

    static boolean rotationLocked(Context c) {
        return Settings.System.getInt(cr(c), Settings.System.ACCELEROMETER_ROTATION, 1) == 0;
    }

    static boolean setRotationLocked(Context c, boolean locked) {
        if (!canWrite(c)) return false;
        return Settings.System.putInt(cr(c), Settings.System.ACCELEROMETER_ROTATION, locked ? 0 : 1);
    }

    /** 0..1, perceptual (square-root) scale like the stock slider. */
    static float brightness(Context c) {
        int v = Settings.System.getInt(cr(c), Settings.System.SCREEN_BRIGHTNESS, 128);
        return (float) Math.sqrt(Math.max(0, Math.min(255, v)) / 255f);
    }

    static boolean setBrightness(Context c, float p) {
        if (!canWrite(c)) return false;
        if (autoBrightness(c)) setAutoBrightness(c, false);
        int v = Math.max(1, Math.round(p * p * 255f));
        return Settings.System.putInt(cr(c), Settings.System.SCREEN_BRIGHTNESS, v);
    }

    static boolean autoBrightness(Context c) {
        return Settings.System.getInt(cr(c), Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    }

    static boolean setAutoBrightness(Context c, boolean on) {
        if (!canWrite(c)) return false;
        return Settings.System.putInt(cr(c), Settings.System.SCREEN_BRIGHTNESS_MODE,
                on ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
    }

    static float volume(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max == 0 ? 0 : am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    static void setVolume(Context c, float p) {
        AudioManager am = c.getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(p * max), 0);
        } catch (SecurityException ignored) {
            // Thrown while Do Not Disturb blocks volume changes.
        }
    }

    static boolean dndOn(Context c) {
        int f = c.getSystemService(NotificationManager.class).getCurrentInterruptionFilter();
        return f != NotificationManager.INTERRUPTION_FILTER_ALL
                && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
    }

    static boolean dndAccess(Context c) {
        return c.getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();
    }

    static boolean setDnd(Context c, boolean on) {
        if (!dndAccess(c)) return false;
        c.getSystemService(NotificationManager.class).setInterruptionFilter(on
                ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                : NotificationManager.INTERRUPTION_FILTER_ALL);
        return true;
    }

    static int battery(Context c) {
        return c.getSystemService(BatteryManager.class)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    static boolean charging(Context c) {
        return c.getSystemService(BatteryManager.class).isCharging();
    }

    static boolean notifAccess(Context c) {
        String s = Settings.Secure.getString(cr(c), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName() + "/");
    }

    static boolean accessibilityOn(Context c) {
        String s = Settings.Secure.getString(cr(c), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(c.getPackageName() + "/");
    }

    /** Back-camera flashlight with live state. */
    static final class Torch {
        private final CameraManager cm;
        private String id;
        boolean on;
        private final Runnable listener;
        private final CameraManager.TorchCallback cb = new CameraManager.TorchCallback() {
            @Override
            public void onTorchModeChanged(String camId, boolean enabled) {
                if (camId.equals(id)) {
                    on = enabled;
                    listener.run();
                }
            }
        };

        Torch(Context c, Runnable listener) {
            this.listener = listener;
            cm = c.getSystemService(CameraManager.class);
            try {
                for (String cid : cm.getCameraIdList()) {
                    CameraCharacteristics ch = cm.getCameraCharacteristics(cid);
                    Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                    if (Boolean.TRUE.equals(flash) && facing != null
                            && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        id = cid;
                        break;
                    }
                }
                cm.registerTorchCallback(cb, new Handler(Looper.getMainLooper()));
            } catch (Throwable ignored) {
            }
        }

        void toggle() {
            if (id == null) return;
            try {
                cm.setTorchMode(id, !on);
            } catch (Throwable ignored) {
            }
        }

        void release() {
            try {
                cm.unregisterTorchCallback(cb);
            } catch (Throwable ignored) {
            }
        }
    }
}
