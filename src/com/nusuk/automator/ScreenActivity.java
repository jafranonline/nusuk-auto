package com.nusuk.automator;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Base for the simple pages (Help, Developer, Notifications): green top bar + scrolling content built in code. */
abstract class ScreenActivity extends Activity {
    protected LinearLayout content;
    protected TextView barAction;
    protected float dp;

    protected abstract String screenTitle();

    protected abstract void build();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_screen);
        dp = getResources().getDisplayMetrics().density;
        content = findViewById(R.id.content);
        barAction = findViewById(R.id.tvBarAction);
        ((TextView) findViewById(R.id.tvBarTitle)).setText(L.t(this, screenTitle()));
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        Links.applyInsets(findViewById(R.id.root), findViewById(R.id.bar), content);
        L.view(this, findViewById(R.id.root));
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        OverlayService.setAppOpen(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        OverlayService.setAppOpen(false);
    }

    protected int px(float v) {
        return (int) (v * dp + 0.5f);
    }

    protected TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(L.t(this, s));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    protected LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = px(top);
        return p;
    }

    /** Small grey section label above a group of cards. */
    protected void label(String s, int top) {
        TextView t = text(L.t(this, s).toUpperCase(), 13, 0xFF78716C);
        t.setLetterSpacing(0.12f);
        t.setPadding(px(4), 0, 0, 0);
        content.addView(t, lp(-2, -2, top));
    }

    /** White rounded card (vertical), added to the page. */
    protected LinearLayout card(int top) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.item_card);
        c.setPadding(px(16), px(14), px(16), px(14));
        content.addView(c, lp(-1, -2, top));
        return c;
    }

    /** Pill button: [filled] green/blue solid with white text, else soft green. */
    protected TextView pill(String label, int iconRes, boolean filled, int fillColor, Runnable click) {
        TextView t = new TextView(this);
        t.setText(L.t(this, label));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setMinHeight(px(40));
        t.setPadding(px(16), 0, px(16), 0);
        if (filled) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(999));
            g.setColor(fillColor);
            t.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF), g, null));
            t.setTextColor(0xFFFFFFFF);
        } else {
            t.setBackgroundResource(R.drawable.btn_soft);
            t.setTextColor(0xFF2C5379);
        }
        if (iconRes != 0) {
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
            t.setCompoundDrawablePadding(px(8));
            t.setPadding(px(14), 0, px(16), 0);
        }
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> click.run());
        return t;
    }

    protected ImageView icon(int res, int sizeDp) {
        ImageView i = new ImageView(this);
        i.setImageResource(res);
        i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        i.setLayoutParams(new ViewGroup.LayoutParams(px(sizeDp), px(sizeDp)));
        return i;
    }
}
