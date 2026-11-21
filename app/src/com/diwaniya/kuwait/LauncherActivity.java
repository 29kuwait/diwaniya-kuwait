package com.diwaniya.kuwait;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * الواجهة الرئيسية الخاصة (بدون إعلانات، مثل آبل تيفي).
 * زر البيت في الريموت يفتحها (عن طريق DiwanAccess). التصميم في assets/launcher.html.
 */
public class LauncherActivity extends Activity {

    private WebView web;
    private static final Map<String, String> ICON_CACHE = new HashMap<>();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#05070d"));
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.addJavascriptInterface(new Bridge(), "DiwanHome");
        setContentView(web);
        web.loadUrl("file:///android_asset/launcher.html");
        web.requestFocus(View.FOCUS_DOWN);
        startService(new Intent(this, BarService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onHomeResume&&onHomeResume()", null);
    }

    @Override
    public void onBackPressed() {
        // في الواجهة الرئيسية زر الرجوع ما يسوي شي (مثل أي شاشة رئيسية)
        if (web != null) web.evaluateJavascript("window.onBack&&onBack()", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        if (web != null) { try { web.destroy(); } catch (Exception ignored) { } web = null; }
        super.onDestroy();
    }

    private static String toPng(Drawable d, int w, int h) {
        try {
            Bitmap b;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null
                    && ((BitmapDrawable) d).getBitmap().getWidth() <= w * 2) {
                b = Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), w, h, true);
            } else {
                b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                d.setBounds(0, 0, w, h);
                d.draw(c);
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            return "data:image/png;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    final class Bridge {
        /** كل التطبيقات اللي تنفتح: [{pkg,label,banner,icon}] */
        @JavascriptInterface
        public String getApps() {
            try {
                PackageManager pm = getPackageManager();
                Set<String> seen = new HashSet<>();
                JSONArray out = new JSONArray();
                String[] cats = {"android.intent.category.LEANBACK_LAUNCHER", Intent.CATEGORY_LAUNCHER};
                for (String cat : cats) {
                    Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                    List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
                    for (ResolveInfo r : list) {
                        String pkg = r.activityInfo.packageName;
                        if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                        ActivityInfo ai = r.activityInfo;
                        JSONObject o = new JSONObject();
                        o.put("pkg", pkg);
                        o.put("label", String.valueOf(r.loadLabel(pm)));
                        String key = pkg;
                        String banner = ICON_CACHE.get(key + "#b");
                        String icon = ICON_CACHE.get(key + "#i");
                        if (banner == null) {
                            Drawable bd = null;
                            try { bd = ai.loadBanner(pm); } catch (Throwable ignored) { }
                            if (bd == null) try { bd = ai.applicationInfo.loadBanner(pm); } catch (Throwable ignored) { }
                            banner = bd != null ? toPng(bd, 320, 180) : "";
                            ICON_CACHE.put(key + "#b", banner);
                        }
                        if (icon == null) {
                            icon = toPng(r.loadIcon(pm), 144, 144);
                            ICON_CACHE.put(key + "#i", icon);
                        }
                        o.put("banner", banner);
                        o.put("icon", icon);
                        out.put(o);
                    }
                }
                return out.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        /** التطبيقات المفضلة (من apps.json على اللابتوب: favorites) */
        @JavascriptInterface
        public String getFavorites() {
            return getSharedPreferences("bar", MODE_PRIVATE).getString("favorites",
                    "com.royalplus.app,com.mbm_soft.flash4k,com.mbm_soft.aroma4k,com.google.android.youtube.tv,net.mbc.shahidTV,com.netflix.ninja");
        }

        @JavascriptInterface
        public void launch(String pkg) {
            try {
                PackageManager pm = getPackageManager();
                Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
            } catch (Exception ignored) { }
        }

        /** أزرار الشريط العلوي: بحث، شبكة، حساب، إعدادات */
        @JavascriptInterface
        public void action(String act) {
            String[] tries;
            if ("search".equals(act)) tries = new String[]{"android.search.action.GLOBAL_SEARCH", Intent.ACTION_ASSIST, Intent.ACTION_WEB_SEARCH};
            else if ("wifi".equals(act)) tries = new String[]{Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS};
            else if ("account".equals(act)) tries = new String[]{Settings.ACTION_SYNC_SETTINGS, Settings.ACTION_ADD_ACCOUNT};
            else tries = new String[]{Settings.ACTION_SETTINGS};
            for (String a : tries) {
                try { startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
                catch (Exception ignored) { }
            }
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void openSettings() {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        }
    }
}
