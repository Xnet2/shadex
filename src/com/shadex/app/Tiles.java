package com.shadex.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Control Center building blocks, sized from the grid cell like iOS modules. */
final class Tiles {
    private Tiles() {}

    static final int WHITE = 0xFFFFFFFF;
    static final int ORANGE = 0xFFFF9500;
    static final int GREEN = 0xFF34C759;
    static final int BLUE = 0xFF007AFF;
    static final int PURPLE = 0xFF5E5CE6;
    static final int RED = 0xFFFF3B30;
    static final int YELLOW = 0xFFFFCC00;

    interface Module {
        void onCell(int cell);
    }

    static float radius(int cell) {
        return cell * 0.27f;
    }

    /** Rounded module background with a soft shadow in the light theme. */
    static GradientDrawable moduleBg(View v, Look look) {
        GradientDrawable bg = Ui.rect(look.module, 0);
        bg.setStroke(Math.max(1, Ui.dp(v.getContext(), 0.75f)), look.stroke);
        v.setBackground(bg);
        if (look.moduleElevation > 0) {
            v.setElevation(look.moduleElevation);
            v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        }
        return bg;
    }

    /** 1x1 square button. Off: plain module. On: filled with its accent colour (dark: white). */
    static final class Square extends FrameLayout implements Module {
        final ImageView icon;
        private final Look look;
        private final int accent;
        private final GradientDrawable bg;
        private boolean raw;
        boolean active;

        /** Shows another app's own icon, untinted and a little larger. */
        void setRawIcon(Drawable d) {
            raw = true;
            icon.setImageDrawable(d);
            icon.setImageTintList(null);
        }

        Square(Context c, Look look, int res, int accent) {
            super(c);
            this.look = look;
            this.accent = accent;
            bg = moduleBg(this, look);
            icon = new ImageView(c);
            icon.setImageResource(res);
            addView(icon, new LayoutParams(0, 0, Gravity.CENTER));
            setClickable(true);
            Ui.press(this);
            apply();
        }

        @Override
        public void onCell(int cell) {
            bg.setCornerRadius(radius(cell));
            LayoutParams lp = (LayoutParams) icon.getLayoutParams();
            lp.width = lp.height = (int) (cell * (raw ? 0.5f : 0.36f));
        }

        void setActive(boolean a) {
            if (a == active) return;
            active = a;
            apply();
        }

        private void apply() {
            if (raw) {
                bg.setColor(look.module);
                return;
            }
            bg.setColor(active ? look.activeBg : look.module);
            icon.setImageTintList(ColorStateList.valueOf(active ? look.activeIcon : look.icon));
        }
    }

    /** Round toggle used inside the connectivity platter. */
    static final class Circle extends FrameLayout {
        private final ImageView icon;
        private final Look look;
        private final int accent;
        private final GradientDrawable bg;
        boolean active;

        Circle(Context c, Look look, int res, int accent) {
            super(c);
            this.look = look;
            this.accent = accent;
            bg = Ui.oval(look.circleOff);
            bg.setStroke(Math.max(1, Ui.dp(c, 0.75f)), look.stroke);
            setBackground(bg);
            icon = new ImageView(c);
            icon.setImageResource(res);
            icon.setImageTintList(ColorStateList.valueOf(look.circleIcon));
            addView(icon, new LayoutParams(0, 0, Gravity.CENTER));
            badge = Ui.line(c, 10, look.circleIcon, 700);
            badge.setVisibility(GONE);
            addView(badge, new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
            setClickable(true);
            Ui.press(this);
        }

        private final TextView badge;
        private int iconRes = -1;

        @Override
        protected void onMeasure(int ws, int hs) {
            int s = MeasureSpec.getSize(ws);
            boolean b = badge.getVisibility() == VISIBLE;
            LayoutParams lp = (LayoutParams) icon.getLayoutParams();
            lp.width = lp.height = (int) (s * (b ? 0.36f : 0.42f));
            icon.setTranslationY(b ? -s * 0.08f : 0);
            ((LayoutParams) badge.getLayoutParams()).bottomMargin = (int) (s * 0.12f);
            super.onMeasure(ws, hs);
        }

        /** Another app's own icon, untinted. */
        void setRawIcon(Drawable d) {
            iconRes = -2;
            icon.setImageDrawable(d);
            icon.setImageTintList(null);
        }

        void setIcon(int res) {
            if (res == iconRes || iconRes == -2) return;
            iconRes = res;
            icon.setImageResource(res);
        }

        /** Small text under the glyph, e.g. a connected device's battery level; null hides it. */
        void setBadge(String text) {
            boolean show = text != null;
            if (show) badge.setText(text);
            if ((badge.getVisibility() == VISIBLE) != show) {
                badge.setVisibility(show ? VISIBLE : GONE);
                requestLayout();
            }
        }

        void setActive(boolean a) {
            if (a == active) return;
            active = a;
            bg.setColor(a ? look.activeBg : look.circleOff);
            int tint = a ? look.activeIcon : look.circleIcon;
            icon.setImageTintList(ColorStateList.valueOf(tint));
            badge.setTextColor(tint);
        }
    }

