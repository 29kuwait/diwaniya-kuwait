package com.diwaniya.kuwait;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Point;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * شاشة سكون «شريط ديوانية الكويت»: الساعة الذهبية الفخمة فوق، والشريط تحت.
 * تُختار من الإعدادات: تفضيلات الجهاز ← شاشة التوقف (Device Preferences ← Screen saver).
 */
public class TickerDream extends DreamService {

    private WebView clock, bar;

    @SuppressLint("SetJavaScriptEnabled")
    private WebView makeWeb(String html, String bg) {
        WebView w = new WebView(this);
        w.setBackgroundColor(Color.parseColor(bg));
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        if (html != null) w.loadDataWithBaseURL("https://diwaniya.local/", html, "text/html", "utf-8", null);
        return w;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        Point size = new Point();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(size);
        int screenH = Math.min(size.x, size.y);
        int barH = Math.max(30, Math.round(screenH * 0.06f));

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.parseColor("#04070f"));

        clock = makeWeb(BarService.loadSaver(this), "#04070f");
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP);
        clp.bottomMargin = barH;
        frame.addView(clock, clp);

        // التنبيهات (القصف / العيد / رمضان / الأخبار) تظهر في شاشة التوقف بعد — من الرسيفر نفسه
        NewsWorker.start();
        bar = new WebView(this);
        bar.setBackgroundColor(Color.parseColor("#0b1220"));
        bar.getSettings().setJavaScriptEnabled(true);
        bar.getSettings().setDomStorageEnabled(true);
        bar.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        bar.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public String getNews() {
                return NewsWorker.ready() ? NewsWorker.current(TickerDream.this) : "";
            }
        }, "DiwanBar");
        String tickerHtml = BarService.loadHtml(this);
        if (tickerHtml != null) bar.loadDataWithBaseURL("https://diwaniya.local/", tickerHtml, "text/html", "utf-8", null);
        frame.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, barH, Gravity.BOTTOM));

        setContentView(frame);
    }

    @Override
    public void onDetachedFromWindow() {
        for (WebView w : new WebView[]{clock, bar}) {
            if (w != null) try { w.destroy(); } catch (Exception ignored) { }
        }
        clock = bar = null;
        super.onDetachedFromWindow();
    }
}
