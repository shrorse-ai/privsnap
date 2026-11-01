package io.privsnap.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.TimeZone;

public final class I18n {
    public static final String PREFS_NAME = "privsnap_prefs";
    public static final String KEY_LANG = "app_lang";
    public static final String LANG_AUTO = "auto";
    public static final String LANG_ZH = "zh";
    public static final String LANG_EN = "en";

    private static volatile String currentMode = LANG_AUTO;

    private I18n() {}

    public static void init(Context context) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        currentMode = prefs.getString(KEY_LANG, LANG_AUTO);
    }

    public static String getMode(Context context) {
        if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            currentMode = prefs.getString(KEY_LANG, LANG_AUTO);
        }
        return currentMode;
    }

    public static void setMode(Context context, String mode) {
        if (mode == null || (!LANG_ZH.equals(mode) && !LANG_EN.equals(mode) && !LANG_AUTO.equals(mode))) {
            mode = LANG_AUTO;
        }
        currentMode = mode;
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LANG, mode)
                    .apply();
        }
    }

    public static void toggleLanguage(Context context) {
        setMode(context, isEn() ? LANG_ZH : LANG_EN);
    }

    public static boolean isEn() {
        if (LANG_EN.equals(currentMode)) return true;
        if (LANG_ZH.equals(currentMode)) return false;
        return !isSystemZh();
    }

    public static boolean isSystemZh() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            for (String key : new String[]{"persist.sys.locale", "persist.sys.language", "ro.product.locale", "ro.product.locale.language"}) {
                String val = (String) get.invoke(null, key, "");
                if (val != null && !val.isEmpty()) {
                    String lower = val.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("zh") || lower.contains("zh-") || lower.contains("zh_") || lower.contains("hans") || lower.contains("hant")) {
                        return true;
                    }
                    if (lower.startsWith("en")) {
                        return false;
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            Configuration sysCfg = Resources.getSystem().getConfiguration();
            if (Build.VERSION.SDK_INT >= 24) {
                LocaleList list = sysCfg.getLocales();
                if (list != null && !list.isEmpty()) {
                    Locale first = list.get(0);
                    if (first != null && first.getLanguage() != null) {
                        if (first.getLanguage().toLowerCase(Locale.ROOT).startsWith("zh")) {
                            return true;
                        }
                    }
                }
            } else if (sysCfg.locale != null && sysCfg.locale.getLanguage() != null) {
                if (sysCfg.locale.getLanguage().toLowerCase(Locale.ROOT).startsWith("zh")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        Locale def = Locale.getDefault();
        if (def != null && def.getLanguage() != null && def.getLanguage().toLowerCase(Locale.ROOT).startsWith("zh")) {
            return true;
        }

        try {
            String tz = TimeZone.getDefault().getID();
            if (tz != null && (tz.equals("Asia/Shanghai") || tz.equals("Asia/Chongqing")
                    || tz.equals("Asia/Harbin") || tz.equals("Asia/Urumqi")
                    || tz.equals("Asia/Hong_Kong") || tz.equals("Asia/Taipei") || tz.equals("Asia/Macau"))) {
                return true;
            }
        } catch (Throwable ignored) {}

        return false;
    }

    public static String tr(String zh, String en) {
        return isEn() ? en : zh;
    }
}
