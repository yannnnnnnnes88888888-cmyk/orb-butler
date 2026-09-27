package cn.workbuddy.orb;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * 通知渠道 + 真系统通知 + 精确闹钟。
 * 只用框架 API，不依赖 androidx，构建更快。
 */
public final class Notifier {

    public static final String CH_REMIND = "orb_remind";     // 到期 / 催促：高优先级
    public static final String CH_DAILY = "orb_daily";       // 每日盘问：默认优先级
    public static final String CH_SERVICE = "orb_service";   // 前台服务常驻：低优先级

    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel remind = new NotificationChannel(
                CH_REMIND, "任务提醒与催促", NotificationManager.IMPORTANCE_HIGH);
        remind.setDescription("截止时间、到点开始、发现你卡住时的提醒");
        remind.enableVibration(true);
        remind.setSound(sound, attrs);
        remind.setBypassDnd(false);
        nm.createNotificationChannel(remind);

        NotificationChannel daily = new NotificationChannel(
                CH_DAILY, "每日待办采集", NotificationManager.IMPORTANCE_DEFAULT);
        daily.setDescription("每天固定时间问你今天要做什么");
        daily.enableVibration(true);
        daily.setSound(sound, attrs);
        nm.createNotificationChannel(daily);

        NotificationChannel svc = new NotificationChannel(
                CH_SERVICE, "悬浮球常驻", NotificationManager.IMPORTANCE_LOW);
        svc.setDescription("保持悬浮光球在后台运行");
        svc.setSound(null, null);
        svc.enableVibration(false);
        nm.createNotificationChannel(svc);
    }

    /** 通知开关是否真的打开了（不是浏览器的通知，是系统通知） */
    public static boolean hasPermission(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return nm.areNotificationsEnabled();
        }
        return nm.areNotificationsEnabled();
    }

    /** kind: alarm(强铃声+震动) / warn / normal / daily */
    public static void notifyNow(Context c, String title, String text, String kind, String tag) {
        ensureChannels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (!hasPermission(c)) return;

        String channel = "daily".equals(kind) ? CH_DAILY : CH_REMIND;
        int id = (tag == null || tag.isEmpty()) ? 9001 : Math.abs(tag.hashCode() % 100000) + 1000;

        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("from_notify", true);
        PendingIntent pi = PendingIntent.getActivity(c, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 「标记完成」快捷动作：点了直接打开 App 的任务页
        Intent done = new Intent(c, MainActivity.class);
        done.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        done.putExtra("from_notify", true);
        done.putExtra("open_tab", "tasks");
        PendingIntent piDone = PendingIntent.getActivity(c, id + 500000, done,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_stat_orb)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_agenda, "去处理", piDone).build());

        if ("alarm".equals(kind)) {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound != null) b.setSound(sound);
            b.setVibrate(new long[]{0, 220, 140, 220, 140, 220});
            b.setPriority(Notification.PRIORITY_MAX);
        } else {
            b.setVibrate(new long[]{0, 120, 80, 120});
            b.setPriority(Notification.PRIORITY_DEFAULT);
        }

        nm.notify(id, b.build());
        Prefs.setLong(c, "fired_" + (tag == null ? "" : tag), System.currentTimeMillis());
    }

    /** 前台服务常驻通知：带「收纳 / 打开 / 退出」三个按钮 */
    public static Notification buildServiceNotification(Context c) {
        ensureChannels(c);

        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(c, 11, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent piHide = servicePi(c, FloatingService.ACTION_COLLAPSE, 12);
        PendingIntent piStop = servicePi(c, FloatingService.ACTION_STOP, 13);

        return new Notification.Builder(c, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_orb)
                .setContentTitle("光球管家在待命")
                .setContentText("点一下光球就能记待办；到点会提醒你")
                .setContentIntent(piOpen)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "收纳", piHide).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_view, "打开", piOpen).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_delete, "退出", piStop).build())
                .build();
    }

    private static PendingIntent servicePi(Context c, String action, int req) {
        Intent i = new Intent(c, FloatingService.class);
        i.setAction(action);
        return PendingIntent.getService(c, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 安排一个精确提醒（App 被杀 / 页面没打开，系统也会按时唤醒） */
    public static void schedule(Context c, String id, long whenMillis, String title, String text, String kind) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (whenMillis <= System.currentTimeMillis() + 5_000) return;

        Intent it = new Intent(c, ReminderReceiver.class);
        it.setAction(ReminderReceiver.ACTION_REMIND);
        it.putExtra("title", title);
        it.putExtra("text", text);
        it.putExtra("kind", kind == null ? "normal" : kind);
        it.putExtra("tag", id);
        int piId = Math.abs(id.hashCode() % 100000) + 2000;
        PendingIntent pi = PendingIntent.getBroadcast(c, piId, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean exact = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            exact = am.canScheduleExactAlarms();
        }
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pi);
        } catch (SecurityException e) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pi);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void cancel(Context c, String id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent it = new Intent(c, ReminderReceiver.class);
        it.setAction(ReminderReceiver.ACTION_REMIND);
        int piId = Math.abs(id.hashCode() % 100000) + 2000;
        PendingIntent pi = PendingIntent.getBroadcast(c, piId, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }

    /** Android 12+ 精确闹钟授权页（荣耀手机在「设置 → 应用 → 光球管家 → 闹钟和提醒」） */
    public static void openExactAlarmSettings(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                i.setData(Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Throwable ignored) {
            }
        }
    }
}
