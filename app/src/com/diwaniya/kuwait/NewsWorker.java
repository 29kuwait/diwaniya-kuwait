package com.diwaniya.kuwait;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * متابع الأخبار داخل الرسيفر نفسه (بدون اللابتوب):
 *  - خبر مهم (كونا والصحف الكويتية): واحد كل 3 ساعات، يظهر 15 دقيقة، ما يتكرر.
 *  - قصف على الكويت (مسيّرة / صاروخ): كل قصف جديد له رقم اليوم.
 *  - إعلان رمضان / العيد (6 ساعات).
 * المصدر: أخبار جوجل (Google News RSS). مو نظام إنذار رسمي — يتأخر وممكن يخطي.
 * نفس منطق news_worker.py على اللابتوب.
 */
final class NewsWorker {

    private static final long HEADLINE_EVERY = 3 * 3600_000L;
    private static final long HEADLINE_SHOW = 15 * 60_000L;
    private static final long ANNOUNCE_MS = 6 * 3600_000L;
    private static final long FETCH_EVERY = 3 * 60_000L;
    private static final long EVENT_GAP = 90 * 60_000L;
    private static final long KW = 3 * 3600_000L;

    private static final Pattern IMPORTANT = Pattern.compile("عاجل|سمو الأمير|أمير البلاد|ولي العهد|مجلس الوزراء|رئيس الوزراء|"
            + "تعطيل|عطلة|الدوام|الداخلية|الدفاع المدني|الإنذار|الأرصاد|الحرس الوطني|رئاسة الأركان|الجيش الكويتي|وزارة الصحة|الكهرباء");
    private static final Pattern KUWAIT = Pattern.compile("الكويت|الكويتية|أجواء البلاد");
    private static final Pattern DRONE = Pattern.compile("مسير|مسيّر");
    private static final Pattern MISSILE = Pattern.compile("صاروخ|صواريخ|باليستي");
    private static final Pattern ACTION = Pattern.compile("اعتراض|تصد|تتصدى|التصدي|أطلق|إطلاق|استهداف|استهدف|هجوم|هجمات|سقوط|اختراق|إسقاط");
    private static final Pattern FROM_KUWAIT = Pattern.compile("(?:من|انطلاق).{0,10}الكويت");
    private static final Pattern REACTION = Pattern.compile("يدين|تدين|إدانة|يندد|تندد|يستنكر|تستنكر|استنكار|تضامن|يتضامن|ترفض|رفض|"
            + "تؤكد|يؤكد|تحذر|يحذر|تدعو|يدعو|هل |تحليل|قراءة|سيناريو");
    private static final Pattern GENERIC = Pattern.compile("(?:هجمات|هجوم)\\s*جوي|تتصدى|تتصديان|اعتراض|تستهدف الكويت|يستهدف الكويت|استهداف الكويت");
    private static final Pattern MOON = Pattern.compile("هلال|الرؤية|رؤية");
    private static final Pattern MOON_KW = Pattern.compile("الكويت|الكويتية|هيئة الرؤية");
    private static final Pattern MOON_EID = Pattern.compile("شوال|عيد الفطر|ذي الحجة|ذو الحجة|عيد الأضحى|العيد");
    private static final Pattern MOON_RAM = Pattern.compile("رمضان");
    private static final Pattern MOON_DECIDE = Pattern.compile("ثبوت|تعذر|عدم|غدا|غداً|أول أيام|المتمم|يوافق|الأحد|الاثنين|الإثنين|الثلاثاء|الأربعاء|الخميس|الجمعة|السبت");

    static final class Item { String title, id; long ts; }

    private static volatile List<Item> news = new ArrayList<>(), attack = new ArrayList<>(), moon = new ArrayList<>();
    private static volatile boolean started = false;
    private static volatile long lastOk = 0;

    private static String gnews(String q) {
        try {
            return "https://news.google.com/rss/search?q=" + URLEncoder.encode(q, "UTF-8") + "&hl=ar&gl=KW&ceid=KW:ar";
        } catch (Exception e) { return null; }
    }

