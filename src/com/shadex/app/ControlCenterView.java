package com.shadex.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * iOS Control Center. Fixed modules on top (connectivity, media, focus, sliders), then the user's
 * own buttons in the order chosen in the app.
 */
final class ControlCenterView extends ControlsBase {
    private final ScrollView scroll;
    private final Tiles.Circle airplane, cellular, wifi, bt;
    private final Tiles.Wide focus;
    private final Tiles.VSlider bright, volume;
    private final Map<String, Tiles.Square> squares = new LinkedHashMap<>();

    ControlCenterView(Context c, Panel p) {
        super(c, p);
        int side = Math.max(Ui.dp(c, 22), (p.sw - Ui.dp(c, 430)) / 2);
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int top = topPad(c), bottom = bottomPad(c);
        col.setPadding(side, top, side, bottom);
        col.setOnClickListener(v -> panel.close());


        CCGrid grid = new CCGrid(c, Ui.dp(c, 14));
        grid.setOnClickListener(v -> panel.close());
        // Everything on one screen: the grid shrinks to the space below the status bar.
        grid.fitHeight(p.sheetH - top - bottom, Ui.dp(c, 52));
        col.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(col, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, -1));

        airplane = wire(new Tiles.Circle(c, look, R.drawable.ic_airplane, 0), "airplane");
        cellular = wire(new Tiles.Circle(c, look, R.drawable.ic_cell, 0), "cell");
        wifi = wire(new Tiles.Circle(c, look, R.drawable.ic_wifi, 0), "wifi");
        bt = wire(new Tiles.Circle(c, look, R.drawable.ic_bt, 0), "bt");
        grid.put(new Tiles.Connectivity(c, look, airplane, cellular, wifi, bt), 0, 0, 2, 2);

        Tiles.Media media = new Tiles.Media(c, look);
        grid.put(media, 2, 0, 2, 2);
        bindMedia(media, false);

        focus = wire(new Tiles.Wide(c, look, R.drawable.ic_moon, "נא לא להפריע", 0), "dnd");
        grid.put(focus, 0, 2, 2, 1);

        bright = new Tiles.VSlider(c, look, R.drawable.ic_sun);
        grid.put(bright, 2, 2, 1, 2);
        bright.listener = this::onBrightness;
        bright.onHold = this::onBrightnessHold;
        volume = new Tiles.VSlider(c, look, R.drawable.ic_volume);
        grid.put(volume, 3, 2, 1, 2);
        volume.listener = v -> Sys.setVolume(c, v);
        volume.onHold = () -> svc.launch(new Intent(android.provider.Settings.Panel.ACTION_VOLUME));

        // The user's buttons: two cells left of the sliders, then rows of four.
        int slot = 0;
        for (String id : Catalog.load(c)) {
            if (!available(id)) continue;
            Tiles.Square t = new Tiles.Square(c, look, Catalog.icon(id), 0);
            if (Catalog.isApp(id)) {
                Drawable d = appIcon(id);
                if (d == null) continue;
                t.setRawIcon(d);
            }
            wire(t, id);
            squares.put(id, t);
            int col0 = slot < 2 ? slot : (slot - 2) % 4;
            int row = slot < 2 ? 3 : 4 + (slot - 2) / 4;
            grid.put(t, col0, row, 1, 1);
            slot++;
        }
        refresh();
    }

    /** Round pencil that opens the button editor in the app (shared by all styles). */
    static ImageView editButton(Context c, Look look) {
        ImageView edit = new ImageView(c);
        edit.setImageResource(R.drawable.ic_pencil);
        edit.setImageTintList(ColorStateList.valueOf(look.text));
        int ep = Ui.dp(c, 7);
        edit.setPadding(ep, ep, ep, ep);
        edit.setBackground(Ui.oval(look.module));
        Ui.press(edit);
        Ui.onTap(edit, () -> {
            if (ShadeService.instance != null) {
                ShadeService.instance.launch(new Intent(c, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_EDIT, true));
            }
        });
        return edit;
    }

    /** Space for the panel's status bar above a controls page. */
    static int topPad(Context c) {
        return Panel.barHeight(c) + Ui.dp(c, 18);
    }

    /** Space for the page dots and handle below a controls page. */
    static int bottomPad(Context c) {
        return Ui.dp(c, 50);
    }

    static LinearLayout.LayoutParams editParams(Context c) {
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34));
        el.setMarginStart(Ui.dp(c, 12));
        return el;
    }

    @Override
    public ScrollView scroll() {
        return scroll;
    }

    @Override
    void refresh() {
        airplane.setActive(on("airplane"));
        cellular.setActive(on("cell"));
        wifi.setActive(on("wifi"));
        bt.setActive(on("bt"));
        Sys.BtInfo bi = Sys.btInfo();
        bt.setIcon(bi.connected ? R.drawable.ic_bt_connected : R.drawable.ic_bt);
        bt.setBadge(bi.battery >= 0 ? bi.battery + "%" : null);
        boolean dnd = on("dnd");
        focus.setState(dnd, dnd ? "פועל" : "כבוי");
        for (Map.Entry<String, Tiles.Square> e : squares.entrySet()) {
            if (Catalog.stateful(e.getKey())) e.getValue().setActive(on(e.getKey()));
        }
        if (!bright.dragging) bright.setValue(Sys.brightness(c));
        if (!volume.dragging) volume.setValue(Sys.volume(c));
    }
}
