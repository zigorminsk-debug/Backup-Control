package by.csl.backupcontrol

import android.app.Application
import by.csl.backupcontrol.worker.BackupScheduleManager
import by.csl.backupcontrol.worker.MorningBannerNotifier

class BackupControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MorningBannerNotifier.ensureChannelsCreated(this)
        BackupScheduleManager.scheduleNextAlarm(this)
    }
}
