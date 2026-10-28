package com.zigorminsk.backupcontrol;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

final class Notifications {
    static final String CHECKS = "weekly_checks";
    static final String ERRORS = "error_reminders";
    static final String UPDATES = "app_updates";
    static final int WEEKLY_ID = 1;
    static final int UPDATE_ID = 2;

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

    static boolean weekly(Context context) {
        return post(context, CHECKS, WEEKLY_ID, "Пора проверить бэкапы",
                "Откройте чек-лист и отметьте проверенные резервные копии.", 0, false);
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

    private static boolean post(Context context, String channel, int id, String title, String body,
                                int itemId, boolean installUpdate) {
        if (!canPost(context, channel)) return false;
        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("item_id", itemId)
                .putExtra("install_update", installUpdate);
        PendingIntent contentIntent = PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, channel) : new Notification.Builder(context);
        Notification notification = builder.setSmallIcon(R.drawable.ic_notification)
                .setColor(0xff179774)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(channel.equals(ERRORS) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT)
                .build();
        try {
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, notification);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}
