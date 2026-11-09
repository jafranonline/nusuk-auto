package com.nusuk.automator;

import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Who built the app, with a link to the developer's Facebook profile. */
public class DeveloperActivity extends ScreenActivity {

    @Override
    protected String screenTitle() {
        return "Developer";
    }

    @Override
    protected void build() {
        LinearLayout c = card(0);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(px(20), px(28), px(20), px(24));

        TextView avatar = text("J", 40, 0xFFFFFFFF);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackgroundResource(R.drawable.avatar_bg);
        c.addView(avatar, new LinearLayout.LayoutParams(px(96), px(96)));

        TextView name = text(Links.DEV_NAME, 24, 0xFF1C1917);
        name.setGravity(Gravity.CENTER);
        c.addView(name, lp(-2, -2, 16));
        TextView role = text("Developer of Nusuk Auto", 14, 0xFF78716C);
        role.setGravity(Gravity.CENTER);
        c.addView(role, lp(-2, -2, 4));

        c.addView(pill("Facebook profile", R.drawable.ic_facebook_white, true, 0xFF1877F2,
                () -> Links.openFacebook(this, Links.DEV_URL)), lp(-2, -2, 20));
        c.addView(pill("Join the Facebook group", R.drawable.ic_facebook, false, 0,
                () -> Links.openFacebook(this, Links.groupUrl(this))), lp(-2, -2, 10));

        label("About", 22);
        LinearLayout about = card(8);
        about.addView(text("Nusuk Auto is an unofficial helper made for Nusuk users. It is not connected to the Nusuk app or the Ministry of Hajj and Umrah.", 14, 0xFF44403C));
        about.addView(text("Questions, problems or ideas? Open Help for step-by-step fixes, or write in the Facebook group or to the developer.", 14, 0xFF44403C), lp(-1, -2, 10));
        String v = "";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        about.addView(text("Version " + v, 13, 0xFF78716C), lp(-2, -2, 12));
    }
}
