package com.zigorminsk.backupcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (ReminderScheduler.WEEKLY.equals(intent.getAction())) {
            new BackupStore(context).syncCycle();
            Notifications.weekly(context);
            ReminderScheduler.scheduleWeekly(context);
            AppEvents.changed(context);
        } else if (ReminderScheduler.ERROR.equals(intent.getAction())) {
            ReminderScheduler.deliverError(context, intent.getIntExtra("item_id", 0));
            AppEvents.changed(context);
        }
    }
}