    /** 2x2 platter holding four Circles (airplane, cellular, Wi-Fi, Bluetooth). */
    static final class Connectivity extends ViewGroup implements Module {
        private final GradientDrawable bg;
        private int d;

        Connectivity(Context c, Look look, View... kids) {
            super(c);
            bg = moduleBg(this, look);
            for (View k : kids) addView(k);
            setClickable(true);
        }

        @Override
        public void onCell(int cell) {
            bg.setCornerRadius(radius(cell));
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
            d = (int) (Math.min(w, h) / 2f * 0.8f);
            int spec = MeasureSpec.makeMeasureSpec(d, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(spec, spec);
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int qw = (r - l) / 2, qh = (b - t) / 2;
            for (int i = 0; i < getChildCount(); i++) {
                int cx = qw * (i % 2) + qw / 2, cy = qh * (i / 2) + qh / 2;
                getChildAt(i).layout(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2);
            }
        }
    }

    /** 2x1 module with a round glyph and a label (Focus / Do Not Disturb). */
    static final class Wide extends LinearLayout implements Module {
        private final GradientDrawable bg, circleBg;
        private final Look look;
        private final FrameLayout circle;
        private final ImageView icon;
        private final TextView label, sub;
        private final int accent;
        boolean active;

        Wide(Context c, Look look, int res, String text, int accent) {
            super(c);
            this.look = look;
            this.accent = accent;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            bg = moduleBg(this, look);
            circle = new FrameLayout(c);
            circleBg = Ui.oval(look.circleOff);
            circle.setBackground(circleBg);
            icon = new ImageView(c);
            icon.setImageResource(res);
            icon.setImageTintList(ColorStateList.valueOf(look.circleIcon));
            circle.addView(icon, new FrameLayout.LayoutParams(0, 0, Gravity.CENTER));
            addView(circle, new LayoutParams(0, 0));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            label = Ui.line(c, 15, look.text, 600);
            label.setText(text);
            sub = Ui.line(c, 12, look.sub, 400);
            texts.addView(label);
            texts.addView(sub);
            LayoutParams tl = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            tl.setMarginStart(Ui.dp(c, 10));
            addView(texts, tl);
            setClickable(true);
            Ui.press(this);
        }

        @Override
        public void onCell(int cell) {
            bg.setCornerRadius(radius(cell));
            int pad = (int) (cell * 0.16f);
            setPadding(pad, 0, pad, 0);
            LayoutParams cl = (LayoutParams) circle.getLayoutParams();
            cl.width = cl.height = (int) (cell * 0.5f);
            FrameLayout.LayoutParams il = (FrameLayout.LayoutParams) icon.getLayoutParams();
            il.width = il.height = (int) (cell * 0.26f);
        }

        void setState(boolean a, String subText) {
            active = a;
            circleBg.setColor(a ? look.activeBg : look.circleOff);
            icon.setImageTintList(ColorStateList.valueOf(a ? look.activeIcon : look.circleIcon));
            sub.setText(subText);
        }
    }

