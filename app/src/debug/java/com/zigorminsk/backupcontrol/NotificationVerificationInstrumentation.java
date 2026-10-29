package com.zigorminsk.backupcontrol;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Debug-only check that a weekly reminder really interrupts the user.
 *
 * A notification that is technically posted but has no banner and no sound is
 * a missed reminder, so this verifies the channel's importance, sound and
 * vibration, that the test notification reaches the shade, and that only the
 * weekly summary alerts while per-task rows stay silent.
 */
public final class NotificationVerificationInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Context context = getTargetContext();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Bundle result = new Bundle();
        BackupStore store = new BackupStore(context);
        Integer addedId = null;
        try {
            require(Notifications.canPost(context, Notifications.CHECKS),
                    "notifications must be allowed for this check");
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = manager.getNotificationChannel(Notifications.CHECKS);
                require(channel != null, "weekly channel is missing");
                require(channel.getImportance() == NotificationManager.IMPORTANCE_HIGH,
                        "weekly channel importance is " + channel.getImportance() + ", expected HIGH");
                require(channel.getSound() != null, "weekly channel has no sound");
                require(channel.shouldVibrate(), "weekly channel does not vibrate");
                require(channel.getLockscreenVisibility() == Notification.VISIBILITY_PUBLIC,
                        "weekly channel hides its content on the lock screen");
                // The pre-1.0.28 silent channel must not survive next to the new
                // one, otherwise the user can keep a muted duplicate.
                require(manager.getNotificationChannel("weekly_checks") == null,
                        "legacy silent channel was not removed");
            }
            require(Notifications.checksCanInterrupt(context), "weekly channel cannot interrupt");

            Notifications.cancelTest(context);
            require(Notifications.test(context), "test notification was not posted");
            require(awaitTag(manager, Notifications.TEST_TAG), "test notification is not in the shade");

            List<BackupItem> pending = new ArrayList<>();
            for (BackupItem item : store.items()) if (!item.checked) pending.add(item);
            if (pending.isEmpty()) {
                store.add("Проверка уведомлений", new ArrayList<>());
                addedId = store.items().get(store.items().size() - 1).id;
                pending.add(store.item(addedId));
            }
            require(Notifications.weekly(context, pending), "weekly reminder was not posted");
            require(awaitTag(manager, "weekly_summary"), "weekly summary is not in the shade");
            require(awaitTag(manager, "weekly_task"), "per-task reminder is not in the shade");
            for (StatusBarNotification notification : manager.getActiveNotifications()) {
                if (!"weekly_task".equals(notification.getTag())) continue;
                require(notification.getNotification().getGroup() != null,
                        "per-task reminder is outside the weekly group");
                if (Build.VERSION.SDK_INT >= 26) {
                    require(notification.getNotification().getGroupAlertBehavior()
                                    == Notification.GROUP_ALERT_SUMMARY,
                            "per-task reminder would alert on its own");
                }
            }
            result.putString("notification_check", "PASS");
            cleanup(context, manager, addedId == null ? 0 : addedId);
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError e) {
            result.putString("notification_check", "FAIL: " + e);
            cleanup(context, manager, addedId == null ? 0 : addedId);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static boolean awaitTag(NotificationManager manager, String tag) {
        for (int attempt = 0; attempt < 25; attempt++) {
            for (StatusBarNotification notification : manager.getActiveNotifications()) {
                if (tag.equals(notification.getTag())) return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                return false;
            }
        }
        return false;
    }

    private static void cleanup(Context context, NotificationManager manager, int addedId) {
        Notifications.cancelTest(context);
        manager.cancel("weekly_summary", Notifications.WEEKLY_ID);
        for (StatusBarNotification notification : manager.getActiveNotifications()) {
            if ("weekly_task".equals(notification.getTag())) {
                manager.cancel("weekly_task", notification.getId());
            }
        }
        if (addedId != 0) new BackupStore(context).remove(addedId);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
