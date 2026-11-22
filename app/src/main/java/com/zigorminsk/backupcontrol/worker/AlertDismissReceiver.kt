package com.zigorminsk.backupcontrol.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zigorminsk.backupcontrol.data.repository.BackupMonitorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlertDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DISMISS_MORNING_BANNER) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        MorningBannerNotifier.cancelMorningBanner(appContext)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                BackupMonitorRepository.getInstance(appContext).dismissMorningBanner()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_DISMISS_MORNING_BANNER = "com.zigorminsk.backupcontrol.ACTION_DISMISS_MORNING_BANNER"
    }
}
