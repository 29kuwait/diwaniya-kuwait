package com.diwaniya.kuwait.lite;

/**
 * Offline prayer-time calculation (standard astronomical method),
 * Kuwait method: Fajr 18°, Isha 17.5°, Asr standard (shadow factor 1).
 * Location: Kuwait City, UTC+3 (no daylight saving).
 */
final class PrayerTimes {
    static final double LAT = 29.3759, LNG = 47.9774, TZ = 3.0;
    static final double FAJR_ANGLE = 18.0, ISHA_ANGLE = 17.5;

    static final int FAJR = 0, SUNRISE = 1, DHUHR = 2, ASR = 3, MAGHRIB = 4, ISHA = 5;
    static final String[] NAMES = {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"};

    /** Minutes added per prayer to match the official Kuwait timetable. */
    static final double[] OFFSET_MIN = {0, 0, 0, 0, 0, 0};

    private PrayerTimes() {}

    /** Returns minutes after local midnight for fajr, sunrise, dhuhr, asr, maghrib, isha. */
    static double[] compute(int year, int month, int day) {
        double jd = julian(year, month, day) - LNG / (15.0 * 24.0);
        double[] t = {5, 6, 12, 13, 18, 18};
        for (int iter = 0; iter < 2; iter++) {
            double[] p = new double[6];
            for (int k = 0; k < 6; k++) p[k] = t[k] / 24.0;
            double[] n = new double[6];
            n[FAJR] = sunAngleTime(jd, FAJR_ANGLE, p[FAJR], true);
            n[SUNRISE] = sunAngleTime(jd, 0.833, p[SUNRISE], true);
            n[DHUHR] = midDay(jd, p[DHUHR]);
            n[ASR] = asrTime(jd, 1, p[ASR]);
            n[MAGHRIB] = sunAngleTime(jd, 0.833, p[MAGHRIB], false);
            n[ISHA] = sunAngleTime(jd, ISHA_ANGLE, p[ISHA], false);
            t = n;
        }
        double[] out = new double[6];
        for (int k = 0; k < 6; k++) out[k] = (t[k] + TZ - LNG / 15.0) * 60.0 + OFFSET_MIN[k];
        return out;
    }

    private static double julian(int year, int month, int day) {
        if (month <= 2) { year -= 1; month += 12; }
        double a = Math.floor(year / 100.0);
        double b = 2 - a + Math.floor(a / 4.0);
        return Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + b - 1524.5;
    }

    /** Returns {declination, equationOfTime}. */
    private static double[] sunPosition(double jd) {
        double d = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * d);
        double q = fixAngle(280.459 + 0.98564736 * d);
        double l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g));
        double e = 23.439 - 0.00000036 * d;
        double ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0;
        double eqt = q / 15.0 - fixHour(ra);
        double decl = darcsin(dsin(e) * dsin(l));
        return new double[]{decl, eqt};
    }

    private static double midDay(double jd, double t) {
        double eqt = sunPosition(jd + t)[1];
        return fixHour(12 - eqt);
    }

    private static double sunAngleTime(double jd, double angle, double t, boolean beforeNoon) {
        double decl = sunPosition(jd + t)[0];
        double noon = midDay(jd, t);
        double v = (-dsin(angle) - dsin(decl) * dsin(LAT)) / (dcos(decl) * dcos(LAT));
        v = Math.max(-1, Math.min(1, v));
        double h = darccos(v) / 15.0;
        return noon + (beforeNoon ? -h : h);
    }

    private static double asrTime(double jd, double factor, double t) {
        double decl = sunPosition(jd + t)[0];
        double angle = -darccot(factor + dtan(Math.abs(LAT - decl)));
        return sunAngleTime(jd, angle, t, false);
    }

    private static double dsin(double d) { return Math.sin(Math.toRadians(d)); }
    private static double dcos(double d) { return Math.cos(Math.toRadians(d)); }
    private static double dtan(double d) { return Math.tan(Math.toRadians(d)); }
    private static double darcsin(double x) { return Math.toDegrees(Math.asin(x)); }
    private static double darccos(double x) { return Math.toDegrees(Math.acos(x)); }
    private static double darctan2(double y, double x) { return Math.toDegrees(Math.atan2(y, x)); }
    private static double darccot(double x) { return Math.toDegrees(Math.atan(1.0 / x)); }
    private static double fixAngle(double a) { a = a - 360.0 * Math.floor(a / 360.0); return a < 0 ? a + 360 : a; }
    private static double fixHour(double a) { a = a - 24.0 * Math.floor(a / 24.0); return a < 0 ? a + 24 : a; }
}
