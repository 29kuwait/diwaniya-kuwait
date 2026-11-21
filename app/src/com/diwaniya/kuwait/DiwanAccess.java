package com.diwaniya.kuwait;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * يراقب ضغطات الريموت فقط:
 *  - يسجّل آخر وقت استُخدم فيه الريموت (عشان شاشة التوقف الخاصة تطلع بعد فترة بدون استخدام).
 *  - إذا كانت شاشة التوقف ظاهرة: أول ضغطة تخفيها بس (ما توصل للتطبيق اللي تحت)، مثل شاشة التوقف العادية.
 */
public class DiwanAccess extends AccessibilityService {

    static volatile long lastInput = System.currentTimeMillis();
    static volatile boolean connected = false;
    private static volatile int swallowUp = -1;

    static boolean isEnabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(c.getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        connected = true;
        lastInput = System.currentTimeMillis();
    }

    /** هل ضغط المستخدم ميوت؟ (الرسيفر ما يعرف صوت التلفزيون، فنتابع زر الميوت نفسه) */
    static volatile boolean keyMuted = false;
    /** آخر الأزرار المضغوطة (للفحص) */
    static volatile String lastKeys = "";
    /** رقم زر ميوت إضافي (لو ريموتك يرسل رقم غير معتاد) — يتغيّر من apps.json: mute_key */
    static volatile int extraMuteKey = -999;
    /** زر البيت يفتح واجهتنا (من apps.json: home_override) */
    static volatile boolean homeOverride = true;

    @Override
    public boolean onKeyEvent(KeyEvent e) {
        lastInput = System.currentTimeMillis();
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            // نسجّل آخر الأزرار (للفحص: نعرف رقم زر الميوت في ريموتك)
            String s = lastKeys + k + "(" + e.getScanCode() + "),";
            lastKeys = s.length() > 80 ? s.substring(s.length() - 80) : s;
        }
        // زر البيت: يفتح واجهتنا (بدون إعلانات) بدل الشاشة الرئيسية حقت قوقل
        if (k == KeyEvent.KEYCODE_HOME && homeOverride) {
            if (BarService.saverShowing) BarService.requestHideSaver();
            if (e.getAction() == KeyEvent.ACTION_UP) {
                try {
                    Intent i = new Intent(this, LauncherActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(i);
                } catch (Exception ignored) { return false; }
            }
            return true;
        }
        // زر الميوت: يوصل للتلفزيون عادي (ما نمسكه)، وأول ما ينكتم تطلع الساعة فوراً
        if (k == KeyEvent.KEYCODE_VOLUME_MUTE || k == KeyEvent.KEYCODE_MUTE || k == extraMuteKey) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                keyMuted = !keyMuted;
                if (keyMuted) BarService.requestShowSaver(); else BarService.requestHideSaver();
            }
            return false;
        }
        // رفع/خفض الصوت: بعد ما يتغيّر الصوت نشيّك — وصل صفر ⇒ تطلع الساعة، أكثر من صفر ⇒ تختفي
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (e.getAction() == KeyEvent.ACTION_UP) BarService.requestVolumeCheck();
            return false;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && BarService.saverShowing) {
            swallowUp = e.getKeyCode();
            BarService.requestHideSaver();
            return true;                       // الضغطة الأولى بس تخفي شاشة التوقف
        }
        if (e.getAction() == KeyEvent.ACTION_UP && e.getKeyCode() == swallowUp) {
            swallowUp = -1;
            return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // تغيّر الشاشة (فتح تطبيق) = استخدام — بس مو من تطبيقنا نفسه
        CharSequence p = event.getPackageName();
        if (p != null && getPackageName().contentEquals(p)) return;
        lastInput = System.currentTimeMillis();
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        connected = false;
        super.onDestroy();
    }
}
