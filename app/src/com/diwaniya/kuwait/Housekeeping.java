package com.diwaniya.kuwait;

import android.app.ActivityManager;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.StatFs;

import java.io.File;
import java.util.regex.Pattern;

/** تنظيف ملفات التحميل القديمة حقتنا + قياس الذاكرة والتخزين والواي فاي (للتقرير) */
final class Housekeeping {

    /** بس ملفاتنا: نسخ التطبيق القديمة وملفات الشريط وتطبيق الطرفية */
    private static final Pattern OURS = Pattern.compile(
            "(?i)^(DiwaniyaKuwait.*\\.apk|DiwaniyaKuwait.*\\.apk|diwaniya.*\\.html|omi.*\\.html|ticker.*\\.html|terminal.*\\.apk|pt\\.zip)$");
    static volatile int cleaned = 0;
    static volatile long cleanedKb = 0;

    static void cleanDownloads() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File[] files = dir == null ? null : dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.isFile() && OURS.matcher(f.getName()).matches()) {
                    long kb = f.length() / 1024;
                    if (f.delete()) { cleaned++; cleanedKb += kb; }
                }
            }
        } catch (Throwable ignored) { }
    }

    /** التطبيقات اللي نزّلها المستخدم (مو تطبيقات النظام) — عشان نعرف وش نقدر نحذف */
    static String userApps(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            for (android.content.pm.ApplicationInfo ai : c.getPackageManager().getInstalledApplications(0)) {
                boolean system = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (system) continue;
                long mb = 0;
                try { mb = new File(ai.sourceDir).length() / 1048576; } catch (Throwable ignored) { }
                if (sb.length() > 0) sb.append(',');
                sb.append(ai.packageName).append(':').append(mb);
            }
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    /** يفتح صفحة حذف تطبيق (النظام يطلب «موافق» من المستخدم) */
    static void askUninstall(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);   // مثبّت؟
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_DELETE,
                    android.net.Uri.parse("package:" + pkg));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) { }
    }

    static String stats(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            sb.append("ramFreeMB=").append(mi.availMem / 1048576).append("/").append(mi.totalMem / 1048576)
              .append(";lowRam=").append(mi.lowMemory);
        } catch (Throwable ignored) { }
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            sb.append(";diskFreeMB=").append(fs.getAvailableBytes() / 1048576)
              .append("/").append(fs.getTotalBytes() / 1048576);
        } catch (Throwable ignored) { }
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            sb.append(";wifiRssi=").append(wi.getRssi()).append(";wifiMbps=").append(wi.getLinkSpeed())
              .append(";wifiMHz=").append(wi.getFrequency());
        } catch (Throwable ignored) { }
        sb.append(";cleaned=").append(cleaned).append("(").append(cleanedKb).append("KB)");
        return sb.toString();
    }
}
