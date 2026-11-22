package com.zigorminsk.backupcontrol

import android.app.Application
import com.zigorminsk.backupcontrol.update.AppUpdateManager
import com.zigorminsk.backupcontrol.worker.BackupScheduleManager
import com.zigorminsk.backupcontrol.worker.MorningBannerNotifier

class BackupControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MorningBannerNotifier.ensureChannelsCreated(this)
        BackupScheduleManager.scheduleNextAlarm(this)
        AppUpdateManager.refreshState(this)
        AppUpdateManager.checkAsync(this, force = false)
    }
}
