package com.zigorminsk.backupcontrol.worker

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.zigorminsk.backupcontrol.data.preferences.SecureSettingsStore
import com.zigorminsk.backupcontrol.domain.scheduler.WeekdayScheduleCalculator
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object BackupScheduleManager {

    private const val ALARM_REQUEST_CODE = 920
    const val UNIQUE_MANUAL_OR_SCHEDULED_WORK = "backup_control_sync_work"

    @SuppressLint("ScheduleExactAlarm", "MissingPermission")
    fun scheduleNextAlarm(context: Context): ZonedDateTime? {
        val appContext = context.applicationContext
        val settings = SecureSettingsStore.getInstance(appContext).current
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildAlarmPendingIntent(appContext)

        if (!settings.scheduleEnabled || settings.scheduledDays.isEmpty()) {
            alarmManager.cancel(pendingIntent)
            return null
        }

        val nextRun = WeekdayScheduleCalculator.nextScheduledRun(
            now = ZonedDateTime.now(),
            hour = settings.scheduleHour,
            minute = settings.scheduleMinute,
            allowedDays = settings.scheduledDays
        ) ?: run {
            alarmManager.cancel(pendingIntent)
            return null
        }

        val triggerAtMillis = nextRun.toInstant().toEpochMilli()

        val canUseExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (canUseExact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }

        return nextRun
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun enqueueImmediateSync(
        context: Context,
        isScheduledMorningRun: Boolean,
        forceSixMonthScan: Boolean = false
    ) {
        val inputData = Data.Builder()
            .putBoolean(BackupCheckWorker.KEY_IS_SCHEDULED, isScheduledMorningRun)
            .putBoolean(BackupCheckWorker.KEY_FORCE_SIX_MONTHS, forceSixMonthScan)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<BackupCheckWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_MANUAL_OR_SCHEDULED_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun buildAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_MORNING_BACKUP_CHECK
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
