package com.shadex.app;

import android.animation.LayoutTransition;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.provider.MediaStore;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * Notification Center page (iOS and Xiaomi styles): a clock header, the notification list, and on
 * iOS the flashlight and camera buttons at the bottom.
 */
final class NotificationCenterView extends FrameLayout implements ShadePage, Panel.Live {
    private final ScrollView scroll;
    private final Panel panel;
    private final Context c;
    private final Look look;
    private final TextView clock, date;
    private FrameLayout torchBtn;
    private ImageView torchIcon;
    private Sys.Torch torch;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            updateClock();
        }
    };

    NotificationCenterView(Context c, Panel p) {
        super(c);
        this.c = c;
        this.panel = p;
        this.look = p.look;
        p.live(this);
        boolean hyper = look.is(Look.XIAOMI);

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        int side = Math.max(Ui.dp(c, 12), (p.sw - Ui.dp(c, 520)) / 2);
        // The list scrolls under nothing: it stops at the panel's status bar.
        scroll.setPadding(0, Panel.barHeight(c), 0, 0);
        scroll.setClipToPadding(true);
        list.setPadding(side, Ui.dp(c, hyper ? 18 : 26), side,
                Ui.navBarHeight(c) + Ui.dp(c, 120));
        list.setOnClickListener(v -> panel.close());
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, -1));

        if (hyper) {
            // HyperOS: big light clock on the start side, date under it.
            clock = Ui.text(c, 64, look.text, 300);
            clock.setIncludeFontPadding(false);
            date = Ui.text(c, 15, look.sub, 500);
            int pad = Ui.dp(c, 10);
            clock.setPadding(pad, 0, pad, 0);
            date.setPadding(pad, 0, pad, 0);
            list.addView(clock, mw(0, 0));
            list.addView(date, mw(Ui.dp(c, 2), 0));
        } else {
            date = Ui.text(c, 19, look.text, 600);
            date.setGravity(Gravity.CENTER);
            list.addView(date, mw(0, 0));
            clock = Ui.text(c, 84, look.text, 700);
            clock.setGravity(Gravity.CENTER);
            clock.setLetterSpacing(-0.02f);
            clock.setIncludeFontPadding(false);
            list.addView(clock, mw(Ui.dp(c, 2), 0));
        }
        updateClock();

        list.addView(new NotifList(c, p, scroll), mw(Ui.dp(c, hyper ? 22 : 30), 0));
        LayoutTransition lt = new LayoutTransition();
        lt.enableTransitionType(LayoutTransition.CHANGING);
        lt.setAnimator(LayoutTransition.APPEARING, null);
        lt.setAnimator(LayoutTransition.DISAPPEARING, null);
        lt.setDuration(300);
        list.setLayoutTransition(lt);

        if (!hyper) addBottomButtons(p);
    }

    private void addBottomButtons(Panel p) {
        KeyguardManager km = c.getSystemService(KeyguardManager.class);
        boolean locked = km != null && km.isKeyguardLocked();
        int nav = Ui.navBarHeight(c);
        int btn = Ui.dp(c, 50);
        torchBtn = roundButton(R.drawable.ic_flash);
        torchIcon = (ImageView) torchBtn.getChildAt(0);
        LayoutParams tl = new LayoutParams(btn, btn, Gravity.BOTTOM | Gravity.LEFT);
        tl.leftMargin = Ui.dp(c, 46);
        tl.bottomMargin = nav + Ui.dp(c, 40);
        addView(torchBtn, tl);
        Ui.onTap(torchBtn, () -> {
            if (torch != null) torch.toggle();
        });
        FrameLayout cam = roundButton(R.drawable.ic_camera);
        LayoutParams cl = new LayoutParams(btn, btn, Gravity.BOTTOM | Gravity.RIGHT);
        cl.rightMargin = Ui.dp(c, 46);
        cl.bottomMargin = nav + Ui.dp(c, 40);
        addView(cam, cl);
        Ui.onTap(cam, () -> p.svc.launch(new Intent(locked
                ? MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
                : MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)));
    }

    private LinearLayout.LayoutParams mw(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        return lp;
    }

    @Override
    public ScrollView scroll() {
        return scroll;
    }

    private FrameLayout roundButton(int res) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable bg = Ui.oval(look.roundBtn);
        if (look.stroke != 0) bg.setStroke(Math.max(1, Ui.dp(c, 0.75f)), look.stroke);
        f.setBackground(bg);
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setImageTintList(ColorStateList.valueOf(look.roundBtnIcon));
        int s = Ui.dp(c, 24);
        f.addView(i, new LayoutParams(s, s, Gravity.CENTER));
        f.setClickable(true);
        Ui.press(f);
        return f;
    }

    private void updateTorch() {
        if (torchBtn == null) return;
        boolean on = torch != null && torch.on;
        ((GradientDrawable) torchBtn.getBackground()).setColor(on ? look.activeBg : look.roundBtn);
        torchIcon.setImageTintList(ColorStateList.valueOf(on ? look.activeIcon : look.roundBtnIcon));
    }

    private boolean live;

    /** Starts listening to the system: only while the panel is open. */
    @Override
    public void resume() {
        if (live) return;
        live = true;
        c.registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        updateClock();
        if (torchBtn != null) torch = new Sys.Torch(c, this::updateTorch);
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
            c.unregisterReceiver(tick);
        } catch (Exception ignored) {
        }
        if (torch != null) torch.release();
        torch = null;
    }

    private void updateClock() {
        long now = System.currentTimeMillis();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(c) ? "H:mm" : "h:mm", now));
        date.setText(DateFormat.format(
                DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), now));
    }
}
