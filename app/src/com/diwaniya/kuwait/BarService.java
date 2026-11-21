package com.diwaniya.kuwait;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * شريط رفيع أسفل الشاشة فوق كل التطبيقات، لا يستقبل الريموت ولا اللمس.
 * المحتوى صفحة الشريط (ticker.html): نسخة مدمجة بالتطبيق + آخر نسخة وصلت من اللابتوب.
 * كل دقيقة يسأل اللابتوب: هل الشريط شغّال؟ هل فيه نسخة جديدة؟ ولو اللابتوب مطفي يكمل بآخر نسخة.
 */
public class BarService extends Service {

    static volatile boolean running = false;

    /** عنوان اللابتوب على الشبكة (سيرفر شريط الديوانية). */
    private static final String BASE = "http://192.168.1.100:8765";
    /** ارتفاع الشريط نسبة لارتفاع الشاشة — أقل بقليل من شريط أخبار القنوات (~7-8%). */
    private static final float BAR_RATIO = 0.06f;
    private static final long SYNC_EVERY_MS = 60 * 1000L;

    private static final String PREFS = "bar";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_REMOTE_ON = "remote_on";
    private static final String KEY_VER = "ver";
    private static final String KEY_HIDDEN = "hidden_until_boot";
    private static final String CACHE_FILE = "ticker.html";
    private static final String SAVER_FILE = "saver.html";
    private static final String KEY_SAVER_VER = "saver_ver";

    private static final String KEY_SHOW_IN = "show_in";

    /**
     * الشريط يظهر فقط فوق هذي التطبيقات (بث / IPTV / مشغلات فيديو).
     * القائمة تتحدث من اللابتوب (ملف apps.json) بدون إعادة تثبيت.
     */
    private static final String DEFAULT_SHOW_IN =
            "com.nathnetwork.xciptv,com.code.next,com.sharkiptvone.sharkprooneiptvbox,"
            + "com.nst.iptvsmarterstvbox,com.nst.smartersplayer,ar.tvplayer.tv,com.ottplay.ottplay,"
            + "com.ibopro.player,com.ibo.player,com.duplexplay.duplex,com.setiptv.android,"
            + "com.google.android.youtube.tv,com.google.android.youtube,com.netflix.ninja,com.netflix.mediaclient,"
            + "net.mbc.shahid,net.mbc.shahidTV,com.starzplay.android,com.osn.go,com.tod.app,com.beinsports.connect,"
            + "org.videolan.vlc,com.mxtech.videoplayer.ad,com.mxtech.videoplayer.pro,com.amazon.avod.thirdpartyclient,"
            + "com.disney.disneyplus,com.google.android.videos,com.android.dreams.basic,com.amlogic.DVBPlayer";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WebView web;
    private FrameLayout root;
    private WindowManager.LayoutParams lp;
    private int barHeight = 30;
    private int barH() { return barHeight; }
    private volatile boolean syncing = false;
    private UsageStatsManager usage;

    /* ===== شاشة التوقف الخاصة (ما تعتمد على إعداد النظام اللي يرجع للقديم) ===== */
    private static final String KEY_SAVER_MIN = "saver_minutes";
    static volatile boolean saverShowing = false;
    private static volatile BarService instance;
    private FrameLayout saverRoot;
    private WebView saverClock, saverBar;
    private long saverShownAt = 0;

    static void requestShowSaver() {
        final BarService s = instance;
        if (s != null) s.handler.post(new Runnable() { @Override public void run() { s.showSaver(); } });
    }

    /** بعد ضغط رفع/خفض الصوت: الصوت صفر = «ميوت» ⇒ الساعة فوراً، غير كذا ⇒ تختفي */
    static void requestVolumeCheck() {
        final BarService s = instance;
        if (s == null) return;
        s.handler.postDelayed(new Runnable() { @Override public void run() {
            try {
                android.media.AudioManager am = (android.media.AudioManager) s.getSystemService(AUDIO_SERVICE);
                int vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                if (vol == 0 || isMuted(am)) { DiwanAccess.keyMuted = true; s.showSaver(); }
                else { DiwanAccess.keyMuted = false; if (saverShowing) s.hideSaver(); }
            } catch (Exception ignored) { }
        } }, 350);
    }

