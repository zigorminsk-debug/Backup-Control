package by.csl.backupcontrol.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import by.csl.backupcontrol.data.repository.BackupMonitorRepository

class BackupCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = MorningBannerNotifier.buildSyncProgressNotification(
            applicationContext,
            "Подключение к backup@csl.by и проверка отчётов…"
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                MorningBannerNotifier.NOTIFICATION_ID_SYNC_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(
                MorningBannerNotifier.NOTIFICATION_ID_SYNC_PROGRESS,
                notification
            )
        }
    }

    override suspend fun doWork(): Result {
        val isScheduled = inputData.getBoolean(KEY_IS_SCHEDULED, false)
        val forceSixMonths = inputData.getBoolean(KEY_FORCE_SIX_MONTHS, false)

        // Best-effort foreground promotion (safe on Android 12–15 when started from background alarm)
        runCatching { setForeground(getForegroundInfo()) }

        val repository = BackupMonitorRepository.getInstance(applicationContext)
        val settings = repository.settingsStore.current
        if (!settings.isConfigured) {
            return Result.success()
        }

        val triggerLabel = when {
            forceSixMonths -> "Первичный сбор (${settings.initialLookbackMonths} мес.)"
            isScheduled -> String.format(
                "По расписанию (%02d:%02d)",
                settings.scheduleHour,
                settings.scheduleMinute
            )
            else -> "Ручная проверка"
        }

        val outcome = repository.runSync(
            triggerType = triggerLabel,
            forceSixMonthScan = forceSixMonths
        )

        // Ensure next alarm remains scheduled
        BackupScheduleManager.scheduleNextAlarm(applicationContext)

        if (outcome.success && outcome.report != null) {
            val report = outcome.report
            if (report.hasIssues) {
                // Show high-priority banner on scheduled morning runs (and also after initial 6-month scan)
                if (isScheduled || outcome.wasInitialScan) {
                    MorningBannerNotifier.showMorningProblemBanner(applicationContext, report)
                }
            } else {
                MorningBannerNotifier.cancelMorningBanner(applicationContext)
                if (isScheduled && repository.settingsStore.current.notifyWhenAllHealthy) {
                    MorningBannerNotifier.showHealthyBanner(
                        applicationContext,
                        monitoredCount = repository.syncProgress.value.discoveredOrganizations
                    )
                }
            }
            return Result.success()
        }

        // On transient failure during scheduled morning check, retry automatically up to 2 times
        if (isScheduled && runAttemptCount < 2) {
            return Result.retry()
        }

        if (isScheduled && !outcome.errorMessage.isNullOrBlank()) {
            MorningBannerNotifier.showConnectionFailureBanner(
                applicationContext,
                outcome.errorMessage
            )
        }

        return Result.failure()
    }

    companion object {
        const val KEY_IS_SCHEDULED = "is_scheduled"
        const val KEY_FORCE_SIX_MONTHS = "force_six_months"
    }
}
