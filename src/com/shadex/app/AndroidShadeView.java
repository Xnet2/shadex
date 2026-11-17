package com.shadex.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Latest-Android (Material You) shade: one page with a date header, a horizontal brightness
 * slider, two-column Quick Settings tiles, a media card, and the notifications underneath.
 */
final class AndroidShadeView extends ControlsBase {
    private final ScrollView scroll;
    private final Tiles.HSlider bright;
    private final TextView date;
    private final Map<String, Tiles.BigTile> tiles = new LinkedHashMap<>();

    AndroidShadeView(Context c, Panel p) {
        super(c, p);
        int side = Math.max(Ui.dp(c, 14), (p.sw - Ui.dp(c, 520)) / 2);
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.setPadding(0, Panel.barHeight(c), 0, 0);
        scroll.setClipToPadding(true);
        col.setPadding(side, Ui.dp(c, 10), side,
                Ui.navBarHeight(c) + Ui.dp(c, 48));
        col.setOnClickListener(v -> panel.close());
        scroll.addView(col, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, -1));

        // Header: the date and a settings button (time and battery are in the status bar).
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        date = Ui.line(c, 16, look.text, 600);
        header.addView(date, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView gear = roundIcon(R.drawable.ic_cog);
        Ui.onTap(gear, () -> svc.launch(new Intent(Settings.ACTION_SETTINGS)));
        header.addView(gear, ControlCenterView.editParams(c));
        int hp = Ui.dp(c, 6);
        header.setPadding(hp, 0, hp, 0);
        col.addView(header, mw(0, 0));

        bright = new Tiles.HSlider(c, look, R.drawable.ic_sun);
        bright.listener = this::onBrightness;
        bright.onHold = this::onBrightnessHold;
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.dp(c, 54));
        bl.topMargin = Ui.dp(c, 18);
        col.addView(bright, bl);

        // Two-column tiles.
        List<String> ids = new ArrayList<>();
        for (String id : Catalog.load(c)) {
            if (available(id)) ids.add(id);
        }
        int gap = Ui.dp(c, 8);
        for (int i = 0; i < ids.size(); i += 2) {
            LinearLayout row = new LinearLayout(c);
            for (int k = i; k < Math.min(i + 2, ids.size()); k++) {
                String id = ids.get(k);
                Tiles.BigTile t = new Tiles.BigTile(c, look, Catalog.icon(id),
                        Catalog.label(c, id), 0.36f, false);
                if (Catalog.isApp(id)) {
                    Drawable d = appIcon(id);
                    if (d != null) t.setRawIcon(d);
                }
                wire(t, id);
                tiles.put(id, t);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, Ui.dp(c, 72), 1f);
                if (k == i) tl.setMarginEnd(gap / 2);
                else tl.setMarginStart(gap / 2);
                row.addView(t, tl);
            }
            if (row.getChildCount() == 1) {
                row.addView(new android.view.View(c), new LinearLayout.LayoutParams(0, 1, 1f));
            }
            col.addView(row, mw(i == 0 ? Ui.dp(c, 14) : gap, 0));
        }

        Tiles.Media media = new Tiles.Media(c, look, true);
        media.setCorner(Ui.dp(c, 28));
        col.addView(media, mw(Ui.dp(c, 12), 0));
        media.getLayoutParams().height = Ui.dp(c, 80);
        bindMedia(media, true);

        col.addView(new NotifList(c, p, scroll), mw(Ui.dp(c, 22), 0));
        refresh();
    }

    private ImageView roundIcon(int res) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(look.text));
        int ep = Ui.dp(c, 7);
        v.setPadding(ep, ep, ep, ep);
        v.setBackground(Ui.oval(look.module));
        Ui.press(v);
        return v;
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

    @Override
    void refresh() {
        for (Map.Entry<String, Tiles.BigTile> e : tiles.entrySet()) {
            String id = e.getKey();
            Tiles.BigTile t = e.getValue();
            t.setState(Catalog.stateful(id) && on(id), sub(id));
            if ("bt".equals(id)) t.setIcon(icon(id));
        }
        if (!bright.dragging) bright.setValue(Sys.brightness(c));
        long now = System.currentTimeMillis();
        date.setText(DateFormat.format(
                DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), now));
    }
}
