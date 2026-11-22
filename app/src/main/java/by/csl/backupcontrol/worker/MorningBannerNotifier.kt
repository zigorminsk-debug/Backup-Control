package by.csl.backupcontrol.worker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import by.csl.backupcontrol.MainActivity
import by.csl.backupcontrol.R
import by.csl.backupcontrol.domain.model.MorningAlertReport

@SuppressLint("MissingPermission")
object MorningBannerNotifier {

    const val CHANNEL_MORNING_BANNER = "morning_backup_banner_v1"
    const val CHANNEL_SYNC_PROGRESS = "backup_sync_progress_v1"

    const val NOTIFICATION_ID_MORNING_BANNER = 1001
    const val NOTIFICATION_ID_SYNC_PROGRESS = 1002
    const val NOTIFICATION_ID_TEST_BANNER = 1003

    const val EXTRA_FROM_MORNING_BANNER = "extra_from_morning_banner"

    fun ensureChannelsCreated(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val alertChannel = NotificationChannel(
            CHANNEL_MORNING_BANNER,
            "Утренний баннер проблем бэкапов",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Всплывающий баннер в 09:20 (Пн–Пт) об ошибках бэкапов и отсутствии писем от организаций"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 300L, 160L, 300L)
            enableLights(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val syncChannel = NotificationChannel(
            CHANNEL_SYNC_PROGRESS,
            "Фоновая проверка почты",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Отображает процесс чтения почты backup@csl.by"
            setShowBadge(false)
        }

        manager.createNotificationChannel(alertChannel)
        manager.createNotificationChannel(syncChannel)
    }

    fun buildSyncProgressNotification(context: Context, text: String): Notification {
        ensureChannelsCreated(context)
        val openIntent = createOpenAppPendingIntent(context, fromBanner = false)
        return NotificationCompat.Builder(context, CHANNEL_SYNC_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle("Backup Control — чтение почты")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .build()
    }

    fun showMorningProblemBanner(context: Context, report: MorningAlertReport) {
        if (!report.hasIssues || !canPostNotifications(context)) return
        ensureChannelsCreated(context)

        val title = "⚠️ ${report.buildNotificationTitle()}"
        val summary = report.buildCompactNotificationSummary()
        val expandedText = report.buildExpandedNotificationLines().joinToString("\n")

        val openPendingIntent = createOpenAppPendingIntent(context, fromBanner = true)
        val dismissIntent = Intent(context, AlertDismissReceiver::class.java).apply {
            action = AlertDismissReceiver.ACTION_DISMISS_MORNING_BANNER
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            201,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MORNING_BANNER)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setColor(0xFFD32F2F.toInt())
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(expandedText)
                    .setSummaryText("backup@csl.by • Утренний контроль")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Открыть список", openPendingIntent)
            .addAction(0, "Принято", dismissPendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_MORNING_BANNER, notification)
    }

    fun showHealthyBanner(context: Context, monitoredCount: Int) {
        if (!canPostNotifications(context)) return
        ensureChannelsCreated(context)

        val openPendingIntent = createOpenAppPendingIntent(context, fromBanner = false)
        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setColor(0xFF2E7D32.toInt())
            .setContentTitle("✅ Все бэкапы в норме ($monitoredCount орг.)")
            .setContentText("Ошибок резервного копирования и пропусков почты не обнаружено.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_MORNING_BANNER, notification)
    }

    fun showConnectionFailureBanner(context: Context, errorMessage: String) {
        if (!canPostNotifications(context)) return
        ensureChannelsCreated(context)

        val openPendingIntent = createOpenAppPendingIntent(context, fromBanner = true)
        val notification = NotificationCompat.Builder(context, CHANNEL_MORNING_BANNER)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setColor(0xFFE65100.toInt())
            .setContentTitle("⚠️ Не удалось проверить backup@csl.by")
            .setContentText(errorMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(errorMessage))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_MORNING_BANNER, notification)
    }

    fun showTestBanner(context: Context, currentReport: MorningAlertReport?) {
        if (!canPostNotifications(context)) return
        ensureChannelsCreated(context)

        if (currentReport != null && currentReport.hasIssues) {
            showMorningProblemBanner(context, currentReport)
            return
        }

        val openPendingIntent = createOpenAppPendingIntent(context, fromBanner = true)
        val sampleLines = """
            ❌ Ошибка бэкапа (1):
            • ООО «Пример-Клиент» — [Failed] Архивация базы 1С

            📭 Нет почты несколько дней (1):
            • ЗАО «ТестСервис» — 4 дн. без писем (порог 3 дн.)
        """.trimIndent()

        val notification = NotificationCompat.Builder(context, CHANNEL_MORNING_BANNER)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setColor(0xFFD32F2F.toInt())
            .setContentTitle("⚠️ Тест баннера: Проблемы с бэкапами (2 орг.)")
            .setContentText("Ошибки (1): ООО «Пример-Клиент» • Нет писем (1): ЗАО «ТестСервис»")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("⚠️ Тест утреннего баннера (09:20 Пн–Пт)")
                    .bigText(sampleLines)
                    .setSummaryText("Проверка всплывающего уведомления Android")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_TEST_BANNER, notification)
    }

    fun cancelMorningBanner(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_MORNING_BANNER)
    }

    fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    private fun createOpenAppPendingIntent(context: Context, fromBanner: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_FROM_MORNING_BANNER, fromBanner)
        }
        val reqCode = if (fromBanner) 101 else 102
        return PendingIntent.getActivity(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
