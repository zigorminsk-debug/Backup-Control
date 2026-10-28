package com.zigorminsk.backupcontrol;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Notifications {
    static final String CHECKS = "weekly_checks";
    static final String ERRORS = "error_reminders";
    static final String UPDATES = "app_updates";
    static final int WEEKLY_ID = 1;
    static final int UPDATE_ID = 2;
    private static final String WEEKLY_TAG = "weekly_task";
    private static final String SUMMARY_TAG = "weekly_summary";
    private static final String GROUP = "backup_weekly_tasks";

    private Notifications() { }

    static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel checks = new NotificationChannel(CHECKS,
                context.getString(R.string.notification_channel_checks), NotificationManager.IMPORTANCE_DEFAULT);
        checks.setDescription("Напоминание начать еженедельную проверку резервных копий");
        NotificationChannel errors = new NotificationChannel(ERRORS,
                context.getString(R.string.notification_channel_errors), NotificationManager.IMPORTANCE_HIGH);
        errors.setDescription("Напоминания разобраться с ошибками резервного копирования");
        NotificationChannel updates = new NotificationChannel(UPDATES,
                context.getString(R.string.notification_channel_updates), NotificationManager.IMPORTANCE_DEFAULT);
        updates.setDescription("Обновления приложения, готовые к установке");
        manager.createNotificationChannel(checks);
        manager.createNotificationChannel(errors);
        manager.createNotificationChannel(updates);
    }

    static boolean canPost(Context context, String channel) {
        createChannels(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel notificationChannel = manager.getNotificationChannel(channel);
            if (notificationChannel != null && notificationChannel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }

    /** A grouped notification names every task still awaiting this week's check. */
    static boolean weekly(Context context, List<BackupItem> pending) {
        if (pending.isEmpty()) return true;
        if (!canPost(context, CHECKS)) return false;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            manager.cancel(WEEKLY_ID); // Remove the generic notification from older versions.
            // A previous week's summary can still be visible. Replace it with a
            // fresh notification so this week's reminder can alert again.
            manager.cancel(SUMMARY_TAG, WEEKLY_ID);
            for (StatusBarNotification existing : manager.getActiveNotifications()) {
                if (WEEKLY_TAG.equals(existing.getTag())) manager.cancel(WEEKLY_TAG, existing.getId());
            }
            for (BackupItem item : pending) {
                Intent open = new Intent(context, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .setData(Uri.parse("backupcontrol://weekly/" + item.id))
                        .putExtra("item_id", item.id);
                PendingIntent content = PendingIntent.getActivity(context, 0, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification.Builder builder = base(context, CHECKS)
                        .setContentTitle("Пора проверить: " + item.name)
                        .setContentText("Откройте задачу и отметьте выполненную проверку.")
                        .setContentIntent(content).setAutoCancel(true)
                        .setGroup(GROUP).setOnlyAlertOnce(true);
                if (Build.VERSION.SDK_INT >= 26) builder.setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
                else builder.setPriority(Notification.PRIORITY_LOW);
                manager.notify(WEEKLY_TAG, item.id, builder.build());
            }
            postWeeklySummary(context, pending);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    static void cancelWeeklyTask(Context context, int itemId) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(WEEKLY_TAG, itemId);
        Set<Integer> shown = new HashSet<>();
        for (StatusBarNotification notification : manager.getActiveNotifications()) {
            if (WEEKLY_TAG.equals(notification.getTag())) shown.add(notification.getId());
        }
        if (shown.isEmpty()) {
            manager.cancel(SUMMARY_TAG, WEEKLY_ID);
            return;
        }
        List<BackupItem> remaining = new ArrayList<>();
        for (BackupItem item : new BackupStore(context).items()) {
            if (!item.checked && shown.contains(item.id)) remaining.add(item);
        }
        if (remaining.isEmpty()) manager.cancel(SUMMARY_TAG, WEEKLY_ID);
        else if (canPost(context, CHECKS)) {
            try {
                postWeeklySummary(context, remaining);
            } catch (SecurityException ignored) {
                // Permission may have changed between the check and posting.
            }
        }
    }

    private static void postWeeklySummary(Context context, List<BackupItem> pending) {
        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .setData(Uri.parse("backupcontrol://weekly/summary"));
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.InboxStyle lines = new Notification.InboxStyle()
                .setBigContentTitle("Задачи на проверку");
        for (int i = 0; i < Math.min(5, pending.size()); i++) lines.addLine(pending.get(i).name);
        if (pending.size() > 5) lines.setSummaryText("И ещё " + (pending.size() - 5));
        Notification.Builder builder = base(context, CHECKS)
                .setContentTitle("Пора проверить бэкапы")
                .setContentText("Ожидают проверки: " + pending.size())
                .setStyle(lines).setContentIntent(content).setAutoCancel(true)
                .setGroup(GROUP).setGroupSummary(true).setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= 26) builder.setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(SUMMARY_TAG, WEEKLY_ID, builder.build());
    }

    static boolean error(Context context, BackupItem item) {
        String detail = item.reminderNote.isEmpty() ? "Разберитесь с ошибками резервного копирования."
                : item.reminderNote;
        return post(context, ERRORS, errorId(item.id), "Разобраться с ошибкой: " + item.name,
                detail, item.id, false);
    }

    static boolean update(Context context, String version) {
        return post(context, UPDATES, UPDATE_ID, "Обновление готово к установке",
                "Backup Control " + version + " загружен. Нажмите, чтобы установить обновление.", 0, true);
    }

    static void cancelError(Context context, int id) {
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(errorId(id));
    }

    static void cancelUpdate(Context context) {
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(UPDATE_ID);
    }

    private static int errorId(int id) { return 1000 + id; }

    private static Notification.Builder base(Context context, String channel) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, channel) : new Notification.Builder(context);
        return builder.setSmallIcon(R.drawable.ic_notification).setColor(0xff179774)
                .setPriority(channel.equals(ERRORS) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT);
    }

    private static boolean post(Context context, String channel, int id, String title, String body,
                                int itemId, boolean installUpdate) {
        if (!canPost(context, channel)) return false;
        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("item_id", itemId)
                .putExtra("open_error", channel.equals(ERRORS))
                .putExtra("install_update", installUpdate);
        PendingIntent contentIntent = PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = base(context, channel).setContentTitle(title).setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(contentIntent).setAutoCancel(true).build();
        try {
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, notification);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}
