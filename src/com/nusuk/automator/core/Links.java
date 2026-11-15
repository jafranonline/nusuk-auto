package com.nusuk.automator.core;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

/** Outside links shared by every screen (Facebook group, developer profile, alfitan.com). */
public final class Links {
    static final String GROUP_URL = "https://www.facebook.com/groups/nusukauto/";
    public static final String DEV_URL = "https://www.facebook.com/jafranonline/";
    public static final String GITHUB_URL = "https://github.com/jafranonline";
    public static final String WEB_URL = "https://jafran.dev";
    public static final String DEV_NAME = "Jafran Hasan";
    /** The link users share to spread the app: our website (download + tutorials). */
    static final String SHARE_URL = "https://nusuk.minbars.net/";
    public static final String FITAN_URL = "https://alfitan.com/";

    private Links() {}

    /** Nusuk's own app (the one we automate). */
    public static final String NUSUK_PACKAGE = "com.moh.nusukapp";
    /** Our site and file hosts (the server API base is the api_base string resource). */
    public static final String SITE_HOST = "nusuk.minbars.net";
    public static final String SITE_URL = "https://" + SITE_HOST;
    public static final String FILE_HOST = "https://files-nusuk.minbars.net/";

    /** Sites a link sent by the server (announcements, group link, update page) may open. A link to
     *  anywhere else is ignored, so a hijacked server cannot push users to a phishing page. */
    private static final String[] TRUSTED_HOSTS = {
            "minbars.net", "facebook.com", "fb.com", "fb.me", "github.com", "jafran.dev", "alfitan.com",
            "play.google.com", "youtube.com", "youtu.be", "wa.me", "whatsapp.com", "t.me"};

    public static boolean isTrusted(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        String host = Uri.parse(url).getHost();
        if (host == null) return false;
        host = host.toLowerCase(java.util.Locale.ROOT);
        for (String h : TRUSTED_HOSTS) if (host.equals(h) || host.endsWith("." + h)) return true;
        return false;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("links", Context.MODE_PRIVATE);
    }

    /** Remembers the admin's group link from the server so every screen uses it. */
    public static void saveGroupUrl(Context c, String url) {
        if (isTrusted(url)) prefs(c).edit().putString("group", url).apply();
    }

    public static String groupUrl(Context c) {
        return prefs(c).getString("group", GROUP_URL);
    }

    /** Opens a Facebook page in the Facebook app (or Lite), falling back to the browser. */
    public static void openFacebook(Context c, String url) {
        for (String pkg : new String[]{"com.facebook.katana", "com.facebook.lite"}) {
            try {
                c.getPackageManager().getPackageInfo(pkg, 0);
                Intent fb = new Intent(Intent.ACTION_VIEW, Uri.parse("fb://facewebmodal/f?href=" + Uri.encode(url)));
                fb.setPackage(pkg);
                c.startActivity(fb);
                return;
            } catch (Exception ignored) {}
        }
        open(c, url);
    }

    /** Opens the share sheet with a short message and the website link. */
    public static void share(Context c) {
        String msg = L.isBn(c)
                ? "নুসুক অ্যাপের কোড আর কুইজ নিজে থেকেই করে দেয় Nusuk Auto। ফ্রী ডাউনলোড:\n" + SHARE_URL
                : "Nusuk Auto adds Umrah codes and plays the Nusuk quiz for you. Free download:\n" + SHARE_URL;
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, msg);
        try {
            c.startActivity(Intent.createChooser(send, L.t(c, "Share the app")));
        } catch (Exception e) {
            Toast.makeText(c, SHARE_URL, Toast.LENGTH_LONG).show();
        }
    }

    public static void open(Context c, String url) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(c, url, Toast.LENGTH_LONG).show();
        }
    }

    /** Edge-to-edge (Android 15+): pads [bar] below the status bar and [content] above the nav bar. */
    public static void applyInsets(View root, View bar, View content) {
        int barTop = bar.getPaddingTop();
        int contentBottom = content.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            bar.setPadding(bar.getPaddingLeft(), barTop + insets.getSystemWindowInsetTop(),
                    bar.getPaddingRight(), bar.getPaddingBottom());
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getPaddingRight(), contentBottom + insets.getSystemWindowInsetBottom());
            return insets;
        });
    }
}
