package com.zigorminsk.backupcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SystemEventReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        boolean crossedCycle = new BackupStore(context).syncCycle();
        if (crossedCycle) Notifications.weekly(context);
        ReminderScheduler.rescheduleAll(context);
        UpdateJobService.ensureScheduled(context);
        AppEvents.changed(context);
    }
}
