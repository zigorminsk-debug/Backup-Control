package by.csl.backupcontrol

import by.csl.backupcontrol.domain.analyzer.BackupAlertEvaluator
import by.csl.backupcontrol.domain.analyzer.OrganizationRecordInput
import by.csl.backupcontrol.domain.analyzer.RecentEmailInput
import by.csl.backupcontrol.domain.model.AppSettings
import by.csl.backupcontrol.domain.model.BackupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BackupAlertEvaluatorTest {

    private val minskZone = ZoneId.of("Europe/Minsk")

    @Test
    fun `flags organizations with backup error and organizations silent for several days`() {
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
            settings = AppSettings(missingDaysThreshold = 3),
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
    fun `detects failed earlier job even if another job succeeded later same night`() {
        val now = ZonedDateTime.of(2026, 11, 24, 9, 20, 0, 0, minskZone).toInstant().toEpochMilli()
        val job1FailedTime = now - 8 * 3_600_000L // 01:20
        val job2OkTime = now - 5 * 3_600_000L // 04:20

        val org = OrganizationRecordInput(
            id = 10L,
            orgKey = "backup@multi.by",
            displayName = "ООО МультиДжоб",
            isCustomName = true,
            senderEmail = "backup@multi.by",
            senderPersonal = "",
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
            settings = AppSettings(),
            nowMillis = now,
            zoneId = minskZone
        )

        assertTrue(report.hasIssues)
        assertEquals(1, report.errorOrganizations.size)
        assertEquals("[Failed] SQL 1C Backup", report.errorOrganizations.first().errorSummarySubject)
    }

    @Test
    fun `ignored organizations do not trigger morning banner`() {
        val now = ZonedDateTime.of(2026, 11, 24, 9, 20, 0, 0, minskZone).toInstant().toEpochMilli()
        val org = OrganizationRecordInput(
            id = 20L,
            orgKey = "postmaster@mail.by",
            displayName = "Postmaster",
            isCustomName = false,
            senderEmail = "postmaster@mail.by",
            senderPersonal = "",
            isMonitored = false,
            customMissingDays = null,
            lastEmailAtMillis = now - 10 * 86_400_000L,
            firstSeenAtMillis = now - 20 * 86_400_000L,
            emailCount = 1,
            lastSubject = "Delivery failed",
            lastSnippet = "Error",
            lastBackupStatus = BackupStatus.ERROR,
            acknowledgedAtMillis = 0L
        )

        val (_, report) = BackupAlertEvaluator.evaluate(
            organizations = listOf(org),
            recentEmails = emptyList(),
            settings = AppSettings(),
            nowMillis = now,
            zoneId = minskZone
        )

        assertFalse(report.hasIssues)
    }
}