    static synchronized void start() {
        if (started) return;
        started = true;
        new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try {
                        List<Item> n = fetch(gnews("\"كونا\" when:12h"));
                        List<Item> a = fetch(gnews("الكويت (مسيرة OR مسيرات OR مسيّرة OR صاروخ OR صواريخ) when:1d"));
                        List<Item> m = fetch(gnews("الكويت هلال (رمضان OR شوال OR \"ذي الحجة\" OR \"عيد الفطر\" OR \"عيد الأضحى\") when:1d"));
                        if (n != null) news = n;
                        if (a != null) attack = a;
                        if (m != null) moon = m;
                        if (a != null) lastOk = System.currentTimeMillis();
                    } catch (Throwable ignored) { }
                    try { Thread.sleep(FETCH_EVERY); } catch (InterruptedException e) { return; }
                }
            }
        }, "diwan-news").start();
    }

    static boolean ready() { return lastOk > 0; }

    private static List<Item> fetch(String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(25000);
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) Chrome/120");
            if (con.getResponseCode() != 200) return null;
            InputStream in = con.getInputStream();
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            List<Item> out = new ArrayList<>();
            Item cur = null;
            String tag = null, src = null;
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = p.getName();
                    if ("item".equals(tag)) { cur = new Item(); src = null; }
                } else if (ev == XmlPullParser.TEXT && cur != null && tag != null) {
                    String t = p.getText();
                    if ("title".equals(tag)) cur.title = (cur.title == null ? "" : cur.title) + t;
                    else if ("guid".equals(tag)) cur.id = t;
                    else if ("source".equals(tag)) src = t;
                    else if ("pubDate".equals(tag)) {
                        try { cur.ts = f.parse(t.trim()).getTime(); } catch (Exception ignored) { }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if ("item".equals(p.getName()) && cur != null && cur.title != null && cur.ts > 0) {
                        String title = cur.title.trim();
                        if (src != null && title.endsWith(" - " + src.trim())) title = title.substring(0, title.length() - src.trim().length() - 3);
                        title = title.replaceAll("\\s+-\\s+[^-]{2,40}$", "").trim();
                        cur.title = title;
                        if (cur.id == null) cur.id = title;
                        out.add(cur);
                        cur = null;
                    }
                    tag = null;
                }
            }
            in.close();
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static boolean find(Pattern p, String s) { return p.matcher(s).find(); }

    private static String shortText(String t, int n) {
        t = t.replaceFirst("^\\s*عاجل\\s*[|:\\-]\\s*", "");
        return t.length() <= n ? t : t.substring(0, n - 1).trim() + "…";
    }

    private static String kwDay(long ms) {
        SimpleDateFormat d = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        d.setTimeZone(TimeZone.getTimeZone("UTC"));
        return d.format(new Date(ms + KW));
    }

    /** نفس رد /news من اللابتوب: {"headline":..., "events":[...], "announce":..., "now":...} */
    static synchronized String current(Context c) {
        try {
            long now = System.currentTimeMillis();
            SharedPreferences sp = c.getSharedPreferences("news", Context.MODE_PRIVATE);
            JSONObject st = new JSONObject(sp.getString("state", "{}"));
            JSONArray shown = st.optJSONArray("shown");
            if (shown == null) shown = new JSONArray();
            JSONObject det = st.optJSONObject("detected");
            if (det == null) det = new JSONObject();
            boolean changed = false;

            // ---------- الخبر المهم ----------
            JSONObject headline = null;
            JSONObject act = st.optJSONObject("active");
            if (act != null && now < act.optLong("until")) {
                headline = act;
            } else if (now - st.optLong("last_shown") >= HEADLINE_EVERY) {
                Item best = null;
                for (Item x : news) {
                    if (!find(IMPORTANT, x.title) || now - x.ts > 12 * 3600_000L) continue;
                    boolean seen = false;
                    for (int i = 0; i < shown.length(); i++) if (x.id.equals(shown.optString(i))) { seen = true; break; }
                    if (seen) continue;
                    if (best == null || x.ts > best.ts) best = x;
                }
                if (best != null) {
                    headline = new JSONObject().put("text", shortText(best.title, 75)).put("until", now + HEADLINE_SHOW);
                    shown.put(best.id);
                    st.put("last_shown", now).put("active", headline);
                    changed = true;
                }
            }

            // ---------- إعلان رمضان / العيد ----------
            JSONObject announce = null;
            List<Item> ms = new ArrayList<>(moon);
            Collections.sort(ms, BY_TS);
            for (Item x : ms) {
                String t = x.title;
                if (now - x.ts > ANNOUNCE_MS) continue;
                if (!(find(MOON, t) && find(MOON_KW, t) && find(MOON_DECIDE, t))) continue;
                String kind = find(MOON_EID, t) ? "eid" : find(MOON_RAM, t) ? "ramadan" : null;
                if (kind == null) continue;
                if (announce == null || !kind.equals(announce.optString("kind")))
                    announce = new JSONObject().put("kind", kind).put("time", x.ts).put("until", x.ts + ANNOUNCE_MS);
            }

            // ---------- القصف على الكويت ----------
            List<Item> hits = new ArrayList<>();
            for (Item x : attack) {
                String t = x.title;
                if (now - x.ts > 26 * 3600_000L) continue;
                if (!find(KUWAIT, t) || find(REACTION, t) || find(FROM_KUWAIT, t)) continue;
                boolean weapon = find(DRONE, t) || find(MISSILE, t);
                if (!((weapon && find(ACTION, t)) || find(GENERIC, t))) continue;
                hits.add(x);
            }
            Collections.sort(hits, BY_TS);
            List<List<Item>> clusters = new ArrayList<>();
            for (Item h : hits) {
                if (!clusters.isEmpty()) {
                    List<Item> last = clusters.get(clusters.size() - 1);
                    if (h.ts - last.get(last.size() - 1).ts < EVENT_GAP) { last.add(h); continue; }
                }
                List<Item> nl = new ArrayList<>(); nl.add(h); clusters.add(nl);
            }
            String today = kwDay(now);
            JSONArray events = new JSONArray();
            int n = 0;
            for (List<Item> cl : clusters) {
                long start = cl.get(0).ts;
                if (!today.equals(kwDay(start))) continue;
                n++;
                String id = String.valueOf(start / 1000);
                if (!det.has(id)) {
                    det.put(id, now - start > 30 * 60_000L ? start : now);   // القديم ما يطلع متأخر
                    changed = true;
                }
                boolean d = false, m = false;
                for (Item h : cl) { d |= find(DRONE, h.title); m |= find(MISSILE, h.title); }
                String kind = d && !m ? "drone" : m && !d ? "missile" : d ? "both" : "air";
                events.put(new JSONObject().put("id", id).put("n", n).put("kind", kind)
                        .put("time", start).put("detected", det.getLong(id)));
            }

            if (changed) {
                while (shown.length() > 300) shown.remove(0);
                st.put("shown", shown).put("detected", det);
                sp.edit().putString("state", st.toString()).apply();
            }
            return new JSONObject().put("headline", headline == null ? JSONObject.NULL : headline)
                    .put("events", events).put("announce", announce == null ? JSONObject.NULL : announce)
                    .put("now", now).put("source", "receiver").toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static final Comparator<Item> BY_TS = new Comparator<Item>() {
        @Override public int compare(Item a, Item b) { return a.ts < b.ts ? -1 : a.ts > b.ts ? 1 : 0; }
    };
}
