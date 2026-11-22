package by.csl.backupcontrol.domain.model

data class OrganizationSummary(
    val id: Long,
    val orgKey: String,
    val displayName: String,
    val isCustomName: Boolean,
    val senderEmail: String,
    val senderPersonal: String,
    val isMonitored: Boolean,
    val customMissingDays: Int?,
    val effectiveMissingDaysThreshold: Int,
    val lastEmailAtMillis: Long,
    val firstSeenAtMillis: Long,
    val emailCount: Int,
    val lastSubject: String,
    val lastSnippet: String,
    val lastBackupStatus: BackupStatus,
    val hasUnresolvedErrorInWindow: Boolean,
    val errorSummarySubject: String?,
    val errorSummarySnippet: String?,
    val daysSinceLastEmail: Int,
    val isMissingMail: Boolean,
    val hasErrorAlert: Boolean,
    val health: OrganizationHealth,
    val acknowledgedAtMillis: Long
)