    /** Tall slider: track, fill from the bottom, glyph that changes colour under the fill. */
    static final class VSlider extends View implements Module {
        interface OnChange {
            void onChange(float value);
        }

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private final Drawable icon;
        private final Look look;
        private final int slop;
        private float radius, startY, startV, value;
        private boolean moved;
        boolean dragging;
        OnChange listener;
        Runnable onHold;
        private final Runnable holdCheck = () -> {
            if (!moved && onHold != null) {
                Ui.haptic(this);
                onHold.run();
            }
        };

        VSlider(Context c, Look look, int res) {
            super(c);
            this.look = look;
            track.setColor(look.track);
            fill.setColor(look.fill);
            icon = c.getDrawable(res).mutate();
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            if (look.moduleElevation > 0) {
                setElevation(look.moduleElevation);
                setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                    }
                });
            }
        }

        @Override
        public void onCell(int cell) {
            radius = radius(cell);
        }

        void setValue(float v) {
            value = Math.max(0, Math.min(1, v));
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            invalidateOutline();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.save();
            c.clipPath(clip);
            c.drawRect(rect, track);
            float top = h * (1f - value);
            c.drawRect(0, top, w, h, fill);
            c.restore();
            int s = (int) (w * 0.32f);
            int left = (w - s) / 2, iconTop = h - (int) (w * 0.24f) - s;
            icon.setTint(top < iconTop + s / 2f ? look.iconOnFill : look.iconOnTrack);
            icon.setBounds(left, iconTop, left + s, iconTop + s);
            icon.draw(c);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    startY = e.getY();
                    startV = value;
                    moved = false;
                    dragging = true;
                    animate().scaleX(1.04f).scaleY(1.02f).setDuration(140).start();
                    postDelayed(holdCheck, 550);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getY() - startY;
                    if (!moved && Math.abs(dy) < slop) return true;
                    moved = true;
                    float v = Math.max(0, Math.min(1, startV - dy / getHeight()));
                    if (v != value) {
                        if ((v == 0 || v == 1) && value != v) Ui.haptic(this);
                        value = v;
                        invalidate();
                        if (listener != null) listener.onChange(v);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(holdCheck);
                    dragging = false;
                    animate().scaleX(1f).scaleY(1f).setDuration(200).start();
                    return true;
            }
            return true;
        }
    }

    /** Now Playing: 2x2 (iOS) or a wide one-row card (Android, Xiaomi). */
    static final class Media extends FrameLayout implements Module {
        private final GradientDrawable bg;
        private final Look look;
        private final boolean wide;
        final ImageView art, prev, play, next;
        final TextView title, artist;
        private final LinearLayout col;

        Media(Context c, Look look) {
            this(c, look, false);
        }

        Media(Context c, Look look, boolean wide) {
            super(c);
            this.look = look;
            this.wide = wide;
            bg = moduleBg(this, look);
            setClickable(true);
            col = new LinearLayout(c);
            col.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_VERTICAL);
            addView(col, new LayoutParams(-1, -1));

            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            art = new ImageView(c);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackground(Ui.rect(look.circleOff, Ui.dp(c, wide ? 10 : 8)));
            art.setClipToOutline(true);
            int a = Ui.dp(c, wide ? 48 : 40);
            head.addView(art, new LinearLayout.LayoutParams(a, a));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = Ui.line(c, wide ? 15 : 14, look.text, 600);
            artist = Ui.line(c, 13, look.sub, 400);
            texts.addView(title);
            texts.addView(artist);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.setMarginStart(Ui.dp(c, 12));
            head.addView(texts, tl);
            col.addView(head, wide ? new LinearLayout.LayoutParams(0, -2, 1f)
                    : new LinearLayout.LayoutParams(-1, -2));

            if (!wide) col.addView(new View(c), new LinearLayout.LayoutParams(-1, 0, 1f));

            LinearLayout controls = new LinearLayout(c);
            controls.setLayoutDirection(LAYOUT_DIRECTION_LTR);
            controls.setGravity(Gravity.CENTER);
            prev = control(c, R.drawable.ic_prev, controls);
            play = control(c, R.drawable.ic_play, controls);
            next = control(c, R.drawable.ic_next, controls);
            col.addView(controls, wide ? new LinearLayout.LayoutParams(Ui.dp(c, 132), Ui.dp(c, 44))
                    : new LinearLayout.LayoutParams(-1, Ui.dp(c, 48)));
            if (wide) {
                int p = Ui.dp(c, 14);
                col.setPadding(p, p, Ui.dp(c, 8), p);
            }
        }

        private ImageView control(Context c, int res, LinearLayout parent) {
            ImageView v = new ImageView(c);
            v.setImageResource(res);
            v.setImageTintList(ColorStateList.valueOf(look.text));
            int p = Ui.dp(c, 9);
            v.setPadding(p, p, p, p);
            v.setClickable(true);
            Ui.press(v);
            parent.addView(v, new LinearLayout.LayoutParams(0, -1, 1f));
            return v;
        }

        /** Rounded corners for a wide card that is not in a grid. */
        void setCorner(float r) {
            bg.setCornerRadius(r);
        }

        @Override
        public void onCell(int cell) {
            bg.setCornerRadius(radius(cell));
            if (wide) return;
            int p = (int) (cell * 0.17f);
            col.setPadding(p, p, p, (int) (p * 0.6f));
        }

        void show(CharSequence t, CharSequence a, Bitmap b, boolean playing) {
            title.setText(t == null || t.length() == 0 ? "לא מתנגן" : t);
            artist.setText(a == null ? "" : a);
            artist.setVisibility(a == null || a.length() == 0 ? GONE : VISIBLE);
            if (b != null) {
                art.setImageBitmap(b);
                art.setImageTintList(null);
                art.setPadding(0, 0, 0, 0);
            } else {
                art.setImageResource(R.drawable.ic_note);
                art.setImageTintList(ColorStateList.valueOf(look.sub));
                int p = Ui.dp(getContext(), 9);
                art.setPadding(p, p, p, p);
            }
            play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    /**
     * Large tile with a glyph, a label and a state line: Xiaomi's big Wi-Fi / data tiles and
     * Android's two-column Quick Settings tiles.
     */
    static final class BigTile extends LinearLayout implements Module {
        private final Look look;
        private final GradientDrawable bg;
        private final FrameLayout iconBox;
        private final GradientDrawable iconBg;
        private final ImageView icon;
        private final TextView label, sub;
        private final float cornerFactor;
        private final boolean circleIcon;
        private boolean raw;
        boolean active;

        /** cornerFactor: corner radius as a fraction of the height. circleIcon: glyph in a disc. */
        BigTile(Context c, Look look, int res, CharSequence text, float cornerFactor,
                boolean circleIcon) {
            super(c);
            this.look = look;
            this.cornerFactor = cornerFactor;
            this.circleIcon = circleIcon;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            bg = moduleBg(this, look);
            iconBox = new FrameLayout(c);
            iconBg = Ui.oval(circleIcon ? look.circleOff : 0);
            iconBox.setBackground(iconBg);
            icon = new ImageView(c);
            icon.setImageResource(res);
            int is = Ui.dp(c, circleIcon ? 22 : 24);
            iconBox.addView(icon, new FrameLayout.LayoutParams(is, is, Gravity.CENTER));
            int box = Ui.dp(c, circleIcon ? 40 : 28);
            addView(iconBox, new LayoutParams(box, box));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            label = Ui.line(c, 15, look.text, 600);
            label.setText(text);
            sub = Ui.line(c, 12.5f, look.sub, 400);
            texts.addView(label);
            texts.addView(sub);
            LayoutParams tl = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            tl.setMarginStart(Ui.dp(c, 12));
            addView(texts, tl);
            int p = Ui.dp(c, 14);
            setPadding(p, 0, p, 0);
            setClickable(true);
            Ui.press(this);
            apply();
        }

        void setRawIcon(Drawable d) {
            raw = true;
            icon.setImageDrawable(d);
            icon.setImageTintList(null);
        }

        void setIcon(int res) {
            if (!raw) icon.setImageResource(res);
        }

        @Override
        public void onCell(int cell) {
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            bg.setCornerRadius(h * cornerFactor);
        }

        void setState(boolean a, String subText) {
            active = a;
            sub.setText(subText);
            sub.setVisibility(TextUtils.isEmpty(subText) ? GONE : VISIBLE);
            apply();
        }

        private void apply() {
            if (circleIcon) {
                // Xiaomi: the whole tile turns blue, the disc stays white.
                bg.setColor(active ? look.activeBg : look.module);
                iconBg.setColor(active ? 0x33FFFFFF : look.circleOff);
            } else {
                bg.setColor(active ? look.activeBg : look.module);
            }
            int fg = active ? look.activeIcon : look.text;
            if (!raw) icon.setImageTintList(ColorStateList.valueOf(active ? look.activeIcon : look.icon));
            label.setTextColor(fg);
            sub.setTextColor(active ? (look.activeIcon & 0x00FFFFFF) | 0xCC000000 : look.sub);
        }
    }

    /** Xiaomi-style round toggle with its name underneath. */
    static final class RoundToggle extends LinearLayout implements Module {
        final Circle circle;
        private final TextView label;

        RoundToggle(Context c, Look look, int res, CharSequence text) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            circle = new Circle(c, look, res, 0);
            addView(circle, new LayoutParams(0, 0));
            label = Ui.line(c, 11.5f, look.text, 500);
            label.setText(text);
            label.setGravity(Gravity.CENTER);
            LayoutParams ll = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            ll.topMargin = Ui.dp(c, 5);
            addView(label, ll);
        }

        @Override
        public void onCell(int cell) {
            LayoutParams lp = (LayoutParams) circle.getLayoutParams();
            lp.width = lp.height = (int) (cell * 0.66f);
        }

        void setLabel(CharSequence t) {
            label.setText(t);
        }
    }

    /** Horizontal slider (Android brightness): thick pill, fill from the start side. */
    static final class HSlider extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF(), fr = new RectF();
        private final Drawable icon;
        private final Look look;
        private final int slop;
        private float startX, startV, value;
        private boolean moved;
        boolean dragging;
        VSlider.OnChange listener;
        Runnable onHold;
        private final Runnable holdCheck = () -> {
            if (!moved && onHold != null) {
                Ui.haptic(this);
                onHold.run();
            }
        };

        HSlider(Context c, Look look, int res) {
            super(c);
            this.look = look;
            track.setColor(look.track);
            fill.setColor(look.fill);
            icon = c.getDrawable(res).mutate();
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        void setValue(float v) {
            value = Math.max(0, Math.min(1, v));
            invalidate();
        }

        private boolean rtl() {
            return getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float r = h / 2f;
            rect.set(0, 0, w, h);
            c.drawRoundRect(rect, r, r, track);
            // The fill is never narrower than the circle around the icon.
            float fw = h + (w - h) * value;
            if (rtl()) fr.set(w - fw, 0, w, h);
            else fr.set(0, 0, fw, h);
            c.drawRoundRect(fr, r, r, fill);
            int s = (int) (h * 0.42f);
            int left = rtl() ? (int) (w - r - s / 2f) : (int) (r - s / 2f);
            int top = (h - s) / 2;
            icon.setTint(look.iconOnFill);
            icon.setBounds(left, top, left + s, top + s);
            icon.draw(c);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    startX = e.getX();
                    startV = value;
                    moved = false;
                    dragging = true;
                    postDelayed(holdCheck, 550);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - startX;
                    if (!moved && Math.abs(dx) < slop) return true;
                    moved = true;
                    float d = (rtl() ? -dx : dx) / Math.max(1, getWidth() - getHeight());
                    float v = Math.max(0, Math.min(1, startV + d));
                    if (v != value) {
                        if ((v == 0 || v == 1) && value != v) Ui.haptic(this);
                        value = v;
                        invalidate();
                        if (listener != null) listener.onChange(v);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(holdCheck);
                    dragging = false;
                    return true;
            }
            return true;
        }
    }
}
