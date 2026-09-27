package cn.workbuddy.orb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/** 开机 / 更新后自动把光球和提醒接回来 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;

        ReminderReceiver.rescheduleAll(c);

        if (!Prefs.autoStart(c)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(c)) return;

        Intent i = new Intent(c, FloatingService.class);
        i.setAction(FloatingService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            try {
                c.startService(i);
            } catch (Throwable ignored) {
            }
        }
    }
}
