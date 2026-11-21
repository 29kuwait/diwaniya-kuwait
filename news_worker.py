"""متابع الأخبار للشريط — يشتغل داخل سيرفر اللابتوب.

1) خبر مهم من الكويت (كونا والصحف الكويتية): خبر واحد كل 3 ساعات، يظهر 15 دقيقة، وما يتكرر.
2) تنبيه هجوم على الكويت (مسيّرة / صاروخ) من عناوين الأخبار: يظهر 12 ساعة من آخر خبر.

تنبيه: المصدر أخبار منشورة (Google News)، مو نظام إنذار رسمي — يتأخر وممكن يخطي.
"""
import json, os, re, threading, time, datetime, urllib.request, urllib.parse
import xml.etree.ElementTree as ET
from email.utils import parsedate_to_datetime

HERE = os.path.dirname(os.path.abspath(__file__))
STATE = os.path.join(HERE, "news-state.json")
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120"}

HEADLINE_EVERY = 3 * 3600      # خبر كل 3 ساعات
HEADLINE_SHOW = 15 * 60        # يظهر 15 دقيقة
ALERT_HOURS = 12               # التنبيه يبقى 12 ساعة
FETCH_EVERY = 3 * 60           # نجيب الأخبار كل 3 دقايق (عشان تنبيه القصف يوصل أسرع)
EVENT_GAP = 90 * 60            # أخبار بفارق أقل من 90 دقيقة = نفس القصف

def gnews(q):
    return ("https://news.google.com/rss/search?q=" + urllib.parse.quote(q)
            + "&hl=ar&gl=KW&ceid=KW:ar")

FEED_NEWS = gnews('"كونا" when:12h')
FEED_ATTACK = gnews('الكويت (مسيرة OR مسيرات OR مسيّرة OR صاروخ OR صواريخ) when:1d')
FEED_MOON = gnews('الكويت هلال (رمضان OR شوال OR "ذي الحجة" OR "عيد الفطر" OR "عيد الأضحى") when:1d')

# إعلان رمضان / العيد في الكويت (هيئة الرؤية الشرعية)
ANNOUNCE_HOURS = 6
MOON = re.compile(r"هلال|الرؤية|رؤية")
MOON_KW = re.compile(r"الكويت|الكويتية|هيئة الرؤية")
MOON_EID = re.compile(r"شوال|عيد الفطر|ذي الحجة|ذو الحجة|عيد الأضحى|العيد")
MOON_RAM = re.compile(r"رمضان")
MOON_DECIDE = re.compile(r"ثبوت|تعذر|عدم|غدا|غداً|أول أيام|المتمم|يوافق|الأحد|الاثنين|الإثنين|الثلاثاء|الأربعاء|الخميس|الجمعة|السبت")

# كلمات تخلي الخبر "مهم"
IMPORTANT = re.compile(r"عاجل|سمو الأمير|أمير البلاد|ولي العهد|مجلس الوزراء|رئيس الوزراء|"
                       r"تعطيل|عطلة|الدوام|الداخلية|الدفاع المدني|الإنذار|الأرصاد|"
                       r"الحرس الوطني|رئاسة الأركان|الجيش الكويتي|وزارة الصحة|الكهرباء")
# تنبيه الهجوم: لازم الخبر يذكر الكويت + مسيّرة/صاروخ + فعل هجوم/اعتراض
KUWAIT = re.compile(r"الكويت|الكويتية|أجواء البلاد")
WEAPON_DRONE = re.compile(r"مسير|مسيّر")
WEAPON_MISSILE = re.compile(r"صاروخ|صواريخ|باليستي")
ACTION = re.compile(r"اعتراض|تصد|تتصدى|التصدي|أطلق|إطلاق|استهداف|استهدف|هجوم|هجمات|سقوط|اختراق|إسقاط")
NOT_KUWAIT_TARGET = re.compile(r"(?:من|انطلاق).{0,10}الكويت")  # ما نبيه لو الإطلاق "من" الكويت
# أخبار ردود الفعل (إدانة/تضامن/تحليل) مو هجوم جديد — نتجاهلها
REACTION = re.compile(r"يدين|تدين|إدانة|يندد|تندد|يستنكر|تستنكر|استنكار|تضامن|يتضامن|ترفض|رفض|"
                      r"تؤكد|يؤكد|تحذر|يحذر|تدعو|يدعو|هل |تحليل|قراءة|سيناريو")
# هجوم جوي عام على الكويت (بدون ذكر النوع)
GENERIC = re.compile(r"(?:هجمات|هجوم)\s*جوي|تتصدى|تتصديان|اعتراض|تستهدف الكويت|يستهدف الكويت|استهداف الكويت")

AR_NUM = {"اثنين": 2, "اثنتين": 2, "ثلاث": 3, "ثلاثة": 3, "أربع": 4, "أربعة": 4, "خمس": 5, "خمسة": 5,
          "ست": 6, "ستة": 6, "سبع": 7, "سبعة": 7, "ثماني": 8, "ثمانية": 8, "تسع": 9, "تسعة": 9, "عشر": 10, "عشرة": 10}

_lock = threading.Lock()
_items_news, _items_attack, _items_moon = [], [], []


def _fetch(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=25) as r:
        root = ET.fromstring(r.read())
    out = []
    for it in root.iter("item"):
        title = (it.findtext("title") or "").strip()
        src = (it.findtext("source") or "").strip()
        try:
            ts = parsedate_to_datetime(it.findtext("pubDate")).timestamp()
        except Exception:
            continue
        # نشيل اسم المصدر من آخر العنوان
        if src and title.endswith(" - " + src):
            title = title[: -len(" - " + src)]
        title = re.sub(r"\s+-\s+[^-]{2,40}$", "", title).strip()
        out.append({"title": title, "src": src, "ts": ts, "id": it.findtext("guid") or title})
    return out


