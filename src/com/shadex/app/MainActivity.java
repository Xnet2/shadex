package com.shadex.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/** The management app: five pages behind a bottom navigation bar. */
public class MainActivity extends Activity {
    static final String EXTRA_EDIT = "edit";
    private static final int HOME = 0, STYLE = 1, TILES = 2, HOMESCREEN = 3, MORE = 4;

    private SharedPreferences sp;
    private boolean dark;
    private int bg, card, text, sub, sep, accent;
    private FrameLayout content;
    private final View[] pages = new View[5];
    private final View[] navItems = new View[5];
    private int current = -1;
    private final List<Runnable> updaters = new ArrayList<>();
    private LinearLayout tileList, styleList;
    private TextView diag;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = Prefs.get(this);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        bg = dark ? 0xFF000000 : 0xFFF2F2F7;
        card = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        text = dark ? 0xFFFFFFFF : 0xFF000000;
        sub = dark ? 0x99EBEBF5 : 0x993C3C43;
        sep = dark ? 0x26FFFFFF : 0x1F000000;
        accent = dark ? 0xFF0A84FF : 0xFF007AFF;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(card);
        if (!dark) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        View line = new View(this);
        line.setBackgroundColor(sep);
        root.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))));
        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(card);
        String[] names = {"בית", "סגנון", "כפתורים", "מסך בית", "עוד"};
        int[] icons = {R.drawable.ic_nav_home, R.drawable.ic_nav_style, R.drawable.ic_nav_tiles,
                R.drawable.ic_apps, R.drawable.ic_nav_more};
        for (int i = 0; i < 5; i++) {
            int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            ImageView ic = new ImageView(this);
            ic.setImageResource(icons[i]);
            item.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView t = Ui.line(this, 11, sub, 600);
            t.setText(names[i]);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
            tl.topMargin = dp(3);
            item.addView(t, tl);
            item.setOnClickListener(v -> show(idx));
            navItems[i] = item;
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(62), 1f));
        }
        root.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        show(getIntent().getBooleanExtra(EXTRA_EDIT, false) ? TILES : HOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Runnable r : updaters) r.run();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (i.getBooleanExtra(EXTRA_EDIT, false)) show(TILES);
    }

    private void show(int i) {
        if (i == current) return;
        if (pages[i] == null) {
            pages[i] = build(i);
            content.addView(pages[i], new FrameLayout.LayoutParams(-1, -1));
        }
        if (current >= 0) {
            View old = pages[current];
            old.animate().alpha(0f).setDuration(120).withEndAction(() -> old.setVisibility(View.GONE)).start();
        }
        View now = pages[i];
        now.setVisibility(View.VISIBLE);
        now.setAlpha(0f);
        now.setTranslationY(dp(10));
        now.animate().alpha(1f).translationY(0).setDuration(220).start();
        current = i;
        for (int k = 0; k < 5; k++) {
            LinearLayout item = (LinearLayout) navItems[k];
            int col = k == i ? accent : sub;
            ((ImageView) item.getChildAt(0)).setImageTintList(ColorStateList.valueOf(col));
            ((TextView) item.getChildAt(1)).setTextColor(col);
        }
        for (Runnable r : updaters) r.run();
    }

    private View build(int i) {
        switch (i) {
            case STYLE: return stylePage();
            case TILES: return tilesPage();
            case HOMESCREEN: return homeScreenPage();
            case MORE: return morePage();
            default: return homePage();
        }
    }

    // ================================================================ pages

    private View homePage() {
        LinearLayout col = page("ShadeX", "הווילון שלך מראש המסך, בסגנון שתבחר");

        // Status card: ready, or how many permissions are missing.
        LinearLayout status = group(col);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(16), dp(16), dp(16), dp(16));
        ImageView si = new ImageView(this);
        status.addView(si, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.VERTICAL);
        TextView sTitle = Ui.text(this, 18, text, 700);
        TextView sSub = Ui.text(this, 14, sub, 400);
        st.addView(sTitle);
        st.addView(sSub);
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(0, -2, 1f);
        stl.setMarginStart(dp(14));
        status.addView(st, stl);

        if (Variant.ROOT) rootCard(col);

        label(col, "בדיקה מהירה");
        LinearLayout tests = new LinearLayout(this);
        TextView t1 = button("פתח מרכז בקרה", true, () -> test(Panel.CC));
        TextView t2 = button("פתח התראות", false, () -> test(Panel.NC));
        tests.addView(t1, new LinearLayout.LayoutParams(0, dp(50), 1f));
        LinearLayout.LayoutParams t2l = new LinearLayout.LayoutParams(0, dp(50), 1f);
        t2l.setMarginStart(dp(10));
        tests.addView(t2, t2l);
        col.addView(tests);
        updaters.add(() -> {
            boolean one = Look.ANDROID.equals(Prefs.style(this));
            t1.setText(one ? "פתח את החלונית" : "פתח מרכז בקרה");
            t2.setVisibility(one ? View.GONE : View.VISIBLE);
        });

        label(col, "הרשאות");
        LinearLayout perms = group(col);
        List<BooleanSupplier> checks = new ArrayList<>();
        perm(perms, checks, "שירות נגישות", "חובה: מציג את החלונית ותופס את ההחלקה",
                () -> Sys.accessibilityOn(this),
                () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        perm(perms, checks, "גישה להתראות", "ההתראות ונגן המוזיקה",
                () -> Sys.notifAccess(this),
                () -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        perm(perms, checks, "שינוי הגדרות מערכת", "בהירות ונעילת סיבוב",
                () -> Settings.System.canWrite(this),
                () -> open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri())));
        perm(perms, checks, "נא לא להפריע", "כדי שהכפתור יוכל להפעיל ולכבות",
                () -> Sys.dndAccess(this),
                () -> open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
        perm(perms, checks, "ללא הגבלת סוללה", "כדי שהשירות לא ייעצר ברקע",
                () -> getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()),
                () -> open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri())));
        updaters.add(() -> {
            int missing = 0;
            for (BooleanSupplier s : checks) if (!s.getAsBoolean()) missing++;
            boolean ok = missing == 0;
            si.setImageResource(ok ? R.drawable.ic_check : R.drawable.ic_alert);
            si.setImageTintList(ColorStateList.valueOf(ok ? 0xFF34C759 : 0xFFFF9F0A));
            sTitle.setText(ok ? "הכול מוכן" : missing == 1 ? "חסרה הרשאה אחת" : "חסרות " + missing + " הרשאות");
            sSub.setText(ok ? "החלק מטה מראש המסך כדי לפתוח" : "הפעל אותן ברשימה למטה");
        });

        label(col, "עזרה");
        LinearLayout help = group(col);
        row(help, "המתג אפור ('הגדרה מוגבלת')?",
                "קורה באפליקציות שהותקנו מקובץ. פרטי האפליקציה ← ⋮ ← 'אפשר הגדרות מוגבלות', ואז להפעיל שוב.",
                chevron(), () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri())));
        row(help, "החלונית של המערכת נפתחת יחד עם שלי",
                "בהחלקה מראש המסך המערכת מקשיבה במקביל. החלונית שלה נסגרת מיד כשהיא מופיעה. "
                        + "החלקה שמתחילה קצת מתחת לשורת המצב, או מסך הבית של האפליקציה, עוקפות את זה לגמרי.",
                null, null);
        return wrap(col);
    }

    /** Root build only: the system shade block, its state and a way to apply it now. */
    private void rootCard(LinearLayout col) {
        label(col, "חסימת החלונית של המערכת (רוט)");
        LinearLayout g = group(col);
        LinearLayout r = row(g, "", "", null, null);
        LinearLayout texts = (LinearLayout) r.getChildAt(0);
        TextView title = (TextView) texts.getChildAt(0);
        TextView desc = (TextView) texts.getChildAt(1);
        switchRow(g, "לחסום את החלונית של המערכת", "רק ShadeX נפתח מראש המסך", Prefs.ROOT_BLOCK, true);
        row(g, "החל עכשיו", "מבקש הרשאת רוט אם עוד לא אושרה", chevron(), () -> {
            ShadeService s = ShadeService.instance;
            if (s == null) {
                Toast.makeText(this, "קודם צריך להפעיל את שירות הנגישות", Toast.LENGTH_LONG).show();
                return;
            }
            s.applyRootBlock();
            title.postDelayed(() -> { for (Runnable u : updaters) u.run(); }, 2000);
        });
        updaters.add(() -> {
            ShadeService s = ShadeService.instance;
            boolean on = Prefs.rootBlock(this);
            if (s == null) {
                title.setText("השירות כבוי");
                desc.setText("החסימה פועלת כששירות הנגישות של ShadeX פעיל");
            } else if (s.rootBlocked) {
                title.setText("✓ החלונית של המערכת חסומה");
                desc.setText("עד שהשירות נעצר. אחרי הפעלה מחדש של הטלפון היא נחסמת שוב לבד");
            } else {
                title.setText(on ? "לא חסומה עדיין" : "החסימה כבויה");
                desc.setText(on ? "לחץ 'החל עכשיו' ואשר את ShadeX באפליקציית הרוט" : "");
            }
        });
    }

    private View stylePage() {
        LinearLayout col = page("סגנון", "בחר איך החלונית תיראה");
        styleList = new LinearLayout(this);
        styleList.setOrientation(LinearLayout.VERTICAL);
        col.addView(styleList);
        renderStyles();

        label(col, "מראה");
        String[] themes = {"light", "dark", "system"};
        String[] themeNames = {"בהיר", "כהה", "לפי הטלפון"};
        int sel = 0;
        for (int i = 0; i < 3; i++) if (themes[i].equals(Prefs.theme(this))) sel = i;
        col.addView(segmented(themeNames, sel, i -> sp.edit().putString(Prefs.THEME, themes[i]).apply()));
        LinearLayout g = group(col);
        ((LinearLayout.LayoutParams) g.getLayoutParams()).topMargin = dp(12);
        switchRow(g, "רקע שקוף ומטושטש", "רואים את המסך מטושטש מאחורי החלונית", Prefs.BLUR, true);
        return wrap(col);
    }

    private LinearLayout hiddenList;

    private View homeScreenPage() {
        LinearLayout col = page("מסך בית", "מסך הבית של האפליקציה, באותו סגנון של החלונית");

        LinearLayout st = group(col);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setGravity(Gravity.CENTER_VERTICAL);
        st.setPadding(dp(16), dp(16), dp(16), dp(16));
        ImageView si = new ImageView(this);
        st.addView(si, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = Ui.text(this, 17, text, 700);
        TextView t2 = Ui.text(this, 13.5f, sub, 400);
        tx.addView(t1);
        tx.addView(t2);
        LinearLayout.LayoutParams txl = new LinearLayout.LayoutParams(0, -2, 1f);
        txl.setMarginStart(dp(14));
        st.addView(tx, txl);
        TextView set = button("הגדר", true, () -> open(new Intent(Settings.ACTION_HOME_SETTINGS)));
        set.setPadding(dp(16), 0, dp(16), 0);
        st.addView(set, new LinearLayout.LayoutParams(-2, dp(40)));
        updaters.add(() -> {
            boolean ours = isOurHome();
            si.setImageResource(ours ? R.drawable.ic_check : R.drawable.ic_nav_home);
            si.setImageTintList(ColorStateList.valueOf(ours ? 0xFF34C759 : accent));
            t1.setText(ours ? "מסך הבית פעיל" : "מסך הבית לא פעיל");
            t2.setText(ours ? "לחזרה למסך בית אחר: 'הגדר' ובוחרים אותו"
                    : "'הגדר' ובוחרים 'ShadeX Home' כאפליקציית הבית");
            set.setText(ours ? "שנה" : "הגדר");
        });

        label(col, "פריסה");
        LinearLayout lay = group(col);
        lay.setPadding(dp(14), dp(14), dp(14), dp(14));
        TextView cl = Ui.text(this, 14, sub, 500);
        cl.setText("אייקונים בשורה");
        lay.addView(cl);
        LinearLayout segCols = segmented(new String[]{"4", "5"}, Prefs.homeCols(this) == 5 ? 1 : 0,
                i -> sp.edit().putInt(Prefs.HOME_COLS, i == 1 ? 5 : 4).apply());
        LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(-1, -2);
        scl.topMargin = dp(8);
        scl.bottomMargin = dp(14);
        lay.addView(segCols, scl);
        TextView il = Ui.text(this, 14, sub, 500);
        il.setText("גודל אייקונים");
        lay.addView(il);
        LinearLayout segIcon = segmented(new String[]{"קטן", "בינוני", "גדול"}, Prefs.homeIcon(this),
                i -> sp.edit().putInt(Prefs.HOME_ICON, i).apply());
        LinearLayout.LayoutParams sil = new LinearLayout.LayoutParams(-1, -2);
        sil.topMargin = dp(8);
        lay.addView(segIcon, sil);
        LinearLayout opts = group(col);
        ((LinearLayout.LayoutParams) opts.getLayoutParams()).topMargin = dp(12);
        switchRow(opts, "שמות מתחת לאייקונים", null, Prefs.HOME_LABELS, true);
        switchRow(opts, "רקע זכוכית למעגן", "בסגנון iOS", Prefs.HOME_DOCK_BG, true);

        label(col, "החלקה מטה באמצע המסך");
        LinearLayout mid = group(col);
        String[][] m = {{"search", "חיפוש אפליקציות", "כמו באייפון"}, {"panel", "החלונית", "מרכז ההתראות / הבקרה"}};
        List<ImageView> checks = new ArrayList<>();
        for (String[] o : m) {
            ImageView ck = new ImageView(this);
            ck.setImageResource(R.drawable.ic_check);
            ck.setImageTintList(ColorStateList.valueOf(accent));
            checks.add(ck);
            row(mid, o[1], o[2], ck, () -> {
                sp.edit().putString(Prefs.HOME_MIDDLE, o[0]).apply();
                for (int k = 0; k < m.length; k++) {
                    checks.get(k).setVisibility(m[k][0].equals(o[0]) ? View.VISIBLE : View.INVISIBLE);
                }
            });
            ck.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
            ck.setVisibility(o[0].equals(Prefs.homeMiddle(this)) ? View.VISIBLE : View.INVISIBLE);
        }
        TextView mn = Ui.text(this, 13, sub, 400);
        mn.setText("החלקה מטה מהחלק העליון פותחת תמיד את החלונית. באנדרואיד: החלקה למעלה פותחת את כל האפליקציות.");
        mn.setPadding(dp(16), dp(6), dp(16), 0);
        col.addView(mn);

        label(col, "אפליקציות מוסתרות");
        hiddenList = group(col);
        renderHidden();
        updaters.add(this::renderHidden);
        TextView hn = Ui.text(this, 13, sub, 400);
        hn.setText("להסתרה: לחיצה ארוכה על אייקון במסך הבית ← 'הסתר'. מוסתרות עדיין מופיעות בחיפוש.");
        hn.setPadding(dp(16), dp(6), dp(16), 0);
        col.addView(hn);

        label(col, "מעגן");
        LinearLayout dock = group(col);
        row(dock, "אפס את המעגן", "חוזר לטלפון, הודעות, דפדפן ומצלמה", chevron(), () -> {
            sp.edit().remove(Prefs.DOCK).apply();
            Toast.makeText(this, "המעגן אופס", Toast.LENGTH_SHORT).show();
        });
        return wrap(col);
    }

    private boolean isOurHome() {
        ResolveInfo ri = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    private void renderHidden() {
        if (hiddenList == null) return;
        hiddenList.removeAllViews();
        List<String> hidden = new ArrayList<>();
        for (String h : Prefs.homeHidden(this).split(",")) if (!h.isEmpty()) hidden.add(h);
        if (hidden.isEmpty()) {
            row(hiddenList, "אין אפליקציות מוסתרות", null, null, null);
            return;
        }
        PackageManager pm = getPackageManager();
        for (String pkg : hidden) {
            CharSequence name = pkg;
            try {
                name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (Exception ignored) {
            }
            TextView show = Ui.line(this, 14, accent, 600);
            show.setText("הצג");
            row(hiddenList, name.toString(), null, show, () -> {
                List<String> l = new ArrayList<>(hidden);
                l.remove(pkg);
                sp.edit().putString(Prefs.HOME_HIDDEN, android.text.TextUtils.join(",", l)).apply();
                renderHidden();
            });
        }
    }

    private void renderStyles() {
        styleList.removeAllViews();
        String cur = Prefs.style(this);
        String[][] styles = {
                {Look.IOS, "iOS", "מרכז בקרה ומרכז התראות נפרדים, כפתורי זכוכית ללא צבע"},
                {Look.ANDROID, "אנדרואיד (החדש ביותר)", "חלונית אחת: אריחים, בהירות והתראות, בצבעי הטפט שלך"},
                {Look.XIAOMI, "Xiaomi (HyperOS)", "אריחים גדולים, סליידרים, כפתורים עגולים עם שמות"}};
        for (String[] s : styles) {
            boolean on = s[0].equals(cur);
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable cb = Ui.rect(card, dp(18));
            cb.setStroke(dp(on ? 2.5f : 1), on ? accent : sep);
            c.setBackground(cb);
            c.setPadding(dp(12), dp(12), dp(12), dp(14));
            c.addView(preview(s[0]), new LinearLayout.LayoutParams(-1, dp(176)));
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(4), dp(12), dp(4), 0);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView n = Ui.text(this, 17, text, 700);
            n.setText(s[1]);
            TextView d = Ui.text(this, 13.5f, sub, 400);
            d.setText(s[2]);
            texts.addView(n);
            texts.addView(d);
            line.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ic_check);
            check.setImageTintList(ColorStateList.valueOf(accent));
            check.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            line.addView(check, new LinearLayout.LayoutParams(dp(28), dp(28)));
            c.addView(line);
            Ui.press(c);
            c.setOnClickListener(v -> {
                sp.edit().putString(Prefs.STYLE, s[0]).apply();
                renderStyles();
                Toast.makeText(this, "הסגנון עודכן: " + s[1], Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(12);
            styleList.addView(c, lp);
        }
    }

    private View tilesPage() {
        LinearLayout col = page("כפתורים", "בחר אילו כפתורים יופיעו ובאיזה סדר");
        TextView note = Ui.text(this, 14, sub, 400);
        // Each style has its own list; say which buttons that style keeps fixed.
        updaters.add(() -> {
            String st = Prefs.style(this);
            note.setText(Look.ANDROID.equals(st) ? "סגנון אנדרואיד: כל האריחים כאן, בסדר שתבחר."
                    : Look.XIAOMI.equals(st) ? "סגנון Xiaomi: Wi-Fi ונתונים ניידים הם האריחים הגדולים הקבועים. כאן בוחרים את הכפתורים העגולים ואת הסדר."
                    : "סגנון iOS: Wi-Fi, Bluetooth, נתונים, טיסה ו'נא לא להפריע' קבועים למעלה. כאן בוחרים את שאר הכפתורים ואת הסדר.");
            if (tileList != null) renderTiles();
        });
        note.setPadding(dp(4), 0, dp(4), dp(4));
        col.addView(note);
        tileList = group(col);
        renderTiles();
        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, dp(14), 0, 0);
        btns.addView(button("+ הוסף כפתור", true, this::addTileDialog), new LinearLayout.LayoutParams(0, dp(50), 2f));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, dp(50), 1f);
        rl.setMarginStart(dp(10));
        btns.addView(button("איפוס", false, () -> {
            Catalog.reset(this);
            renderTiles();
        }), rl);
        col.addView(btns);
        TextView foot = Ui.text(this, 13, sub, 400);
        foot.setText("כפתורים שאפליקציות אחרות מוסיפות לחלונית של המערכת אנדרואיד מאפשר להפעיל רק לממשק המערכת עצמו. במקום זה אפשר להוסיף את האפליקציה עצמה ככפתור.");
        foot.setPadding(dp(4), dp(14), dp(4), 0);
        col.addView(foot);
        return wrap(col);
    }

    private void gestureSections(LinearLayout col) {
        label(col, "מה נפתח בהחלקה מראש המסך");
        LinearLayout modes = group(col);
        String[][] m = {
                {"split", "כמו באייפון", "צד אחד מרכז בקרה, השאר התראות"},
                {"cc", "תמיד מרכז הבקרה", null},
                {"nc", "תמיד ההתראות", null}};
        List<ImageView> checks = new ArrayList<>();
        for (String[] o : m) {
            ImageView ck = new ImageView(this);
            ck.setImageResource(R.drawable.ic_check);
            ck.setImageTintList(ColorStateList.valueOf(accent));
            checks.add(ck);
            row(modes, o[1], o[2], ck, () -> {
                sp.edit().putString(Prefs.MODE, o[0]).apply();
                for (int k = 0; k < m.length; k++) {
                    checks.get(k).setVisibility(m[k][0].equals(o[0]) ? View.VISIBLE : View.INVISIBLE);
                }
            });
            ck.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
            ck.setVisibility(o[0].equals(Prefs.mode(this)) ? View.VISIBLE : View.INVISIBLE);
        }
        TextView andNote = Ui.text(this, 13, sub, 400);
        andNote.setText("בסגנון אנדרואיד יש חלונית אחת, אז זה לא משנה.");
        andNote.setPadding(dp(16), dp(6), dp(16), 0);
        col.addView(andNote);

        label(col, "אזור ההחלקה");
        LinearLayout zone = group(col);
        switchRow(zone, "מרכז הבקרה בצד שמאל", "במקום ימין", Prefs.CC_LEFT, false);
        sliderRow(zone, "רוחב האזור של מרכז הבקרה", Prefs.CC_ZONE, 40, 15, 85, v -> v + "%");
        sliderRow(zone, "גובה אזור ההחלקה", Prefs.STRIP_DP, 0, 0, 80,
                v -> v == 0 ? "כמו שורת המצב" : v + "dp");
        switchRow(zone, "הצג את האזורים בצבע", "כדי לכוון את הגודל", Prefs.SHOW_ZONE, false);

        switchRow(zone, "לא לפעול במסך רוחבי", "משחקים וסרטים", Prefs.HIDE_LANDSCAPE, true);
    }

    private View morePage() {
        LinearLayout col = page("עוד", "מחוות, רטט ואבחון");
        gestureSections(col);
        label(col, "כללי");
        LinearLayout g = group(col);
        switchRow(g, "רטט קל במגע", null, Prefs.HAPTIC, true);

        label(col, "קיצורים");
        LinearLayout s = group(col);
        row(s, "הגדרות נגישות", null, chevron(), () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        row(s, "גישה להתראות", null, chevron(), () -> open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        row(s, "פרטי האפליקציה", null, chevron(), () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri())));

        label(col, "יומן אבחון");
        LinearLayout d = group(col);
        row(d, "רענן יומן", "אחרי החלקה שלא עבדה, רענן ושלח צילום מסך", chevron(), this::refreshDiag);
        diag = Ui.text(this, 11.5f, text, 400);
        diag.setTypeface(android.graphics.Typeface.MONOSPACE);
        diag.setTextIsSelectable(true);
        diag.setTextDirection(View.TEXT_DIRECTION_LTR);
        diag.setPadding(dp(16), dp(10), dp(16), dp(14));
        d.addView(diag);
        updaters.add(this::refreshDiag);

        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        TextView about = Ui.text(this, 12.5f, sub, 400);
        about.setText("ShadeX " + ver + "\nאייקונים: Material Design Icons (Pictogrammers), רישיון Apache 2.0");
        about.setGravity(Gravity.CENTER);
        about.setPadding(0, dp(24), 0, 0);
        col.addView(about);
        return wrap(col);
    }

    // ================================================================ building blocks

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private LinearLayout page(String title, String subtitle) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(28), dp(16), dp(32));
        TextView t = Ui.text(this, 32, text, 800);
        t.setText(title);
        col.addView(t);
        if (subtitle != null) {
            TextView s = Ui.text(this, 15, sub, 400);
            s.setText(subtitle);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
            l.topMargin = dp(2);
            l.bottomMargin = dp(14);
            col.addView(s, l);
        } else {
            col.addView(new View(this), new LinearLayout.LayoutParams(1, dp(14)));
        }
        return col;
    }

    private View wrap(LinearLayout col) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        return sv;
    }

    private void label(LinearLayout col, String s) {
        TextView t = Ui.text(this, 13, sub, 600);
        t.setText(s);
        t.setPadding(dp(16), dp(22), dp(16), dp(7));
        col.addView(t);
    }

    /** A rounded card that rows go into (separated by hairlines). */
    private LinearLayout group(LinearLayout col) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(Ui.rect(card, dp(16)));
        g.setClipToOutline(true);
        col.addView(g, new LinearLayout.LayoutParams(-1, -2));
        return g;
    }

    private void addRow(LinearLayout g, View row) {
        if (g.getChildCount() > 0) {
            View s = new View(this);
            s.setBackgroundColor(sep);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
            l.setMarginStart(dp(16));
            g.addView(s, l);
        }
        g.addView(row);
    }

    private LinearLayout row(LinearLayout g, String title, String subtitle, View end, Runnable click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(13), dp(14), dp(13));
        r.setMinimumHeight(dp(54));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(this, 16, text, 500);
        t.setText(title);
        texts.addView(t);
        if (subtitle != null) {
            TextView s = Ui.text(this, 13, sub, 400);
            s.setText(subtitle);
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (end != null) {
            LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-2, -2);
            el.setMarginStart(dp(10));
            r.addView(end, el);
        }
        if (click != null) {
            r.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(sep),
                    null, new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
            r.setOnClickListener(v -> click.run());
        }
        addRow(g, r);
        return r;
    }

    private ImageView chevron() {
        ImageView c = new ImageView(this);
        c.setImageResource(R.drawable.ic_chevron);
        c.setRotation(90);
        c.setImageTintList(ColorStateList.valueOf(sub));
        c.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        return c;
    }

    private void switchRow(LinearLayout g, String title, String subtitle, String key, boolean def) {
        Switch s = new Switch(this);
        s.setChecked(sp.getBoolean(key, def));
        s.setOnCheckedChangeListener((x, on) -> sp.edit().putBoolean(key, on).apply());
        row(g, title, subtitle, s, () -> s.toggle());
    }

    interface Fmt {
        String f(int v);
    }

    private void sliderRow(LinearLayout g, String title, String key, int def, int min, int max, Fmt fmt) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(8));
        LinearLayout top = new LinearLayout(this);
        TextView t = Ui.text(this, 16, text, 500);
        t.setText(title);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView val = Ui.text(this, 15, accent, 600);
        top.addView(val);
        r.addView(top);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        int v0 = sp.getInt(key, def);
        bar.setProgress(v0 - min);
        val.setText(fmt.f(v0));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                val.setText(fmt.f(p + min));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                sp.edit().putInt(key, s.getProgress() + min).apply();
            }
        });
        r.addView(bar);
        addRow(g, r);
    }

    interface Pick {
        void pick(int i);
    }

    /** iOS-like segmented control. */
    private LinearLayout segmented(String[] names, int selected, Pick pick) {
        LinearLayout seg = new LinearLayout(this);
        seg.setBackground(Ui.rect(dark ? 0xFF2C2C2E : 0xFFE3E3E8, dp(12)));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        List<TextView> opts = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            int idx = i;
            TextView o = Ui.line(this, 14, text, 600);
            o.setText(names[i]);
            o.setGravity(Gravity.CENTER);
            opts.add(o);
            o.setOnClickListener(v -> {
                for (int k = 0; k < opts.size(); k++) {
                    opts.get(k).setBackground(k == idx ? Ui.rect(dark ? 0xFF636366 : 0xFFFFFFFF, dp(10)) : null);
                }
                pick.pick(idx);
            });
            o.setBackground(i == selected ? Ui.rect(dark ? 0xFF636366 : 0xFFFFFFFF, dp(10)) : null);
            seg.addView(o, new LinearLayout.LayoutParams(0, dp(36), 1f));
        }
        return seg;
    }

    private TextView button(String label, boolean primary, Runnable click) {
        TextView b = Ui.line(this, 16, primary ? 0xFFFFFFFF : accent, 600);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.rect(primary ? accent : card, dp(14)));
        Ui.press(b);
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private void perm(LinearLayout g, List<BooleanSupplier> checks, String name, String desc,
                      BooleanSupplier ok, Runnable fix) {
        TextView state = Ui.line(this, 14, accent, 600);
        state.setPadding(dp(12), dp(6), dp(12), dp(6));
        row(g, name, desc, state, fix);
        checks.add(ok);
        updaters.add(() -> {
            boolean good = ok.getAsBoolean();
            state.setText(good ? "✓ פעיל" : "הפעל");
            state.setTextColor(good ? 0xFF34C759 : 0xFFFFFFFF);
            state.setBackground(good ? null : Ui.rect(accent, dp(14)));
        });
    }

    // ================================================================ style previews

    /** A small drawn mock-up of each style's Control Center over a wallpaper-like gradient. */
    private View preview(String style) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable wall = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF5B7CFA, 0xFFB46CC8, 0xFFF29D7E});
        wall.setCornerRadius(dp(12));
        f.setBackground(wall);
        f.setClipToOutline(true);
        FrameLayout m = new FrameLayout(this);
        m.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        f.addView(m, new FrameLayout.LayoutParams(dp(168), dp(164), Gravity.CENTER));
        if (Look.ANDROID.equals(style)) {
            int acc = 0xFF0B57D0, tile = 0xFFE1E2EC;
            box(m, 0, 0, 168, 164, 16, 0xF0F1F0F4);
            box(m, 12, 12, 54, 12, 6, 0xFF1B1B1F);
            box(m, 12, 28, 34, 6, 3, 0x991B1B1F);
            box(m, 12, 44, 144, 18, 9, 0xFFD3E3FD);
            box(m, 12, 44, 92, 18, 9, acc);
            for (int r = 0; r < 3; r++) {
                int y = 70 + r * 30;
                box(m, 12, y, 70, 24, 10, r == 0 ? acc : tile);
                box(m, 86, y, 70, 24, 10, r == 1 ? acc : tile);
            }
        } else if (Look.XIAOMI.equals(style)) {
            int blue = 0xFF2F7CF6;
            box(m, 0, 0, 168, 164, 16, 0x99F2F2F2);
            box(m, 12, 12, 68, 30, 10, blue);
            box(m, 12, 50, 68, 30, 10, 0xF2FFFFFF);
            box(m, 88, 12, 30, 68, 10, 0xF2FFFFFF);
            box(m, 88, 46, 30, 34, 10, blue);
            box(m, 126, 12, 30, 68, 10, 0xF2FFFFFF);
            box(m, 126, 56, 30, 24, 10, blue);
            box(m, 12, 88, 144, 26, 10, 0xF2FFFFFF);
            for (int i = 0; i < 4; i++) box(m, 16 + i * 38, 124, 22, 22, 11, i == 0 ? blue : 0xF2FFFFFF);
        } else {
            box(m, 0, 0, 168, 164, 16, 0x8CF2F2F7);
            box(m, 12, 12, 68, 68, 16, 0xCCFFFFFF);
            int[][] circles = {{20, 20}, {46, 20}, {20, 46}, {46, 46}};
            for (int i = 0; i < 4; i++) box(m, circles[i][0], circles[i][1], 22, 22, 11, i == 2 ? 0xFF1C1C1E : 0xFFF2F2F7);
            box(m, 88, 12, 68, 68, 16, 0xCCFFFFFF);
            box(m, 12, 88, 68, 30, 12, 0xCCFFFFFF);
            box(m, 88, 88, 30, 68, 12, 0xCCFFFFFF);
            box(m, 88, 118, 30, 38, 12, 0xFF1C1C1E);
            box(m, 126, 88, 30, 68, 12, 0xCCFFFFFF);
            box(m, 126, 128, 30, 28, 12, 0xFF1C1C1E);
            box(m, 12, 126, 30, 30, 10, 0xCCFFFFFF);
            box(m, 50, 126, 30, 30, 10, 0xCCFFFFFF);
        }
        return f;
    }

    private void box(FrameLayout p, int x, int y, int w, int h, int r, int color) {
        View v = new View(this);
        v.setBackground(Ui.rect(color, dp(r)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(w), dp(h), Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = dp(x);
        lp.topMargin = dp(y);
        p.addView(v, lp);
    }

    // ================================================================ actions

    private void test(int type) {
        if (ShadeService.instance == null) {
            Toast.makeText(this, "קודם צריך להפעיל את שירות הנגישות", Toast.LENGTH_LONG).show();
            return;
        }
        ShadeService.instance.showPanel(type);
    }

    private Uri pkgUri() {
        return Uri.parse("package:" + getPackageName());
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri()));
            } catch (Exception ignored) {
            }
        }
    }

    private void refreshDiag() {
        if (diag == null) return;
        StringBuilder sb = new StringBuilder("service: ")
                .append(ShadeService.instance != null ? "running" : "NOT running").append('\n');
        synchronized (ShadeService.LOG) {
            for (String s : ShadeService.LOG) sb.append(s).append('\n');
        }
        diag.setText(sb);
    }

    // ================================================================ button editor

    private void renderTiles() {
        tileList.removeAllViews();
        List<String> ids = Catalog.load(this);
        if (ids.isEmpty()) {
            row(tileList, "אין כפתורים", "הוסף כפתורים עם הכפתור למטה", null, null);
        }
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            int idx = i;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(14), dp(6), dp(6), dp(6));
            ImageView icon = new ImageView(this);
            if (Catalog.isApp(id)) {
                try {
                    icon.setImageDrawable(getPackageManager().getApplicationIcon(Catalog.pkg(id)));
                } catch (Exception e) {
                    icon.setImageResource(R.drawable.ic_apps);
                }
            } else {
                icon.setImageResource(Catalog.icon(id));
                icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
                icon.setPadding(dp(5), dp(5), dp(5), dp(5));
                icon.setBackground(Ui.rect(0xFF8E8E93, dp(8)));
            }
            r.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView name = Ui.line(this, 16, text, 500);
            name.setText(Catalog.label(this, id));
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1f);
            nl.setMarginStart(dp(12));
            r.addView(name, nl);
            r.addView(small("▲", () -> move(idx, -1)));
            r.addView(small("▼", () -> move(idx, 1)));
            r.addView(small("✕", () -> {
                List<String> l = Catalog.load(this);
                l.remove(idx);
                Catalog.save(this, l);
                renderTiles();
            }));
            addRow(tileList, r);
        }
    }

    private TextView small(String s, Runnable r) {
        TextView t = Ui.text(this, 16, accent, 600);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(v -> r.run());
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return t;
    }

    private void move(int i, int d) {
        List<String> l = Catalog.load(this);
        int j = i + d;
        if (j < 0 || j >= l.size()) return;
        Collections.swap(l, i, j);
        Catalog.save(this, l);
        renderTiles();
    }

    private void addTileDialog() {
        List<String> current = Catalog.load(this);
        List<String> options = new ArrayList<>();
        String style = Prefs.style(this);
        for (String id : Catalog.BUILT_IN) {
            if (!current.contains(id) && !Catalog.fixed(style, id)) options.add(id);
        }
        List<CharSequence> labels = new ArrayList<>();
        for (String id : options) labels.add(Catalog.label(this, id));
        labels.add("אפליקציה…");
        new AlertDialog.Builder(this)
                .setTitle("הוסף כפתור")
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                    if (which == options.size()) appDialog();
                    else addTile(options.get(which));
                })
                .show();
    }

    private void appDialog() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        Collections.sort(apps, (a, b) -> a.loadLabel(pm).toString()
                .compareToIgnoreCase(b.loadLabel(pm).toString()));
        List<String> pkgs = new ArrayList<>();
        List<CharSequence> names = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            String p = ri.activityInfo.packageName;
            if (pkgs.contains(p) || p.equals(getPackageName())) continue;
            pkgs.add(p);
            names.add(ri.loadLabel(pm));
        }
        new AlertDialog.Builder(this)
                .setTitle("בחר אפליקציה")
                .setItems(names.toArray(new CharSequence[0]), (d, which) -> addTile("app:" + pkgs.get(which)))
                .show();
    }

    private void addTile(String id) {
        List<String> l = Catalog.load(this);
        if (!l.contains(id)) l.add(id);
        Catalog.save(this, l);
        renderTiles();
    }
}
