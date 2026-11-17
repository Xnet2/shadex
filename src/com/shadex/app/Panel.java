package com.shadex.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * The panel lives in ONE permanent full-screen overlay window that is added once and never
 * re-added during a swipe (adding or changing windows mid-swipe lets SystemUI take the gesture).
 * Between uses the window is empty and untouchable.
 *
 * A session: prepare() builds fresh content on touch-down, the sheet ("curtain") follows the
 * finger down, stops a little above the bottom of the screen, and endSession() empties the window
 * again. Inside the sheet two pages, Notification Center and Control Center, switch sideways.
 * progress 0 = sheet above the screen, 1 = fully down.
 */
final class Panel {
    static final int NC = 0, CC = 1;

    /** Parts that listen to the system: they do it only while the panel is open. */
    interface Live {
        void resume();

        void pause();
    }

    private final java.util.List<Live> lives = new java.util.ArrayList<>();

    void live(Live l) {
        lives.add(l);
    }

    final ShadeService svc;
    final Context ctx;
    private final WindowManager wm;
    Look look;
    private Root root;
    private View scrim;
    private FrameLayout backdrop, sheet;
    private ImageView sheetImage, backImage;
    private View tint;
    private final View[] dots = new View[2];
    /** The pages side by side: [notifications, controls], or one page for the Android style. */
    private final java.util.List<View> pageViews = new java.util.ArrayList<>();
    private LinearLayout dotRow;
    private WindowManager.LayoutParams lp;
    private ValueAnimator anim, pageAnim;
    private float progress, pageOff;
    private int page;
    /** The swipe zone, living in this same window (on top of everything else in it). */
    private View trigger;
    /** Window height while closed (= the swipe zone); the window grows to full screen on use. */
    private int collapsedH;
    private boolean triggerEnabled;

    /** built: content ready (warm). session: in use (from touch-down until closed). */
    private boolean attached, built, session, closing, interactive;
    private View editBtn;
    /** Height of the sheet when fully down (the screen minus a small gap at the bottom). */
    int sheetH;
    private int corner;
    private StatusBarView statusBar;
    int sw, sh;
    long openedAt;
    /** Set by a notification card when a touch starts on it, so sideways swipes dismiss it. */
    boolean cardTouch;

    Panel(ShadeService svc) {
        this.svc = svc;
        this.ctx = new ContextThemeWrapper(svc, android.R.style.Theme_DeviceDefault);
        this.wm = svc.getSystemService(WindowManager.class);
        this.look = Look.of(svc);
    }

