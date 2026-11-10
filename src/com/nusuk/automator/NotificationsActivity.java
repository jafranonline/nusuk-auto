package com.nusuk.automator;

import android.content.Intent;
import android.net.Uri;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Every announcement sent from the server, newest first (kept on the phone). */
public class NotificationsActivity extends ScreenActivity {
    private long readUpTo;

    @Override
    protected String screenTitle() {
        return "Notifications";
    }

    @Override
    protected void build() {
        barAction.setText(L.t(this, "Clear all"));
        barAction.setOnClickListener(v -> {
            Notices.clearHistory(this);
            render();
        });
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Notices.onChange = () -> runOnUiThread(() -> {
            if (!isFinishing()) render();
        });
        Notices.refreshNow(this); // pick up anything new right away
    }

    @Override
    protected void onPause() {
        super.onPause();
        Notices.onChange = null;
    }

    private void render() {
        // "new" = not read before this screen opened; opening it marks them read
        readUpTo = getSharedPreferences("notices", MODE_PRIVATE).getLong("readUpTo", 0);
        JSONArray list = Notices.history(this);
        Notices.markAllRead(this);
        content.removeAllViews();
        barAction.setVisibility(list.length() > 0 ? android.view.View.VISIBLE : android.view.View.GONE);

        if (list.length() == 0) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(px(24), px(64), px(24), 0);
            FrameLayout circle = new FrameLayout(this);
            circle.setBackgroundResource(R.drawable.circle_emerald_soft);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(px(30), px(30), Gravity.CENTER);
            circle.addView(icon(R.drawable.ic_bell_green, 30), ip);
            empty.addView(circle, new LinearLayout.LayoutParams(px(76), px(76)));
            TextView t = text("No notifications yet", 18, 0xFF1C1917);
            t.setGravity(Gravity.CENTER);
            empty.addView(t, lp(-2, -2, 16));
            TextView d = text("News and updates we send from the server will be saved here.", 14, 0xFF78716C);
            d.setGravity(Gravity.CENTER);
            empty.addView(d, lp(-2, -2, 6));
            content.addView(empty, lp(-1, -2, 0));
            return;
        }
        for (int i = 0; i < list.length(); i++) addItem(list.optJSONObject(i), i);
    }

    private void addItem(JSONObject n, int index) {
        boolean unread = n.optLong("id") > readUpTo;
        String url = n.optString("url", "");
        boolean link = url.startsWith("https://");

        LinearLayout row = new LinearLayout(this);
        row.setBackgroundResource(unread ? R.drawable.item_card_unread : R.drawable.item_card);
        row.setPadding(px(14), px(14), px(16), px(14));
        row.setOrientation(LinearLayout.HORIZONTAL);

        FrameLayout badge = new FrameLayout(this);
        badge.setBackgroundResource(R.drawable.circle_emerald_soft);
        badge.addView(icon(R.drawable.ic_bell_green, 20), new FrameLayout.LayoutParams(px(20), px(20), Gravity.CENTER));
        row.addView(badge, new LinearLayout.LayoutParams(px(40), px(40)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.setMarginStart(px(12));
        row.addView(col, cp);

        col.addView(text(n.optString("title", "Nusuk Auto"), 16, 0xFF1C1917));
        String body = n.optString("body", "");
        if (!body.isEmpty()) col.addView(text(body, 14, 0xFF44403C), lp(-2, -2, 4));
        long at = n.optLong("at", 0) * 1000;
        String when = at > 0 ? DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() : "";
        if (link) when += (when.isEmpty() ? "" : "  ·  ") + "Tap to open";
        if (!when.isEmpty()) col.addView(text(when, 12, 0xFF78716C), lp(-2, -2, 8));

        if (link) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> Links.open(this, url));
        }
        content.addView(row, lp(-1, -2, index == 0 ? 0 : 10));
    }
}
