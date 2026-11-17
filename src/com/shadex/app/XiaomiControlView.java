package com.shadex.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Xiaomi HyperOS-style Control Center, all on one screen: two large tiles (mobile data, Wi-Fi)
 * beside tall brightness / volume sliders, a wide Now Playing card, then round toggles with names.
 */
final class XiaomiControlView extends ControlsBase {
    private final ScrollView scroll;
    private final Tiles.BigTile cell, wifi;
    private final Tiles.VSlider bright, volume;
    private final Map<String, Tiles.RoundToggle> toggles = new LinkedHashMap<>();

    XiaomiControlView(Context c, Panel p) {
        super(c, p);
        int side = Math.max(Ui.dp(c, 18), (p.sw - Ui.dp(c, 440)) / 2);
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int top = ControlCenterView.topPad(c), bottom = ControlCenterView.bottomPad(c);
        col.setPadding(side, top, side, bottom);
        col.setOnClickListener(v -> panel.close());


        CCGrid grid = new CCGrid(c, Ui.dp(c, 12));
        grid.setOnClickListener(v -> panel.close());
        // Everything on one screen: the grid shrinks to the space below the status bar.
        grid.fitHeight(p.sheetH - top - bottom, Ui.dp(c, 50));
        col.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(col, new ScrollView.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, -1));

        cell = wire(new Tiles.BigTile(c, look, R.drawable.ic_cell, Catalog.label(c, "cell"), 0.3f, true), "cell");
        grid.put(cell, 0, 0, 2, 1);
        wifi = wire(new Tiles.BigTile(c, look, R.drawable.ic_wifi, Catalog.label(c, "wifi"), 0.3f, true), "wifi");
        grid.put(wifi, 0, 1, 2, 1);

        bright = new Tiles.VSlider(c, look, R.drawable.ic_sun);
        grid.put(bright, 2, 0, 1, 2);
        bright.listener = this::onBrightness;
        bright.onHold = this::onBrightnessHold;
        volume = new Tiles.VSlider(c, look, R.drawable.ic_volume);
        grid.put(volume, 3, 0, 1, 2);
        volume.listener = v -> Sys.setVolume(c, v);
        volume.onHold = () -> svc.launch(new Intent(android.provider.Settings.Panel.ACTION_VOLUME));

        Tiles.Media media = new Tiles.Media(c, look, true);
        grid.put(media, 0, 2, 4, 1);
        bindMedia(media, false);

        int slot = 0;
        for (String id : Catalog.load(c)) {
            if (!available(id) || "cell".equals(id) || "wifi".equals(id)) continue;
            Tiles.RoundToggle t = new Tiles.RoundToggle(c, look, Catalog.icon(id), Catalog.label(c, id));
            if (Catalog.isApp(id)) {
                Drawable d = appIcon(id);
                if (d == null) continue;
                t.circle.setRawIcon(d);
            }
            wire(t.circle, id);
            toggles.put(id, t);
            grid.put(t, slot % 4, 3 + slot / 4, 1, 1);
            slot++;
        }
        refresh();
    }

    @Override
    public ScrollView scroll() {
        return scroll;
    }

    @Override
    void refresh() {
        cell.setState(on("cell"), on("cell") ? "מופעל" : "כבוי");
        boolean w = on("wifi");
        wifi.setState(w, w ? "מופעל" : "כבוי");
        for (Map.Entry<String, Tiles.RoundToggle> e : toggles.entrySet()) {
            String id = e.getKey();
            Tiles.RoundToggle t = e.getValue();
            if (Catalog.stateful(id)) t.circle.setActive(on(id));
            if ("bt".equals(id)) {
                Sys.BtInfo bi = Sys.btInfo();
                t.circle.setIcon(bi.connected ? R.drawable.ic_bt_connected : R.drawable.ic_bt);
                t.setLabel(bi.connected && bi.battery >= 0 ? "Bluetooth " + bi.battery + "%" : "Bluetooth");
            }
        }
        if (!bright.dragging) bright.setValue(Sys.brightness(c));
        if (!volume.dragging) volume.setValue(Sys.volume(c));
    }
}
