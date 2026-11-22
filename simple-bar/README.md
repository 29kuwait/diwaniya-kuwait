# 📺 الشريط البسيط — ديوانيات أهل الكويت
### The Simple Bar — Diwaniyas of Kuwait

[العربية](#العربية) · [English](#english)

---

## العربية

شريط خفيف جداً (حوالي 80 كيلوبايت) يظهر أسفل شاشة التلفزيون: **الطقس، الساعة والتاريخ، والصلاة القادمة بعدّاد تنازلي**. مبني بمكونات أندرويد مباشرة (بدون متصفح)، فيشتغل على الأجهزة القديمة مثل رسيفر **Eurostar ES4000 UHD (أندرويد 5.1)**، وما يحتاج لابتوب ولا سيرفر.

> هذا غير «الشريط الأساسي» (`DiwaniyaKuwait.apk`). الشريط الأساسي فيه تنبيهات القصف والغبار والأخبار والمباريات والساعة الذهبية. الشريط البسيط للأجهزة القديمة اللي ما تشغّل الأساسي.

### ✨ المميزات

| | الميزة | التفاصيل |
|---|---|---|
| 🌡️ | **الطقس** | حرارة اليوم وحالة الجو، وحرارة الغد (عظمى/صغرى)، من open-meteo، ويتحدث كل 30 دقيقة |
| 🕐 | **الساعة والتاريخ** | بنظام 12 ساعة (ص/م) مع اليوم والتاريخ |
| 🕌 | **الصلاة القادمة** | عدّاد تنازلي باسم الصلاة ووقتها. يتحول **أصفر** في آخر 15 دقيقة، وبعد الأذان بـ10 دقايق يكتب «حان الآن وقت صلاة...». يوم الجمعة يكتب «الجمعة» بدل الظهر |
| 📴 | **المواقيت بدون إنترنت** | تُحسب داخل التطبيق بطريقة الكويت |
| 👁️ | **يظهر في التطبيقات اللي تحددها** | الشاشة الرئيسية، ومشغل الستلايت، وتطبيقات IPTV (XCIPTV وShark IPTV وNEXT+). ويختفي في باقي التطبيقات |
| 🔘 | **تشغيل وإيقاف** | افتح التطبيق من الشاشة الرئيسية: مرة يشغّل ومرة يوقف، ويتذكر اختيارك |
| 🔌 | **يبدأ مع الجهاز** | يشتغل تلقائياً بعد تشغيل الرسيفر |
| 🇰🇼 | **شعار ديوانية الكويت** | في الزاوية اليمنى فوق الشريط |

### 📏 المقاس

كل المقاسات نسبة لارتفاع الشاشة، فيناسب أي شاشة:

| العنصر | النسبة | على شاشة 720 | على شاشة 1080 | على شاشة 4K |
|---|---|---|---|---|
| ارتفاع الشريط | 4.6% (أقل شي 28 بكسل) | 33 | 50 | 99 |
| الخط الأزرق الرفيع فوقه | ارتفاع الشريط ÷ 16 (أقل شي 2) | 2 | 3 | 6 |
| الخط | 46% من ارتفاع الشريط | 15 | 23 | 46 |
| الشعار | 1.75 ضعف ارتفاع الشريط | 58 | 87 | 173 |

الشريط بعرض الشاشة كلها وبالأسفل، وخلفيته كحلي شبه معتم (حوالي 90%).

### 🚀 التثبيت

التثبيت من الكمبيوتر بواسطة ADB (أداة جوجل للتحكم بالجهاز):

1. في الرسيفر: الإعدادات ← حول الجهاز ← اضغط «رقم الإصدار» 7 مرات، ثم فعّل «تصحيح USB / الشبكة».
2. الكمبيوتر والرسيفر على نفس الواي فاي.
3. شغّل هذي الأوامر (غيّر العنوان لعنوان رسيفرك):

```
adb connect 192.168.0.220:5555
adb -s 192.168.0.220:5555 install -r DiwaniyaKuwait-SimpleBar.apk
adb -s 192.168.0.220:5555 shell appops set com.diwaniya.kuwait.lite GET_USAGE_STATS allow
adb -s 192.168.0.220:5555 shell am start -n com.diwaniya.kuwait.lite/.MainActivity --ez force_on true
```

الأمر الثالث يعطي الشريط صلاحية «بيانات الاستخدام» عشان يعرف أي تطبيق مفتوح. بدونه الشريط يبقى ظاهر في كل التطبيقات.

### 🛠️ البناء بنفسك
شغّل `build.ps1` (بدون Gradle)، ويحتاج JDK و Android SDK. يسوي مفتاح توقيع محلي `signing.jks` (مستثنى من GitHub، لا تحذفه لأنك تحتاجه للتحديث).

### 📌 ملاحظات
- **مجرّب على** رسيفر Eurostar ES4000 UHD أندرويد 5.1. ما جربته على أجهزة ثانية.
- قائمة التطبيقات اللي يظهر فيها الشريط مكتوبة في `BarService.java` (`SHOW_IN`)، وفيها أسماء حزم خاصة برسيفر ES4000.
- **ما فيه** في الشريط البسيط: تنبيهات القصف والغبار، الأخبار، المباريات، باركود الواي فاي، الساعة الذهبية. هذي في الشريط الأساسي.

---

## English

A very light bar (about 80 KB) at the bottom of the TV showing **weather, clock and date, and the next prayer with a countdown**. It is built with native Android views (no web view), so it runs on old boxes such as the **Eurostar ES4000 UHD (Android 5.1)**, and needs no PC or server.

> This is different from the **main bar** (`DiwaniyaKuwait.apk`), which has attack and dust alerts, news, matches and the gold clock screensaver. The Simple Bar is for older boxes that cannot run the main one.

### ✨ Features

| | Feature | Details |
|---|---|---|
| 🌡️ | **Weather** | Today's temperature and conditions, plus tomorrow's high/low, from open-meteo, refreshed every 30 minutes |
| 🕐 | **Clock and date** | 12-hour clock (AM/PM) with the day and date |
| 🕌 | **Next prayer** | Countdown with the prayer name and time. Turns **yellow** in the last 15 minutes, and for 10 minutes after the adhan it says "It is now time for prayer...". Shows "Friday" instead of Dhuhr on Fridays |
| 📴 | **Prayer times offline** | Calculated inside the app using the Kuwait method |
| 👁️ | **Shows only in chosen apps** | Home screen, satellite player and IPTV apps (XCIPTV, Shark IPTV, NEXT+). Hidden in other apps |
| 🔘 | **On/off** | Open the app from the home screen to toggle it; your choice is remembered |
| 🔌 | **Starts on boot** | Starts automatically when the box powers on |
| 🇰🇼 | **Diwaniya Kuwait emblem** | In the top-right corner above the bar |

### 📏 Size

All sizes are relative to the screen height, so it fits any screen:

| Element | Ratio | 720p | 1080p | 4K |
|---|---|---|---|---|
| Bar height | 4.6% (minimum 28 px) | 33 | 50 | 99 |
| Thin blue line on top | bar height ÷ 16 (minimum 2) | 2 | 3 | 6 |
| Text | 46% of the bar height | 15 | 23 | 46 |
| Emblem | 1.75 × bar height | 58 | 87 | 173 |

The bar spans the full screen width at the bottom, with a semi-opaque navy background (about 90%).

### 🚀 Install

Install from a PC using ADB (Google's device-control tool):

1. On the box: Settings → About → tap "Build number" 7 times, then enable USB / network debugging.
2. Put the PC and the box on the same Wi-Fi.
3. Run these commands (use your box's IP address):

```
adb connect 192.168.0.220:5555
adb -s 192.168.0.220:5555 install -r DiwaniyaKuwait-SimpleBar.apk
adb -s 192.168.0.220:5555 shell appops set com.diwaniya.kuwait.lite GET_USAGE_STATS allow
adb -s 192.168.0.220:5555 shell am start -n com.diwaniya.kuwait.lite/.MainActivity --ez force_on true
```

The third command grants "Usage access" so the bar knows which app is open. Without it, the bar stays visible in every app.

### 🛠️ Build it yourself
Run `build.ps1` (no Gradle). It needs a JDK and the Android SDK. It creates a local signing key `signing.jks` (excluded from GitHub; do not delete it, you need it to update).

### 📌 Notes
- **Tested on** a Eurostar ES4000 UHD with Android 5.1. Not tested on other boxes.
- The list of apps where the bar shows is in `BarService.java` (`SHOW_IN`), and includes package names specific to the ES4000.
- **Not included** in the Simple Bar: attack and dust alerts, news, matches, the Wi-Fi QR and the gold clock. These are in the main bar.
