package com.zigorminsk.backupcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ReminderScheduler.WEEKLY.equals(action)) {
            ReminderScheduler.deliverWeeklyIfDue(context);
            ReminderScheduler.scheduleWeekly(context);
            AppEvents.changed(context);
        } else if (ReminderScheduler.ERROR.equals(action)) {
            ReminderScheduler.deliverError(context, intent.getIntExtra("item_id", 0));
            AppEvents.changed(context);
        } else if (ReminderScheduler.PROMPT.equals(action)) {
            // The reminder was swiped away (or "Отложить…" was tapped): ask how
            // long to postpone. The prompt is posted first so the question is
            // still visible if Android refuses to open the dialog from here.
            Notifications.snoozePrompt(context);
            SnoozeActivity.open(context);
        } else if (ReminderScheduler.PICK.equals(action)) {
            int minutes = intent.getIntExtra(ReminderScheduler.EXTRA_MINUTES, 0);
            // Zero means "do not remind me this week".
            if (minutes > 0) ReminderScheduler.snooze(context, minutes);
            else ReminderScheduler.cancelSnooze(context);
            Notifications.cancelSnoozePrompt(context);
        } else if (ReminderScheduler.SNOOZE_DUE.equals(action)) {
            ReminderScheduler.deliverWeeklyIfDue(context);
            ReminderScheduler.scheduleWeekly(context);
            AppEvents.changed(context);
        }
    }
}
