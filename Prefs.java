package cn.workbuddy.orb;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量存储：任务快照、悬浮球位置、开关、提醒去重标记 */
public final class Prefs {
    private static final String NAME = "orb_prefs";
    private static final String K_TASKS = "tasks_json";
    private static final String K_X = "float_x";
    private static final String K_Y = "float_y";
    private static final String K_AUTOSTART = "autostart";
    private static final String K_LAST = "last_reminder";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static void saveTasks(Context c, String json) {
        if (json == null) return;
        sp(c).edit().putString(K_TASKS, json).apply();
    }

    public static String getTasks(Context c) {
        return sp(c).getString(K_TASKS, "{}");
    }

    public static void savePos(Context c, int x, int y) {
        sp(c).edit().putInt(K_X, x).putInt(K_Y, y).apply();
    }

    public static int getX(Context c) {
        return sp(c).getInt(K_X, Integer.MIN_VALUE);
    }

    public static int getY(Context c) {
        return sp(c).getInt(K_Y, Integer.MIN_VALUE);
    }

    public static boolean autoStart(Context c) {
        return sp(c).getBoolean(K_AUTOSTART, true);
    }

    public static void setAutoStart(Context c, boolean v) {
        sp(c).edit().putBoolean(K_AUTOSTART, v).apply();
    }

    public static void markReminder(Context c, long t) {
        sp(c).edit().putLong(K_LAST, t).apply();
    }

    public static long lastReminder(Context c) {
        return sp(c).getLong(K_LAST, 0);
    }

    /** 通用键值，用于提醒去重（同一条提醒 6 小时内不重复打扰） */
    public static void setLong(Context c, String key, long v) {
        sp(c).edit().putLong(key, v).apply();
    }

    public static long getLong(Context c, String key, long def) {
        return sp(c).getLong(key, def);
    }
}
