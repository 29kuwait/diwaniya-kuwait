"""سيرفر شريط الديوانية — يشتغل على اللابتوب ويخدم الرسيفر على نفس الواي فاي.

/          صفحة فيها زر تحميل ملف الرسيفر (أول مرة فقط)
/get       تحميل ملف الرسيفر (receiver.html باسم diwaniya.html)
/status    هل الشريط شغّال + رقم النسخة الحالية
/view      محتوى الشريط نفسه (ticker.html)
/loader    نفس ملف الرسيفر للتجربة من المتصفح

للإيقاف/التشغيل: ملف state.txt فيه on أو off (تغيّره أيقونات سطح المكتب).
"""
import http.server, json, os, sys, datetime

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import news_worker   # الأخبار المهمة + تنبيه المسيّرات/الصواريخ
TICKER = os.path.join(HERE, "ticker.html")
RECEIVER = os.path.join(HERE, "receiver.html")
STATE = os.path.join(HERE, "state.txt")
LOG = os.path.join(HERE, "server.log")
PORT = 8765


def log(msg):
    try:
        if os.path.exists(LOG) and os.path.getsize(LOG) > 1_000_000:
            os.replace(LOG, LOG + ".old")
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(f"{datetime.datetime.now():%Y-%m-%d %H:%M:%S} {msg}\n")
    except OSError:
        pass


def is_on():
    try:
        with open(STATE, encoding="utf-8") as f:
            return f.read().strip().lower() != "off"
    except OSError:
        return True


def version():
    try:
        return str(int(os.path.getmtime(TICKER)))
    except OSError:
        return "0"


def saver_version():
    try:
        return str(int(os.path.getmtime(SAVER)))
    except OSError:
        return "0"


INDEX = """<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8">
<body style="font-family:sans-serif;background:#0b1220;color:#fff;text-align:center;padding-top:12vh;font-size:5vh">
<p>تثبيت شريط الديوانية على الرسيفر</p>
<a href="/app" autofocus style="display:inline-block;background:#1e5bd6;color:#fff;padding:3vh 6vh;border-radius:2vh;text-decoration:none">تحميل تطبيق الشريط</a>
<p style="font-size:3vh;color:#9aa7bd;margin-top:6vh">(نسخة المتصفح القديمة)</p>
<a href="/get" style="display:inline-block;background:#26324a;color:#fff;padding:2vh 4vh;border-radius:2vh;text-decoration:none;font-size:3.5vh">تحميل الشريط للمتصفح</a>
</body></html>""".encode("utf-8")
APK = os.path.join(HERE, "DiwaniyaKuwait.apk")
SAVER = os.path.join(HERE, "saver.html")    # شاشة السكون (الساعة الذهبية)
APPS = os.path.join(HERE, "apps.json")      # التطبيقات اللي يظهر فوقها الشريط
SEEN = os.path.join(HERE, "seen-apps.txt")  # كل التطبيقات اللي انفتحت على الرسيفر


