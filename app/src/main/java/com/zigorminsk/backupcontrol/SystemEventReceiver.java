package com.zigorminsk.backupcontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class SystemEventReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ReminderScheduler.rescheduleAll(context);
        UpdateJobService.ensureScheduled(context);
        AppEvents.changed(context);
    }
}
