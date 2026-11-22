package com.zigorminsk.backupcontrol.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.zigorminsk.backupcontrol.data.repository.BackupMonitorRepository

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

        runCatching { setForeground(getForegroundInfo()) }

        val repository = BackupMonitorRepository.getInstance(applicationContext)
        val settings = repository.settingsStore.current
        if (!settings.isConfigured) {
            return Result.success()
        }

        val triggerLabel = when {
            forceSixMonths -> "Сбор за ${settings.initialLookbackMonths} мес."
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

        BackupScheduleManager.scheduleNextAlarm(applicationContext)

        if (outcome.success && outcome.report != null) {
            val latestSettings = repository.settingsStore.current
            if (!latestSettings.baselineSaved) {
                MorningBannerNotifier.cancelMorningBanner(applicationContext)
                if (outcome.wasInitialScan) {
                    MorningBannerNotifier.showInitialScanReviewNotification(
                        applicationContext,
                        pendingCount = repository.syncProgress.value.discoveredOrganizations
                    )
                }
                return Result.success()
            }

            val report = outcome.report
            if (report.hasIssues) {
                if (isScheduled) {
                    MorningBannerNotifier.showMorningProblemBanner(applicationContext, report)
                }
            } else {
                MorningBannerNotifier.cancelMorningBanner(applicationContext)
                if (outcome.newlyAddedCount > 0) {
                    MorningBannerNotifier.showNewOrganizationsAddedNotification(
                        applicationContext,
                        newCount = outcome.newlyAddedCount
                    )
                } else if (isScheduled && latestSettings.notifyWhenAllHealthy) {
                    MorningBannerNotifier.showHealthyBanner(
                        applicationContext,
                        monitoredCount = repository.syncProgress.value.discoveredOrganizations
                    )
                }
            }
            return Result.success()
        }

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
