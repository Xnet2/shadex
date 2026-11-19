package com.shadex.app;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Owns the one panel window. While closed the window is just the swipe zone over the status bar
 * (the Trigger view); pulling down on it grows the window and the sheet follows the finger.
 *
 * Kept deliberately simple: no polling, no injected touches, no broadcasts to other apps. The only
 * thing done about the system shade is to collapse it once if it shows up while ours is in use.
 */
public class ShadeService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static ShadeService instance;

    final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private Panel panel;
    private String homePkg;
    /** Last time a finger was on our swipe zone (or our home screen pulled the panel). */
    private long lastTouch;
    /** The system shade may be opened on purpose (the "system" button) until this time. */
    private long allowSystemUntil;
    private long lastCollapse;
    private final Executor shotExec = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (panel != null && panel.hasSession()) panel.endSession();
        }
    };

    // ================================================================ lifecycle

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = getSystemService(WindowManager.class);
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this);
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        log("service connected");
        recreatePanel();
        applyRootBlock();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        if (instance != this) return;
        instance = null;
        h.removeCallbacksAndMessages(null);
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this);
        try {
            unregisterReceiver(screenOff);
        } catch (Exception ignored) {
        }
        if (panel != null) panel.detach();
        panel = null;
        if (Variant.ROOT && rootBlocked) {
            rootBlocked = false;
            new Thread(() -> Root.blockShade(false)).start();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration nc) {
        super.onConfigurationChanged(nc);
        recreatePanel();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (Prefs.ROOT_BLOCK.equals(key)) {
            applyRootBlock();
            return;
        }
        // Home screen settings don't concern the panel.
        if (key != null && (key.startsWith("home_") || key.equals(Prefs.DOCK))) return;
        h.removeCallbacks(recreate);
        h.postDelayed(recreate, 300);
    }

    private final Runnable recreate = this::recreatePanel;

    // ================================================================ the window

    /** (Re)creates the one window with the swipe zone at the current settings. */
    private void recreatePanel() {
        if (wm == null) return;
        if (panel != null) panel.detach();
        panel = null;
        boolean land = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        boolean enabled = !(land && Prefs.hideLandscape(this));
        int dp = Prefs.stripDp(this);
        int height = dp > 0 ? Ui.dp(this, dp) : Ui.statusBarHeight(this);
        Panel p = new Panel(this);
        try {
            p.attach(new Trigger(this), height, enabled);
            panel = p;
            log("panel ready, zone " + height + "px" + (enabled ? "" : " (off)"));
        } catch (Throwable t) {
            log("panel error: " + t);
            return;
        }
        scheduleWarm(80);
    }

    private final Runnable warmer = () -> {
        if (panel == null || panel.hasSession()) return;
        try {
            panel.warm();
        } catch (Throwable t) {
            log("warm error: " + t);
        }
    };

    /** Builds the next panel content ahead of time, so a touch-down only picks a page. */
    private void scheduleWarm(long delay) {
        h.removeCallbacks(warmer);
        h.postDelayed(warmer, delay);
    }

    void onSessionEnded() {
        scheduleWarm(350);
    }

    void onPanelGone(Panel p) {
        if (panel == p) panel = null;
    }

    /** Starts a session (instant when warm); returns null if that failed. */
    private Panel startSession(int type) {
        if (panel == null) recreatePanel();
        if (panel == null) return null;
        try {
            panel.prepare(type);
            return panel;
        } catch (Throwable t) {
            log("prepare error: " + t);
            panel.endSession();
            return null;
        }
    }

    /** Safety net: never leave an empty, grown window on screen. */
    private final Runnable watchdog = () -> {
        if (panel != null && panel.isIdleHidden()) {
            log("watchdog: ended an empty session");
            panel.endSession();
        }
    };

    // ================================================================ blur

    /**
     * Takes a screenshot while the panel is still invisible and hands it to the panel, which shows
     * it blurred behind its translucent tint. Kept as a GPU bitmap: no slow copy.
     */
    private void captureFor(Panel p) {
        if (p == null || !Prefs.blur(this) || Build.VERSION.SDK_INT < 31) return;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExec, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    android.graphics.Bitmap shot = null;
                    try (HardwareBuffer hb = r.getHardwareBuffer()) {
                        shot = android.graphics.Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                    } catch (Throwable t) {
                        log("shot error: " + t);
                    }
                    android.graphics.Bitmap fin = shot;
                    h.post(() -> {
                        if (fin != null && p == panel && p.hasSession()) p.setShot(fin);
                    });
                }

                @Override
                public void onFailure(int code) {
                    // Usually "too soon after the last one": the panel just keeps its plain tint.
                }
            });
        } catch (Throwable t) {
            log("shot: " + t);
        }
    }

    // ================================================================ the system shade

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        // Our window is never focusable, so Back comes through here.
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && panel != null && panel.isShowing()) {
            if (e.getAction() == KeyEvent.ACTION_UP) panel.close();
            return true;
        }
        return false;
    }

    private boolean inUse() {
        return (panel != null && panel.hasSession())
                || SystemClock.uptimeMillis() - lastTouch < 1200;
    }

    /** A big SystemUI window on screen (the expanding shade). Not checked on the lock screen. */
    private boolean systemShadeVisible() {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) return false;
        try {
            int screenH = getResources().getDisplayMetrics().heightPixels;
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                if (r.height() > screenH / 2) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Collapses the system shade (at most every 300 ms, never while it was opened on purpose). */
    private void collapseSystemShade(String why) {
        long now = SystemClock.uptimeMillis();
        if (now < allowSystemUntil || now - lastCollapse < 300) return;
        lastCollapse = now;
        log("closing the system shade (" + why + ")");
        try {
            Object sbm = getSystemService("statusbar");
            sbm.getClass().getMethod("collapsePanels").invoke(sbm);
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
    }

    /** Opens the real system shade on purpose (the "original panel" button). */
    void openSystemShade(int globalAction) {
        if (Variant.ROOT && rootBlocked) {
            rootExec.execute(() -> {
                if (Root.blockShade(false)) rootBlocked = false;
                h.post(() -> {
                    allowSystemUntil = SystemClock.uptimeMillis() + 3000;
                    performGlobalAction(globalAction);
                    h.postDelayed(reblock, 1500);
                });
            });
            return;
        }
        allowSystemUntil = SystemClock.uptimeMillis() + 3000;
        performGlobalAction(globalAction);
    }

    // ================================================================ root build

    private final Executor rootExec = Executors.newSingleThreadExecutor();
    /** True while the system shade is disabled through root. */
    volatile boolean rootBlocked;

    /** Once the system shade opened on purpose is closed again, block it again. */
    private final Runnable reblock = new Runnable() {
        @Override
        public void run() {
            if (systemShadeVisible()) h.postDelayed(this, 1000);
            else applyRootBlock();
        }
    };

    /** Root build: blocks or allows the system shade as the setting says (off the main thread). */
    void applyRootBlock() {
        if (!Variant.ROOT) return;
        boolean want = Prefs.rootBlock(this);
        rootExec.execute(() -> {
            if (!want && !rootBlocked) return;
            boolean ok = Root.blockShade(want);
            if (ok) rootBlocked = want;
            else if (want) h.post(() -> toast("לא התקבלה הרשאת רוט. אשר את ShadeX באפליקציית הרוט"));
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        int type = e.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (inUse() && systemShadeVisible()) collapseSystemShade("window");
            return;
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName();
        if (p == null) return;
        if ("com.android.systemui".contentEquals(p)) {
            if (inUse() && systemShadeVisible()) collapseSystemShade("state");
            return;
        }
        // Going Home / Recents while the panel is open closes it.
        if (panel == null || !panel.isOpen()) return;
        if (SystemClock.uptimeMillis() - panel.openedAt < 600) return;
        if (homePkg == null) homePkg = home();
        if (homePkg != null && homePkg.contentEquals(p)) panel.close();
    }

    private String home() {
        ResolveInfo ri = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : null;
    }

    // ================================================================ opening from elsewhere

    /** Opens a panel with the animation (the app's test buttons). */
    void showPanel(int type) {
        Panel p = startSession(type);
        if (p == null) return;
        lastTouch = SystemClock.uptimeMillis();
        captureFor(p);
        p.makeInteractive();
        h.post(() -> {
            if (p == panel) p.animateTo(1f);
        });
    }

    /** From the launcher shortcut, which first has to get out of the way. */
    void showPanelSoon(int type) {
        h.postDelayed(() -> showPanel(type), 250);
    }

    /** Which page a pull starting at x (of width w) opens, by the user's zone settings. */
    int zoneAt(float x, float w) {
        String mode = Prefs.mode(this);
        if ("cc".equals(mode)) return Panel.CC;
        if ("nc".equals(mode)) return Panel.NC;
        float z = Prefs.ccZone(this) / 100f;
        boolean cc = Prefs.ccLeft(this) ? x < w * z : x > w * (1f - z);
        return cc ? Panel.CC : Panel.NC;
    }

    /** Our home screen saw a swipe down: start the panel and let the finger drive it. */
    boolean beginPull(int type) {
        Panel p = startSession(type);
        if (p == null) return false;
        lastTouch = SystemClock.uptimeMillis();
        captureFor(p);
        return true;
    }

    void pull(float dy) {
        lastTouch = SystemClock.uptimeMillis();
        if (panel != null) panel.dragOpen(dy);
    }

    void releasePull(float vy) {
        lastTouch = SystemClock.uptimeMillis();
        if (panel != null) panel.releaseOpen(vy);
        h.removeCallbacks(watchdog);
        h.postDelayed(watchdog, 1500);
    }

    // ================================================================ used by the panel

    void closePanel() {
        if (panel != null) panel.close();
    }

    /** Closes the panel, then runs the action once it is off screen. */
    void closeThen(Runnable r) {
        closePanel();
        h.postDelayed(r, 480);
    }

    void launch(Intent... candidates) {
        for (Intent i : candidates) {
            if (i == null) continue;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                closePanel();
                return;
            } catch (Exception ignored) {
            }
        }
        toast("לא נמצאה אפליקציה מתאימה");
    }

    void send(PendingIntent pi) {
        try {
            pi.send();
        } catch (Exception ignored) {
        }
        closePanel();
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ================================================================ diagnostic log

    static final ArrayDeque<String> LOG = new ArrayDeque<>();

    static void log(String s) {
        synchronized (LOG) {
            LOG.addFirst(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + "  " + s);
            while (LOG.size() > 40) LOG.removeLast();
        }
    }

    // ================================================================ the swipe zone

    /** The invisible swipe zone over the status bar, living inside the panel window. */
    private final class Trigger extends View {
        private final int slop;
        private final Paint paint = new Paint();
        private float downX, downY, lastDy;
        private boolean dragging;
        private VelocityTracker vt;

        Trigger(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!Prefs.showZone(getContext()) || (panel != null && panel.hasSession())) return;
            int w = getWidth();
            for (int x = 0; x < w; x += 6) {
                paint.setColor(zoneAt(x, w) == Panel.CC ? 0x880A84FF : 0x8834C759);
                c.drawRect(x, 0, x + 6, getHeight(), paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            // Once the panel is open this zone is just the top of the panel.
            if (a == MotionEvent.ACTION_DOWN && panel != null && panel.isOpen()) return false;
            lastTouch = SystemClock.uptimeMillis();
            switch (a) {
                case MotionEvent.ACTION_DOWN: {
                    downX = e.getX();
                    downY = e.getY();
                    lastDy = 0;
                    dragging = false;
                    if (vt != null) vt.recycle();
                    vt = VelocityTracker.obtain();
                    vt.addMovement(e);
                    Panel p = startSession(zoneAt(downX, getWidth()));
                    captureFor(p);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (vt != null) vt.addMovement(e);
                    float dy = e.getY() - downY;
                    lastDy = dy;
                    if (!dragging && dy > slop) dragging = true;
                    if (dragging && panel != null) panel.dragOpen(dy - slop);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean cancel = a == MotionEvent.ACTION_CANCEL;
                    if (panel != null) {
                        if (cancel && (dragging || lastDy > 0)) {
                            // The system took the gesture away: finish opening ours.
                            log("gesture taken away, finishing the panel");
                            collapseSystemShade("cancel");
                            panel.cover();
                        } else if (dragging) {
                            float vy = 0;
                            if (vt != null) {
                                vt.addMovement(e);
                                vt.computeCurrentVelocity(1000);
                                vy = vt.getYVelocity();
                            }
                            panel.releaseOpen(vy);
                        } else if (!panel.isOpen()) {
                            panel.endSession();
                        }
                    }
                    dragging = false;
                    if (vt != null) {
                        vt.recycle();
                        vt = null;
                    }
                    h.removeCallbacks(watchdog);
                    h.postDelayed(watchdog, 1500);
                    return true;
                }
            }
            return true;
        }
    }
}
