package com.diwaniya.kuwait.lite;

import android.app.Notification;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Draws a thin always-on-top bar: weather | clock | countdown to the next prayer. */
public class BarService extends Service {

    static volatile boolean running = false;

    private static final String PREFS = "bar";
    private static final String KEY_ENABLED = "enabled";

    private static final String WEATHER_URL =
            "http://api.open-meteo.com/v1/forecast?latitude=29.3759&longitude=47.9774"
            + "&current=temperature_2m,weather_code,is_day"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=2"
            + "&timezone=Asia%2FKuwait";
    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000L;
    private static final long WEATHER_RETRY_MS = 2 * 60 * 1000L;

    private static final int COLOR_BG = 0xE60B1220;
    private static final int COLOR_ACCENT = 0xFF1E88E5;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SOON = 0xFFFFC107;
    private static final int COLOR_DIVIDER = 0x55FFFFFF;

    private static final Locale AR = new Locale("ar");

    /** The bar shows only while one of these apps is on screen; it hides inside all other apps. */
    private static final Set<String> SHOW_IN = new HashSet<String>(Arrays.asList(
            "com.sen5.launcher",                    // home screen
            "com.amlogic.DVBPlayer",                // satellite TV
            "com.nathnetwork.xciptv",               // XCIPTV
            "com.sharkiptvone.sharkprooneiptvbox",  // Shark IPTV
            "com.code.next",                        // NEXT+
            "com.diwaniya.kuwait.lite"));

    private UsageStatsManager usage;
    private String foregroundPkg = null;
    private long lastEventQuery = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View root;
    private TextView weatherTv, clockTv, prayerTv;

    private volatile String weatherText = "الطقس: جارٍ التحديث…";
    private volatile long lastWeatherOk = 0;
    private volatile boolean weatherBusy = false;
    private long lastWeatherTry = 0;

    private int prayerDayKey = -1;
    private double[] today, tomorrow;

