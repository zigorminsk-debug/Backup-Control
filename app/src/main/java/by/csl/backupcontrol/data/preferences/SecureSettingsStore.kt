@file:Suppress("DEPRECATION")

package by.csl.backupcontrol.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import by.csl.backupcontrol.domain.model.AppSettings
import by.csl.backupcontrol.domain.model.MailSecurityMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.DayOfWeek

class SecureSettingsStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        createEncryptedPrefs() ?: appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _settingsFlow = MutableStateFlow(readFromPrefs())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    val current: AppSettings
        get() = _settingsFlow.value

    private fun createEncryptedPrefs(): SharedPreferences? {
        return runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val updated = transform(_settingsFlow.value)
        writeToPrefs(updated)
        _settingsFlow.value = updated
        return updated
    }

    private fun readFromPrefs(): AppSettings {
        val securityName = prefs.getString(KEY_SECURITY_MODE, MailSecurityMode.SSL_TLS.name)
        val security = runCatching {
            MailSecurityMode.valueOf(securityName ?: MailSecurityMode.SSL_TLS.name)
        }.getOrDefault(MailSecurityMode.SSL_TLS)

        val rawDays = prefs.getString(KEY_SCHEDULE_DAYS, null)
        val scheduledDays = if (rawDays == null) {
            AppSettings.DEFAULT_WEEKDAYS
        } else {
            rawDays.split(",")
                .mapNotNull { token ->
                    runCatching { DayOfWeek.valueOf(token.trim()) }.getOrNull()
                }
                .toSet()
        }

        return AppSettings(
            email = prefs.getString(KEY_EMAIL, AppSettings.DEFAULT_EMAIL) ?: AppSettings.DEFAULT_EMAIL,
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            imapHost = prefs.getString(KEY_IMAP_HOST, AppSettings.DEFAULT_IMAP_HOST) ?: AppSettings.DEFAULT_IMAP_HOST,
            imapPort = prefs.getInt(KEY_IMAP_PORT, security.defaultPort),
            securityMode = security,
            trustSelfSignedCerts = prefs.getBoolean(KEY_TRUST_SELF_SIGNED, true),
            autoDetectImapHost = prefs.getBoolean(KEY_AUTO_DETECT_HOST, true),
            folderName = prefs.getString(KEY_FOLDER_NAME, "INBOX") ?: "INBOX",
            scheduleEnabled = prefs.getBoolean(KEY_SCHEDULE_ENABLED, true),
            scheduleHour = prefs.getInt(KEY_SCHEDULE_HOUR, AppSettings.DEFAULT_SCHEDULE_HOUR),
            scheduleMinute = prefs.getInt(KEY_SCHEDULE_MINUTE, AppSettings.DEFAULT_SCHEDULE_MINUTE),
            scheduledDays = scheduledDays,
            initialLookbackMonths = prefs.getInt(KEY_INITIAL_MONTHS, 6),
            missingDaysThreshold = prefs.getInt(KEY_MISSING_DAYS_THRESHOLD, 3),
            skipWeekendsInSilenceCount = prefs.getBoolean(KEY_SKIP_WEEKENDS, false),
            treatWarningAsError = prefs.getBoolean(KEY_TREAT_WARNING_AS_ERROR, true),
            notifyWhenAllHealthy = prefs.getBoolean(KEY_NOTIFY_HEALTHY, false),
            customErrorKeywords = prefs.getString(KEY_CUSTOM_ERROR_KW, "") ?: "",
            customSuccessKeywords = prefs.getString(KEY_CUSTOM_SUCCESS_KW, "") ?: "",
            initialScanCompleted = prefs.getBoolean(KEY_INITIAL_SCAN_COMPLETED, false),
            lastSyncAtMillis = prefs.getLong(KEY_LAST_SYNC_AT, 0L),
            previousSyncAtMillis = prefs.getLong(KEY_PREV_SYNC_AT, 0L),
            bannerDismissedAtMillis = prefs.getLong(KEY_BANNER_DISMISSED_AT, 0L)
        )
    }

    private fun writeToPrefs(s: AppSettings) {
        prefs.edit()
            .putString(KEY_EMAIL, s.email.trim())
            .putString(KEY_PASSWORD, s.password)
            .putString(KEY_IMAP_HOST, s.imapHost.trim())
            .putInt(KEY_IMAP_PORT, s.imapPort)
            .putString(KEY_SECURITY_MODE, s.securityMode.name)
            .putBoolean(KEY_TRUST_SELF_SIGNED, s.trustSelfSignedCerts)
            .putBoolean(KEY_AUTO_DETECT_HOST, s.autoDetectImapHost)
            .putString(KEY_FOLDER_NAME, s.folderName.trim().ifBlank { "INBOX" })
            .putBoolean(KEY_SCHEDULE_ENABLED, s.scheduleEnabled)
            .putInt(KEY_SCHEDULE_HOUR, s.scheduleHour.coerceIn(0, 23))
            .putInt(KEY_SCHEDULE_MINUTE, s.scheduleMinute.coerceIn(0, 59))
            .putString(KEY_SCHEDULE_DAYS, s.scheduledDays.joinToString(",") { it.name })
            .putInt(KEY_INITIAL_MONTHS, s.initialLookbackMonths.coerceIn(1, 24))
            .putInt(KEY_MISSING_DAYS_THRESHOLD, s.missingDaysThreshold.coerceIn(1, 60))
            .putBoolean(KEY_SKIP_WEEKENDS, s.skipWeekendsInSilenceCount)
            .putBoolean(KEY_TREAT_WARNING_AS_ERROR, s.treatWarningAsError)
            .putBoolean(KEY_NOTIFY_HEALTHY, s.notifyWhenAllHealthy)
            .putString(KEY_CUSTOM_ERROR_KW, s.customErrorKeywords)
            .putString(KEY_CUSTOM_SUCCESS_KW, s.customSuccessKeywords)
            .putBoolean(KEY_INITIAL_SCAN_COMPLETED, s.initialScanCompleted)
            .putLong(KEY_LAST_SYNC_AT, s.lastSyncAtMillis)
            .putLong(KEY_PREV_SYNC_AT, s.previousSyncAtMillis)
            .putLong(KEY_BANNER_DISMISSED_AT, s.bannerDismissedAtMillis)
            .apply()
    }

    companion object {
        private const val ENCRYPTED_PREFS_NAME = "backup_control_secure_prefs"
        private const val FALLBACK_PREFS_NAME = "backup_control_fallback_prefs"

        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD = "password"
        private const val KEY_IMAP_HOST = "imap_host"
        private const val KEY_IMAP_PORT = "imap_port"
        private const val KEY_SECURITY_MODE = "security_mode"
        private const val KEY_TRUST_SELF_SIGNED = "trust_self_signed"
        private const val KEY_AUTO_DETECT_HOST = "auto_detect_host"
        private const val KEY_FOLDER_NAME = "folder_name"
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_SCHEDULE_HOUR = "schedule_hour"
        private const val KEY_SCHEDULE_MINUTE = "schedule_minute"
        private const val KEY_SCHEDULE_DAYS = "schedule_days"
        private const val KEY_INITIAL_MONTHS = "initial_months"
        private const val KEY_MISSING_DAYS_THRESHOLD = "missing_days_threshold"
        private const val KEY_SKIP_WEEKENDS = "skip_weekends"
        private const val KEY_TREAT_WARNING_AS_ERROR = "treat_warning_as_error"
        private const val KEY_NOTIFY_HEALTHY = "notify_healthy"
        private const val KEY_CUSTOM_ERROR_KW = "custom_error_kw"
        private const val KEY_CUSTOM_SUCCESS_KW = "custom_success_kw"
        private const val KEY_INITIAL_SCAN_COMPLETED = "initial_scan_completed"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_PREV_SYNC_AT = "prev_sync_at"
        private const val KEY_BANNER_DISMISSED_AT = "banner_dismissed_at"

        @Volatile
        private var INSTANCE: SecureSettingsStore? = null

        fun getInstance(context: Context): SecureSettingsStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureSettingsStore(context).also { INSTANCE = it }
            }
        }
    }
}
