package com.shadex.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible shortcut that opens our panel, for launchers like Nova: set Nova's "swipe down"
 * gesture to this activity (or to the "Control Center" alias) instead of the system shade.
 */
public class OpenPanelActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean controls = getComponentName().getClassName().endsWith("OpenControlsActivity");
        if (android.content.Intent.ACTION_CREATE_SHORTCUT.equals(getIntent().getAction())) {
            // A launcher is creating a shortcut to us: hand back the intent that opens the panel.
            android.content.Intent open = new android.content.Intent(this, getClass())
                    .setComponent(getComponentName())
                    .setAction(android.content.Intent.ACTION_MAIN);
            android.content.Intent result = new android.content.Intent()
                    .putExtra(android.content.Intent.EXTRA_SHORTCUT_INTENT, open)
                    .putExtra(android.content.Intent.EXTRA_SHORTCUT_NAME,
                            controls ? "מרכז בקרה" : "מרכז התראות")
                    .putExtra(android.content.Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                            android.content.Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_launcher));
            setResult(RESULT_OK, result);
            finish();
            return;
        }
        ShadeService svc = ShadeService.instance;
        if (svc == null) {
            Toast.makeText(this, "קודם צריך להפעיל את שירות הנגישות של ShadeX", Toast.LENGTH_LONG).show();
        } else {
            svc.showPanelSoon(controls ? Panel.CC : Panel.NC);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
