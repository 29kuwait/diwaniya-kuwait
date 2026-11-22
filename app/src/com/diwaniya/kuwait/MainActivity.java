package com.diwaniya.kuwait;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/**
 * فتح التطبيق من القائمة يشغّل الشريط دائماً (ما يطفيه).
 * الإخفاء: ضغطتين بالماوس على الشريط، أو أيقونة «إيقاف الشريط» على اللابتوب.
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        BarService.setEnabled(this, true);
        BarService.setHiddenUntilBoot(this, false);
        startService(new Intent(this, BarService.class));
        if (!BarService.hasUsageAccess(this)) {
            Toast.makeText(this, "فعّل «شريط ديوانية الكويت» في القائمة عشان يظهر فوق تطبيقات البث فقط",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "الرسيفر ما فيه صفحة «الوصول لبيانات الاستخدام»", Toast.LENGTH_LONG).show();
            }
        } else if (!DiwanAccess.isEnabled(this)) {
            // لشاشة التوقف الخاصة: لازم نعرف متى ما أحد يستخدم الريموت
            Toast.makeText(this, "فعّل «شاشة توقف شريط ديوانية الكويت» في إمكانية الوصول عشان تشتغل الساعة الذهبية",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "افتح: الإعدادات ← تفضيلات الجهاز ← إمكانية الوصول", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "شريط ديوانية الكويت شغّال", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
