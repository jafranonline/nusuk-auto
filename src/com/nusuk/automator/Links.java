package com.nusuk.automator;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

/** Outside links shared by every screen (Facebook group, developer profile, alfitan.com). */
final class Links {
    static final String GROUP_URL = "https://www.facebook.com/groups/nusukauto/";
    static final String DEV_URL = "https://www.facebook.com/jafranonline/";
    static final String GITHUB_URL = "https://github.com/jafranonline";
    static final String WEB_URL = "https://jafran.dev";
    static final String DEV_NAME = "Jafran Hasan";
    /** The link users share to spread the app: our website (download + tutorials). */
    static final String SHARE_URL = "https://nusuk.minbars.net/";
    static final String FITAN_URL = "https://alfitan.com/";

    private Links() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("links", Context.MODE_PRIVATE);
    }

    /** Remembers the admin's group link from the server so every screen uses it. */
    static void saveGroupUrl(Context c, String url) {
        if (url != null && url.startsWith("https://")) prefs(c).edit().putString("group", url).apply();
    }

    static String groupUrl(Context c) {
        return prefs(c).getString("group", GROUP_URL);
    }

    /** Opens a Facebook page in the Facebook app (or Lite), falling back to the browser. */
    static void openFacebook(Context c, String url) {
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
    static void share(Context c) {
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

    static void open(Context c, String url) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(c, url, Toast.LENGTH_LONG).show();
        }
    }

    /** Edge-to-edge (Android 15+): pads [bar] below the status bar and [content] above the nav bar. */
    static void applyInsets(View root, View bar, View content) {
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
