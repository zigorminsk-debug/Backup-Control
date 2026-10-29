package com.zigorminsk.backupcontrol;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

final class ReminderScheduler {
    static final String WEEKLY = "com.zigorminsk.backupcontrol.WEEKLY";
    static final String ERROR = "com.zigorminsk.backupcontrol.ERROR";
    /** The user swiped the reminder away: ask how long to postpone. */
    static final String PROMPT = "com.zigorminsk.backupcontrol.SNOOZE_PROMPT";
    /** A postpone option was chosen; the extra carries the minutes. */
    static final String PICK = "com.zigorminsk.backupcontrol.SNOOZE_PICK";
    /** A postponed reminder is due. */
    static final String SNOOZE_DUE = "com.zigorminsk.backupcontrol.SNOOZE_DUE";
    static final String EXTRA_MINUTES = "minutes";

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
        BackupStore store = new BackupStore(context);
        store.syncCycle();
        long snooze = store.snoozeUntil();
        if (snooze > System.currentTimeMillis()) {
            schedule(context, snooze, snoozeIntent(context));
        }
        deliverWeeklyIfDue(context);
        scheduleWeekly(context);
        for (BackupItem item : store.items()) scheduleError(context, item);
    }

    static void deliverWeeklyIfDue(Context context) {
        BackupStore store = new BackupStore(context);
        store.syncCycle();
        long now = System.currentTimeMillis();
        long snooze = store.snoozeUntil();
        if (snooze > now) {
            // Still postponed: keep the alarm and stay quiet.
            schedule(context, snooze, snoozeIntent(context));
            return;
        }
        // A paused reminder that has come due alerts again even though this
        // week's cycle was already marked as delivered.
        boolean postponed = snooze > 0;
        if (postponed) store.clearSnooze();
        String cycle = store.unnotifiedWeeklyCycle();
        if (cycle == null && !postponed) return;
        List<BackupItem> pending = new ArrayList<>();
        for (BackupItem item : store.items()) if (!item.checked) pending.add(item);
        // If permission is disabled, leave the cycle pending and deliver when
        // notifications are enabled. No reminder is needed if all tasks are done.
        if (pending.isEmpty()) {
            if (cycle != null) store.markWeeklyNotified(cycle);
            return;
        }
        if (Notifications.weekly(context, pending)) {
            if (cycle != null) store.markWeeklyNotified(cycle);
            // A notification sound follows the phone's audio route, so the app
            // rings on the built-in speaker itself.
            AlertPlayer.play(context);
        }
    }

    /** Postpones the weekly reminder; the next alert fires after the given delay. */
    static long snooze(Context context, int minutes) {
        BackupStore store = new BackupStore(context);
        long until = System.currentTimeMillis() + minutes * 60_000L;
        store.setSnooze(until);
        schedule(context, until, snoozeIntent(context));
        Notifications.cancelWeekly(context);
        AlertPlayer.stop();
        AppEvents.changed(context);
        return until;
    }

    /** Cancels a postponed reminder, leaving this week without further alerts. */
    static void cancelSnooze(Context context) {
        BackupStore store = new BackupStore(context);
        store.clearSnooze();
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pending = snoozeIntent(context);
        manager.cancel(pending);
        pending.cancel();
        Notifications.cancelWeekly(context);
        AlertPlayer.stop();
        AppEvents.changed(context);
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

    /** Fires when the user swipes the reminder away, or taps "Отложить…". */
    static PendingIntent promptIntent(Context context) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(PROMPT)
                .setData(Uri.parse("backupcontrol://snooze/prompt"));
        return PendingIntent.getBroadcast(context, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Carries the chosen delay; one PendingIntent per option so they stay distinct. */
    static PendingIntent pickIntent(Context context, int minutes) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(PICK)
                .setData(Uri.parse("backupcontrol://snooze/pick/" + minutes))
                .putExtra(EXTRA_MINUTES, minutes);
        return PendingIntent.getBroadcast(context, 100 + minutes, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent snoozeIntent(Context context) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(SNOOZE_DUE)
                .setData(Uri.parse("backupcontrol://snooze/due"));
        return PendingIntent.getBroadcast(context, 3, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
