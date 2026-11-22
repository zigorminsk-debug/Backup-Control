package com.zigorminsk.backupcontrol.domain.model

data class OrganizationSummary(
    val id: Long,
    val orgKey: String,
    val displayName: String,
    val isCustomName: Boolean,
    val senderEmail: String,
    val additionalSenderEmails: String,
    val senderPersonal: String,
    val notes: String,
    val approvalState: OrganizationApprovalState,
    val isMonitored: Boolean,
    val isNewlyAdded: Boolean,
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
) {
    val isActivelyControlled: Boolean
        get() = approvalState == OrganizationApprovalState.SAVED && isMonitored

    val allEmailsFormatted: String
        get() {
            val extras = additionalSenderEmails
                .split(",", ";")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(senderEmail, ignoreCase = true) }
            return (listOfNotNull(senderEmail.takeIf { it.isNotBlank() }) + extras).joinToString(", ")
        }
}
