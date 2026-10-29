package com.zigorminsk.backupcontrol;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Notifications {
    /**
     * A reminder is useless if it cannot be heard, so the weekly channel uses
     * the highest importance, the alarm audio stream and the app's own chime.
     * Android freezes a channel's settings once it exists, hence the version in
     * the id: installs that only knew the old quiet channel get the new one.
     */
    static final String CHECKS = "weekly_checks_v3";
    static final String ERRORS = "error_reminders";
    static final String UPDATES = "app_updates";
    /** Silent by design: it only asks how long to postpone. */
    static final String PROMPT = "snooze_prompt";
    static final String TEST_TAG = "notification_test";
    static final String PROMPT_TAG = "snooze_prompt";
    static final int WEEKLY_ID = 1;
    static final int UPDATE_ID = 2;
    static final int TEST_ID = 3;
    static final int PROMPT_ID = 4;
    /** Offered in the postpone dialog and as actions on the prompt. */
    static final int[] SNOOZE_MINUTES = {15, 30, 60, 120, 180};
    private static final String[] LEGACY_CHECKS = {"weekly_checks", "weekly_checks_v2"};
    private static final String WEEKLY_TAG = "weekly_task";
    private static final String SUMMARY_TAG = "weekly_summary";
    private static final String GROUP = "backup_weekly_tasks";

    private Notifications() { }

    static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel checks = new NotificationChannel(CHECKS,
                context.getString(R.string.notification_channel_checks), NotificationManager.IMPORTANCE_HIGH);
        checks.setDescription("Напоминание начать еженедельную проверку резервных копий");
        // The alarm stream is used on purpose: the phone itself has to ring, and
        // this stream is not muted by a low notification volume.
        checks.setSound(chime(context), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        checks.enableVibration(true);
        checks.setVibrationPattern(new long[]{0, 400, 220, 400});
        checks.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        checks.setShowBadge(true);
        NotificationChannel errors = new NotificationChannel(ERRORS,
                context.getString(R.string.notification_channel_errors), NotificationManager.IMPORTANCE_HIGH);
        errors.setDescription("Напоминания разобраться с ошибками резервного копирования");
        errors.setSound(chime(context), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        errors.enableVibration(true);
        errors.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationChannel updates = new NotificationChannel(UPDATES,
                context.getString(R.string.notification_channel_updates), NotificationManager.IMPORTANCE_DEFAULT);
        updates.setDescription("Обновления приложения, готовые к установке");
        NotificationChannel prompt = new NotificationChannel(PROMPT,
                context.getString(R.string.notification_channel_prompt), NotificationManager.IMPORTANCE_LOW);
        prompt.setDescription("Вопрос, на сколько отложить напоминание");
        prompt.setSound(null, null);
        prompt.enableVibration(false);
        manager.createNotificationChannel(checks);
        manager.createNotificationChannel(errors);
        manager.createNotificationChannel(updates);
        manager.createNotificationChannel(prompt);
        // The earlier channels were created as IMPORTANCE_DEFAULT, which never
        // shows a banner; leaving them would keep a muted duplicate around.
        for (String legacy : LEGACY_CHECKS) {
            if (manager.getNotificationChannel(legacy) != null) manager.deleteNotificationChannel(legacy);
        }
    }

    private static Uri chime(Context context) {
        return Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.notification_chime);
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

    /** True when the weekly channel is allowed to interrupt the user. */
    static boolean checksCanInterrupt(Context context) {
        if (!canPost(context, CHECKS)) return false;
        if (Build.VERSION.SDK_INT < 26) return true;
        NotificationChannel channel = ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .getNotificationChannel(CHECKS);
        return channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH;
    }

    /**
     * Immediate, identical check of the real reminder channel, so the user can
     * confirm banner and sound without waiting for the scheduled day.
     */
    static boolean test(Context context) {
        if (!canPost(context, CHECKS)) return false;
        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .setData(Uri.parse("backupcontrol://test/notification"));
        PendingIntent content = PendingIntent.getActivity(context, TEST_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String body = "Если вы видите баннер и слышите звук, напоминание придёт в назначенный день.";
        Notification.Builder builder = base(context, CHECKS)
                .setContentTitle("Проверка уведомлений")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(content).setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER);
        try {
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                    .notify(TEST_TAG, TEST_ID, builder.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
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
                PendingIntent content = PendingIntent.getActivity(context, item.id, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification.Builder builder = base(context, CHECKS)
                        .setContentTitle("Пора проверить: " + item.name)
                        .setContentText("Откройте задачу и отметьте выполненную проверку.")
                        .setContentIntent(content).setAutoCancel(false)
                        .setGroup(GROUP).setOnlyAlertOnce(true)
                        .setCategory(Notification.CATEGORY_REMINDER);
                if (Build.VERSION.SDK_INT >= 26) {
                    // Only the summary interrupts: ten tasks must not mean ten
                    // sounds, but the reminder itself must still be unmissable.
                    builder.setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
                } else {
                    builder.setPriority(Notification.PRIORITY_LOW);
                }
                manager.notify(WEEKLY_TAG, item.id, builder.build());
            }
            postWeeklySummary(context, pending);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * The visible reminder: stays in the shade until handled, opens the app when
     * tapped, and asks how long to postpone when swiped away.
     */
    private static void postWeeklySummary(Context context, List<BackupItem> pending) {
        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .setData(Uri.parse("backupcontrol://weekly/summary"))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.InboxStyle lines = new Notification.InboxStyle()
                .setBigContentTitle("Задачи на проверку");
        for (int i = 0; i < Math.min(5, pending.size()); i++) lines.addLine(pending.get(i).name);
        if (pending.size() > 5) lines.setSummaryText("И ещё " + (pending.size() - 5));
        Notification.Builder builder = base(context, CHECKS)
                .setContentTitle("Пора проверить бэкапы")
                .setContentText("Ожидают проверки: " + pending.size())
                .setStyle(lines).setContentIntent(content)
                // Swiping away must not lose the reminder: it becomes the
                // "постопнить на сколько?" question.
                .setDeleteIntent(ReminderScheduler.promptIntent(context))
                .setAutoCancel(false)
                .setGroup(GROUP).setGroupSummary(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .addAction(new Notification.Action.Builder((Icon) null, "Отложить…",
                        ReminderScheduler.promptIntent(context)).build());
        if (Build.VERSION.SDK_INT >= 26) builder.setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(SUMMARY_TAG, WEEKLY_ID, builder.build());
    }

    /** Asks how long to postpone; each button schedules the next alert. */
    static boolean snoozePrompt(Context context) {
        if (!canPost(context, PROMPT)) return false;
        Notification.Builder builder = base(context, PROMPT)
                .setContentTitle("Отложить напоминание?")
                .setContentText("Выберите, когда напомнить снова")
                .setPriority(Notification.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(ReminderScheduler.promptIntent(context))
                .setCategory(Notification.CATEGORY_REMINDER);
        // The same choices as the dialog: Android may refuse to open a window
        // from a background receiver, and then this notification is the dialog.
        for (int minutes : SNOOZE_MINUTES) {
            builder.addAction(new Notification.Action.Builder((Icon) null, label(minutes),
                    ReminderScheduler.pickIntent(context, minutes)).build());
        }
        builder.addAction(new Notification.Action.Builder((Icon) null, "Не напоминать на этой неделе",
                ReminderScheduler.pickIntent(context, 0)).build());
        try {
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                    .notify(PROMPT_TAG, PROMPT_ID, builder.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** "15 мин", "30 мин", "1 ч", "2 ч", "3 ч". */
    static String label(int minutes) {
        if (minutes < 60) return minutes + " мин";
        int hours = minutes / 60;
        int rest = minutes % 60;
        return rest == 0 ? hours + " ч" : hours + " ч " + rest + " мин";
    }

    static void cancelSnoozePrompt(Context context) {
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .cancel(PROMPT_TAG, PROMPT_ID);
    }

    /** Removes every weekly reminder, for example after it was postponed. */
    static void cancelWeekly(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(SUMMARY_TAG, WEEKLY_ID);
        manager.cancel(WEEKLY_ID);
        for (StatusBarNotification notification : manager.getActiveNotifications()) {
            if (WEEKLY_TAG.equals(notification.getTag())) {
                manager.cancel(WEEKLY_TAG, notification.getId());
            }
        }
        cancelSnoozePrompt(context);
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

    static void cancelTest(Context context) {
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(TEST_TAG, TEST_ID);
    }

    private static int errorId(int id) { return 1000 + id; }

    private static Notification.Builder base(Context context, String channel) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, channel) : new Notification.Builder(context);
        int priority = channel.equals(UPDATES) || channel.equals(PROMPT)
                ? Notification.PRIORITY_DEFAULT : Notification.PRIORITY_HIGH;
        builder.setSmallIcon(R.drawable.ic_notification).setColor(0xff179774)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(priority);
        if (Build.VERSION.SDK_INT < 26) {
            // Pre-channel Android has no silent prompt channel; supply the sound
            // explicitly so the reminder still rings on the phone itself.
            builder.setSound(channel.equals(PROMPT) ? null : chime(context));
        }
        return builder;
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
