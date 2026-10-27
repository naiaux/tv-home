package com.mihir.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Opens an app for each dedicated app button on the remote (config.xml remote_buttons). Google TV routes
 * these keys to its own launcher, so with that disabled they would otherwise do nothing. Only the listed
 * keys are touched; every other key passes straight through. Turned on in Settings → Accessibility.
 */
public class RemoteKeys extends AccessibilityService {
    private final SparseArray<String> apps = new SparseArray<>();

    @Override
    protected void onServiceConnected() {
        apps.clear();
        for (String rule : getResources().getStringArray(R.array.remote_buttons)) {
            String[] kv = rule.split("=", 2);
            int code = KeyEvent.keyCodeFromString("KEYCODE_" + kv[0].trim());
            if (kv.length == 2 && code != KeyEvent.KEYCODE_UNKNOWN) apps.put(code, kv[1].trim());
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        String pkg = apps.get(e.getKeyCode());
        if (pkg == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return true; // swallow both down and up, so the system's own handler doesn't also fire
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }
}
