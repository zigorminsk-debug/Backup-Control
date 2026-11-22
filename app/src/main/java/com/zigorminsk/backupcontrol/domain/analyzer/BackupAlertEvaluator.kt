package com.zigorminsk.backupcontrol.domain.analyzer

import com.zigorminsk.backupcontrol.domain.model.AppSettings
import com.zigorminsk.backupcontrol.domain.model.BackupStatus
import com.zigorminsk.backupcontrol.domain.model.MorningAlertReport
import com.zigorminsk.backupcontrol.domain.model.OrganizationApprovalState
import com.zigorminsk.backupcontrol.domain.model.OrganizationHealth
import com.zigorminsk.backupcontrol.domain.model.OrganizationSummary
import com.zigorminsk.backupcontrol.domain.scheduler.WeekdayScheduleCalculator
import java.time.ZoneId

data class OrganizationRecordInput(
    val id: Long,
    val orgKey: String,
    val displayName: String,
    val isCustomName: Boolean,
    val senderEmail: String,
    val additionalSenderEmails: String = "",
    val senderPersonal: String,
    val notes: String = "",
    val approvalState: OrganizationApprovalState = OrganizationApprovalState.SAVED,
    val isMonitored: Boolean,
    val isNewlyAdded: Boolean = false,
    val customMissingDays: Int?,
    val lastEmailAtMillis: Long,
    val firstSeenAtMillis: Long,
    val emailCount: Int,
    val lastSubject: String,
    val lastSnippet: String,
    val lastBackupStatus: BackupStatus,
    val acknowledgedAtMillis: Long
)

data class RecentEmailInput(
    val organizationId: Long,
    val receivedAtMillis: Long,
    val subject: String,
    val snippet: String,
    val status: BackupStatus,
    val normalizedJobKey: String
)

object BackupAlertEvaluator {

