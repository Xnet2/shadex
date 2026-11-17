package com.shadex.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Our own home screen, in the chosen style:
 * iOS - pages of rounded app icons, a glass dock, swipe down from the top for the panel and from
 *       the middle for search;
 * Android (Pixel) - date, dock, search bar, swipe up for the app drawer, swipe down for the panel;
 * Xiaomi (HyperOS) - pages of rounded-square icons with names, swipe down anywhere for the panel.
 * Being the home screen means our window owns the touch, so the panel follows the finger.
 */
public class HomeActivity extends Activity {
    private static final class App {
        final String pkg, cls;
        final CharSequence label;

        App(String pkg, String cls, CharSequence label) {
            this.pkg = pkg;
            this.cls = cls;
            this.label = label;
        }

        ComponentName cn() {
            return new ComponentName(pkg, cls);
        }
    }

    private PackageManager pm;
    private Look look;
    private boolean ios, material, hyper, labels;
    private final List<App> apps = new ArrayList<>();
    private final Map<String, Bitmap> icons = new HashMap<>();
    private HomeRoot root;
    private Pager pager;
    private LinearLayout dots;
    private Drawer drawer;
    private EditText search;
    private LinearLayout drawerGrid;
    private ScrollView drawerScroll;
    private int sw, sh, iconPx;
    private String builtKey;
    private boolean appsDirty = true;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            appsDirty = true;
            icons.clear();
            build();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();
        Window w = getWindow();
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        root = new HomeRoot(this);
        setContentView(root);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        build();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(packages);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!key().equals(builtKey)) build();
        if (drawer != null && drawer.open) drawer.close(false);
        // Coming back from an app: the icons settle in with a soft zoom, like iOS.
        if (resumedOnce) {
            root.setScaleX(1.06f);
            root.setScaleY(1.06f);
            root.setAlpha(0.5f);
            root.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320)
                    .setInterpolator(new DecelerateInterpolator(2.2f)).start();
        }
        resumedOnce = true;
    }

    private boolean resumedOnce;

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // Home pressed while already home: close search, back to the first page.
        if (drawer != null && drawer.open) drawer.close(true);
        else if (pager != null) pager.goTo(0, true);
    }

    @Override
    public void onBackPressed() {
        if (drawer != null && drawer.open) drawer.close(true);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private String key() {
        return Prefs.style(this) + Prefs.theme(this) + Prefs.homeLabels(this) + Prefs.homeCols(this)
                + Prefs.homeIcon(this) + Prefs.homeHidden(this) + Prefs.homeDockBg(this)
                + Prefs.get(this).getString(Prefs.DOCK, "");
    }

    // ================================================================ apps and icons

    private void loadApps() {
        apps.clear();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            apps.add(new App(ri.activityInfo.packageName, ri.activityInfo.name, ri.loadLabel(pm)));
        }
        Collator col = Collator.getInstance(Locale.getDefault());
        apps.sort((a, b) -> col.compare(a.label.toString(), b.label.toString()));
        appsDirty = false;
    }

    private Bitmap icon(App a) {
        String k = a.pkg + "/" + a.cls;
        Bitmap b = icons.get(k);
        if (b == null) {
            Drawable d;
            try {
                d = pm.getActivityIcon(a.cn());
            } catch (Exception e) {
                d = pm.getDefaultActivityIcon();
            }
            b = shape(d, iconPx);
            icons.put(k, b);
        }
        return b;
    }

    /** Draws an app icon in the style's shape: iOS squircle-ish, Xiaomi rounded square, Android circle. */
    private Bitmap shape(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Path clip = new Path();
        RectF r = new RectF(0, 0, size, size);
        if (material) clip.addOval(r, Path.Direction.CW);
        else {
            float rad = size * (ios ? 0.225f : 0.27f);
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
        }
        c.clipPath(clip);
        if (d instanceof AdaptiveIconDrawable) {
            // Draw both layers unmasked (the visible part is the middle 2/3), then our own mask.
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            int o = size / 4;
            Drawable bg = a.getBackground(), fg = a.getForeground();
            if (bg != null) {
                bg.setBounds(-o, -o, size + o, size + o);
                bg.draw(c);
            }
            if (fg != null) {
                fg.setBounds(-o, -o, size + o, size + o);
                fg.draw(c);
            }
        } else {
            c.drawColor(0xFFFFFFFF);
            int in = size / 8;
            d.setBounds(in, in, size - in, size - in);
            d.draw(c);
        }
        return b;
    }

    private boolean isHidden(App a) {
        for (String h : Prefs.homeHidden(this).split(",")) if (h.equals(a.pkg)) return true;
        return false;
    }

    private App find(String pkg) {
        for (App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private String pkgFor(Intent i) {
        ResolveInfo ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null || "android".equals(ri.activityInfo.packageName)) return null;
        return ri.activityInfo.packageName;
    }

    /** The dock: the user's choice, or the phone, messages, browser and camera apps. */
    private List<App> dockApps(int max) {
        List<App> out = new ArrayList<>();
        String saved = Prefs.get(this).getString(Prefs.DOCK, null);
        List<String> pkgs = new ArrayList<>();
        if (saved != null) {
            for (String p : saved.split(",")) if (!p.isEmpty()) pkgs.add(p);
        } else {
            String[] defaults = {
                    pkgFor(new Intent(Intent.ACTION_DIAL)),
                    pkgFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)),
                    pkgFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)),
                    pkgFor(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
                    pkgFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))};
            for (String p : defaults) if (p != null && !pkgs.contains(p)) pkgs.add(p);
        }
        for (String p : pkgs) {
            App a = find(p);
            if (a != null && out.size() < max) out.add(a);
        }
        return out;
    }

    private void saveDock(List<App> dock) {
        StringBuilder sb = new StringBuilder();
        for (App a : dock) sb.append(a.pkg).append(',');
        Prefs.get(this).edit().putString(Prefs.DOCK, sb.toString()).apply();
    }

    // ================================================================ building

    private void build() {
        look = Look.of(this);
        ios = look.is(Look.IOS);
        material = look.is(Look.ANDROID);
        hyper = look.is(Look.XIAOMI);
        labels = Prefs.homeLabels(this);
        builtKey = key();
        android.graphics.Point p = new android.graphics.Point();
        getWindowManager().getDefaultDisplay().getRealSize(p);
        sw = p.x;
        sh = p.y;
        float[] sizes = {0.85f, 1f, 1.15f};
        int newIcon = Math.round(dp(material ? 54 : 60) * sizes[Math.max(0, Math.min(2, Prefs.homeIcon(this)))]);
        if (newIcon != iconPx) icons.clear();
        iconPx = newIcon;
        if (appsDirty) loadApps();

        root.removeAllViews();
        pager = null;
        int top = Ui.statusBarHeight(this) + dp(14);
        int nav = Ui.navBarHeight(this);
        if (material) buildAndroid(top, nav);
        else buildPaged(top, nav);
        buildDrawer();
    }

    /** iOS / Xiaomi: pages of icons, page dots and a dock. */
    private void buildPaged(int top, int nav) {
        int dockH = dp(ios ? 96 : 88);
        int dockBottom = nav + dp(ios ? 10 : 4);
        int dotsH = dp(24);
        int cellH = dp(labels ? 104 : 90);
        int cols = Prefs.homeCols(this);
        int rows = Math.max(3, (sh - top - dockH - dockBottom - dotsH - dp(10)) / cellH);
        List<App> dock = dockApps(4);
        List<App> rest = new ArrayList<>(apps);
        rest.removeAll(dock);
        rest.removeIf(this::isHidden);

        pager = new Pager(this);
        int perPage = cols * rows;
        for (int i = 0; i < Math.max(1, rest.size()); i += perPage) {
            List<App> chunk = rest.subList(i, Math.min(rest.size(), i + perPage));
            pager.addPage(grid(chunk, cols, rows, cellH, true, 0xFFFFFFFF));
        }
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, rows * cellH, Gravity.TOP);
        pl.topMargin = top;
        root.addView(pager, pl);

        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        dots.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-1, dotsH, Gravity.BOTTOM);
        dl.bottomMargin = dockBottom + dockH + dp(4);
        root.addView(dots, dl);
        pager.updateDots();

        LinearLayout dockRow = new LinearLayout(this);
        dockRow.setGravity(Gravity.CENTER);
        if (ios && Prefs.homeDockBg(this)) dockRow.setBackground(Ui.rect(0x40FFFFFF, dp(32)));
        int pad = dp(10);
        dockRow.setPadding(pad, 0, pad, 0);
        for (App a : dock) dockRow.addView(cell(a, false, 0xFFFFFFFF), new LinearLayout.LayoutParams(0, -1, 1f));
        for (int i = dock.size(); i < 4; i++) dockRow.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1f));
        FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams(-1, dockH, Gravity.BOTTOM);
        kl.leftMargin = kl.rightMargin = dp(10);
        kl.bottomMargin = dockBottom;
        root.addView(dockRow, kl);
    }

    /** Android (Pixel): date at the top, a dock and a search bar; swipe up for all apps. */
    private void buildAndroid(int top, int nav) {
        TextView date = Ui.text(this, 22, 0xFFFFFFFF, 400);
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"),
                System.currentTimeMillis()));
        date.setShadowLayer(dp(3), 0, dp(1), 0x66000000);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tl.topMargin = top + dp(18);
        tl.setMarginStart(dp(24));
        root.addView(date, tl);

        LinearLayout dockRow = new LinearLayout(this);
        dockRow.setGravity(Gravity.CENTER);
        List<App> dock = dockApps(5);
        for (App a : dock) dockRow.addView(cell(a, false, 0xFFFFFFFF), new LinearLayout.LayoutParams(0, -1, 1f));
        FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams(-1, dp(84), Gravity.BOTTOM);
        kl.leftMargin = kl.rightMargin = dp(12);
        kl.bottomMargin = nav + dp(80);
        root.addView(dockRow, kl);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.rect(look.dark ? 0xF02F3033 : 0xF2FFFFFF, dp(28)));
        bar.setPadding(dp(20), 0, dp(20), 0);
        ImageView si = new ImageView(this);
        si.setImageResource(R.drawable.ic_search);
        si.setImageTintList(ColorStateList.valueOf(look.dark ? 0xFFE3E2E6 : 0xFF44474F));
        bar.addView(si, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView hint = Ui.line(this, 16, look.dark ? 0xFFC4C6D0 : 0xFF44474F, 400);
        hint.setText("חיפוש באפליקציות");
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, -2, 1f);
        hl.setMarginStart(dp(14));
        bar.addView(hint, hl);
        Ui.press(bar);
        bar.setOnClickListener(v -> drawer.openNow(true));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, dp(56), Gravity.BOTTOM);
        bl.leftMargin = bl.rightMargin = dp(20);
        bl.bottomMargin = nav + dp(14);
        root.addView(bar, bl);
    }

    private LinearLayout grid(List<App> list, int cols, int rows, int cellH, boolean label, int textColor) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        int i = 0;
        for (int r = 0; r < rows && i < list.size(); r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < cols; c++, i++) {
                View v = i < list.size() ? cell(list.get(i), label && labels, textColor) : new View(this);
                row.addView(v, new LinearLayout.LayoutParams(0, -1, 1f));
            }
            g.addView(row, new LinearLayout.LayoutParams(-1, cellH));
        }
        return g;
    }

    /** One app: icon, optional name; tap opens, long press for the menu. */
    private View cell(App a, boolean label, int textColor) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(icon(a));
        cell.addView(iv, new LinearLayout.LayoutParams(iconPx, iconPx));
        if (label) {
            TextView t = Ui.line(this, 12, textColor, 500);
            t.setText(a.label);
            t.setGravity(Gravity.CENTER);
            if (textColor == 0xFFFFFFFF) t.setShadowLayer(dp(2), 0, dp(1), 0x99000000);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
            ll.topMargin = dp(6);
            ll.leftMargin = ll.rightMargin = dp(4);
            cell.addView(t, ll);
        }
        Ui.press(iv);
        cell.setOnClickListener(v -> launch(a, iv));
        cell.setOnLongClickListener(v -> {
            Ui.haptic(v);
            menu(a, iv);
            return true;
        });
        return cell;
    }

    private void launch(App a, View from) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(a.cn())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ActivityOptions o = ActivityOptions.makeScaleUpAnimation(from, 0, 0,
                    from.getWidth(), from.getHeight());
            startActivity(i, o.toBundle());
        } catch (Exception e) {
            try {
                startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }

    private void menu(App a, View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        int max = material ? 5 : 4;
        List<App> dock = dockApps(max);
        boolean inDock = dock.contains(a);
        if (inDock) m.getMenu().add(0, 1, 0, "הסר מהמעגן");
        else if (dock.size() < max) m.getMenu().add(0, 2, 0, "הוסף למעגן");
        if (!dock.contains(a)) m.getMenu().add(0, 5, 0, "הסתר ממסך הבית");
        m.getMenu().add(0, 3, 0, "פרטי אפליקציה");
        m.getMenu().add(0, 4, 0, "הסר התקנה");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    dock.remove(a);
                    saveDock(dock);
                    build();
                    break;
                case 2:
                    dock.add(a);
                    saveDock(dock);
                    build();
                    break;
                case 3:
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + a.pkg)));
                    break;
                case 4:
                    startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
                    break;
                case 5: {
                    String h = Prefs.homeHidden(this);
                    Prefs.get(this).edit().putString(Prefs.HOME_HIDDEN,
                            h.isEmpty() ? a.pkg : h + "," + a.pkg).apply();
                    build();
                    break;
                }
            }
            return true;
        });
        m.show();
    }

    // ================================================================ search / app drawer

    private void buildDrawer() {
        drawer = new Drawer(this);
        drawer.setBackgroundColor(material ? look.sheetSolid | 0xFF000000
                : look.dark ? 0xF21C1C1E : 0xF5F2F2F7);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), Ui.statusBarHeight(this) + dp(12), dp(16), 0);
        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(material ? "חיפוש באפליקציות" : "חיפוש");
        search.setTextColor(look.text);
        search.setHintTextColor(look.sub);
        search.setTextSize(16);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setPadding(dp(18), dp(10), dp(18), dp(10));
        search.setBackground(Ui.rect(look.dark ? 0xFF2C2C2E : 0xFFE3E3E8, dp(material ? 28 : 12)));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                fillDrawer(s.toString());
            }

            @Override
            public void afterTextChanged(Editable e) {
            }
        });
        search.setOnEditorActionListener((v, id, ev) -> {
            // Enter opens the first match.
            List<App> m = matches(search.getText().toString());
            if (!m.isEmpty()) launch(m.get(0), v);
            return true;
        });
        col.addView(search, new LinearLayout.LayoutParams(-1, dp(48)));
        drawerScroll = new ScrollView(this);
        drawerScroll.setVerticalScrollBarEnabled(false);
        drawerGrid = new LinearLayout(this);
        drawerGrid.setOrientation(LinearLayout.VERTICAL);
        drawerGrid.setPadding(0, dp(12), 0, Ui.navBarHeight(this) + dp(24));
        drawerScroll.addView(drawerGrid);
        col.addView(drawerScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        drawer.addView(col, new FrameLayout.LayoutParams(-1, -1));
        drawer.setVisibility(View.GONE);
        drawer.setTranslationY(sh);
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        fillDrawer("");
    }

    private List<App> matches(String q) {
        String s = q.trim().toLowerCase(Locale.getDefault());
        List<App> out = new ArrayList<>();
        for (App a : apps) {
            if (s.isEmpty() || a.label.toString().toLowerCase(Locale.getDefault()).contains(s)) out.add(a);
        }
        return out;
    }

    private void fillDrawer(String q) {
        drawerGrid.removeAllViews();
        List<App> m = matches(q);
        int cols = material ? 5 : 4;
        int rows = (m.size() + cols - 1) / cols;
        drawerGrid.addView(grid(m, cols, rows, dp(material ? 96 : 104), true, look.text));
        if (m.isEmpty()) {
            TextView none = Ui.text(this, 15, look.sub, 400);
            none.setText("לא נמצאו אפליקציות");
            none.setGravity(Gravity.CENTER);
            drawerGrid.addView(none, new LinearLayout.LayoutParams(-1, dp(80)));
        }
    }

    /** Full-screen sheet with search and all apps; slides up, drag down at its top to close. */
    private final class Drawer extends FrameLayout {
        boolean open;
        private float sy;
        private boolean dragging;
        private VelocityTracker vt;
        private final int slop;

        Drawer(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setClickable(true);
        }

        void setProgress(float p) {
            setVisibility(VISIBLE);
            setTranslationY((1f - Math.max(0, Math.min(1, p))) * sh);
        }

        void openNow(boolean focus) {
            setVisibility(VISIBLE);
            open = true;
            animate().translationY(0).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
            if (focus) {
                search.requestFocus();
                search.postDelayed(() -> getSystemService(InputMethodManager.class)
                        .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT), 150);
            }
        }

        void close(boolean animate) {
            open = false;
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.setText("");
            search.clearFocus();
            if (!animate) {
                setTranslationY(sh);
                setVisibility(GONE);
                return;
            }
            animate().translationY(sh).setDuration(260).setInterpolator(new DecelerateInterpolator(1.6f))
                    .withEndAction(() -> setVisibility(GONE)).start();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sy = e.getRawY();
                    dragging = false;
                    if (vt != null) vt.recycle();
                    vt = VelocityTracker.obtain();
                    vt.addMovement(e);
                    break;
                case MotionEvent.ACTION_MOVE:
                    vt.addMovement(e);
                    float dy = e.getRawY() - sy;
                    if (dy > slop && !drawerScroll.canScrollVertically(-1)) {
                        dragging = true;
                        sy = e.getRawY();
                    }
                    break;
            }
            return dragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!dragging) return true;
            if (vt != null) vt.addMovement(e);
            float dy = e.getRawY() - sy;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    setTranslationY(Math.max(0, dy));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    vt.computeCurrentVelocity(1000);
                    if (dy > sh * 0.18f || vt.getYVelocity() > dp(400)) close(true);
                    else animate().translationY(0).setDuration(200).start();
                    dragging = false;
                    return true;
            }
            return true;
        }
    }

    // ================================================================ pages

    /** Horizontal pages of icons, following the finger; right-to-left in Hebrew like the phone. */
    private final class Pager extends FrameLayout {
        private final List<View> pages = new ArrayList<>();
        private final int slop;
        private int page;
        private float sx, sy, off;
        private boolean dragging;
        private VelocityTracker vt;
        private final int dir;

        Pager(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            dir = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
        }

        void addPage(View v) {
            pages.add(v);
            addView(v, new FrameLayout.LayoutParams(-1, -1));
            layoutPages();
        }

        private void layoutPages() {
            for (int i = 0; i < pages.size(); i++) pages.get(i).setTranslationX(dir * (i * sw) + off);
        }

        void goTo(int p, boolean animate) {
            page = Math.max(0, Math.min(pages.size() - 1, p));
            float target = -dir * page * sw;
            if (!animate) {
                off = target;
                layoutPages();
            } else {
                android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(off, target);
                va.setDuration(280);
                va.setInterpolator(new DecelerateInterpolator(2f));
                va.addUpdateListener(an -> {
                    off = (float) an.getAnimatedValue();
                    layoutPages();
                });
                va.start();
            }
            updateDots();
        }

        void updateDots() {
            if (dots == null) return;
            dots.removeAllViews();
            if (pages.size() < 2) return;
            for (int i = 0; i < pages.size(); i++) {
                View d = new View(getContext());
                d.setBackground(Ui.oval(i == page ? 0xFFFFFFFF : 0x66FFFFFF));
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(7), dp(7));
                l.leftMargin = l.rightMargin = dp(4);
                dots.addView(d, l);
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = e.getX();
                    sy = e.getY();
                    dragging = false;
                    if (vt != null) vt.recycle();
                    vt = VelocityTracker.obtain();
                    vt.addMovement(e);
                    break;
                case MotionEvent.ACTION_MOVE:
                    vt.addMovement(e);
                    float dx = e.getX() - sx, dy = e.getY() - sy;
                    if (pages.size() > 1 && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                        dragging = true;
                        sx = e.getX();
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    break;
            }
            return dragging;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!dragging) return true;
            if (vt != null) vt.addMovement(e);
            float dx = e.getX() - sx;
            float base = -dir * page * sw;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    off = base + dx;
                    layoutPages();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    vt.computeCurrentVelocity(1000);
                    float vx = vt.getXVelocity();
                    int target = page;
                    // Moving the content against the reading direction goes to the next page.
                    float toward = -dir * dx, vtoward = -dir * vx;
                    if (toward > sw * 0.2f || vtoward > dp(500)) target = page + 1;
                    else if (toward < -sw * 0.2f || vtoward < -dp(500)) target = page - 1;
                    goTo(target, true);
                    dragging = false;
                    return true;
            }
            return true;
        }
    }

    // ================================================================ home gestures

    /**
     * Vertical swipes on the home screen. Down: our panel, driven by the finger (iOS: from the
     * middle it is search instead). Up (Android): the app drawer, following the finger.
     */
    private final class HomeRoot extends FrameLayout {
        private static final int NONE = 0, PANEL = 1, DRAWER = 2, DONE = 3;
        private final int slop;
        private float sx, sy;
        private int mode;
        private VelocityTracker vt;

        HomeRoot(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mode = NONE;
                sx = e.getX();
                sy = e.getY();
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
            }
            if (vt != null) vt.addMovement(e);
            return super.dispatchTouchEvent(e);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (drawer != null && drawer.open) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && mode == NONE) decide(e);
            return mode != NONE;
        }

        private void decide(MotionEvent e) {
            float dx = e.getX() - sx, dy = e.getY() - sy;
            if (dy > slop && dy > Math.abs(dx) * 1.2f) {
                boolean fromTop = sy < sh * 0.2f;
                if (!material && !fromTop && "search".equals(Prefs.homeMiddle(HomeActivity.this))) {
                    mode = DONE;
                    drawer.openNow(true);
                    return;
                }
                ShadeService svc = ShadeService.instance;
                if (svc != null && svc.beginPull(svc.zoneAt(sx, getWidth()))) {
                    mode = PANEL;
                    sy = e.getY();
                } else {
                    mode = DONE;
                    expandSystemShade();
                }
            } else if (material && dy < -slop && -dy > Math.abs(dx) * 1.2f) {
                mode = DRAWER;
                sy = e.getY();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
            if (mode == NONE && e.getActionMasked() == MotionEvent.ACTION_MOVE) decide(e);
            ShadeService svc = ShadeService.instance;
            float dy = e.getY() - sy;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (mode == PANEL && svc != null) svc.pull(dy);
                    else if (mode == DRAWER) drawer.setProgress(-dy / (sh * 0.7f));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    vt.computeCurrentVelocity(1000);
                    float vy = e.getActionMasked() == MotionEvent.ACTION_UP ? vt.getYVelocity() : 0;
                    if (mode == PANEL && svc != null) {
                        svc.releasePull(vy);
                    } else if (mode == DRAWER) {
                        if (-dy > sh * 0.18f || vy < -dp(400)) drawer.openNow(false);
                        else drawer.close(true);
                    }
                    mode = NONE;
                    return true;
            }
            return true;
        }
    }

    /** Without our service running, a swipe down still opens the system shade. */
    @SuppressWarnings("JavaReflectionMemberAccess")
    private void expandSystemShade() {
        try {
            Object sbm = getSystemService("statusbar");
            sbm.getClass().getMethod("expandNotificationsPanel").invoke(sbm);
        } catch (Throwable ignored) {
        }
    }
}
