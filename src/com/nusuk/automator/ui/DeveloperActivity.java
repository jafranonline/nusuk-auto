package com.nusuk.automator.ui;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nusuk.automator.R;
import com.nusuk.automator.core.Links;

/** About the developer: who built the app, where to find them, and what the app is. */
public class DeveloperActivity extends ScreenActivity {

    @Override
    protected String screenTitle() {
        return "About developer";
    }

    /** One tappable link: line icon, title, small address under it, chevron. */
    private LinearLayout linkRow(int iconRes, String title, String sub, Runnable open) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(66));
        row.setPadding(px(16), px(8), px(14), px(8));
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> open.run());

        FrameLayout disc = new FrameLayout(this);
        ImageView ic = icon(iconRes, 24);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER);
        disc.addView(ic, ip);
        row.addView(disc, new LinearLayout.LayoutParams(px(42), px(42)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 17, 0xFF1C1917));
        TextView s = new TextView(this); // an address, never translated
        s.setText(sub);
        s.setTextSize(15);
        s.setTextColor(0xFF78716C);
        col.addView(s, lp(-2, -2, 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.setMarginStart(px(14));
        row.addView(col, cp);

        row.addView(icon(R.drawable.ic_chevron, 18));
        return row;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(0xFFF0EEEC);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, px(1));
        p.setMarginStart(px(72));
        d.setLayoutParams(p);
        return d;
    }

    @Override
    protected void build() {
        // ----- who -----
        LinearLayout c = card(0);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(px(20), px(26), px(20), px(24));

        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.dev_avatar);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setContentDescription(Links.DEV_NAME);
        avatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        avatar.setClipToOutline(true);
        c.addView(avatar, new LinearLayout.LayoutParams(px(96), px(96)));

        TextView name = text(Links.DEV_NAME, 22, 0xFF1C1917);
        name.setGravity(Gravity.CENTER);
        c.addView(name, lp(-2, -2, 14));
        TextView role = text("Developer of Nusuk Auto", 16, 0xFF78716C);
        role.setGravity(Gravity.CENTER);
        c.addView(role, lp(-2, -2, 3));

        // ----- where to find them -----
        label("Connect", 22);
        LinearLayout links = card(8);
        links.setPadding(0, px(4), 0, px(4));
        links.addView(linkRow(R.drawable.ic_facebook_line, "Facebook profile", "facebook.com/jafranonline",
                () -> Links.openFacebook(this, Links.DEV_URL)));
        links.addView(divider());
        links.addView(linkRow(R.drawable.ic_github_line, "GitHub", "github.com/jafranonline",
                () -> Links.open(this, Links.GITHUB_URL)));
        links.addView(divider());
        links.addView(linkRow(R.drawable.ic_globe_line, "Website", "jafran.dev",
                () -> Links.open(this, Links.WEB_URL)));

        // ----- the app -----
        label("About the app", 22);
        LinearLayout about = card(8);
        about.addView(text("Nusuk Auto is an unofficial helper made for Nusuk users. It is not connected to the Nusuk app or the Ministry of Hajj and Umrah.", 16, 0xFF44403C));
        about.addView(text("Questions, problems or ideas? Open Help for step-by-step fixes, or write in the Facebook group or to the developer.", 16, 0xFF44403C), lp(-1, -2, 10));
        String v = "";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        about.addView(text("Version " + v, 15, 0xFF78716C), lp(-2, -2, 12));
    }
}
