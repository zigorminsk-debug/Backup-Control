package by.csl.backupcontrol.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootAndTimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        BackupScheduleManager.scheduleNextAlarm(context)
    }
}