    static void requestHideSaver() {
        final BarService s = instance;
        if (s != null) s.handler.post(new Runnable() { @Override public void run() { s.hideSaver(); } });
    }

    /** isStreamMute موجودة من أندرويد 6 — نستدعيها بالانعكاس لأننا نبني على نسخة أقدم */
    private static boolean isMuted(android.media.AudioManager am) {
        try { return (Boolean) am.getClass().getMethod("isStreamMute", int.class).invoke(am, android.media.AudioManager.STREAM_MUSIC); }
        catch (Throwable e) { return false; }
    }

    private String soundInfo() {
        try { android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            return am.isMusicActive() + "/mute=" + isMuted(am) + "/vol=" + am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        } catch (Throwable e) { return "?"; }
    }

    private int saverMinutes() { return prefs().getInt(KEY_SAVER_MIN, 5); }

    /** هل نطلّع شاشة التوقف؟ ما أحد ضغط الريموت من فترة + ما فيه صوت شغّال (مو فيلم/مباراة) */
    private void checkSaver() {
        if (saverShowing) return;
        if (!DiwanAccess.connected) return;                 // بدون صلاحية إمكانية الوصول ما نقدر نعرف
        int min = saverMinutes();
        if (min <= 0) return;
        // داخل تطبيقات البث/الأفلام: لازم 30 دقيقة بدون ريموت (فيلم موقّف ومنسي) — مو 5 دقايق
        String fg = foregroundPkg;
        boolean home = fg == null || fg.contains("launcher") || fg.contains("appdrawer") || fg.equals(getPackageName());
        if (!home) min = Math.max(min, prefs().getInt("saver_minutes_apps", 30));
        long idle = System.currentTimeMillis() - DiwanAccess.lastInput;
        if (idle < min * 60_000L) return;
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            // فيه صوت مسموع فعلاً؟ (فيديو شغّال + الصوت مو مكتوم ومو صفر) — لو ميوت نعتبره بدون صوت
            // فيه صوت شغّال وما انضغط ميوت ⇒ لا تطلع (مستوى الصوت في الرسيفر دائماً صفر فما نعتمد عليه)
            if (am != null && am.isMusicActive() && !DiwanAccess.keyMuted && !isMuted(am)
                    && am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) > 0) return;   // الصوت صفر = ميوت
        } catch (Exception ignored) { }
        showSaver();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView saverWeb(String html, String bg, boolean withNews) {
        WebView w = new WebView(this);
        w.setBackgroundColor(Color.parseColor(bg));
        w.setFocusable(false);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        if (withNews) {
            w.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public String getNews() { return NewsWorker.ready() ? NewsWorker.current(BarService.this) : ""; }
            }, "DiwanBar");
        }
        if (html != null) w.loadDataWithBaseURL("https://diwaniya.local/saver", html, "text/html", "utf-8", null);
        return w;
    }

    @SuppressWarnings("deprecation")
    private void showSaver() {
        if (saverShowing || wm == null) return;
        try {
            Point size = new Point();
            wm.getDefaultDisplay().getRealSize(size);
            int screenH = Math.min(size.x, size.y);
            int bh = Math.max(30, Math.round(screenH * BAR_RATIO));

            saverRoot = new FrameLayout(this);
            saverRoot.setBackgroundColor(Color.parseColor("#04070f"));
            saverClock = saverWeb(loadSaver(this), "#04070f", false);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP);
            clp.bottomMargin = bh;
            saverRoot.addView(saverClock, clp);
            saverBar = saverWeb(loadHtml(this), "#0b1220", true);
            saverRoot.addView(saverBar, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, bh, Gravity.BOTTOM));

            WindowManager.LayoutParams slp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.OPAQUE);
            try { wm.addView(saverRoot, slp); }
            catch (Exception e) { slp.type = WindowManager.LayoutParams.TYPE_PHONE; wm.addView(saverRoot, slp); }
            saverShowing = true;
            saverShownAt = System.currentTimeMillis();
            if (root != null) root.setVisibility(View.GONE);   // الشريط موجود داخل شاشة التوقف
        } catch (Exception e) {
            hideSaver();
        }
    }

    private void hideSaver() {
        saverShowing = false;
        if (saverRoot != null) { try { wm.removeView(saverRoot); } catch (Exception ignored) { } }
        for (WebView w : new WebView[]{saverClock, saverBar}) {
            if (w != null) try { w.destroy(); } catch (Exception ignored) { }
        }
        saverRoot = null; saverClock = null; saverBar = null;
        DiwanAccess.lastInput = System.currentTimeMillis();
        applyVisibility();
    }

    /* ===== الباركود في الزاوية العلوية اليمنى ===== */
    private WebView cornerWeb;
    private String cornerSvg = null;

    @SuppressWarnings("deprecation")
    private void createCornerQr(String svg, int sizePx) {
        if (wm == null || svg == null || svg.equals(cornerSvg)) return;
        cornerSvg = svg;
        if (cornerWeb != null) { try { wm.removeView(cornerWeb); cornerWeb.destroy(); } catch (Exception ignored) { } cornerWeb = null; }
        int size = sizePx > 0 ? sizePx : Math.round(barH() * 0.84f);
        WebView w = new WebView(this);
        w.setBackgroundColor(Color.TRANSPARENT);
        w.setFocusable(false);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:transparent;overflow:hidden}"
                + "div{width:100%;height:100%;background:#fff;border-radius:4px;overflow:hidden;box-sizing:border-box}"
                + "svg{width:100%;height:100%;display:block}</style></head><body><div>" + svg + "</div></body></html>";
        w.loadDataWithBaseURL("https://diwaniya.local/qr", html, "text/html", "utf-8", null);
        WindowManager.LayoutParams clp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        clp.gravity = Gravity.TOP | Gravity.RIGHT;
        clp.x = Math.round(barH() * 0.45f);
        clp.y = Math.round(barH() * 0.45f);
        try { wm.addView(w, clp); }
        catch (Exception e) { try { clp.type = WindowManager.LayoutParams.TYPE_PHONE; wm.addView(w, clp); } catch (Exception ignored) { return; } }
        cornerWeb = w;
        applyVisibility();
    }

    private String foregroundPkg = null;
    private String reportedPkg = null;
    private long lastEventQuery = 0;

    /** هل أعطى المستخدم التطبيق صلاحية «الوصول لبيانات الاستخدام» (عشان يعرف وش التطبيق المفتوح)؟ */
    static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    /** مخفي بضغطتين: ما يرجع إلا بفتح التطبيق أو إعادة تشغيل الرسيفر. */
    static boolean isHiddenUntilBoot(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_HIDDEN, false);
    }

    static void setHiddenUntilBoot(Context c, boolean hidden) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_HIDDEN, hidden).commit();
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (web == null) {
            createBar();
            Notification n = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_menu_day)
                    .setContentTitle("شريط الديوانية")
                    .setPriority(Notification.PRIORITY_MIN)
                    .build();
            startForeground(1, n);
            handler.post(syncLoop);
            handler.post(tick);
        }
        running = true;
        instance = this;
        new Thread(new Runnable() { @Override public void run() { Housekeeping.cleanDownloads(); } }).start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        hideSaver();
        if (cornerWeb != null) { try { wm.removeView(cornerWeb); cornerWeb.destroy(); } catch (Exception ignored) { } cornerWeb = null; }
        instance = null;
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) { }
            root = null;
        }
        if (web != null) {
            try { web.destroy(); } catch (Exception ignored) { }
            web = null;
        }
        super.onDestroy();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private void createBar() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        int screenH = Math.min(size.x, size.y);
        int barH = Math.max(30, Math.round(screenH * BAR_RATIO));
        barHeight = barH;

        web = new WebView(this);
        // خلفية شفافة: الشريط يرسم المحتوى بس، والفيديو يبان من وراه
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(false);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // طبقة شفافة فوق الشريط تلتقط ضغطتين بالماوس: يختفي الشريط لين يُفتح التطبيق أو يُعاد تشغيل الرسيفر
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                setHiddenUntilBoot(BarService.this, true);
                Toast.makeText(BarService.this, "تم إخفاء الشريط — يرجع بفتح التطبيق أو إعادة تشغيل الرسيفر",
                        Toast.LENGTH_LONG).show();
                stopSelf();
                return true;
            }
        });
        View catcher = new View(this);
        catcher.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return gd.onTouchEvent(e); }
        });
        root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(catcher, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // الصفحة تبلّغنا بعرضها: النافذة تقصر وتطول على قد المحتوى، وتكون في نص الشاشة تحت
        final int maxW = Math.max(size.x, size.y);
        NewsWorker.start();   // الأخبار والتنبيهات من الرسيفر نفسه (بدون اللابتوب)
        web.addJavascriptInterface(new Object() {
            /** الشريط يسأل: وش الأخبار/التنبيهات؟ (فاضي لين أول تحميل ينجح) */
            @JavascriptInterface
            public String getNews() {
                return NewsWorker.ready() ? NewsWorker.current(BarService.this) : "";
            }

            /** الباركود في زاوية الشاشة العلوية (نافذة صغيرة مستقلة فوق الشريط) */
            @JavascriptInterface
            public void showCornerQr(final String svg, final int sizePx) {
                handler.post(new Runnable() { @Override public void run() { createCornerQr(svg, sizePx); } });
            }

            /** حالة التطبيق لتقرير الفحص */
            @JavascriptInterface
            public String status() {
                long idle = (System.currentTimeMillis() - DiwanAccess.lastInput) / 1000;
                return "v=" + BuildInfo.VERSION + ";usage=" + hasUsageAccess(BarService.this)
                        + ";access=" + DiwanAccess.isEnabled(BarService.this) + ";accConn=" + DiwanAccess.connected
                        + ";saverMin=" + saverMinutes() + ";idleSec=" + idle + ";saver=" + saverShowing + ";sound=" + soundInfo() + ";keyMuted=" + DiwanAccess.keyMuted + ";keys=" + DiwanAccess.lastKeys + ";" + Housekeeping.stats(BarService.this) + ";home=" + DiwanAccess.homeOverride + ";apps=" + Housekeeping.userApps(BarService.this)
                        + ";fg=" + foregroundPkg;
            }

            @JavascriptInterface
            public void setWidth(final int px) {
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (root == null || lp == null) return;
                        int w = Math.max(barH(), Math.min(px, maxW));
                        if (w >= maxW - 2) w = WindowManager.LayoutParams.MATCH_PARENT;
                        if (lp.width == w) return;
                        lp.width = w;
                        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
                    }
                });
            }
        }, "DiwanBar");

        // FLAG_NOT_FOCUSABLE: الريموت يروح للتطبيق اللي تحت. الماوس على الشريط فقط يوصل للشريط.
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, barH,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        try {
            wm.addView(root, lp);
        } catch (Exception e) {
            lp.type = WindowManager.LayoutParams.TYPE_PHONE;
            wm.addView(root, lp);
        }
        loadPage(readCache());
        applyVisibility();
    }

    /** يعرض الصفحة. أصل الصفحة https عشان طلبات الحرارة والصلاة تشتغل. */
    private void loadPage(String html) {
        if (web == null) return;
        if (html == null) html = readAsset();
        if (html == null) return;
        web.loadDataWithBaseURL("https://diwaniya.local/", html, "text/html", "utf-8", null);
    }

    private void applyVisibility() {
        if (root == null) return;
        boolean show = !saverShowing && prefs().getBoolean(KEY_REMOTE_ON, true) && allowedHere();
        int want = show ? View.VISIBLE : View.GONE;
        if (root.getVisibility() != want) root.setVisibility(want);
        if (cornerWeb != null && cornerWeb.getVisibility() != want) cornerWeb.setVisibility(want);
    }

    /** يظهر فقط فوق تطبيقات القائمة. بدون صلاحية الاستخدام ما نقدر نعرف، فيظهر في كل مكان. */
    private boolean allowedHere() {
        if (!hasUsageAccess(this)) return true;
        if (foregroundPkg == null) return false;
        String list = "," + prefs().getString(KEY_SHOW_IN, DEFAULT_SHOW_IN) + ",";
        return list.contains("," + foregroundPkg + ",");
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateForeground();
            checkSaver();
            applyVisibility();
            handler.postDelayed(this, 1000);
        }
    };

    /** يعرف وش التطبيق المفتوح الحين، ويبلّغ اللابتوب أول مرة يشوفه (عشان نضيفه للقائمة). */
    private void updateForeground() {
        if (!hasUsageAccess(this)) return;
        if (usage == null) usage = (UsageStatsManager) getSystemService("usagestats");
        if (usage == null) return;
        long now = System.currentTimeMillis();
        try {
            long from = lastEventQuery == 0 ? now - 6 * 60 * 60 * 1000L : lastEventQuery - 2000;
            UsageEvents events = usage.queryEvents(from, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) foregroundPkg = e.getPackageName();
            }
            lastEventQuery = now;
        } catch (Exception ignored) {
            return;
        }
        final String p = foregroundPkg;
        if (p != null && !p.equals(reportedPkg)) {
            reportedPkg = p;
            new Thread(new Runnable() {
                @Override public void run() {
                    try { httpGet(BASE + "/seen?pkg=" + java.net.URLEncoder.encode(p, "UTF-8")); } catch (Exception ignored) { }
                }
            }).start();
        }
    }

    private final Runnable syncLoop = new Runnable() {
        @Override
        public void run() {
            sync();
            handler.postDelayed(this, SYNC_EVERY_MS);
        }
    };

    /** يسأل اللابتوب عن الحالة والنسخة. إذا ما رد (مطفي) ما يتغير شي. */
    private void sync() {
        if (syncing) return;
        syncing = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String st = httpGet(BASE + "/status");
                    if (st == null) return;
                    JSONObject j = new JSONObject(st);
                    final boolean on = j.optBoolean("on", true);
                    String v = j.optString("v", "");
                    prefs().edit().putBoolean(KEY_REMOTE_ON, on).apply();
                    // قائمة التطبيقات اللي يظهر فوقها الشريط
                    String cfg = httpGet(BASE + "/apps");
                    if (cfg != null) {
                        try {
                            JSONObject cj = new JSONObject(cfg);
                            if (cj.has("saver_minutes")) prefs().edit().putInt(KEY_SAVER_MIN, cj.optInt("saver_minutes", 5)).apply();
                            if (cj.has("uninstall") && cj.optInt("cmd_id", 0) > prefs().getInt("cmd_done", 0)) {
                                final org.json.JSONArray un = cj.getJSONArray("uninstall");
                                prefs().edit().putInt("cmd_done", cj.optInt("cmd_id", 0)).apply();
                                for (int q = 0; q < un.length(); q++) { final String pk = un.getString(q); handler.postDelayed(new Runnable() { @Override public void run() { Housekeeping.askUninstall(BarService.this, pk); } }, 1500L * q); }
                            }
                            if (cj.has("home_override")) DiwanAccess.homeOverride = cj.optBoolean("home_override", true);
                            if (cj.has("favorites")) { org.json.JSONArray fa = cj.getJSONArray("favorites"); StringBuilder fs = new StringBuilder(); for (int q = 0; q < fa.length(); q++) { if (q > 0) fs.append(","); fs.append(fa.getString(q)); } prefs().edit().putString("favorites", fs.toString()).apply(); }
                            if (cj.has("mute_key")) DiwanAccess.extraMuteKey = cj.optInt("mute_key", -999);
                            if (cj.has("saver_minutes_apps")) prefs().edit().putInt("saver_minutes_apps", cj.optInt("saver_minutes_apps", 30)).apply();
                            org.json.JSONArray arr = cj.getJSONArray("show_in");
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < arr.length(); i++) {
                                if (sb.length() > 0) sb.append(',');
                                sb.append(arr.getString(i).trim());
                            }
                            if (sb.length() > 0) prefs().edit().putString(KEY_SHOW_IN, sb.toString()).apply();
                        } catch (Exception ignored) { }
                    }
                    // شاشة السكون (الساعة الذهبية): نحدّثها إذا تغيرت نسختها
                    String sv = j.optString("sv", "");
                    if (sv.length() > 0 && !sv.equals(prefs().getString(KEY_SAVER_VER, ""))) {
                        String saver = httpGet(BASE + "/saver");
                        if (saver != null && saver.length() > 100) {
                            writeFile(BarService.this, SAVER_FILE, saver);
                            prefs().edit().putString(KEY_SAVER_VER, sv).apply();
                        }
                    }
                    String html = null;
                    if (on && !v.equals(prefs().getString(KEY_VER, ""))) {
                        html = httpGet(BASE + "/view");
                        if (html != null && html.length() > 100) {
                            writeCache(html);
                            prefs().edit().putString(KEY_VER, v).apply();
                        } else {
                            html = null;
                        }
                    }
                    final String fresh = html;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (fresh != null) loadPage(fresh);
                            applyVisibility();
                        }
                    });
                } catch (Exception ignored) {
                } finally {
                    syncing = false;
                }
            }
        }).start();
    }

    private static String httpGet(String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url + (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis()).openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(15000);
            con.setUseCaches(false);
            if (con.getResponseCode() != 200) return null;
            return new String(readAll(con.getInputStream()), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private String readCache() { return readCache(this); }

    /** آخر نسخة من الشريط وصلت من اللابتوب، أو النسخة المدمجة بالتطبيق. (تستعملها شاشة السكون أيضاً) */
    static String loadHtml(Context c) {
        String h = readCache(c);
        if (h != null) return h;
        try {
            return new String(readAll(c.getAssets().open(CACHE_FILE)), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** صفحة الساعة الذهبية لشاشة السكون: آخر نسخة من اللابتوب أو المدمجة بالتطبيق. */
    static String loadSaver(Context c) {
        try {
            File f = new File(c.getFilesDir(), SAVER_FILE);
            if (f.exists()) return new String(readAll(new FileInputStream(f)), "UTF-8");
        } catch (Exception ignored) { }
        try {
            return new String(readAll(c.getAssets().open(SAVER_FILE)), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(Context c, String name, String content) {
        try {
            File tmp = new File(c.getFilesDir(), name + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            o.write(content.getBytes("UTF-8"));
            o.close();
            tmp.renameTo(new File(c.getFilesDir(), name));
        } catch (Exception ignored) { }
    }

    private static String readCache(Context c) {
        try {
            File f = new File(c.getFilesDir(), CACHE_FILE);
            if (!f.exists()) return null;
            return new String(readAll(new FileInputStream(f)), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(String html) {
        try {
            File tmp = new File(getFilesDir(), CACHE_FILE + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            o.write(html.getBytes("UTF-8"));
            o.close();
            tmp.renameTo(new File(getFilesDir(), CACHE_FILE));
        } catch (Exception ignored) { }
    }

    private String readAsset() {
        try {
            return new String(readAll(getAssets().open(CACHE_FILE)), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
