package com.shadex.app;

import android.animation.Animator;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The live notification list, styled per Look (iOS / Android / Xiaomi cards).
 *
 * Updated in place (not rebuilt): a new notification slides in, a dismissed one slides out, and
 * expanding a card animates its height. Each item is a "slot" keyed by a tag; a slot whose content
 * changed is refilled where it stands.
 */
final class NotifList extends LinearLayout implements Panel.Live {
    private final Panel panel;
    private final ShadeService svc;
    private final Context c;
    private final PackageManager pm;
    private final Look look;
    private final ScrollView scroll;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final boolean material, hyper;
    private final Set<String> expandedGroups = new HashSet<>();
    private final Set<String> expandedCards = new HashSet<>();
    private final Set<String> silent = new HashSet<>();
    private final Map<String, String> signatures = new HashMap<>();
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Map<String, CharSequence> labels = new HashMap<>();
    private boolean clearArmed;

    private final Runnable refresher = this::refresh;
    private final Runnable onChange = () -> {
        h.removeCallbacks(refresher);
        h.postDelayed(refresher, 120);
    };

    NotifList(Context c, Panel p, ScrollView scroll) {
        super(c);
        this.c = c;
        this.panel = p;
        this.svc = p.svc;
        this.pm = c.getPackageManager();
        this.look = p.look;
        this.scroll = scroll;
        material = look.is(Look.ANDROID);
        hyper = look.is(Look.XIAOMI);
        setOrientation(VERTICAL);
        setOnClickListener(v -> panel.close());
        p.live(this);
        refresh();
        // Animate every later change (not the first fill).
        setLayoutTransition(transition());
    }

