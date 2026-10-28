package com.zigorminsk.backupcontrol;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

final class ReminderScheduler {
    static final String WEEKLY = "com.zigorminsk.backupcontrol.WEEKLY";
    static final String ERROR = "com.zigorminsk.backupcontrol.ERROR";

    private ReminderScheduler() { }

    static boolean exactAllowed(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms();
    }

    static void scheduleWeekly(Context context) {
        BackupStore store = new BackupStore(context);
        schedule(context, store.nextControl(), weeklyIntent(context));
    }

    static void scheduleError(Context context, BackupItem item) {
        if (item.reminderAt <= 0 || item.reminderDelivered) return;
        if (item.reminderAt <= System.currentTimeMillis()) {
            deliverError(context, item.id);
        } else {
            schedule(context, item.reminderAt, errorIntent(context, item.id));
        }
    }

    static void cancelError(Context context, int id) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pending = errorIntent(context, id);
        manager.cancel(pending);
        pending.cancel();
        Notifications.cancelError(context, id);
    }

    static void rescheduleAll(Context context) {
        scheduleWeekly(context);
        for (BackupItem item : new BackupStore(context).items()) scheduleError(context, item);
    }

    static void deliverError(Context context, int id) {
        BackupStore store = new BackupStore(context);
        BackupItem item = store.item(id);
        if (item == null || item.reminderAt <= 0 || item.reminderDelivered) return;
        if (item.reminderAt > System.currentTimeMillis()) {
            scheduleError(context, item);
            return;
        }
        // Leave a missed reminder pending when permission/channel is disabled, so
        // it can be delivered immediately when the app is reopened and enabled.
        if (Notifications.error(context, item)) store.markReminderDelivered(id, item.reminderAt);
    }

    private static void schedule(Context context, long when, PendingIntent intent) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        try {
            if (exactAllowed(context)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, intent);
            } else {
                // Android 12+ can deny exact alarms; the reminder still fires,
                // but the OS may defer it to save battery.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, intent);
            }
        } catch (SecurityException e) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, intent);
        }
    }

    private static PendingIntent weeklyIntent(Context context) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(WEEKLY);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent errorIntent(Context context, int id) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(ERROR)
                .setData(Uri.parse("backupcontrol://reminder/" + id)).putExtra("item_id", id);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