    fun evaluate(
        organizations: List<OrganizationRecordInput>,
        recentEmails: List<RecentEmailInput>,
        settings: AppSettings,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Pair<List<OrganizationSummary>, MorningAlertReport> {
        val windowStartMillis = computeEffectiveErrorWindowStart(
            nowMillis = nowMillis,
            previousSyncMillis = settings.previousSyncAtMillis,
            zoneId = zoneId
        )

        val emailsByOrg = recentEmails.groupBy { it.organizationId }

        val summaries = organizations.map { org ->
            val effectiveThreshold = (org.customMissingDays ?: settings.missingDaysThreshold)
                .coerceAtLeast(1)

            val daysSilent = if (org.lastEmailAtMillis > 0L) {
                WeekdayScheduleCalculator.calculateSilentDays(
                    lastEmailMillis = org.lastEmailAtMillis,
                    nowMillis = nowMillis,
                    skipWeekends = settings.skipWeekendsInSilenceCount,
                    zoneId = zoneId
                )
            } else {
                Int.MAX_VALUE
            }

            // Контролируются ТОЛЬКО сохранённые организации (SAVED) с включённым мониторингом
            val isActivelyControlled = org.approvalState == OrganizationApprovalState.SAVED && org.isMonitored

            val isMissingMail = isActivelyControlled && (
                org.lastEmailAtMillis <= 0L || daysSilent >= effectiveThreshold
                )

            val orgRecentEmails = emailsByOrg[org.id].orEmpty()
                .filter { it.receivedAtMillis >= windowStartMillis && it.receivedAtMillis > org.acknowledgedAtMillis }
                .sortedByDescending { it.receivedAtMillis }

            val latestPerJob = orgRecentEmails
                .groupBy { it.normalizedJobKey.ifBlank { "default" } }
                .mapValues { (_, list) -> list.first() }
                .values

            val unresolvedJobFailure = latestPerJob.firstOrNull { email ->
                isAlertableFailure(email.status, settings.treatWarningAsError)
            }

            val latestOverallUnacknowledgedFailure = org.lastEmailAtMillis > org.acknowledgedAtMillis &&
                isAlertableFailure(org.lastBackupStatus, settings.treatWarningAsError)

            val hasErrorAlert = isActivelyControlled && (
                latestOverallUnacknowledgedFailure || unresolvedJobFailure != null
                )

            val errorSubject = when {
                unresolvedJobFailure != null -> unresolvedJobFailure.subject
                latestOverallUnacknowledgedFailure -> org.lastSubject
                else -> null
            }

            val errorSnippet = when {
                unresolvedJobFailure != null -> unresolvedJobFailure.snippet
                latestOverallUnacknowledgedFailure -> org.lastSnippet
                else -> null
            }

            val health = when {
                org.approvalState == OrganizationApprovalState.EXCLUDED -> OrganizationHealth.EXCLUDED
                org.approvalState == OrganizationApprovalState.PENDING_REVIEW -> OrganizationHealth.PENDING_REVIEW
                !org.isMonitored -> OrganizationHealth.PAUSED
                hasErrorAlert && (org.lastBackupStatus == BackupStatus.ERROR || unresolvedJobFailure?.status == BackupStatus.ERROR) ->
                    OrganizationHealth.ERROR
                hasErrorAlert ->
                    OrganizationHealth.WARNING
                isMissingMail ->
                    OrganizationHealth.MISSING_MAIL
                org.lastBackupStatus == BackupStatus.WARNING && org.lastEmailAtMillis > org.acknowledgedAtMillis ->
                    OrganizationHealth.WARNING
                else ->
                    OrganizationHealth.HEALTHY
            }

            OrganizationSummary(
                id = org.id,
                orgKey = org.orgKey,
                displayName = org.displayName,
                isCustomName = org.isCustomName,
                senderEmail = org.senderEmail,
                additionalSenderEmails = org.additionalSenderEmails,
                senderPersonal = org.senderPersonal,
                notes = org.notes,
                approvalState = org.approvalState,
                isMonitored = org.isMonitored,
                isNewlyAdded = org.isNewlyAdded,
                customMissingDays = org.customMissingDays,
                effectiveMissingDaysThreshold = effectiveThreshold,
                lastEmailAtMillis = org.lastEmailAtMillis,
                firstSeenAtMillis = org.firstSeenAtMillis,
                emailCount = org.emailCount,
                lastSubject = org.lastSubject,
                lastSnippet = org.lastSnippet,
                lastBackupStatus = org.lastBackupStatus,
                hasUnresolvedErrorInWindow = unresolvedJobFailure != null,
                errorSummarySubject = errorSubject,
                errorSummarySnippet = errorSnippet,
                daysSinceLastEmail = if (daysSilent == Int.MAX_VALUE) 999 else daysSilent,
                isMissingMail = isMissingMail,
                hasErrorAlert = hasErrorAlert,
                health = health,
                acknowledgedAtMillis = org.acknowledgedAtMillis
            )
        }.sortedWith(
            compareBy<OrganizationSummary>(
                { healthSortOrder(it.health) },
                { if (it.isNewlyAdded) 0 else 1 },
                { it.displayName.lowercase() }
            )
        )

        val errorOrgs = summaries.filter { it.isActivelyControlled && it.hasErrorAlert }
        val missingOrgs = summaries.filter { it.isActivelyControlled && it.isMissingMail }
        val newlyAddedOrgs = summaries.filter { it.approvalState == OrganizationApprovalState.SAVED && it.isNewlyAdded }

        val report = MorningAlertReport(
            evaluatedAtMillis = nowMillis,
            errorOrganizations = errorOrgs,
            missingMailOrganizations = missingOrgs,
            newlyAddedOrganizations = newlyAddedOrgs
        )

        return summaries to report
    }

    private fun isAlertableFailure(status: BackupStatus, treatWarningAsError: Boolean): Boolean {
        return status == BackupStatus.ERROR || (treatWarningAsError && status == BackupStatus.WARNING)
    }

    private fun computeEffectiveErrorWindowStart(
        nowMillis: Long,
        previousSyncMillis: Long,
        zoneId: ZoneId
    ): Long {
        val dayOfWeek = java.time.Instant.ofEpochMilli(nowMillis).atZone(zoneId).dayOfWeek
        val defaultLookbackHours = if (dayOfWeek == java.time.DayOfWeek.MONDAY) 74L else 26L
        val fallbackWindowStart = nowMillis - defaultLookbackHours * 3_600_000L
        return if (previousSyncMillis > 0L) {
            minOf(previousSyncMillis, fallbackWindowStart)
        } else {
            fallbackWindowStart
        }
    }

    private fun healthSortOrder(health: OrganizationHealth): Int = when (health) {
        OrganizationHealth.ERROR -> 0
        OrganizationHealth.WARNING -> 1
        OrganizationHealth.MISSING_MAIL -> 2
        OrganizationHealth.HEALTHY -> 3
        OrganizationHealth.PENDING_REVIEW -> 4
        OrganizationHealth.PAUSED -> 5
        OrganizationHealth.EXCLUDED -> 6
    }
}