    static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putBoolean(KEY_ENABLED, on);
        e.apply();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (root == null) {
            createBar();
            Notification n = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_menu_day)
                    .setContentTitle("الشريط البسيط")
                    .setPriority(Notification.PRIORITY_MIN)
                    .build();
            startForeground(1, n);
        }
        running = true;
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) { }
            root = null;
        }
        super.onDestroy();
    }

    private void createBar() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        int screenH = Math.min(size.x, size.y);
        int barH = Math.max(28, Math.round(screenH * 0.046f));
        int accentH = Math.max(2, barH / 16);
        float textPx = barH * 0.46f;

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        View accent = new View(this);
        accent.setBackgroundColor(COLOR_ACCENT);
        outer.addView(accent, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, accentH));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); // first section on the right
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(COLOR_BG);

        weatherTv = cell(textPx);
        clockTv = cell(textPx);
        prayerTv = cell(textPx);
        int dividerH = Math.round(barH * 0.6f);
        bar.addView(weatherTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        bar.addView(divider(), new LinearLayout.LayoutParams(Math.max(1, barH / 25), dividerH));
        bar.addView(clockTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        bar.addView(divider(), new LinearLayout.LayoutParams(Math.max(1, barH / 25), dividerH));
        bar.addView(prayerTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        outer.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barH));

        // Diwaniya Kuwait emblem in the right corner, a little taller than the bar so it pops above it.
        int avatar = Math.round(barH * 1.75f);
        int avatarMargin = Math.round(barH * 0.3f);
        int windowH = Math.max(barH + accentH, avatar);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(outer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, barH + accentH, Gravity.BOTTOM));
        int photoId = getResources().getIdentifier("emblem", "drawable", getPackageName());
        if (photoId != 0) {
            bar.setPadding(0, 0, avatar + 2 * avatarMargin, 0);
            ImageView photo = new ImageView(this);
            photo.setImageResource(photoId);
            photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(avatar, avatar, Gravity.BOTTOM | Gravity.RIGHT);
            plp.rightMargin = avatarMargin;
            frame.addView(photo, plp);
        }

        root = frame;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, windowH,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.FILL_HORIZONTAL;
        try {
            wm.addView(root, lp);
        } catch (Exception e) {
            lp.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            wm.addView(root, lp);
        }
        handler.post(tick);
    }

    private TextView cell(float textPx) {
        TextView tv = new TextView(this);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(COLOR_DIVIDER);
        return v;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            updateVisibility(now);
            Calendar c = Calendar.getInstance();
            updateClock(c);
            updatePrayer(c);
            maybeFetchWeather(now);
            weatherTv.setText(weatherText);
            handler.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000));
        }
    };

    /** Hides the bar while an app outside SHOW_IN is on screen. Without usage access it stays visible. */
    private void updateVisibility(long now) {
        if (usage == null) usage = (UsageStatsManager) getSystemService("usagestats");
        if (usage == null) return;
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
        if (foregroundPkg == null) return;
        int want = SHOW_IN.contains(foregroundPkg) ? View.VISIBLE : View.GONE;
        if (root.getVisibility() != want) root.setVisibility(want);
    }

    private void updateClock(Calendar c) {
        int h = c.get(Calendar.HOUR);
        if (h == 0) h = 12;
        String ampm = c.get(Calendar.AM_PM) == Calendar.AM ? "ص" : "م";
        String time = String.format(AR, "%d:%02d %s", h, c.get(Calendar.MINUTE), ampm);
        String date = new SimpleDateFormat("EEEE d MMMM", AR).format(c.getTime());
        clockTv.setText(time + "   |   " + date);
    }

    private void updatePrayer(Calendar c) {
        int key = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
        if (key != prayerDayKey) {
            today = PrayerTimes.compute(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
            Calendar t = (Calendar) c.clone();
            t.add(Calendar.DAY_OF_MONTH, 1);
            tomorrow = PrayerTimes.compute(t.get(Calendar.YEAR), t.get(Calendar.MONTH) + 1, t.get(Calendar.DAY_OF_MONTH));
            prayerDayKey = key;
        }
        double nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0;
        boolean friday = c.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY;
        int[] prayers = {PrayerTimes.FAJR, PrayerTimes.DHUHR, PrayerTimes.ASR, PrayerTimes.MAGHRIB, PrayerTimes.ISHA};

        // For 10 minutes after the adhan, announce it instead of counting down.
        for (int k : prayers) {
            double start = Math.round(today[k]);
            if (nowMin >= start && nowMin < start + 10) {
                prayerTv.setTextColor(COLOR_SOON);
                prayerTv.setText("حان الآن وقت صلاة " + name(k, friday));
                return;
            }
        }

        int next = -1;
        double target = 0;
        for (int k : prayers) {
            if (Math.round(today[k]) > nowMin) { next = k; target = Math.round(today[k]); break; }
        }
        boolean nextIsTomorrow = false;
        if (next < 0) {
            next = PrayerTimes.FAJR;
            target = Math.round(tomorrow[PrayerTimes.FAJR]) + 24 * 60;
            nextIsTomorrow = true;
        }
        long secs = Math.max(0, Math.round((target - nowMin) * 60));
        String count = String.format(AR, "%d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60);
        String label = name(next, friday && !nextIsTomorrow);
        prayerTv.setTextColor(secs <= 15 * 60 ? COLOR_SOON : COLOR_TEXT);
        prayerTv.setText(label + " (" + clockOf(target % (24 * 60)) + ")  بعد  " + count);
    }

    private static String name(int k, boolean friday) {
        if (k == PrayerTimes.DHUHR && friday) return "الجمعة";
        return PrayerTimes.NAMES[k];
    }

    private static String clockOf(double minutes) {
        int m = (int) Math.floor(minutes);
        int h = (m / 60) % 12;
        if (h == 0) h = 12;
        return String.format(AR, "%d:%02d", h, m % 60);
    }

    private void maybeFetchWeather(long now) {
        if (weatherBusy) return;
        boolean due = lastWeatherOk == 0 || now - lastWeatherOk > WEATHER_EVERY_MS;
        if (!due || now - lastWeatherTry < WEATHER_RETRY_MS) return;
        lastWeatherTry = now;
        weatherBusy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection con = (HttpURLConnection) new URL(WEATHER_URL).openConnection();
                    con.setConnectTimeout(10000);
                    con.setReadTimeout(10000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    con.disconnect();
                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject cur = json.getJSONObject("current");
                    long temp = Math.round(cur.getDouble("temperature_2m"));
                    int code = cur.getInt("weather_code");
                    boolean day = cur.optInt("is_day", 1) == 1;
                    String text = "اليوم " + String.format(AR, "%d°", temp) + " " + describe(code, day);
                    JSONObject daily = json.optJSONObject("daily");
                    if (daily != null && daily.getJSONArray("weather_code").length() > 1) {
                        long max = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(1));
                        long min = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(1));
                        int tCode = daily.getJSONArray("weather_code").getInt(1);
                        text += "   •   غداً " + String.format(AR, "%d°/%d°", max, min) + " " + describe(tCode, true);
                    }
                    weatherText = text;
                    lastWeatherOk = System.currentTimeMillis();
                } catch (Exception e) {
                    if (lastWeatherOk == 0) weatherText = "الطقس: لا يوجد اتصال بالإنترنت";
                } finally {
                    weatherBusy = false;
                }
            }
        }).start();
    }

    private static String describe(int code, boolean day) {
        if (code == 0) return day ? "مشمس" : "صافٍ";
        if (code == 1) return day ? "مشمس غالباً" : "صافٍ غالباً";
        if (code == 2) return "غائم جزئياً";
        if (code == 3) return "غائم";
        if (code == 45 || code == 48) return "ضباب";
        if (code >= 51 && code <= 57) return "رذاذ";
        if (code >= 61 && code <= 67) return "مطر";
        if (code >= 71 && code <= 77) return "ثلج";
        if (code >= 80 && code <= 82) return "زخات مطر";
        if (code == 85 || code == 86) return "زخات ثلج";
        if (code >= 95) return "عاصفة رعدية";
        return "";
    }
}