class H(http.server.BaseHTTPRequestHandler):
    def _send(self, body, ctype, extra=None, code=200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        p = self.path.split("?")[0]
        if p not in ("/status", "/seen", "/apps", "/news"):
            log(f"{self.client_address[0]} GET {p}")
        try:
            if p in ("/", "/index.html"):
                return self._send(INDEX, "text/html; charset=utf-8")
            if p == "/status":
                body = json.dumps({"on": is_on(), "v": version(), "sv": saver_version()}).encode()
                return self._send(body, "application/json")
            if p == "/diag":
                # تقرير فحص من الرسيفر: من وين يجيب الأخبار، وهل واجهة التطبيق موجودة، إلخ
                from urllib.parse import unquote
                q = unquote(self.path.split("?", 1)[1]) if "?" in self.path else ""
                log(f"{self.client_address[0]} DIAG {q}")
                return self._send(b"ok", "text/plain")
            if p == "/news":
                body = json.dumps(news_worker.current(), ensure_ascii=False).encode("utf-8")
                return self._send(body, "application/json; charset=utf-8")
            if p == "/aref-ruqaa-bold.woff2":
                return self._send(open(os.path.join(HERE, "source", "aref-ruqaa-bold.woff2"), "rb").read(), "font/woff2")
            if p == "/launcher":
                # معاينة الواجهة الرئيسية على الكمبيوتر (بتطبيقات وهمية)
                body = open(os.path.join(HERE, "source", "launcher.html"), "rb").read()
                mock = b"""<script>window.DiwanHome={getApps:()=>JSON.stringify([
{pkg:'com.royalplus.app',label:'Royal Plus',banner:'',icon:''},{pkg:'com.mbm_soft.flash4k',label:'Flash 4K',banner:'',icon:''},
{pkg:'com.mbm_soft.aroma4k',label:'Aroma 4K',banner:'',icon:''},{pkg:'com.google.android.youtube.tv',label:'YouTube',banner:'',icon:''},
{pkg:'a.b',label:'Chrome',banner:'',icon:''},{pkg:'c.d',label:'Google Play',banner:'',icon:''},{pkg:'e.f',label:'Downloader',banner:'',icon:''},
{pkg:'g.h',label:'VLC',banner:'',icon:''},{pkg:'i.j',label:'File Manager',banner:'',icon:''}]),
getFavorites:()=>'com.royalplus.app,com.mbm_soft.flash4k,com.mbm_soft.aroma4k,com.google.android.youtube.tv',launch:p=>console.log('launch',p),openSettings:()=>console.log('settings'),action:a=>console.log('action',a)};</script>"""
                return self._send(body.replace(b"<script>", mock + b"<script>", 1), "text/html; charset=utf-8")
            if p == "/preview":
                # معاينة: صورة ملعب، والشريط الشفاف فوقها تحت (نفس مقاس التلفزيون 6%)
                body = ("""<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;height:100%;overflow:hidden;background:#2d6a2f url(https://images.unsplash.com/photo-1574629810360-7efbbe195018?w=1920) center/cover}
iframe{position:fixed;left:0;right:0;bottom:0;width:100%;height:6vh;border:0;background:transparent}
</style></head><body><iframe src="/view" allowtransparency="true"></iframe></body></html>""").encode("utf-8")
                return self._send(body, "text/html; charset=utf-8")
            if p == "/saver":
                return self._send(open(SAVER, "rb").read(), "text/html; charset=utf-8")
            if p == "/view":
                return self._send(open(TICKER, "rb").read(), "text/html; charset=utf-8")
            if p == "/loader":
                return self._send(open(RECEIVER, "rb").read(), "text/html; charset=utf-8")
            if p == "/apps":
                # قائمة التطبيقات اللي يظهر فوقها الشريط (تعدّل من ملف apps.json)
                try:
                    body = open(APPS, "rb").read()
                except OSError:
                    body = b'{"show_in": []}'
                return self._send(body, "application/json")
            if p == "/seen":
                # الرسيفر يبلّغ عن التطبيق المفتوح — نسجله عشان نعرف أسماء تطبيقات البث عنده
                q = self.path.split("?", 1)[1] if "?" in self.path else ""
                pkg = ""
                for part in q.split("&"):
                    if part.startswith("pkg="):
                        from urllib.parse import unquote
                        pkg = unquote(part[4:])
                if pkg:
                    seen = set()
                    try:
                        seen = set(open(SEEN, encoding="utf-8").read().split())
                    except OSError:
                        pass
                    if pkg not in seen:
                        with open(SEEN, "a", encoding="utf-8") as f:
                            f.write(pkg + "\n")
                    log(f"{self.client_address[0]} foreground {pkg}")
                return self._send(b"ok", "text/plain")
            if p == "/app":
                return self._send(open(APK, "rb").read(), "application/vnd.android.package-archive",
                                  {"Content-Disposition": 'attachment; filename="DiwaniyaKuwait.apk"'})
            if p == "/get":
                return self._send(open(RECEIVER, "rb").read(), "application/octet-stream",
                                  {"Content-Disposition": 'attachment; filename="diwaniya.html"'})
        except OSError as e:
            log(f"error {e}")
            return self._send(b"error", "text/plain", code=500)
        self._send(b"not found", "text/plain", code=404)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    log("server start")
    news_worker.start()
    http.server.ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
