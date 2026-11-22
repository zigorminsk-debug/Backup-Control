package by.csl.backupcontrol.domain.model

import java.time.DayOfWeek

data class AppSettings(
    val email: String = DEFAULT_EMAIL,
    val password: String = "",
    val imapHost: String = DEFAULT_IMAP_HOST,
    val imapPort: Int = 993,
    val securityMode: MailSecurityMode = MailSecurityMode.SSL_TLS,
    val trustSelfSignedCerts: Boolean = true,
    val autoDetectImapHost: Boolean = true,
    val folderName: String = "INBOX",
    val scheduleEnabled: Boolean = true,
    val scheduleHour: Int = DEFAULT_SCHEDULE_HOUR,
    val scheduleMinute: Int = DEFAULT_SCHEDULE_MINUTE,
    val scheduledDays: Set<DayOfWeek> = DEFAULT_WEEKDAYS,
    val initialLookbackMonths: Int = 6,
    val missingDaysThreshold: Int = 3,
    val skipWeekendsInSilenceCount: Boolean = false,
    val treatWarningAsError: Boolean = true,
    val notifyWhenAllHealthy: Boolean = false,
    val customErrorKeywords: String = "",
    val customSuccessKeywords: String = "",
    val initialScanCompleted: Boolean = false,
    val lastSyncAtMillis: Long = 0L,
    val previousSyncAtMillis: Long = 0L,
    val bannerDismissedAtMillis: Long = 0L
) {
    val isConfigured: Boolean
        get() = email.isNotBlank() && password.isNotBlank() && imapHost.isNotBlank()

    companion object {
        const val DEFAULT_EMAIL = "backup@csl.by"
        const val DEFAULT_IMAP_HOST = "mail.csl.by"
        const val DEFAULT_SCHEDULE_HOUR = 9
        const val DEFAULT_SCHEDULE_MINUTE = 20

        val DEFAULT_WEEKDAYS: Set<DayOfWeek> = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY
        )
    }
}
