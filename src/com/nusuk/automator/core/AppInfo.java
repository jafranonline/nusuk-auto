package com.nusuk.automator.core;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

import com.nusuk.automator.automation.AutomationService;

/** Small facts about this install that several screens need. */
public final class AppInfo {
    private AppInfo() {}

    /** versionName of this app, "" if it cannot be read. */
    public static String versionName(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /** The Settings switch for our accessibility service (exact component match, not a substring). */
    public static boolean isAccessibilitySwitchedOn(Context c) {
        try {
            String enabled = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) return false;
            ComponentName ours = new ComponentName(c, AutomationService.class);
            TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
            sp.setString(enabled);
            while (sp.hasNext()) {
                ComponentName cn = ComponentName.unflattenFromString(sp.next());
                if (ours.equals(cn)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
