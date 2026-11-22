package by.csl.backupcontrol.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // 1. Immediately schedule the next weekday 09:20 alarm so the chain continues seamlessly
        BackupScheduleManager.scheduleNextAlarm(context)

        // 2. Enqueue the morning backup check worker
        BackupScheduleManager.enqueueImmediateSync(
            context = context,
            isScheduledMorningRun = true,
            forceSixMonthScan = false
        )
    }

    companion object {
        const val ACTION_MORNING_BACKUP_CHECK = "by.csl.backupcontrol.ACTION_MORNING_BACKUP_CHECK"
    }
}
