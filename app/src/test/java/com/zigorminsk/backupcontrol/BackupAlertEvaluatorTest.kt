package com.zigorminsk.backupcontrol

import com.zigorminsk.backupcontrol.domain.analyzer.BackupAlertEvaluator
import com.zigorminsk.backupcontrol.domain.analyzer.OrganizationRecordInput
import com.zigorminsk.backupcontrol.domain.analyzer.RecentEmailInput
import com.zigorminsk.backupcontrol.domain.model.AppSettings
import com.zigorminsk.backupcontrol.domain.model.BackupStatus
import com.zigorminsk.backupcontrol.domain.model.OrganizationApprovalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BackupAlertEvaluatorTest {

    private val minskZone = ZoneId.of("Europe/Minsk")

    @Test
    fun `flags saved organizations with backup error and organizations silent for several days`() {
        val now = ZonedDateTime.of(2026, 11, 23, 9, 20, 0, 0, minskZone).toInstant().toEpochMilli()
        val twoHoursAgo = now - 2 * 3_600_000L
        val fiveDaysAgo = now - 5 * 86_400_000L

        val orgs = listOf(
            OrganizationRecordInput(
                id = 1L,
                orgKey = "err@client1.by",
                displayName = "ООО Клиент-Ошибка",
                isCustomName = true,
                senderEmail = "err@client1.by",
                senderPersonal = "",
                approvalState = OrganizationApprovalState.SAVED,
                isMonitored = true,
                customMissingDays = null,
                lastEmailAtMillis = twoHoursAgo,
                firstSeenAtMillis = fiveDaysAgo,
                emailCount = 10,
                lastSubject = "[Failed] Backup 1C",
                lastSnippet = "Disk full",
                lastBackupStatus = BackupStatus.ERROR,
                acknowledgedAtMillis = 0L
            ),
            OrganizationRecordInput(
                id = 2L,
                orgKey = "silent@client2.by",
                displayName = "ЗАО Молчун",
                isCustomName = true,
                senderEmail = "silent@client2.by",
                senderPersonal = "",
                approvalState = OrganizationApprovalState.SAVED,
                isMonitored = true,
                customMissingDays = null,
                lastEmailAtMillis = fiveDaysAgo,
                firstSeenAtMillis = fiveDaysAgo,
                emailCount = 4,
                lastSubject = "[Success] Backup OK",
                lastSnippet = "OK",
                lastBackupStatus = BackupStatus.SUCCESS,
                acknowledgedAtMillis = 0L
            ),
            OrganizationRecordInput(
                id = 3L,
                orgKey = "ok@client3.by",
                displayName = "ЧТУП Норма",
                isCustomName = true,
                senderEmail = "ok@client3.by",
                senderPersonal = "",
                approvalState = OrganizationApprovalState.SAVED,
                isMonitored = true,
                customMissingDays = null,
                lastEmailAtMillis = twoHoursAgo,
                firstSeenAtMillis = fiveDaysAgo,
                emailCount = 20,
                lastSubject = "[Success] Backup OK",
                lastSnippet = "OK",
                lastBackupStatus = BackupStatus.SUCCESS,
                acknowledgedAtMillis = 0L
            )
        )

        val (_, report) = BackupAlertEvaluator.evaluate(
            organizations = orgs,
            recentEmails = emptyList(),
            settings = AppSettings(missingDaysThreshold = 3, baselineSaved = true),
            nowMillis = now,
            zoneId = minskZone
        )

        assertTrue(report.hasIssues)
        assertEquals(1, report.errorOrganizations.size)
        assertEquals("ООО Клиент-Ошибка", report.errorOrganizations.first().displayName)
        assertEquals(1, report.missingMailOrganizations.size)
        assertEquals("ЗАО Молчун", report.missingMailOrganizations.first().displayName)
        assertEquals(5, report.missingMailOrganizations.first().daysSinceLastEmail)
    }

    @Test
    fun `monitors only saved organizations and ignores pending review or excluded organizations`() {
        val now = ZonedDateTime.of(2026, 11, 24, 9, 20, 0, 0, minskZone).toInstant().toEpochMilli()
        val tenDaysAgo = now - 10 * 86_400_000L

        val orgs = listOf(
            OrganizationRecordInput(
                id = 101L,
                orgKey = "pending@old.by",
                displayName = "Несохранённая после сбора",
                isCustomName = false,
                senderEmail = "pending@old.by",
                senderPersonal = "",
                approvalState = OrganizationApprovalState.PENDING_REVIEW,
                isMonitored = true,
                customMissingDays = null,
                lastEmailAtMillis = tenDaysAgo,
                firstSeenAtMillis = tenDaysAgo,
                emailCount = 3,
                lastSubject = "[Failed] Old job",
                lastSnippet = "Error",
                lastBackupStatus = BackupStatus.ERROR,
                acknowledgedAtMillis = 0L
            ),
            OrganizationRecordInput(
                id = 102L,
                orgKey = "excluded@old.by",
                displayName = "Удалённый при редактировании клиент",
                isCustomName = true,
                senderEmail = "excluded@old.by",
                senderPersonal = "",
                approvalState = OrganizationApprovalState.EXCLUDED,
                isMonitored = false,
                customMissingDays = null,
                lastEmailAtMillis = tenDaysAgo,
                firstSeenAtMillis = tenDaysAgo,
                emailCount = 5,
                lastSubject = "[Failed] Closed client",
                lastSnippet = "Error",
                lastBackupStatus = BackupStatus.ERROR,
                acknowledgedAtMillis = 0L
            ),
            OrganizationRecordInput(
                id = 103L,
                orgKey = "new@client.by",
                displayName = "ООО Новый Клиент",
                isCustomName = false,
                senderEmail = "new@client.by",
                senderPersonal = "ООО Новый Клиент",
                approvalState = OrganizationApprovalState.SAVED,
                isMonitored = true,
                isNewlyAdded = true,
                customMissingDays = null,
                lastEmailAtMillis = now - 3_600_000L,
                firstSeenAtMillis = now - 3_600_000L,
                emailCount = 1,
                lastSubject = "[Success] First backup",
                lastSnippet = "OK",
                lastBackupStatus = BackupStatus.SUCCESS,
                acknowledgedAtMillis = 0L
            )
        )

        val (_, report) = BackupAlertEvaluator.evaluate(
            organizations = orgs,
            recentEmails = emptyList(),
            settings = AppSettings(missingDaysThreshold = 3, baselineSaved = true),
            nowMillis = now,
            zoneId = minskZone
        )

        assertFalse(report.hasIssues)
        assertEquals(0, report.errorOrganizations.size)
        assertEquals(0, report.missingMailOrganizations.size)
        assertEquals(1, report.newlyAddedOrganizations.size)
        assertEquals("ООО Новый Клиент", report.newlyAddedOrganizations.first().displayName)
    }

    @Test
    fun `detects failed earlier job even if another job succeeded later same night`() {
        val now = ZonedDateTime.of(2026, 11, 24, 9, 20, 0, 0, minskZone).toInstant().toEpochMilli()
        val job1FailedTime = now - 8 * 3_600_000L
        val job2OkTime = now - 5 * 3_600_000L

        val org = OrganizationRecordInput(
            id = 10L,
            orgKey = "backup@multi.by",
            displayName = "ООО МультиДжоб",
            isCustomName = true,
            senderEmail = "backup@multi.by",
            senderPersonal = "",
            approvalState = OrganizationApprovalState.SAVED,
            isMonitored = true,
            customMissingDays = null,
            lastEmailAtMillis = job2OkTime,
            firstSeenAtMillis = job1FailedTime,
            emailCount = 2,
            lastSubject = "[Success] FileServer Backup",
            lastSnippet = "OK",
            lastBackupStatus = BackupStatus.SUCCESS,
            acknowledgedAtMillis = 0L
        )

        val recent = listOf(
            RecentEmailInput(
                organizationId = 10L,
                receivedAtMillis = job1FailedTime,
                subject = "[Failed] SQL 1C Backup",
                snippet = "Cannot connect to SQL",
                status = BackupStatus.ERROR,
                normalizedJobKey = "sql 1c backup"
            ),
            RecentEmailInput(
                organizationId = 10L,
                receivedAtMillis = job2OkTime,
                subject = "[Success] FileServer Backup",
                snippet = "OK",
                status = BackupStatus.SUCCESS,
                normalizedJobKey = "fileserver backup"
            )
        )

        val (_, report) = BackupAlertEvaluator.evaluate(
            organizations = listOf(org),
            recentEmails = recent,
            settings = AppSettings(baselineSaved = true),
            nowMillis = now,
            zoneId = minskZone
        )

        assertTrue(report.hasIssues)
        assertEquals(1, report.errorOrganizations.size)
        assertEquals("[Failed] SQL 1C Backup", report.errorOrganizations.first().errorSummarySubject)
    }
}