    /**
     * Adds the one permanent window. Closed, it is only as tall as the swipe zone and holds the
     * trigger view, like Android's own status bar window; on use it grows to the full screen and
     * the sheet slides down inside it. Everything happens in this single window.
     */
    void attach(View trigger, int collapsedH, boolean triggerEnabled) {
        this.trigger = trigger;
        this.collapsedH = collapsedH;
        this.triggerEnabled = triggerEnabled;
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            sw = b.width();
            sh = b.height();
        } else {
            Point p = new Point();
            wm.getDefaultDisplay().getRealSize(p);
            sw = p.x;
            sh = p.y;
        }
        sheetH = sh - Ui.dp(ctx, 12);
        corner = Ui.dp(ctx, 34);
        root = new Root(ctx);
        root.addView(trigger, new FrameLayout.LayoutParams(-1, collapsedH, Gravity.TOP));
        lp = new WindowManager.LayoutParams(sw, collapsedH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | (triggerEnabled ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(root, lp);
        attached = true;
    }

    /**
     * Starts a session on a page. Instant when the content was already built by warm(), which is
     * the point: the touch-down must stay light, or the system shade gets ahead of us.
     */
    void prepare(int page) {
        if (!attached) return;
        if (session) endSession(false);
        if (!built) warm();
        if (!built) return;
        session = true;
        closing = false;
        cardTouch = false;
        this.page = Math.min(page, pageViews.size() - 1);
        pageOff = -this.page * sw;
        layoutPages();
        setProgress(0f);
        expand();
        openedAt = SystemClock.uptimeMillis();
        root.post(() -> {
            if (session) for (Live l : lives) l.resume();
        });
    }

    /** Full screen while in use (the touch carries on in this same window). */
    private void expand() {
        if (lp.height == sh) return;
        lp.height = sh;
        update();
    }

    /** Back to just the swipe zone. */
    private void collapse() {
        lp.height = collapsedH;
        lp.flags = triggerEnabled ? lp.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                : lp.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        update();
    }

    /** Builds all content ahead of time, off the touch path; the sheet stays hidden. */
    void warm() {
        if (!attached || built || session) return;
        look = Look.of(svc);
        int page = 0;

        // Soft shade over the app while the sheet is down (it also tints the gap at the bottom).
        scrim = new View(ctx);
        scrim.setBackgroundColor(look.dark ? 0x59000000 : 0x2E000000);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // backdrop: full-screen cover used only if the system shade ever takes the swipe.
        backdrop = new FrameLayout(ctx);
        backdrop.setBackgroundColor(look.sheetSolid);
        backdrop.setAlpha(0f);
        backImage = blurImage();
        backdrop.addView(backImage, new FrameLayout.LayoutParams(-1, -1));
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        // The sheet: rounded bottom corners and a shadow, stopping a little above the bottom of
        // the screen. Inside: a blurred copy of the screen kept aligned with the screen while the
        // sheet slides, a translucent tint over it, then the pages.
        sheet = new FrameLayout(ctx);
        sheet.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, -corner, v.getWidth(), v.getHeight(), corner);
            }
        });
        sheet.setClipToOutline(true);
        sheet.setElevation(Ui.dp(ctx, 18));
        sheetImage = blurImage();
        sheet.addView(sheetImage, new FrameLayout.LayoutParams(-1, sh));
        tint = new View(ctx);
        tint.setBackgroundColor(look.sheetSolid);
        sheet.addView(tint, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout pages = new FrameLayout(ctx);
        sheet.addView(pages, new FrameLayout.LayoutParams(-1, -1));
        pageViews.clear();
        if (look.is(Look.ANDROID)) {
            pageViews.add(new AndroidShadeView(ctx, this));
        } else if (look.is(Look.XIAOMI)) {
            pageViews.add(new NotificationCenterView(ctx, this));
            pageViews.add(new XiaomiControlView(ctx, this));
        } else {
            pageViews.add(new NotificationCenterView(ctx, this));
            pageViews.add(new ControlCenterView(ctx, this));
        }
        this.page = page;
        for (View pv : pageViews) pages.addView(pv, new FrameLayout.LayoutParams(-1, -1));

        View handle = new View(ctx);
        handle.setBackground(Ui.rect(look.handle, Ui.dp(ctx, 3)));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(Ui.dp(ctx, 134), Ui.dp(ctx, 5),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hl.bottomMargin = Ui.dp(ctx, 9);
        pages.addView(handle, hl);

        LinearLayout dl = new LinearLayout(ctx);
        dotRow = dl;
        dl.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        int ds = Ui.dp(ctx, 7);
        for (int i = 0; i < 2; i++) {
            dots[i] = new View(ctx);
            dots[i].setBackground(Ui.oval(look.dotOff));
            LinearLayout.LayoutParams d = new LinearLayout.LayoutParams(ds, ds);
            d.leftMargin = d.rightMargin = Ui.dp(ctx, 4);
            dl.addView(dots[i], d);
        }
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = Ui.dp(ctx, 26);
        pages.addView(dl, dlp);

        // Edit pencil at the bottom corner, shown on controls pages.
        editBtn = ControlCenterView.editButton(ctx, look);
        int eb = Ui.dp(ctx, 32);
        FrameLayout.LayoutParams ebl = new FrameLayout.LayoutParams(eb, eb, Gravity.BOTTOM | Gravity.END);
        ebl.setMarginEnd(Ui.dp(ctx, 18));
        ebl.bottomMargin = Ui.dp(ctx, 12);
        pages.addView(editBtn, ebl);

        // Thin status bar fixed on top, like the phone's own.
        statusBar = new StatusBarView(ctx, look);
        live(statusBar);
        sheet.addView(statusBar, new FrameLayout.LayoutParams(-1, barHeight(ctx), Gravity.TOP));

        root.addView(sheet, new FrameLayout.LayoutParams(-1, sheetH));
        pageOff = 0;
        layoutPages();
        setProgress(0f);
        trigger.bringToFront();
        built = true;
    }

    /** Height of the panel's status bar (the phone's own status bar height). */
    static int barHeight(Context c) {
        return Math.max(Ui.statusBarHeight(c), Ui.dp(c, 24));
    }

    void endSession() {
        endSession(true);
    }

    /**
     * Ends the session: empties the window (views only) and makes it untouchable again; with
     * rewarm the service builds fresh content for next time.
     */
    void endSession(boolean rewarm) {
        cancelAnim();
        if (pageAnim != null) pageAnim.cancel();
        boolean was = session || built;
        session = false;
        built = false;
        for (Live l : lives) l.pause();
        lives.clear();
        if (sheetImage != null) sheetImage.setImageDrawable(null);
        if (backImage != null) backImage.setImageDrawable(null);
        closing = false;
        progress = 0f;
        if (root != null) {
            // Remove the content but never the swipe zone: it may be handling the finger now.
            for (int i = root.getChildCount() - 1; i >= 0; i--) {
                if (root.getChildAt(i) != trigger) root.removeViewAt(i);
            }
        }
        pageViews.clear();
        interactive = false;
        if (attached) collapse();
        if (rewarm && was) svc.onSessionEnded();
    }

    /** Removes the window for good (service stopping, screen rotated). */
    void detach() {
        endSession(false);
        if (attached) {
            attached = false;
            try {
                wm.removeViewImmediate(root);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Makes the window touchable. Called only once the finger has let go (or the system took the
     * gesture), never during the pull. The window is never focusable; the Back key reaches us
     * through the accessibility key filter instead.
     */
    void makeInteractive() {
        if (!session || interactive) return;
        interactive = true;
        if ((lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            update();
        }
    }

    private void update() {
        try {
            wm.updateViewLayout(root, lp);
        } catch (Exception ignored) {
        }
    }

    boolean hasSession() {
        return session;
    }

    boolean isOpen() {
        return session && !closing && interactive;
    }

    /** True from the moment the sheet is visible until it starts closing (for the Back key). */
    boolean isShowing() {
        return session && !closing && progress > 0f;
    }

    /** A session that shows nothing and has nothing moving it. */
    boolean isIdleHidden() {
        return session && anim == null && progress <= 0.001f;
    }

    private void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        sheet.setTranslationY(-(1f - progress) * sheetH);
        sheetImage.setTranslationY((1f - progress) * sheetH);
        scrim.setAlpha(progress);
    }

    // ------------------------------------------------------------ open / close

    /** Finger is dy px below where the pull started; the sheet's bottom edge follows it. */
    void dragOpen(float dy) {
        if (!session || closing) return;
        cancelAnim();
        setProgress(dy / sheetH);
    }

    void releaseOpen(float vy) {
        if (!session || closing) return;
        float fling = Ui.dp(ctx, 300);
        if (vy > fling || (progress > 0.35f && vy > -fling)) {
            makeInteractive();
            animateTo(1f);
        } else {
            close();
        }
    }

    private void dragClose(float dy) {
        cancelAnim();
        setProgress(1f + dy / sheetH);
    }

    private void releaseClose(float vy) {
        if (vy < -Ui.dp(ctx, 300) || progress < 0.65f) close();
        else animateTo(1f);
    }

    /** The system shade took the swipe: cover the screen and finish the curtain smoothly. */
    void cover() {
        if (!session || closing) return;
        makeInteractive();
        backdrop.animate().cancel();
        backdrop.setAlpha(1f);
        if (anim == null || progress < 1f) animateTo(1f);
        backdrop.animate().alpha(0f).setStartDelay(700).setDuration(250).start();
    }

    void close() {
        if (!session || closing) return;
        closing = true;
        animateTo(0f);
    }

    private void cancelAnim() {
        if (anim != null) {
            anim.removeAllListeners();
            anim.cancel();
            anim = null;
        }
    }

    void animateTo(float target) {
        if (!session) return;
        cancelAnim();
        float from = progress;
        anim = ValueAnimator.ofFloat(from, target);
        // Smooth, iOS-like ease-out; long enough to read as a sheet sliding, not a jump.
        anim.setDuration((long) (240 + 280 * Math.abs(target - from)));
        anim.setInterpolator(new PathInterpolator(0.2f, 0.85f, 0.25f, 1f));
        anim.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                anim = null;
                if (target == 0f) endSession();
                else openedAt = SystemClock.uptimeMillis();
            }
        });
        anim.start();
    }

    private ImageView blurImage() {
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        return iv;
    }

    /** The screenshot taken on touch-down; shown blurred behind the translucent tint. */
    void setShot(Bitmap shot) {
        if (!session || shot == null) return;
        float r = Ui.dp(ctx, 40);
        for (ImageView iv : new ImageView[]{sheetImage, backImage}) {
            iv.setImageBitmap(shot);
            if (Build.VERSION.SDK_INT >= 31) {
                iv.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
            }
        }
        ValueAnimator fade = ValueAnimator.ofArgb(look.sheetSolid, look.sheet);
        fade.setDuration(150);
        View t = tint;
        fade.addUpdateListener(a -> t.setBackgroundColor((int) a.getAnimatedValue()));
        fade.start();
        backdrop.setBackgroundColor(0);
        backdrop.setForeground(new ColorDrawable(look.sheet));
    }

    // ------------------------------------------------------------ pages

    private void layoutPages() {
        for (int i = 0; i < pageViews.size(); i++) pageViews.get(i).setTranslationX(pageOff + i * sw);
        dotRow.setVisibility(pageViews.size() > 1 ? View.VISIBLE : View.GONE);
        if (editBtn != null) {
            boolean controls = pageViews.get(Math.max(0, Math.min(pageViews.size() - 1,
                    Math.round(-pageOff / sw)))) instanceof ControlsBase;
            editBtn.setVisibility(controls ? View.VISIBLE : View.GONE);
        }
        int near = -pageOff > sw / 2f ? CC : NC;
        for (int i = 0; i < 2; i++) {
            ((android.graphics.drawable.GradientDrawable) dots[i].getBackground())
                    .setColor(i == near ? look.dotOn : look.dotOff);
        }
    }

    private void dragPage(float dx) {
        if (pageAnim != null) pageAnim.cancel();
        float off = -page * sw + dx;
        if (off > 0) off *= 0.25f;
        else if (off < -sw) off = -sw + (off + sw) * 0.25f;
        pageOff = off;
        layoutPages();
    }

    private void releasePage(float dx, float vx) {
        float fling = Ui.dp(ctx, 400);
        int target = page;
        if (page == NC && (dx < -sw * 0.22f || vx < -fling)) target = CC;
        else if (page == CC && (dx > sw * 0.22f || vx > fling)) target = NC;
        if (target != page) Ui.haptic(root);
        animatePage(target);
    }

    private void animatePage(int target) {
        page = target;
        if (pageAnim != null) pageAnim.cancel();
        pageAnim = ValueAnimator.ofFloat(pageOff, -target * sw);
        pageAnim.setDuration(280);
        pageAnim.setInterpolator(new DecelerateInterpolator(2f));
        pageAnim.addUpdateListener(a -> {
            if (!session) return;
            pageOff = (float) a.getAnimatedValue();
            layoutPages();
        });
        pageAnim.start();
    }

    private boolean canDragClose(float y) {
        if (y > sheetH - Ui.dp(ctx, 70)) return true;
        ScrollView s = ((ShadePage) pageViews.get(page)).scroll();
        return !s.canScrollVertically(1);
    }

    /** Window root: tap-outside, swipe-up-to-close and sideways page swipes. */
    private final class Root extends FrameLayout {
        private static final int NONE = 0, PAGE = 1, CLOSE = 2;
        private final int slop;
        private float sx, sy;
        private int mode;
        private VelocityTracker vt;

        Root(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setClipChildren(false);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) close();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            if (closing) return true;
            if (!interactive) return super.dispatchTouchEvent(e);
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                mode = NONE;
                cardTouch = false;
                sx = e.getX();
                sy = e.getY();
            }
            if (vt != null) vt.addMovement(e);
            return super.dispatchTouchEvent(e);
        }

        /** Decides from the first real movement what this gesture is. */
        private void decide(MotionEvent e, boolean fromChild) {
            float dx = e.getX() - sx, dy = e.getY() - sy;
            if (pageViews.size() > 1 && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f
                    && !cardTouch) {
                mode = PAGE;
                sx = e.getX();
            } else if (dy < -slop && Math.abs(dy) > Math.abs(dx) * 1.2f
                    && (!fromChild || canDragClose(sy))) {
                mode = CLOSE;
                sy = e.getY();
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (!interactive) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && mode == NONE) decide(e, true);
            return mode != NONE;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!interactive) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (mode == NONE) decide(e, false);
                    if (mode == PAGE) dragPage(e.getX() - sx);
                    else if (mode == CLOSE) dragClose(e.getY() - sy);
                    return true;
                case MotionEvent.ACTION_UP:
                    vt.computeCurrentVelocity(1000);
                    if (mode == PAGE) releasePage(e.getX() - sx, vt.getXVelocity());
                    else if (mode == CLOSE) releaseClose(vt.getYVelocity());
                    else if (Math.hypot(e.getX() - sx, e.getY() - sy) < slop) close();
                    mode = NONE;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (mode == PAGE) animatePage(page);
                    else if (mode == CLOSE) animateTo(1f);
                    mode = NONE;
                    return true;
            }
            return true;
        }
    }
}