    private LayoutTransition transition() {
        LayoutTransition lt = new LayoutTransition();
        lt.enableTransitionType(LayoutTransition.CHANGING);
        float lift = -Ui.dp(c, 28);
        Animator in = ObjectAnimator.ofPropertyValuesHolder((Object) null,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, lift, 0f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.94f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.94f, 1f));
        Animator out = ObjectAnimator.ofPropertyValuesHolder((Object) null,
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.94f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.94f));
        lt.setAnimator(LayoutTransition.APPEARING, in);
        lt.setAnimator(LayoutTransition.DISAPPEARING, out);
        lt.setDuration(LayoutTransition.APPEARING, 380);
        lt.setDuration(LayoutTransition.DISAPPEARING, 220);
        lt.setDuration(LayoutTransition.CHANGE_APPEARING, 380);
        lt.setDuration(LayoutTransition.CHANGE_DISAPPEARING, 300);
        lt.setDuration(LayoutTransition.CHANGING, 300);
        lt.setStartDelay(LayoutTransition.APPEARING, 60);
        lt.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 120);
        PathInterpolator ease = new PathInterpolator(0.2f, 0.85f, 0.25f, 1f);
        lt.setInterpolator(LayoutTransition.APPEARING, ease);
        lt.setInterpolator(LayoutTransition.CHANGE_APPEARING, ease);
        lt.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, ease);
        lt.setInterpolator(LayoutTransition.CHANGING, ease);
        return lt;
    }

    private boolean live;

    /** Starts listening to the system: only while the panel is open. */
    @Override
    public void resume() {
        if (live) return;
        live = true;
        NotifService.listeners.add(onChange);
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
        NotifService.listeners.remove(onChange);
        h.removeCallbacksAndMessages(null);
    }

    private LinearLayout.LayoutParams mw(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        return lp;
    }

    private float radius() {
        return Ui.dp(c, material ? 26 : hyper ? 20 : 24);
    }

    // ---------------------------------------------------------------- incremental list

    private static final class Entry {
        final String tag, sig;
        final Supplier<View> make;

        Entry(String tag, String sig, Supplier<View> make) {
            this.tag = tag;
            this.sig = sig;
            this.make = make;
        }
    }

    void refresh() {
        List<Entry> want = new ArrayList<>();
        NotifService s = NotifService.instance;
        if (s == null) {
            want.add(new Entry("noaccess", "1", this::noAccessView));
        } else {
            List<StatusBarNotification> all = collect(s);
            if (all.isEmpty()) {
                want.add(new Entry("empty", "1", this::emptyView));
            } else {
                List<StatusBarNotification> loud = new ArrayList<>(), quiet = new ArrayList<>();
                for (StatusBarNotification sbn : all) {
                    (silent.contains(sbn.getKey()) ? quiet : loud).add(sbn);
                }
                want.add(new Entry("sec:main", "1", this::mainHeader));
                addGroups(want, loud, "");
                if (!quiet.isEmpty()) {
                    want.add(new Entry("sec:quiet", "1", this::quietHeader));
                    addGroups(want, quiet, ":q");
                }
            }
        }
        apply(want);
    }

    private void addGroups(List<Entry> want, List<StatusBarNotification> list, String suffix) {
        LinkedHashMap<String, List<StatusBarNotification>> groups = new LinkedHashMap<>();
        for (StatusBarNotification sbn : list) {
            // iOS stacks an app's notifications; Android and Xiaomi list every one on its own.
            String key = (material || hyper ? sbn.getKey()
                    : sbn.getPackageName() + "|" + sbn.getUser().hashCode()) + suffix;
            List<StatusBarNotification> g = groups.get(key);
            if (g == null) groups.put(key, g = new ArrayList<>());
            g.add(sbn);
        }
        for (Map.Entry<String, List<StatusBarNotification>> e : groups.entrySet()) {
            String key = e.getKey();
            List<StatusBarNotification> g = e.getValue();
            StringBuilder sig = new StringBuilder(expandedGroups.contains(key) ? "E" : "c");
            for (StatusBarNotification sbn : g) {
                sig.append(sbn.getKey()).append('@').append(sbn.getPostTime())
                        .append(expandedCards.contains(sbn.getKey()) ? '+' : '-');
            }
            want.add(new Entry("g:" + key, sig.toString(), () -> group(key, g)));
        }
    }

    /** Brings the slots in line with `want`: removes, inserts and refills only what changed. */
    private void apply(List<Entry> want) {
        Set<String> tags = new HashSet<>();
        for (Entry e : want) tags.add(e.tag);
        for (int i = getChildCount() - 1; i >= 0; i--) {
            Object t = getChildAt(i).getTag();
            if (!tags.contains(t)) {
                removeViewAt(i);
                signatures.remove(t);
            }
        }
        for (int i = 0; i < want.size(); i++) {
            Entry e = want.get(i);
            FrameLayout slot = null;
            for (int j = 0; j < getChildCount(); j++) {
                if (e.tag.equals(getChildAt(j).getTag())) {
                    slot = (FrameLayout) getChildAt(j);
                    break;
                }
            }
            if (slot != null && indexOfChild(slot) != i) {
                // Moved (e.g. an app got a newer notification): leave its old place, enter anew.
                removeView(slot);
                slot = null;
            }
            if (slot == null) {
                slot = new FrameLayout(c);
                slot.setTag(e.tag);
                slot.addView(e.make.get());
                addView(slot, Math.min(i, getChildCount()), mw(0, Ui.dp(c, material ? 6 : 10)));
                signatures.put(e.tag, e.sig);
            } else if (!e.sig.equals(signatures.get(e.tag))) {
                slot.removeAllViews();
                View v = e.make.get();
                slot.addView(v);
                v.setAlpha(0.4f);
                v.animate().alpha(1f).setDuration(220).start();
                signatures.put(e.tag, e.sig);
            }
        }
    }

    private View noAccessView() {
        TextView t = Ui.text(c, 15, look.cardText, 400);
        t.setText("כדי לראות כאן התראות צריך לאשר 'גישה להתראות' ל-ShadeX. הקש כאן.");
        t.setBackground(cardBg());
        int pd = Ui.dp(c, 16);
        t.setPadding(pd, pd, pd, pd);
        Ui.onTap(t, () -> svc.launch(new Intent(c, MainActivity.class)));
        return t;
    }

    private View emptyView() {
        TextView t = Ui.text(c, 15, look.sub, 500);
        t.setText("אין התראות חדשות");
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(c, 24), 0, Ui.dp(c, 24));
        return t;
    }

    private View mainHeader() {
        LinearLayout sec = new LinearLayout(c);
        sec.setGravity(Gravity.CENTER_VERTICAL);
        int sp = Ui.dp(c, 8);
        sec.setPadding(sp, 0, sp, 0);
        TextView label = material ? Ui.line(c, 14, look.sub, 600) : Ui.line(c, hyper ? 17 : 21, look.text, 700);
        label.setText(material || hyper ? "התראות" : "מרכז ההתראות");
        sec.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        boolean words = material || hyper;
        TextView clear = pill(clearArmed || words ? "נקה הכול" : "✕");
        Ui.onTap(clear, () -> {
            if (!clearArmed && !words) {
                clearArmed = true;
                clear.setText("נקה הכול");
                h.postDelayed(() -> {
                    clearArmed = false;
                    clear.setText("✕");
                }, 3000);
            } else if (NotifService.instance != null) {
                try {
                    NotifService.instance.cancelAllNotifications();
                } catch (Exception ignored) {
                }
            }
        });
        sec.addView(clear);
        return sec;
    }

    private View quietHeader() {
        TextView q = material ? Ui.line(c, 14, look.sub, 600) : Ui.line(c, 17, look.text, 700);
        q.setText("התראות שקטות");
        q.setPadding(Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8), 0);
        return q;
    }

    private GradientDrawable cardBg() {
        GradientDrawable g = Ui.rect(look.card, radius());
        if (look.stroke != 0) g.setStroke(Math.max(1, Ui.dp(c, 0.75f)), look.stroke);
        return g;
    }

    private TextView pill(String text) {
        TextView t = Ui.line(c, 13, look.cardText, 600);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(c, 30));
        t.setMinHeight(Ui.dp(c, 30));
        t.setPadding(Ui.dp(c, 12), Ui.dp(c, 5), Ui.dp(c, 12), Ui.dp(c, 5));
        GradientDrawable bg = Ui.rect(material ? look.pill : look.card, Ui.dp(c, 15));
        if (look.stroke != 0) bg.setStroke(Math.max(1, Ui.dp(c, 0.75f)), look.stroke);
        t.setBackground(bg);
        Ui.press(t);
        return t;
    }

    // ---------------------------------------------------------------- collecting

    private List<StatusBarNotification> collect(NotifService s) {
        silent.clear();
        List<StatusBarNotification> out = new ArrayList<>();
        StatusBarNotification[] arr;
        NotificationListenerService.RankingMap rm = null;
        try {
            arr = s.getActiveNotifications();
            rm = s.getCurrentRanking();
        } catch (Throwable t) {
            return out;
        }
        if (arr == null) return out;
        Set<String> groupsWithChildren = new HashSet<>();
        for (StatusBarNotification sbn : arr) {
            if ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0) {
                groupsWithChildren.add(sbn.getGroupKey());
            }
        }
        NotificationListenerService.Ranking r = new NotificationListenerService.Ranking();
        for (StatusBarNotification sbn : arr) {
            Notification n = sbn.getNotification();
            if (sbn.getPackageName().equals(c.getPackageName())) continue;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0
                    && groupsWithChildren.contains(sbn.getGroupKey())) continue;
            if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) continue;
            if (rm != null && rm.getRanking(sbn.getKey(), r)
                    && r.getImportance() <= NotificationManager.IMPORTANCE_LOW) {
                silent.add(sbn.getKey());
            }
            if (TextUtils.isEmpty(title(n)) && TextUtils.isEmpty(body(n))) continue;
            out.add(sbn);
        }
        out.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        return out;
    }

    // ---------------------------------------------------------------- groups and cards

    private View group(String key, List<StatusBarNotification> g) {
        int n = g.size();
        if (n > 1 && expandedGroups.contains(key)) {
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            LinearLayout hdr = new LinearLayout(c);
            hdr.setGravity(Gravity.CENTER_VERTICAL);
            int sp = Ui.dp(c, 8);
            hdr.setPadding(sp, 0, sp, 0);
            TextView name = Ui.line(c, material ? 15 : 19, look.text, 700);
            name.setText(label(g.get(0).getPackageName()));
            hdr.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView less = pill("הצג פחות");
            Ui.onTap(less, () -> {
                expandedGroups.remove(key);
                refresh();
            });
            hdr.addView(less);
            TextView x = pill("✕");
            LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(-2, -2);
            xl.setMarginStart(Ui.dp(c, 8));
            Ui.onTap(x, () -> dismiss(g));
            hdr.addView(x, xl);
            box.addView(hdr, mw(0, Ui.dp(c, 10)));
            for (StatusBarNotification sbn : g) {
                List<StatusBarNotification> one = new ArrayList<>();
                one.add(sbn);
                box.addView(swipeable(card(sbn, 1), one, sbn), mw(0, Ui.dp(c, material ? 4 : 8)));
            }
            return box;
        }

        StatusBarNotification top = g.get(0);
        View card = card(top, n);
        if (n == 1) return swipeable(card, g, top);

        // Collapsed stack: the newest card with two layers peeking out underneath.
        Ui.onTap(card, () -> {
            expandedGroups.add(key);
            refresh();
        });
        FrameLayout stack = new FrameLayout(c);
        int base = look.card & 0x00FFFFFF;
        View l2 = new View(c);
        l2.setBackground(Ui.rect(base | 0x73000000, radius() - Ui.dp(c, 4)));
        FrameLayout.LayoutParams p2 = new FrameLayout.LayoutParams(-1, Ui.dp(c, 40), Gravity.BOTTOM);
        p2.leftMargin = p2.rightMargin = Ui.dp(c, 28);
        stack.addView(l2, p2);
        View l1 = new View(c);
        l1.setBackground(Ui.rect(base | 0xA6000000, radius() - Ui.dp(c, 2)));
        FrameLayout.LayoutParams p1 = new FrameLayout.LayoutParams(-1, Ui.dp(c, 40), Gravity.BOTTOM);
        p1.leftMargin = p1.rightMargin = Ui.dp(c, 14);
        p1.bottomMargin = Ui.dp(c, 8);
        stack.addView(l1, p1);
        FrameLayout.LayoutParams pc = new FrameLayout.LayoutParams(-1, -2);
        pc.bottomMargin = Ui.dp(c, 16);
        stack.addView(card, pc);
        boolean clearable = false;
        for (StatusBarNotification sbn : g) clearable |= sbn.isClearable();
        return new SwipeCard(c, stack, clearable, () -> dismiss(g), () -> panel.cardTouch = true,
                null, () -> scroll.canScrollVertically(-1));
    }

    private View swipeable(View card, List<StatusBarNotification> g, StatusBarNotification sbn) {
        boolean expandable = expandable(sbn.getNotification());
        String k = sbn.getKey();
        if (!card.hasOnClickListeners()) Ui.onTap(card, () -> open(sbn));
        Runnable expand = expandable && !expandedCards.contains(k) ? () -> toggleCard(k) : null;
        return new SwipeCard(c, card, sbn.isClearable(), () -> dismiss(g),
                () -> panel.cardTouch = true, expand, () -> scroll.canScrollVertically(-1));
    }

    private void toggleCard(String key) {
        if (!expandedCards.remove(key)) expandedCards.add(key);
        refresh();
    }

    private static boolean expandable(Notification n) {
        if (pictureOf(n) != null) return true;
        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (a.actionIntent != null && !TextUtils.isEmpty(a.title)
                        && (a.getRemoteInputs() == null || a.getRemoteInputs().length == 0)) {
                    return true;
                }
            }
        }
        CharSequence b = body(n);
        return b != null && (b.length() > 90 || b.toString().indexOf('\n') >= 0);
    }

    private static Bitmap pictureOf(Notification n) {
        try {
            Object p = n.extras.get(Notification.EXTRA_PICTURE);
            return p instanceof Bitmap ? (Bitmap) p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private ImageView chevron(boolean expanded, String k) {
        ImageView chev = new ImageView(c);
        chev.setImageResource(R.drawable.ic_chevron);
        chev.setImageTintList(ColorStateList.valueOf(look.cardSub));
        chev.setRotation(expanded ? 180f : 0f);
        int cp = Ui.dp(c, 3);
        chev.setPadding(cp, cp, cp, cp);
        chev.setBackground(Ui.oval(look.pill));
        Ui.press(chev);
        Ui.onTap(chev, () -> toggleCard(k));
        return chev;
    }

    /**
     * One card. iOS / Xiaomi: app icon, bold title + time, text. Android: small round app icon,
     * "App · time" line, then bold title and text. All expandable with ⌄.
     */
    private View card(StatusBarNotification sbn, int count) {
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        String k = sbn.getKey();
        boolean expanded = count == 1 && expandedCards.contains(k);
        boolean canExpand = count == 1 && expandable(n);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(cardBg());
        int pd = Ui.dp(c, material ? 16 : 14);
        card.setPadding(pd, Ui.dp(c, material ? 14 : 13), pd, Ui.dp(c, material ? 14 : 13));
        card.setClickable(true);

        ImageView icon = new ImageView(c);
        icon.setImageDrawable(icon(pkg));
        int is = Ui.dp(c, material ? 32 : hyper ? 40 : 38);
        icon.setBackground(material ? Ui.oval(0) : Ui.rect(0, Ui.dp(c, hyper ? 10 : 9)));
        icon.setClipToOutline(true);
        card.addView(icon, new LinearLayout.LayoutParams(is, is));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.setMarginStart(Ui.dp(c, 12));
        card.addView(col, cl);

        CharSequence t = title(n);
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (material) {
            TextView app = Ui.line(c, 12.5f, look.cardSub, 500);
            app.setText(label(pkg) + " · " + when(sbn.getPostTime()));
            top.addView(app, new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            TextView title = Ui.line(c, 15, look.cardText, 600);
            title.setText(TextUtils.isEmpty(t) ? label(pkg) : t);
            top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView time = Ui.line(c, 13, look.cardSub, 400);
            time.setText(when(sbn.getPostTime()));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
            tl.setMarginStart(Ui.dp(c, 8));
            top.addView(time, tl);
        }
        if (canExpand) {
            LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24));
            chl.setMarginStart(Ui.dp(c, 8));
            top.addView(chevron(expanded, k), chl);
        }
        col.addView(top);
        if (material && !TextUtils.isEmpty(t)) {
            TextView title = Ui.line(c, 15, look.cardText, 600);
            title.setText(t);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
            tl.topMargin = Ui.dp(c, 2);
            col.addView(title, tl);
        }

        CharSequence b = body(n);
        if (!TextUtils.isEmpty(b)) {
            TextView body = Ui.text(c, material ? 14 : 15, material ? look.cardSub : look.cardText, 400);
            body.setText(b);
            body.setMaxLines(expanded ? 30 : material ? 2 : 3);
            body.setEllipsize(TextUtils.TruncateAt.END);
            body.setLineSpacing(0, 1.08f);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
            bl.topMargin = Ui.dp(c, 2);
            col.addView(body, bl);
        }
        if (count > 1) {
            TextView more = Ui.line(c, 13, look.cardSub, 400);
            more.setText(count == 2 ? "עוד התראה אחת" : "עוד " + (count - 1) + " התראות");
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
            ml.topMargin = Ui.dp(c, 4);
            col.addView(more, ml);
        }

        if (expanded) {
            Bitmap pic = pictureOf(n);
            if (pic != null) {
                ImageView pv = new ImageView(c);
                pv.setImageBitmap(pic);
                pv.setAdjustViewBounds(true);
                pv.setMaxHeight(Ui.dp(c, 240));
                pv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                pv.setBackground(Ui.rect(0, Ui.dp(c, 12)));
                pv.setClipToOutline(true);
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
                pl.topMargin = Ui.dp(c, 10);
                col.addView(pv, pl);
            }
            LinearLayout actions = new LinearLayout(c);
            if (n.actions != null) {
                for (Notification.Action a : n.actions) {
                    if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;
                    if (a.actionIntent == null || TextUtils.isEmpty(a.title)) continue;
                    TextView btn = pill(a.title.toString());
                    Ui.onTap(btn, () -> {
                        try {
                            a.actionIntent.send();
                        } catch (Exception ignored) {
                        }
                    });
                    LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
                    al.setMarginEnd(Ui.dp(c, 8));
                    actions.addView(btn, al);
                }
            }
            if (actions.getChildCount() > 0) {
                LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
                al.topMargin = Ui.dp(c, 10);
                col.addView(actions, al);
            }
        } else {
            Icon large = n.getLargeIcon();
            if (large != null && pictureOf(n) == null) {
                try {
                    Drawable d = large.loadDrawable(c);
                    if (d != null) {
                        ImageView li = new ImageView(c);
                        li.setImageDrawable(d);
                        li.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        li.setBackground(material ? Ui.oval(0) : Ui.rect(0, Ui.dp(c, 9)));
                        li.setClipToOutline(true);
                        int ls = Ui.dp(c, 38);
                        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ls, ls);
                        ll.setMarginStart(Ui.dp(c, 10));
                        ll.gravity = Gravity.CENTER_VERTICAL;
                        card.addView(li, ll);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (canExpand) Ui.onHold(card, () -> toggleCard(k));
        Ui.press(card);
        return card;
    }

    private static CharSequence title(Notification n) {
        Bundle x = n.extras;
        CharSequence t = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        if (TextUtils.isEmpty(t)) t = x.getCharSequence(Notification.EXTRA_TITLE_BIG);
        if (TextUtils.isEmpty(t)) t = x.getCharSequence(Notification.EXTRA_TITLE);
        return t;
    }

    @SuppressWarnings("deprecation")
    private static CharSequence body(Notification n) {
        Bundle x = n.extras;
        Parcelable[] msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0) {
            boolean group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION);
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, msgs.length - 6); i < msgs.length; i++) {
                if (!(msgs[i] instanceof Bundle)) continue;
                Bundle m = (Bundle) msgs[i];
                CharSequence text = m.getCharSequence("text");
                if (TextUtils.isEmpty(text)) continue;
                if (sb.length() > 0) sb.append('\n');
                CharSequence sender = m.getCharSequence("sender");
                if (group && !TextUtils.isEmpty(sender)) sb.append(sender).append(": ");
                sb.append(text);
            }
            if (sb.length() > 0) return sb;
        }
        CharSequence t = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (TextUtils.isEmpty(t)) t = x.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(t)) {
            CharSequence[] lines = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) t = TextUtils.join("\n", lines);
        }
        if (TextUtils.isEmpty(t)) t = x.getCharSequence(Notification.EXTRA_SUB_TEXT);
        return t;
    }

    private CharSequence when(long t) {
        long d = System.currentTimeMillis() - t;
        if (d < 60_000) return "עכשיו";
        if (d < 3_600_000) return (d / 60_000) + " דק'";
        if (DateUtils.isToday(t)) return DateFormat.getTimeFormat(c).format(t);
        return DateUtils.formatDateTime(c, t, DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR);
    }

    private Drawable icon(String pkg) {
        Drawable d = icons.get(pkg);
        if (d == null) {
            try {
                d = pm.getApplicationIcon(pkg);
            } catch (Exception e) {
                d = c.getDrawable(android.R.drawable.sym_def_app_icon);
            }
            icons.put(pkg, d);
        }
        return d;
    }

    private CharSequence label(String pkg) {
        CharSequence l = labels.get(pkg);
        if (l == null) {
            try {
                l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (Exception e) {
                l = pkg;
            }
            labels.put(pkg, l);
        }
        return l;
    }

    // ---------------------------------------------------------------- actions

    private void open(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n.contentIntent != null) {
            svc.send(n.contentIntent);
            if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0 && NotifService.instance != null) {
                try {
                    NotifService.instance.cancelNotification(sbn.getKey());
                } catch (Exception ignored) {
                }
            }
        } else {
            svc.launch(pm.getLaunchIntentForPackage(sbn.getPackageName()));
        }
    }

    private void dismiss(List<StatusBarNotification> g) {
        NotifService s = NotifService.instance;
        if (s == null) return;
        List<String> keys = new ArrayList<>();
        for (StatusBarNotification sbn : g) if (sbn.isClearable()) keys.add(sbn.getKey());
        try {
            s.cancelNotifications(keys.toArray(new String[0]));
        } catch (Exception ignored) {
        }
    }

    /**
     * Sideways swipe dismisses; pulling down expands (when the list is at its top, so it doesn't
     * fight scrolling). Other movement is left to the scroll view and the panel.
     */
    static final class SwipeCard extends FrameLayout {
        interface Check {
            boolean get();
        }

        private final boolean dismissible;
        private final Runnable onDismiss, onTouchStart, onExpand;
        private final Check listScrolled;
        private final int slop;
        private float sx, sy;
        private boolean swiping, pulling, held, done;
        private VelocityTracker vt;

        SwipeCard(Context c, View child, boolean dismissible, Runnable onDismiss,
                  Runnable onTouchStart, Runnable onExpand, Check listScrolled) {
            super(c);
            this.dismissible = dismissible;
            this.onDismiss = onDismiss;
            this.onTouchStart = onTouchStart;
            this.onExpand = onExpand;
            this.listScrolled = listScrolled;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            addView(child, new LayoutParams(-1, -2));
        }

        private void cancelChildren(MotionEvent e) {
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            if (done) return true;
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                onTouchStart.run();
                sx = e.getRawX();
                sy = e.getRawY();
                swiping = pulling = false;
                // Hold the parents off until we know which way the finger goes, so a pull-down
                // to expand isn't taken as scrolling.
                held = onExpand != null && !listScrolled.get();
                if (held) getParent().requestDisallowInterceptTouchEvent(true);
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
            }
            if (vt != null) {
                MotionEvent r = MotionEvent.obtain(e);
                r.setLocation(e.getRawX(), e.getRawY());
                vt.addMovement(r);
                r.recycle();
            }
            float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
            if (a == MotionEvent.ACTION_MOVE && !swiping && !pulling) {
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                    swiping = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    cancelChildren(e);
                } else if (held && dy > slop && dy > Math.abs(dx) * 1.3f) {
                    pulling = true;
                    cancelChildren(e);
                    Ui.haptic(this);
                    onExpand.run();
                } else if (held && (dy < -slop || Math.abs(dx) > slop)) {
                    // Going up: give the gesture back to the scroll view / panel.
                    held = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
            if (pulling) return true;
            if (swiping) {
                if (a == MotionEvent.ACTION_MOVE) {
                    setTranslationX(dismissible ? dx : dx * 0.2f);
                    if (dismissible) setAlpha(1f - Math.min(0.6f, Math.abs(dx) / getWidth()));
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    vt.computeCurrentVelocity(1000);
                    float vx = vt.getXVelocity();
                    boolean out = dismissible && a == MotionEvent.ACTION_UP
                            && (Math.abs(dx) > getWidth() * 0.35f
                            || (Math.abs(vx) > 1500 && Math.signum(vx) == Math.signum(dx)));
                    if (out) {
                        done = true;
                        Ui.haptic(this);
                        animate().translationX(Math.signum(dx) * getWidth() * 1.2f).alpha(0f)
                                .setDuration(200).withEndAction(onDismiss).start();
                    } else {
                        animate().translationX(0).alpha(1f).setDuration(220).start();
                    }
                    swiping = false;
                }
                return true;
            }
            boolean handled = super.dispatchTouchEvent(e);
            return a == MotionEvent.ACTION_DOWN || handled;
        }
    }
}
