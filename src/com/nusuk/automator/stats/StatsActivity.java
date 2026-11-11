package com.nusuk.automator.stats;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.nusuk.automator.R;
import com.nusuk.automator.codes.KeyRepository;
import com.nusuk.automator.core.L;
import com.nusuk.automator.ui.ScreenActivity;

/** Today's numbers, all-time totals, and a date-wise breakdown of codes added and quiz questions solved. */
public class StatsActivity extends ScreenActivity {

    @Override
    protected String screenTitle() {
        return "Statistics";
    }

    @Override
    protected void onResume() {
        super.onResume();
        content.removeAllViews();
        build();
    }

    @Override
    protected void build() {
        int codesTotal = KeyRepository.getInstance(this).getSuccessCount();
        int quizTotal = Stats.quizTotal(this);

        content.addView(text("This is your own usage on this phone.", 16, 0xFF78716C), lp(-2, -2, 0));

        // ----- today + total -----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(bigStat("Codes today", Stats.codesToday(this), "Total " + codesTotal), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMarginStart(px(10));
        top.addView(bigStat("Quiz today", Stats.quizToday(this), "Total " + quizTotal), lp);
        content.addView(top, lp(-1, -2, 14));

        // ----- by date -----
        label("By date", 22);
        LinearLayout card = card(8);
        card.setPadding(px(16), px(6), px(16), px(6));
        card.addView(row("Date", "Codes", "Quiz", true));

        List<Stats.Day> days = Stats.days(this);
        int trackedCodes = 0, trackedQuiz = 0;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        for (Stats.Day d : days) {
            trackedCodes += d.codes;
            trackedQuiz += d.quiz;
            card.addView(divider());
            card.addView(row(niceDate(d.date, today), String.valueOf(d.codes), String.valueOf(d.quiz), false));
        }
        // activity from before the per-day history existed can't be split by date
        int olderCodes = Math.max(0, codesTotal - trackedCodes), olderQuiz = Math.max(0, quizTotal - trackedQuiz);
        if (olderCodes > 0 || olderQuiz > 0) {
            card.addView(divider());
            card.addView(row("Before daily tracking", String.valueOf(olderCodes), String.valueOf(olderQuiz), false));
        }
        if (days.isEmpty() && olderCodes == 0 && olderQuiz == 0) {
            TextView t = text("Nothing yet. Codes you add and questions the app solves are counted here by day.", 16, 0xFF78716C);
            t.setPadding(0, px(12), 0, px(12));
            card.addView(t);
        }
    }

    private LinearLayout bigStat(String label, int value, String sub) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.item_card);
        c.setPadding(px(16), px(14), px(16), px(14));
        TextView v = text(String.valueOf(value), 34, 0xFF1C1917);
        v.setTypeface(getResources().getFont(R.font.plex_light));
        c.addView(v);
        c.addView(text(label, 16, 0xFF1C1917), lp(-2, -2, 2));
        c.addView(text(sub, 13, 0xFF78716C), lp(-2, -2, 6));
        return c;
    }

    private LinearLayout row(String date, String codes, String quiz, boolean head) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(px(head ? 36 : 48));
        int color = head ? 0xFF78716C : 0xFF1C1917;
        float sp = head ? 12 : 15;
        r.addView(text(head ? L.t(this, date).toUpperCase() : date, sp, color), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(cell(head ? L.t(this, codes).toUpperCase() : codes, sp, color), new LinearLayout.LayoutParams(px(64), -2));
        r.addView(cell(head ? L.t(this, quiz).toUpperCase() : quiz, sp, color), new LinearLayout.LayoutParams(px(64), -2));
        return r;
    }

    private TextView cell(String s, float sp, int color) {
        TextView t = text(s, sp, color);
        t.setGravity(Gravity.END);
        return t;
    }

    private android.view.View divider() {
        android.view.View v = new android.view.View(this);
        v.setBackgroundColor(0xFFEFEDEB);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, px(1)));
        return v;
    }

    private String niceDate(String ymd, String today) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date d = in.parse(ymd);
            if (ymd.equals(today)) return "Today";
            Date yesterday = new Date(in.parse(today).getTime() - 86_400_000L);
            if (ymd.equals(in.format(yesterday))) return "Yesterday";
            return new SimpleDateFormat("EEE, d MMM yyyy", L.locale(this)).format(d);
        } catch (Exception e) {
            return ymd;
        }
    }
}
