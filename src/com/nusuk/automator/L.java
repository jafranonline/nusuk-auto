package com.nusuk.automator;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * App language: English (default) or Bangla, chosen in Settings.
 * Every text in the app is written in English; {@link #t} swaps it for the Bangla one when Bangla
 * is on (exact match first, then pattern rules for texts with numbers / codes inside).
 * Texts that aren't listed (server messages, the user's own data) stay as they are.
 */
final class L {
    static final String EN = "en", BN = "bn";
    private static final String PREFS = "language", KEY = "lang";
    private static volatile String lang = null;

    private static final Map<String, String> EXACT = new HashMap<>();
    private static final Map<String, String> RULES = new LinkedHashMap<>();

    private L() {}

    // ---------- language choice ----------

    static String lang(Context c) {
        String l = lang;
        if (l == null) {
            SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            l = BN.equals(p.getString(KEY, EN)) ? BN : EN;
            lang = l;
        }
        return l;
    }

    static boolean isBn(Context c) {
        return BN.equals(lang(c));
    }

    static void set(Context c, String l) {
        lang = BN.equals(l) ? BN : EN;
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).apply();
    }

    static Locale locale(Context c) {
        return isBn(c) ? new Locale("bn", "BD") : Locale.getDefault();
    }

    // ---------- translating ----------

    /** The text in the chosen language. */
    static String t(Context c, String s) {
        if (s == null || s.isEmpty() || !isBn(c)) return s;
        return bn(s);
    }

    /** Translates every TextView's text and every content description under {@code root}. Call once after inflating. */
    static void view(Context c, View root) {
        if (root == null || !isBn(c)) return;
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            CharSequence cs = tv.getText();
            if (cs != null && cs.length() > 0 && !(root instanceof android.widget.EditText)) tv.setText(bn(cs.toString()));
            CharSequence hint = tv.getHint();
            if (hint != null) tv.setHint(bn(hint.toString()));
        }
        CharSequence d = root.getContentDescription();
        if (d != null) root.setContentDescription(bn(d.toString()));
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) view(c, g.getChildAt(i));
        }
    }

    private static String bn(String s) {
        String key = s.replace("’", "'").replace("…", "...");
        String hit = EXACT.get(key);
        if (hit != null) return digits(hit);
        // leading symbols (▶ ❚❚ ■ ✕ ...) stay, the words after them are translated
        int i = 0;
        while (i < key.length() && !Character.isLetterOrDigit(key.charAt(i)) && key.charAt(i) != '(') i++;
        if (i > 0 && i < key.length()) {
            String rest = key.substring(i);
            String r = EXACT.get(rest);
            if (r != null) return key.substring(0, i) + digits(r);
        }
        for (Map.Entry<String, String> e : RULES.entrySet()) {
            Matcher m = Pattern.compile(e.getKey(), Pattern.DOTALL).matcher(key);
            if (m.matches()) {
                String out = e.getValue();
                for (int g = 1; g <= m.groupCount(); g++) out = out.replace("$" + g, "\u0001" + (char) ('a' + g));
                out = digits(out);
                for (int g = 1; g <= m.groupCount(); g++) {
                    String part = m.group(g);
                    if (part == null) part = "";
                    // numbers / times become Bangla digits; codes and names (letters + digits) stay as they are
                    if (part.matches("[\\d\\s/:.,%()+\\-]*(\\s?[AaPp][Mm])?")) part = digits(part);
                    out = out.replace("\u0001" + (char) ('a' + g), part);
                }
                return out;
            }
        }
        // plain numbers ("0 / 100", "9+", "v1.9", "✓ 3   ✗ 1")
        if (key.matches("v?[^A-Za-z]*") && key.matches(".*\\d.*")) return digits(s);
        return s;
    }

    /** 0-9 -> ০-৯. */
    static String digits(String s) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                if (b == null) b = new StringBuilder(s);
                b.setCharAt(i, (char) ('\u09E6' + (ch - '0')));
            }
        }
        return b == null ? s : b.toString();
    }

    private static void x(String en, String bn) {
        EXACT.put(en, bn);
    }

    private static void r(String regex, String bn) {
        RULES.put(regex, bn);
    }

    static {
        // ----- main screen -----
        x("Notifications", "নোটিফিকেশন");
        x("Open statistics", "পরিসংখ্যান দেখো");
        x("Codes today", "আজকের কোড");
        x("Quiz today", "আজকের কুইজ");
        x("Adding codes", "কোড যোগ হচ্ছে");
        x("Playing quiz", "কুইজ খেলা হচ্ছে");
        x("Running in the background", "ব্যাকগ্রাউন্ডে চলছে");
        x("Stop", "থামান");
        x("Stopped", "থামানো হয়েছে");
        x("Setup required", "সেটআপ প্রয়োজন");
        x("Finish setup to work inside Nusuk.", "নুসুকের ভেতরে কাজ করতে সেটআপ শেষ করো।");
        x("Nusuk app not installed", "নুসুক অ্যাপ ইনস্টল করা নেই");
        x("Install", "ইনস্টল");
        x("Accessibility service", "অ্যাক্সেসিবিলিটি সার্ভিস");
        x("Accessibility stopped: turn it off and on", "অ্যাক্সেসিবিলিটি বন্ধ হয়ে গেছে: একবার বন্ধ করে আবার চালু করো");
        x("Enable", "চালু করো");
        x("Fix", "ঠিক করো");
        x("Floating overlay", "ভাসমান ওভারলে");
        x("Allow", "অনুমতি দাও");
        x("Add codes", "কোড অ্যাড করো");
        x("Enters Umrah codes for you", "ওমরাহ কোড নিজে থেকেই দেওয়া হবে");
        x("Play quiz", "কুইজ খেলো");
        x("Answers the Nusuk quiz for you", "তোমার হয়ে নুসুক কুইজের উত্তর দেয়");
        x("Help", "সাহায্য");
        x("Fixes and guides", "সমস্যার সমাধান ও গাইড");
        x("Facebook group", "ফেসবুক গ্রুপ");
        x("Join community", "কমিউনিটিতে জয়েন করো");
        x("DISCOVER", "আবিষ্কার করো");
        x("Al Fitan, the largest digital archive of the trials and signs of the Hour.",
                "আল ফিতান: ফিতনা ও কিয়ামতের আলামত নিয়ে সবচেয়ে বড় ডিজিটাল আর্কাইভ।");
        x("Visit alfitan.com", "alfitan.com ভিজিট করো");
        x("DEVELOPED BY", "ডেভেলপার");
        x("Jafran Hasan", "জাফরান হাসান");
        x("Settings", "সেটিংস");
        x("Back", "পেছনে");
        x("Downloading codes...", "কোড ডাউনলোড হচ্ছে...");
        x("Updating codes...", "কোড আপডেট হচ্ছে...");
        x("Couldn't download codes. Tap to retry", "কোড ডাউনলোড করা যায়নি। আবার চেষ্টা করতে ট্যাপ করো");
        x("Codes are still downloading...", "কোড এখনো ডাউনলোড হচ্ছে...");
        x("No codes yet. Connect to the internet and tap the banner to retry.", "এখনো কোড নেই। ইন্টারনেটে যুক্ত হয়ে ব্যানারে ট্যাপ করো।");
        x("Install the Nusuk app first", "আগে নুসুক অ্যাপ ইনস্টল করো");
        x("Android stopped Nusuk Auto. In Accessibility, turn it off and on again.", "অ্যান্ড্রয়েড Nusuk Auto বন্ধ করে দিয়েছে। অ্যাক্সেসিবিলিটিতে গিয়ে একবার বন্ধ করে আবার চালু করো।");
        x("Enable the accessibility service first", "আগে অ্যাক্সেসিবিলিটি সার্ভিস চালু করো");
        x("Allow the floating overlay first", "আগে ভাসমান ওভারলের অনুমতি দাও");
        x("Nusuk Auto isn't connected. In Accessibility, turn Nusuk Auto off and on again.", "Nusuk Auto যুক্ত নেই। অ্যাক্সেসিবিলিটিতে গিয়ে Nusuk Auto একবার বন্ধ করে আবার চালু করো।");
        x("Play Quiz", "কুইজ খেলো");
        x("Play Quiz isn't available right now.", "এখন কুইজ খেলা যাচ্ছে না।");
        x("OK", "ঠিক আছে");
        r("Update ↑ v(.+)", "আপডেট ↑ v$1");
        r("Daily limit resets every day at (.+?) \\(your time\\)", "দৈনিক সীমা প্রতিদিন $1-এ রিসেট হয় (তোমার সময়)");
        r("Daily limit resets every day at (.+)", "দৈনিক সীমা প্রতিদিন $1-এ রিসেট হয়");
        r("Could not launch Nusuk app: (.*)", "নুসুক অ্যাপ চালু করা যায়নি: $1");

        // ----- overlay -----
        x("Codes", "কোড");
        x("Quiz", "কুইজ");
        x("Ready", "প্রস্তুত");
        x("Human-like pace", "মানুষের মতো গতি");
        x("Pause", "বিরতি");
        x("Resume", "চালু করো");
        x("Start", "শুরু");
        x("Start quiz", "কুইজ শুরু");
        x("Start codes", "কোড শুরু");
        x("Close", "বন্ধ");
        x("Normal", "স্বাভাবিক");
        x("Fast", "দ্রুত");
        r("Quiz pace: (.+)", "কুইজের গতি: $1");

        // ----- status lines (from the automation) -----
        x("Stopped by user", "তুমি থামিয়েছ");
        x("Interrupted", "মাঝপথে থেমে গেছে");
        x("Service destroyed", "সার্ভিস বন্ধ হয়ে গেছে");
        x("Starting Quiz...", "কুইজ শুরু হচ্ছে...");
        x("Starting Codes...", "কোড শুরু হচ্ছে...");
        x("Paused", "বিরতিতে আছে");
        x("Paused: open Nusuk to continue", "বিরতি: চালিয়ে যেতে নুসুক খোলো");
        x("Resuming...", "আবার শুরু হচ্ছে...");
        x("Done", "শেষ");
        x("Thinking...", "ভাবছে...");
        r("Thinking\\.\\.\\. (\\d+)s", "ভাবছে... $1 সেকেন্ড");
        x("Scrolling to Play again...", "'Play again'-এ স্ক্রল করছে...");
        x("Confirming door...", "দরজা নিশ্চিত করছে...");
        x("Scrolling down...", "নিচে স্ক্রল করছে...");
        x("Navigating to Play...", "Play-তে যাচ্ছে...");
        x("Starting challenge...", "চ্যালেঞ্জ শুরু হচ্ছে...");
        x("Opening Challenge...", "চ্যালেঞ্জ খুলছে...");
        x("Navigating to Keys...", "Keys-এ যাচ্ছে...");
        x("Leaving quiz result...", "কুইজের ফলাফল থেকে বেরিয়ে আসছে...");
        x("Log in to Nusuk first", "আগে নুসুকে লগইন করো");
        x("Nusuk is in Arabic. Switch Nusuk to English, then press Start.", "নুসুক আরবিতে আছে। নুসুককে ইংরেজি করো, তারপর Start চাপো।");
        x("Something went wrong. Open Nusuk and press Start again.", "কিছু একটা সমস্যা হয়েছে। নুসুক ওপেন করে আবার Start চাপো।");
        x("All codes tested!", "সব কোড পরীক্ষা হয়ে গেছে!");
        x("Loading your used codes...", "তোমার ব্যবহৃত কোড লোড হচ্ছে...");
        x("Couldn't load your used codes, continuing", "ব্যবহৃত কোড লোড করা যায়নি, তবুও চালিয়ে যাচ্ছে");
        x("Opening Nusuk home...", "নুসুকের হোম খুলছে...");
        x("Looking for the challenge...", "চ্যালেঞ্জ খুঁজছে...");
        x("Going back to Nusuk home...", "নুসুকের হোমে ফিরছে...");
        x("Restarting Nusuk...", "নুসুক আবার চালু করছে...");
        x("Couldn't find the challenge. Open it in Nusuk, then press Start.", "চ্যালেঞ্জ খুঁজে পাওয়া যায়নি। নুসুকে এটি খুলে Start চাপো।");
        r("Done \\((\\d+) quizzes, (\\d+) ✓\\)", "শেষ ($1টি কুইজ, $2 ✓)");
        r("Done \\((\\d+) codes\\)", "শেষ ($1টি কোড)");
        r("Next quiz in (\\d+)s \\((.+)\\)", "পরের কুইজ $1 সেকেন্ডে ($2)");
        r("Playing next quiz \\((.+)\\)\\.\\.\\.", "পরের কুইজ খেলছে ($1)...");
        r("Selecting Door (\\d+)\\.\\.\\.", "দরজা $1 বাছাই করছে...");
        r("Selected \\(saved\\): (.*)", "বাছাই করেছে (সংরক্ষিত): $1");
        r("Collecting key \\((.+)\\)", "কী সংগ্রহ করছে ($1)");
        r("Entering (\\S+) \\((.+)\\)", "$1 দিচ্ছে ($2)");
        r("Checking (\\S+) \\((.+)\\)", "$1 যাচাই করছে ($2)");
        r("Retrying (\\S+)", "$1 আবার চেষ্টা করছে");
        r("Skipped for now: (\\S+)", "আপাতত বাদ: $1");

        // ----- settings -----
        x("Language", "ভাষা");
        x("Help & guides", "হেল্প ও গাইড");
        x("Share", "শেয়ার");
        x("Share the app", "অ্যাপটা শেয়ার করো");
        x("Know someone who does Umrah quizzes? Share the app with them.", "চেনা কেউ ওমরাহ কুইজ করে? তাকে অ্যাপটা শেয়ার করো।");
        x("Something not working? Send a report with your app version, phone model and recent errors.", "কিছু কাজ করছে না? অ্যাপ ভার্সন, ফোনের মডেল আর সাম্প্রতিক এরর সহ একটি রিপোর্ট পাঠাও।");
        x("Share debug report", "ডিবাগ রিপোর্ট শেয়ার করো");
        x("English", "English");
        x("Choose the language of the app.", "অ্যাপের ভাষা বাছাই করো।");
        x("Quiz pace", "কুইজের গতি");
        x("How fast quizzes are played. Nusuk may limit accounts that answer at machine speed.",
                "কুইজ কত দ্রুত খেলা হবে। মেশিনের গতিতে উত্তর দিলে নুসুক অ্যাকাউন্ট সীমিত করে দিতে পারে।");
        x("Normal (recommended)\nThinks 1.5-5 s before answering, waits about 4-25 s between quizzes, and rests 30-90 s every 8-15 quizzes.",
                "স্বাভাবিক (প্রস্তাবিত)\nউত্তর দেওয়ার আগে ১.৫-৫ সেকেন্ড ভাবে, দুই কুইজের মাঝে প্রায় ৪-২৫ সেকেন্ড অপেক্ষা করে, আর প্রতি ৮-১৫টি কুইজ পরপর ৩০-৯০ সেকেন্ড বিশ্রাম নেয়।");
        x("Fast\nNo extra waiting. Quickest, but looks like a machine and may get throttled.",
                "দ্রুত\nবাড়তি অপেক্ষা নেই। সবচেয়ে দ্রুত, কিন্তু মেশিনের মতো দেখায় এবং আটকে দেওয়া হতে পারে।");

        // ----- statistics -----
        x("Statistics", "পরিসংখ্যান");
        x("By date", "তারিখ অনুযায়ী");
        x("Date", "তারিখ");
        x("Today", "আজ");
        x("Yesterday", "গতকাল");
        x("Before daily tracking", "দৈনিক হিসাব শুরুর আগে");
        x("Nothing yet. Codes you add and questions the app solves are counted here by day.",
                "এখনো কিছু নেই। তুমি যত কোড অ্যাড করবে ও অ্যাপ যত প্রশ্নের সলভ করবে, দিন অনুযায়ী এখানে গোনা হবে।");
        r("Total (\\d+)", "মোট $1");

        // ----- notifications -----
        x("Clear all", "সব মুছো");
        x("No notifications yet", "এখনো কোনো নোটিফিকেশন নেই");
        x("News and updates we send from the server will be saved here.", "সার্ভার থেকে পাঠানো খবর ও আপডেট এখানে জমা থাকবে।");
        x("News and updates from Nusuk Auto", "Nusuk Auto-র খবর ও আপডেট");
        r("(.*)Tap to open", "$1ট্যাপ করে খোলো");

        // ----- developer -----
        x("Developer", "ডেভেলপার");
        x("Developer of Nusuk Auto", "Nusuk Auto-র ডেভেলপার");
        x("Facebook profile", "ফেসবুক প্রোফাইল");
        x("Join the Facebook group", "ফেসবুক গ্রুপে জয়েন করো");
        x("Nusuk Auto is an unofficial helper made for Nusuk users. It is not connected to the Nusuk app or the Ministry of Hajj and Umrah.",
                "Nusuk Auto নুসুক ব্যবহারকারীদের জন্য বানানো একটি অনানুষ্ঠানিক সহায়ক অ্যাপ। নুসুক অ্যাপ বা হজ ও ওমরাহ মন্ত্রণালয়ের সঙ্গে এর কোনো সম্পর্ক নেই।");
        x("Questions, problems or ideas? Open Help for step-by-step fixes, or write in the Facebook group or to the developer.",
                "প্রশ্ন, সমস্যা বা আইডিয়া আছে? ধাপে ধাপে সমাধানের জন্য 'সাহায্য' খোলো, অথবা ফেসবুক গ্রুপে বা ডেভেলপারকে লেখো।");
        r("Version (\\d[\\d.]*)", "ভার্সন $1");

        // ----- tutorials + updates -----
        x("Couldn't load the tutorials.\nCheck your internet, then tap here to try again.", "টিউটোরিয়াল লোড করা যায়নি।\nইন্টারনেট দেখে এখানে ট্যাপ করে আবার চেষ্টা করো।");
        x("Checking for updates...", "আপডেট খোঁজা হচ্ছে...");
        x("You have the latest version", "তোমার কাছে সর্বশেষ ভার্সন আছে");
        x("Could not check for updates. Check your internet.", "আপডেট দেখা যায়নি। ইন্টারনেট চেক করো।");
        x("This version no longer works properly. Please install the new version to continue.", "এই ভার্সন আর ঠিকভাবে কাজ করে না। চালিয়ে যেতে নতুন ভার্সন ইনস্টল করো।");
        x("A new version of Nusuk Auto is ready.", "Nusuk Auto-র নতুন ভার্সন প্রস্তুত।");
        x("When the download finishes, open it and tap Update.", "ডাউনলোড শেষ হলে সেটি খুলে Update-এ ট্যাপ করো।");
        x("What's new:", "নতুন কী আছে:");
        x("Your progress is kept when you update.", "আপডেট করলেও তোমার অগ্রগতি থেকে যাবে।");
        x("Update", "আপডেট");
        x("Later", "পরে");
        x("Update required", "আপডেট আবশ্যক");
        x("Update available", "আপডেট পাওয়া যাচ্ছে");

        // ----- casual Bangladeshi-youth wording (overrides the lines above) -----
        x("Open statistics", "স্ট্যাটস দেখো");
        x("Adding codes", "কোড অ্যাড হচ্ছে");
        x("Stop", "থামাও");
        x("Setup required", "সেটআপ করতে হবে");
        x("Finish setup to work inside Nusuk.", "নুসুকের ভেতরে কাজ করতে সেটআপ শেষ করো।");
        x("Accessibility stopped: turn it off and on", "অ্যাক্সেসিবিলিটি বন্ধ হয়ে গেছে, অফ করে আবার অন করো");
        x("Enable", "অন করো");
        x("Fix", "ঠিক করো");
        x("Floating overlay", "ফ্লোটিং ওভারলে");
        x("Allow", "অ্যালাউ করো");
        x("Add codes", "কোড অ্যাড করো");
        x("Enters Umrah codes for you", "ওমরাহ কোড অটো এন্টার হবে");
        x("Play quiz", "কুইজ খেলো");
        x("Answers the Nusuk quiz for you", "তোমার হয়ে নুসুক কুইজ সলভ করে");
        x("Help", "হেল্প");
        x("Fixes and guides", "সমস্যার সমাধান আর গাইড");
        x("Join community", "কমিউনিটিতে জয়েন করো");
        x("DISCOVER", "এক্সপ্লোর করো");
        x("Al Fitan, the largest digital archive of the trials and signs of the Hour.", "আল ফিতান: ফিতনা আর কিয়ামতের আলামত নিয়ে সবচেয়ে বড় ডিজিটাল আর্কাইভ।");
        x("Visit alfitan.com", "alfitan.com ঘুরে এসো");
        x("Back", "ব্যাক");
        x("Couldn't download codes. Tap to retry", "কোড ডাউনলোড হয়নি। আবার চেষ্টা করতে ট্যাপ করো");
        x("No codes yet. Connect to the internet and tap the banner to retry.", "এখনো কোড নেই। নেট অন করে ব্যানারে ট্যাপ করো।");
        x("Install the Nusuk app first", "আগে নুসুক অ্যাপ ইনস্টল করো");
        x("Android stopped Nusuk Auto. In Accessibility, turn it off and on again.", "অ্যান্ড্রয়েড Nusuk Auto বন্ধ করে দিয়েছে। অ্যাক্সেসিবিলিটিতে গিয়ে অফ করে আবার অন করো।");
        x("Enable the accessibility service first", "আগে অ্যাক্সেসিবিলিটি সার্ভিস অন করো");
        x("Allow the floating overlay first", "আগে ফ্লোটিং ওভারলে অ্যালাউ করো");
        x("Nusuk Auto isn't connected. In Accessibility, turn Nusuk Auto off and on again.", "Nusuk Auto কানেক্টেড নেই। অ্যাক্সেসিবিলিটিতে গিয়ে Nusuk Auto অফ করে আবার অন করো।");
        x("Play Quiz", "কুইজ খেলো");
        r("Daily limit resets every day at (.+?) \\(your time\\)", "লিমিট রোজ $1-এ রিসেট (তোমার সময়)");
        r("Daily limit resets every day at (.+)", "লিমিট রোজ $1-এ রিসেট হয়");
        r("Could not launch Nusuk app: (.*)", "নুসুক অ্যাপ ওপেন হয়নি: $1");
        x("ওমরাহ", "ওমরাহ");
        x("Ready", "রেডি");
        x("Human-like pace", "মানুষের মতো স্পিড");
        x("Pause", "পজ");
        x("Resume", "রিজিউম");
        x("Start", "স্টার্ট");
        x("Start quiz", "কুইজ স্টার্ট");
        x("Start codes", "কোড স্টার্ট");
        x("Close", "ক্লোজ");
        x("Normal", "নরমাল");
        x("Fast", "ফাস্ট");
        r("Quiz pace: (.+)", "কুইজ স্পিড: $1");
        x("Stopped by user", "তুমি থামিয়েছ");
        x("Paused", "পজ করা আছে");
        x("Paused: open Nusuk to continue", "পজ: কন্টিনিউ করতে নুসুক ওপেন করো");
        x("Log in to Nusuk first", "আগে নুসুকে লগইন করো");
        x("Nusuk is in Arabic. Switch Nusuk to English, then press Start.", "নুসুক আরবিতে আছে। নুসুককে ইংরেজি করো, তারপর Start চাপো।");
        x("Something went wrong. Open Nusuk and press Start again.", "কিছু একটা সমস্যা হয়েছে। নুসুক ওপেন করে আবার Start চাপো।");
        x("All codes tested!", "সব কোড টেস্ট শেষ!");
        x("Couldn't find the challenge. Open it in Nusuk, then press Start.", "চ্যালেঞ্জ পাওয়া যায়নি। নুসুকে চ্যালেঞ্জ ওপেন করে Start চাপো।");
        r("Selecting Door (\\d+)\\.\\.\\.", "দরজা $1 সিলেক্ট করছে...");
        x("Choose the language of the app.", "অ্যাপের ভাষা বেছে নাও।");
        x("Quiz pace", "কুইজ স্পিড");
        x("How fast quizzes are played. Nusuk may limit accounts that answer at machine speed.", "কুইজ কত স্পিডে খেলা হবে। মেশিনের স্পিডে উত্তর দিলে নুসুক অ্যাকাউন্টে লিমিট দিয়ে দিতে পারে।");
        x("Normal (recommended)\nThinks 1.5-5 s before answering, waits about 4-25 s between quizzes, and rests 30-90 s every 8-15 quizzes.",
                "নরমাল (রেকমেন্ডেড)\nউত্তর দেওয়ার আগে ১.৫-৫ সেকেন্ড ভাবে, দুই কুইজের মাঝে ৪-২৫ সেকেন্ডের মতো গ্যাপ নেয়, আর প্রতি ৮-১৫টা কুইজ পর ৩০-৯০ সেকেন্ড রেস্ট নেয়।");
        x("Fast\nNo extra waiting. Quickest, but looks like a machine and may get throttled.",
                "ফাস্ট\nএক্সট্রা ওয়েট নেই। সবচেয়ে ফাস্ট, কিন্তু মেশিনের মতো দেখায় আর আটকে যেতে পারে।");
        x("Statistics", "স্ট্যাটস");
        x("By date", "ডেট অনুযায়ী");
        x("Date", "ডেট");
        x("Before daily tracking", "ডেইলি ট্র্যাকিং শুরুর আগে");
        x("Nothing yet. Codes you add and questions the app solves are counted here by day.", "এখনো কিছু নেই। তুমি যত কোড অ্যাড করবে আর অ্যাপ যত প্রশ্ন সলভ করবে, দিন অনুযায়ী এখানে দেখা যাবে।");
        x("Clear all", "সব ক্লিয়ার");
        x("News and updates we send from the server will be saved here.", "সার্ভার থেকে পাঠানো নিউজ আর আপডেট এখানে জমা থাকবে।");
        x("News and updates from Nusuk Auto", "Nusuk Auto-র নিউজ ও আপডেট");
        r("(.*)Tap to open", "$1ট্যাপ করে ওপেন করো");
        x("Join the Facebook group", "ফেসবুক গ্রুপে জয়েন করো");
        x("Nusuk Auto is an unofficial helper made for Nusuk users. It is not connected to the Nusuk app or the Ministry of Hajj and Umrah.",
                "Nusuk Auto নুসুক ইউজারদের জন্য বানানো একটা আনঅফিশিয়াল হেল্পার অ্যাপ। নুসুক অ্যাপ বা হজ ও ওমরাহ মন্ত্রণালয়ের সাথে এর কোনো সম্পর্ক নেই।");
        x("Questions, problems or ideas? Open Help for step-by-step fixes, or write in the Facebook group or to the developer.",
                "কোনো প্রশ্ন, সমস্যা বা আইডিয়া আছে? স্টেপ বাই স্টেপ সমাধানের জন্য 'হেল্প' ওপেন করো, অথবা ফেসবুক গ্রুপে বা ডেভেলপারকে লেখো।");
        x("Couldn't load the tutorials.\nCheck your internet, then tap here to try again.", "টিউটোরিয়াল লোড হয়নি।\nনেট চেক করে এখানে ট্যাপ করে আবার চেষ্টা করো।");
        x("Checking for updates...", "আপডেট চেক করা হচ্ছে...");
        x("You have the latest version", "তুমি লেটেস্ট ভার্সনেই আছো");
        x("Could not check for updates. Check your internet.", "আপডেট চেক করা যায়নি। নেট চেক করো।");
        x("This version no longer works properly. Please install the new version to continue.", "এই ভার্সন আর ঠিকমতো কাজ করে না। চালিয়ে যেতে নতুন ভার্সন ইনস্টল করো।");
        x("A new version of Nusuk Auto is ready.", "Nusuk Auto-র নতুন ভার্সন এসেছে।");
        x("When the download finishes, open it and tap Update.", "ডাউনলোড শেষ হলে ফাইলটা ওপেন করে Update-এ ট্যাপ করো।");
        x("Your progress is kept when you update.", "আপডেট করলেও তোমার প্রোগ্রেস থেকে যাবে।");
        x("Update required", "আপডেট দরকার");
        x("Update available", "আপডেট এসেছে");
        x("Al Fitan", "আল ফিতান");
        x("About", "অ্যাবাউট");
        x("Adds Umrah codes and plays the Nusuk quiz for you, so you save time.", "তোমার হয়ে ওমরাহ কোড অ্যাড করে আর নুসুক কুইজ খেলে, ফলে সময় বাঁচে।");
        x("Check for updates", "আপডেট চেক করো");
        r("New version v(.+) is available", "নতুন ভার্সন v$1 এসেছে");
        x("Choose how fast the quiz is played.", "কুইজ কত স্পিডে খেলা হবে, বেছে নাও।");
        x("Normal (recommended)", "নরমাল (রেকমেন্ডেড)");
        x("Takes a few seconds per question and pauses between quizzes, like a real person. Safest for your account.", "প্রতি প্রশ্নে কয়েক সেকেন্ড সময় নেয় আর কুইজের মাঝে বিরতি দেয়, ঠিক মানুষের মতো। অ্যাকাউন্টের জন্য সবচেয়ে সেইফ।");
        x("No waiting at all. Quicker, but looks like a bot and your account may get limited.", "কোনো অপেক্ষা নেই। দ্রুত হয়, কিন্তু বটের মতো দেখায় আর অ্যাকাউন্টে লিমিট পড়তে পারে।");

        // ----- thinking time, add keys, in-app update (v2.1) -----
        x("Thinks for a moment before giving each answer, like a real person. The next door is chosen at once. Safest for your account.",
                "প্রতিটা উত্তর দেওয়ার আগে একটু ভাবে, ঠিক মানুষের মতো। পরের দরজা সাথে সাথেই বেছে নেয়। অ্যাকাউন্টের জন্য সবচেয়ে সেইফ।");
        x("Thinking time before each answer", "প্রতিটা উত্তরের আগে ভাবার সময়");
        x("Shortest", "সবচেয়ে কম");
        x("Longest", "সবচেয়ে বেশি");
        r("(\\d+) s", "$1 সে.");
        x("Paste", "পেস্ট");
        x("Nothing to paste", "পেস্ট করার মতো কিছু নেই");
        x("Checking...", "চেক করছি...");
        x("Could not reach the server. Check your internet and try again.", "সার্ভারে পৌঁছানো যায়নি। ইন্টারনেট চেক করে আবার চেষ্টা করো।");
        x("Nothing to add.", "যোগ করার মতো কিছু নেই।");
        r("＝  (\\d+) already in the list", "＝  $1টা আগে থেকেই লিস্টে আছে");
        x("Allow installing updates", "আপডেট ইনস্টলের অনুমতি দাও");
        x("Android needs your OK once so Nusuk Auto can install its own updates. Turn on \"Allow from this source\", then come back and tap Update again.",
                "অ্যান্ড্রয়েডের একবার তোমার অনুমতি লাগবে, যাতে Nusuk Auto নিজে আপডেট ইনস্টল করতে পারে। \"Allow from this source\" অন করো, তারপর ফিরে এসে আবার Update চাপো।");
        x("Open settings", "সেটিংস খোলো");
        x("Cancel", "বাতিল");
        x("Downloading...", "ডাউনলোড হচ্ছে...");
        x("Installing...", "ইনস্টল হচ্ছে...");
        x("Updating", "আপডেট হচ্ছে");
        x("Website", "ওয়েবসাইট");
        x("Update failed. Try again or download it from the website.", "আপডেট হয়নি। আবার চেষ্টা করো, অথবা ওয়েবসাইট থেকে ডাউনলোড করো।");

        // ----- main screen footer (v2.1) -----
        x("Help & guides", "হেল্প ও গাইড");
        x("Join our Facebook group", "ফেসবুক গ্রুপে জয়েন করো");
        x("About developer", "ডেভেলপার সম্পর্কে");
        r("Update available: Version (.+)", "আপডেট আছে: ভার্সন $1");
        x("About the app", "অ্যাপ সম্পর্কে");
        x("This is your own usage on this phone.", "এগুলো এই ফোনে তোমার নিজের ব্যবহারের হিসাব।");
        x("Facebook group", "ফেসবুক গ্রুপ");
        x("Connect", "যোগাযোগ");
        x("Website", "ওয়েবসাইট");
        x("ALSO BY THE DEVELOPER", "ডেভেলপারের আরেকটি কাজ");
        x("The first and largest digital archive on Fitan and the end times.", "ফিতনা ও শেষ জামানা বিষয়ক সর্বপ্রথম ও সর্ববৃহত ডিজিটাল আর্কাইভ");
        x("Auto Add Codes", "অটো কোড অ্যাড");
        x("Auto Play Quiz", "অটো কুইজ খেলো");
        x("Your codes", "তোমার কোড");
        x("Found some Nusuk codes? Add them to the shared list so everyone can use them.", "নুসুকের কিছু কোড পেয়েছ? শেয়ার্ড লিস্টে অ্যাড করো, সবাই ব্যবহার করতে পারবে।");
        x("Add your own codes", "তোমার নিজের কোড অ্যাড করো");
        x("Paste your codes below, one or many, in any format. Codes look like NG-p36wAxup. We check each one and add the new ones to the shared list.",
                "তোমার কোডগুলো নিচে পেস্ট করো, একটা বা অনেকগুলো, যেকোনো ফরম্যাটে। কোড দেখতে এরকম: NG-p36wAxup। আমরা প্রতিটা চেক করে নতুনগুলো শেয়ার্ড লিস্টে অ্যাড করব।");
        x("No codes found in the text. A code looks like NG-p36wAxup.", "লেখাটায় কোনো কোড পাওয়া যায়নি। কোড দেখতে এরকম: NG-p36wAxup।");
        r("✓  (\\d+) new codes? added\\. Thank you!", "✓  $1টা নতুন কোড অ্যাড হয়েছে। ধন্যবাদ!");
        r("✗  (\\d+) not a valid code \\(NG- and 8 letters or digits\\)", "✗  $1টা সঠিক কোড নয় (NG- আর ৮টা অক্ষর বা সংখ্যা)");
        r("The server could not add the codes right now \\(error (\\d+)\\)\\. Please try again later\\.", "সার্ভার এখন কোড অ্যাড করতে পারছে না (এরর $1)। একটু পরে আবার চেষ্টা করো।");
    }
}