def _load_state():
    try:
        with open(STATE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {"shown": [], "last_shown": 0, "active": None}


def _save_state(s):
    s["shown"] = s["shown"][-300:]
    tmp = STATE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(s, f, ensure_ascii=False)
    os.replace(tmp, STATE)


def _short(t, n=75):
    t = re.sub(r"^\s*(عاجل\s*[|:\-]\s*)", "", t)
    return t if len(t) <= n else t[: n - 1].rstrip() + "…"


def _count(title):
    m = re.search(r"(\d+)\s*(?:مسير|صاروخ|صواريخ)", title)
    if m:
        return int(m.group(1))
    for w, n in AR_NUM.items():
        if re.search(w + r"\s*(?:مسير|صاروخ|صواريخ)", title) or re.search(r"(?:مسير|صواريخ)\S*\s*" + w, title):
            return n
    if re.search(r"مسيرتين|مسيّرتين|صاروخين", title):
        return 2
    return 1


def _loop():
    while True:
        try:
            n = _fetch(FEED_NEWS)
            a = _fetch(FEED_ATTACK)
            try:
                m = _fetch(FEED_MOON)
            except Exception:
                m = None
            with _lock:
                _items_news[:] = n
                _items_attack[:] = a
                if m is not None:
                    _items_moon[:] = m
        except Exception:
            pass
        time.sleep(FETCH_EVERY)


def start():
    threading.Thread(target=_loop, daemon=True).start()


def current():
    """الرد اللي يوصل للشريط: {"headline": ..., "alert": ...}"""
    now = time.time()
    with _lock:
        news = list(_items_news)
        attack = list(_items_attack)
        moon = list(_items_moon)
    s = _load_state()

    # ---------- إعلان رمضان / العيد (يظهر 6 ساعات) ----------
    announce = None
    for x in sorted(moon, key=lambda x: x["ts"]):
        t = x["title"]
        if now - x["ts"] > ANNOUNCE_HOURS * 3600:
            continue
        if not (MOON.search(t) and MOON_KW.search(t) and MOON_DECIDE.search(t)):
            continue
        kind = "eid" if MOON_EID.search(t) else "ramadan" if MOON_RAM.search(t) else None
        if not kind:
            continue
        if announce is None or announce["kind"] != kind:
            announce = {"kind": kind, "time": int(x["ts"] * 1000),
                        "until": int((x["ts"] + ANNOUNCE_HOURS * 3600) * 1000)}

    # ---------- الخبر المهم ----------
    headline = None
    act = s.get("active")
    if act and now < act.get("until", 0):
        headline = act
    elif now - s.get("last_shown", 0) >= HEADLINE_EVERY:
        cands = [x for x in news if IMPORTANT.search(x["title"]) and x["id"] not in s["shown"]
                 and now - x["ts"] < 12 * 3600]
        cands.sort(key=lambda x: -x["ts"])
        if cands:
            x = cands[0]
            headline = {"text": _short(x["title"]), "until": now + HEADLINE_SHOW}
            s["shown"].append(x["id"])
            s["last_shown"] = now
            s["active"] = headline
            _save_state(s)

    # ---------- تنبيه الهجوم ----------
    hits = []
    for x in attack:
        t = x["title"]
        if now - x["ts"] > 26 * 3600:
            continue
        if not KUWAIT.search(t) or REACTION.search(t) or NOT_KUWAIT_TARGET.search(t):
            continue
        weapon = WEAPON_DRONE.search(t) or WEAPON_MISSILE.search(t)
        if not ((weapon and ACTION.search(t)) or GENERIC.search(t)):
            continue
        hits.append(x)
    # نقسم الأخبار إلى "قصفات": أخبار بفارق أقل من 90 دقيقة = نفس القصف
    hits.sort(key=lambda x: x["ts"])
    clusters = []
    for h in hits:
        if clusters and h["ts"] - clusters[-1][-1]["ts"] < EVENT_GAP:
            clusters[-1].append(h)
        else:
            clusters.append([h])
    # اليوم بتوقيت الكويت
    kw_today = time.strftime("%Y-%m-%d", time.gmtime(now + 3 * 3600))
    det = s.setdefault("detected", {})
    events, n_today = [], 0
    changed = False
    for c in clusters:
        start = c[0]["ts"]
        if time.strftime("%Y-%m-%d", time.gmtime(start + 3 * 3600)) != kw_today:
            continue
        n_today += 1
        eid = str(int(start))
        if eid not in det:
            # أول مرة نشوفه: لو الخبر قديم (أكثر من 30 دقيقة) نعتبره مكتشف وقت نشره عشان ما يطلع متأخر
            det[eid] = start if now - start > 30 * 60 else now
            changed = True
        drone = any(WEAPON_DRONE.search(h["title"]) for h in c)
        missile = any(WEAPON_MISSILE.search(h["title"]) for h in c)
        kind = "drone" if drone and not missile else "missile" if missile and not drone else \
               "both" if drone and missile else "air"
        events.append({"id": eid, "n": n_today, "kind": kind,
                       "time": int(start * 1000), "detected": int(det[eid] * 1000)})
    if changed:
        _save_state(s)
    return {"headline": headline, "events": events, "announce": announce, "now": int(now * 1000)}
