package com.zigorminsk.backupcontrol;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.net.Uri;
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
                // The channel is intentionally silent and does not vibrate: the
                // app plays its own chime on the phone speaker and vibrates, so a
                // connected headset can no longer swallow the alert.
                require(channel.getSound() == null, "weekly channel would play a routed system sound");
                // Informational: the platform may normalise lock-screen visibility
                // against the user's own settings, and it does not affect whether
                // the reminder alerts.
                result.putString("lockscreen_visibility",
                        String.valueOf(channel.getLockscreenVisibility()));
                // The pre-1.0.28 silent channel must not survive next to the new
                // one, otherwise the user can keep a muted duplicate.
                require(manager.getNotificationChannel("weekly_checks") == null,
                        "legacy silent channel was not removed");
            }
            require(Notifications.checksCanInterrupt(context), "weekly channel cannot interrupt");

            Notifications.cancelTest(context);
            require(Notifications.test(context), "test notification was not posted");
            require(awaitTag(manager, Notifications.TEST_TAG), "test notification is not in the shade");

            // A reminder only takes over the screen when the app is in the
            // background, which is the situation being checked here.
            MainActivity.foreground = false;
            List<BackupItem> pending = new ArrayList<>();
            for (BackupItem item : store.items()) if (!item.checked) pending.add(item);
            if (pending.isEmpty()) {
                store.add("Проверка уведомлений", new ArrayList<>());
                addedId = store.items().get(store.items().size() - 1).id;
                pending.add(store.item(addedId));
            }
            require(Notifications.weekly(context, pending), "weekly reminder was not posted");
            require(awaitTag(manager, "weekly_summary"), "weekly summary is not in the shade");
            StatusBarNotification summary = byTag(manager, "weekly_summary");
            require(summary != null, "weekly summary disappeared");
            Notification posted = summary.getNotification();
            // It must stay in the shade after a tap, open the app when tapped,
            // and ask how long to postpone when swiped away.
            require((posted.flags & Notification.FLAG_AUTO_CANCEL) == 0,
                    "summary would vanish after being tapped");
            require(posted.contentIntent != null, "summary does not open the app");
            require(posted.deleteIntent != null, "swiping the summary away would be lost");
            require(posted.actions != null && posted.actions.length == 1,
                    "summary has no postpone action");
            // A channel's sound follows the phone's audio route (headphones only
            // with a headset attached), so the channel is silent and the app
            // plays its own chime on the built-in speaker.
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel checks = manager.getNotificationChannel(Notifications.CHECKS);
                require(checks.getSound() == null, "weekly channel has a routed system sound");
            }
            require(AlertPlayer.lastPlayedAt() > 0, "the app did not play its own reminder sound");
            require(AlertPlayer.builtinSpeaker(context) != null,
                    "the phone speaker is not available for the reminder");
            // A locked phone shows no banner, so the reminder also takes over the
            // screen; the same notification becomes a heads-up when in use.
            require(posted.fullScreenIntent != null,
                    "the reminder has no full-screen intent for a locked phone");
            // Swiping the reminder away asks how long to postpone. The gesture
            // itself cannot be performed from here, so fire exactly the intent
            // Android fires on swipe: that is the wiring that matters.
            require(posted.deleteIntent != null, "summary has no swipe intent");
            posted.deleteIntent.send();
            require(awaitTag(manager, Notifications.PROMPT_TAG),
                    "swiping the reminder away produced no postpone prompt");
            StatusBarNotification prompt = byTag(manager, Notifications.PROMPT_TAG);
            require(prompt.getNotification().actions != null
                            && prompt.getNotification().actions.length
                            == Notifications.SNOOZE_MINUTES.length + 1,
                    "snooze prompt does not offer every delay");
            require(Notifications.SNOOZE_MINUTES.length >= 5,
                    "postpone dialog offers too few delays");
            // Choosing an option on that prompt postpones the reminder.
            ReminderScheduler.pickIntent(context, 15).send();
            // A broadcast is delivered asynchronously, so give it a moment.
            long until = 0;
            for (int attempt = 0; attempt < 25 && until <= System.currentTimeMillis(); attempt++) {
                until = store.snoozeUntil();
                if (until <= System.currentTimeMillis()) Thread.sleep(100);
            }
            require(until > System.currentTimeMillis(),
                    "choosing a postpone option did not store the delay");
            Notifications.cancelWeekly(context);
            ReminderScheduler.deliverWeeklyIfDue(context);
            require(awaitAbsent(manager, "weekly_summary"),
                    "a postponed reminder alerted again immediately");
            // When the delay is over the reminder must return.
            store.setSnooze(System.currentTimeMillis() - 1000);
            ReminderScheduler.deliverWeeklyIfDue(context);
            require(awaitTag(manager, "weekly_summary"), "the reminder did not return after postponing");
            require(store.snoozeUntil() == 0, "the finished postpone was not cleared");
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

    private static StatusBarNotification byTag(NotificationManager manager, String tag) {
        for (StatusBarNotification notification : manager.getActiveNotifications()) {
            if (tag.equals(notification.getTag())) return notification;
        }
        return null;
    }

    /** Cancelling a notification is reflected in the shade asynchronously. */
    private static boolean awaitAbsent(NotificationManager manager, String tag) {
        for (int attempt = 0; attempt < 25; attempt++) {
            if (byTag(manager, tag) == null) return true;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                return false;
            }
        }
        return false;
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

    /**
     * Leaves the weekly reminder in the shade on purpose: the UI smoke test
     * swipes it away right after this run to check the postpone dialog.
     */
    private static void cleanup(Context context, NotificationManager manager, int addedId) {
        Notifications.cancelTest(context);
        Notifications.cancelSnoozePrompt(context);
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
