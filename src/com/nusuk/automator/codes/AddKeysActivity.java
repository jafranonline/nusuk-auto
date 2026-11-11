package com.nusuk.automator.codes;

import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import com.nusuk.automator.R;
import com.nusuk.automator.core.L;
import com.nusuk.automator.ui.ScreenActivity;

/** Paste any text with Nusuk keys in it; they are checked and added to the shared key list. */
public class AddKeysActivity extends ScreenActivity {
    private static final int MAX_SHOWN = 40;
    private EditText input;
    private TextView submit;
    private LinearLayout results;
    private boolean busy;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected String screenTitle() {
        return "Add your own codes";
    }

    @Override
    protected void build() {
        LinearLayout c = card(0);
        c.addView(text("Paste your codes below, one or many, in any format. Codes look like NG-p36wAxup. We check each one and add the new ones to the shared list.", 16, 0xFF44403C));

        input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextSize(17);
        input.setTextColor(0xFF1C1917);
        input.setHint(L.t(this, "NG-xxxxxxxx"));
        input.setHintTextColor(0xFFA8A29E);
        input.setPadding(px(12), px(10), px(12), px(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(12));
        bg.setColor(0xFFFFFFFF);
        bg.setStroke(px(1), 0xFFD6D3D1);
        input.setBackground(bg);
        c.addView(input, lp(-1, -2, 14));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView paste = pill("Paste", 0, false, 0, this::pasteFromClipboard);
        row.addView(paste);
        android.view.View sp = new android.view.View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        submit = pill("Add codes", 0, true, 0xFF3E7CB1, this::onSubmit);
        row.addView(submit);
        c.addView(row, lp(-1, -2, 12));

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        content.addView(results, lp(-1, -2, 0));
    }

    private void pasteFromClipboard() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (t != null && t.length() > 0) {
                input.getText().insert(Math.max(0, input.getSelectionStart()), t);
                return;
            }
        }
        android.widget.Toast.makeText(this, L.t(this, "Nothing to paste"), android.widget.Toast.LENGTH_SHORT).show();
    }

    private void onSubmit() {
        if (busy) return;
        KeySubmit.Parsed p = KeySubmit.parse(input.getText().toString());
        results.removeAllViews();
        if (p.keys.isEmpty() && p.invalid.isEmpty()) {
            showMessage("No codes found in the text. A code looks like NG-p36wAxup.", 0xFFB45309);
            return;
        }
        if (p.keys.isEmpty()) {
            show(p, new KeySubmit.Result());
            return;
        }
        busy = true;
        submit.setText(L.t(this, "Checking..."));
        submit.setAlpha(0.6f);
        final List<String> keys = new ArrayList<>(p.keys);
        new Thread(() -> {
            KeySubmit.Result r = KeySubmit.submit(getApplicationContext(), keys);
            main.post(() -> {
                busy = false;
                submit.setText(L.t(this, "Add codes"));
                submit.setAlpha(1f);
                if (isFinishing() || isDestroyed()) return;
                show(p, r);
                if (!r.added.isEmpty()) CodeDownloader.start(getApplicationContext(), true); // bring them into this phone's list too
            });
        }, "key-submit").start();
    }

    private void show(KeySubmit.Parsed p, KeySubmit.Result r) {
        results.removeAllViews();
        List<String> dup = new ArrayList<>(r.duplicate);
        dup.addAll(p.repeated);
        List<String> bad = new ArrayList<>(p.invalid);
        for (String k : r.invalid) if (!bad.contains(k)) bad.add(k);

        if (r.error != null) {
            showMessage("NETWORK".equals(r.error) ? "Could not reach the server. Check your internet and try again."
                    : r.error.startsWith("SERVER ") ? "The server could not add the codes right now (error " + r.error.substring(7) + "). Please try again later."
                    : r.error, 0xFFB91C1C);
        }
        if (!r.added.isEmpty()) group("✓  " + r.added.size() + " new code" + (r.added.size() == 1 ? "" : "s") + " added. Thank you!", r.added, 0xFF15803D);
        if (!dup.isEmpty()) group("＝  " + dup.size() + " already in the list", dup, 0xFFB45309);
        if (!bad.isEmpty()) group("✗  " + bad.size() + " not a valid code (NG- and 8 letters or digits)", bad, 0xFFB91C1C);
        if (r.error == null && r.added.isEmpty() && dup.isEmpty() && bad.isEmpty()) showMessage("Nothing to add.", 0xFF78716C);
        if (r.error == null && !r.added.isEmpty()) input.setText(""); // done: ready for the next paste
    }

    private void showMessage(String msg, int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.item_card);
        c.setPadding(px(16), px(14), px(16), px(14));
        c.addView(text(msg, 16, color));
        results.addView(c, lp(-1, -2, 12));
    }

    private void group(String title, List<String> keys, int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.item_card);
        c.setPadding(px(16), px(14), px(16), px(14));
        TextView t = new TextView(this);
        t.setText(L.t(this, title));
        t.setTextSize(17);
        t.setTextColor(color);
        t.setTypeface(getResources().getFont(R.font.plex_medium));
        c.addView(t);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(MAX_SHOWN, keys.size()); i++) sb.append(keys.get(i)).append('\n');
        if (keys.size() > MAX_SHOWN) sb.append("+ ").append(keys.size() - MAX_SHOWN).append(" ...");
        TextView list = new TextView(this);
        list.setText(sb.toString().trim());
        list.setTextSize(15);
        list.setTextColor(0xFF57534E);
        list.setTypeface(android.graphics.Typeface.MONOSPACE);
        list.setTextIsSelectable(true);
        c.addView(list, lp(-1, -2, 8));
        results.addView(c, lp(-1, -2, 12));
    }
}
