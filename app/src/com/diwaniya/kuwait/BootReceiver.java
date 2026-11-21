package com.diwaniya.kuwait;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * يشغّل الشريط مع تشغيل الرسيفر.
 * إذا كان مخفي بضغطتين: إعادة التشغيل (أو تحديث التطبيق) ترجّعه، وباقي الأحداث (مثل رجوع الواي فاي) ما ترجّعه.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        if (boot) BarService.setHiddenUntilBoot(context, false);
        if (BarService.isEnabled(context) && !BarService.isHiddenUntilBoot(context) && !BarService.running) {
            context.startService(new Intent(context, BarService.class));
        }
    }
}
