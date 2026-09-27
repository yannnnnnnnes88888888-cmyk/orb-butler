package cn.workbuddy.orb;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 真正的系统级提醒：即使 App 被划掉、页面没打开，也会由 AlarmManager 按时唤醒并弹到通知栏。
 * 每 15 分钟自检一次：逾期 / 快到点 / 今天还没盘待办。
 */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_REMIND = "cn.workbuddy.orb.action.REMIND";
    public static final String ACTION_TICK = "cn.workbuddy.orb.action.TICK";

    private static final long TICK_MS = 15 * 60 * 1000L;
    private static final long DEDUPE_MS = 5 * 60 * 60 * 1000L;
    private static final long LEAD_MS = 30 * 60 * 1000L;   // 提前 30 分钟催
    private static final long OVERDUE_WINDOW = 14 * 60 * 60 * 1000L;

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? ACTION_TICK : i.getAction();
        if (ACTION_REMIND.equals(a)) {
            String title = i.getStringExtra("title");
            String text = i.getStringExtra("text");
            String kind = i.getStringExtra("kind");
            String tag = i.getStringExtra("tag");
            if (title == null) title = "光球提醒";
            if (text == null) text = "";
            Notifier.notifyNow(c, title, text, kind == null ? "normal" : kind, tag);
            FloatingService.ping(c, "alert".equals(kind) ? "alert" : "warn",
                    ("alarm".equals(kind) ? "到点啦：「" : "快到点啦：「") + title + "」");
            scheduleTick(c);
            return;
        }
        doCheck(c);
        rescheduleAll(c);
    }

    /* ---------------- 自检 ---------------- */

    private void doCheck(Context c) {
        long now = System.currentTimeMillis();
        List<TaskItem> list = readTasks(c);
        if (list.isEmpty()) {
            askForCheckIn(c, now, true);
            return;
        }

        for (TaskItem t : list) {
            if (t.done) continue;
            if (t.deadline <= 0) continue;

            long left = t.deadline - now;
            if (left <= 0) {
                if (now - t.deadline < OVERDUE_WINDOW && once(c, "overdue_" + t.id)) {
                    Notifier.notifyNow(c, "已经逾期了 · " + t.title,
                            "现在就开始做 15 分钟，或者把截止时间往后挪一天。",
                            "alarm", "overdue_" + t.id);
                    FloatingService.ping(c, "alert", "「" + t.title + "」已经逾期了，做 15 分钟先？");
                }
            } else if (left <= LEAD_MS) {
                if (once(c, "lead_" + t.id)) {
                    int min = (int) Math.max(1, left / 60000);
                    Notifier.notifyNow(c, min + " 分钟后 · " + t.title,
                            "还有 " + min + " 分钟到点，先把手头的事收个尾。",
                            "warn", "lead_" + t.id);
                    FloatingService.ping(c, "warn", "「" + t.title + "」还有 " + min + " 分钟到点啦");
                }
            }
        }
        askForCheckIn(c, now, false);
    }

    private void askForCheckIn(Context c, long now, boolean empty) {
        int hour = LocalDateTime.now().getHour();
        if (hour < 8 || hour > 21) return;
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!once(c, "checkin_" + day)) return;

        if (empty) {
            Notifier.notifyNow(c, "今天想搞定什么？",
                    "点一下光球，把今天的待办和截止时间丢给我，我来排。",
                    "daily", "checkin_" + day);
        } else {
            Notifier.notifyNow(c, "今天还顺利吗？",
                    "有卡住的任务就点开光球，我帮你拆成 15 分钟能动手的第一步。",
                    "daily", "checkin_" + day);
        }
        FloatingService.ping(c, "idle", empty
                ? "今天想搞定什么？把待办丢给我呀～"
                : "今天还顺利吗？卡住了就跟我说～");
    }

    private boolean once(Context c, String key) {
        long last = Prefs.getLong(c, "fired_" + key, 0);
        long now = System.currentTimeMillis();
        if (now - last < DEDUPE_MS) return false;
        Prefs.setLong(c, "fired_" + key, now);
        return true;
    }

    /* ---------------- 排程 ---------------- */

    /** 每次任务有变动后调用：重排所有闹钟 */
    public static void rescheduleAll(Context c) {
        scheduleTick(c);

        long now = System.currentTimeMillis();
        for (TaskItem t : readTasks(c)) {
            if (t.done || t.deadline <= 0 || t.deadline <= now) continue;
            Notifier.schedule(c, t.id + "@d", t.deadline,
                    "到点了 · " + t.title, "现在开始做，先 15 分钟。", "alarm");
            long lead = t.deadline - LEAD_MS;
            if (lead > now) {
                Notifier.schedule(c, t.id + "@lead", lead,
                        "30 分钟后 · " + t.title, "先把手头的事收个尾。", "warn");
            }
        }

        // 每天 09:00 的待办盘问
        LocalDateTime next = LocalDate.now().atTime(LocalTime.of(9, 0));
        if (next.isBefore(LocalDateTime.now())) next = next.plusDays(1);
        Notifier.schedule(c, "daily_checkin",
                next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                "早上好，今天想搞定什么？", "把待办和截止时间丢给我，我给你排好顺序。", "daily");
    }

    private static void scheduleTick(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent it = new Intent(c, ReminderReceiver.class);
        it.setAction(ACTION_TICK);
        PendingIntent pi = PendingIntent.getBroadcast(c, 7777, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long at = System.currentTimeMillis() + TICK_MS;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Throwable ignored) {
            }
        }
    }

    /* ---------------- 读任务 ---------------- */

    static List<TaskItem> readTasks(Context c) {
        List<TaskItem> out = new ArrayList<>();
        try {
            String raw = Prefs.getTasks(c);
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.optJSONArray("tasks");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                TaskItem t = new TaskItem();
                t.id = o.optString("id", String.valueOf(i));
                t.title = o.optString("title", "未命名任务");
                String st = o.optString("status", "todo");
                t.done = "done".equals(st) || "archived".equals(st);
                t.deadline = parseTime(o.optString("deadline", null));
                out.add(t);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static class TaskItem {
        String id;
        String title;
        boolean done;
        long deadline;
    }

    static long parseTime(String s) {
        if (s == null || s.isEmpty()) return 0;
        s = s.trim();
        try {
            if (s.matches("\\d+")) return Long.parseLong(s);
            try {
                return Instant.parse(s).toEpochMilli();
            } catch (Throwable ignored) {
            }
            try {
                return OffsetDateTime.parse(s).toInstant().toEpochMilli();
            } catch (Throwable ignored) {
            }
            try {
                return ZonedDateTime.parse(s).toInstant().toEpochMilli();
            } catch (Throwable ignored) {
            }
            try {
                return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Throwable ignored) {
            }
            return LocalDate.parse(s).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Throwable e) {
            return 0;
        }
    }
}
