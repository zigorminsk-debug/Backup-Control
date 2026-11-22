package by.csl.backupcontrol.data.repository

import android.content.Context
import by.csl.backupcontrol.data.local.BackupDatabase
import by.csl.backupcontrol.data.local.entity.BackupEmailEntity
import by.csl.backupcontrol.data.local.entity.OrganizationEntity
import by.csl.backupcontrol.data.local.entity.SyncLogEntity
import by.csl.backupcontrol.data.mail.ConnectionTestResult
import by.csl.backupcontrol.data.mail.FetchedMailItem
import by.csl.backupcontrol.data.mail.ImapMailClient
import by.csl.backupcontrol.data.preferences.SecureSettingsStore
import by.csl.backupcontrol.domain.analyzer.BackupAlertEvaluator
import by.csl.backupcontrol.domain.analyzer.OrganizationRecordInput
import by.csl.backupcontrol.domain.analyzer.RecentEmailInput
import by.csl.backupcontrol.domain.model.AppSettings
import by.csl.backupcontrol.domain.model.BackupStatus
import by.csl.backupcontrol.domain.model.MorningAlertReport
import by.csl.backupcontrol.domain.model.OrganizationSummary
import by.csl.backupcontrol.domain.model.SyncProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime

data class SyncOutcome(
    val success: Boolean,
    val wasInitialScan: Boolean,
    val scannedMessages: Int,
    val report: MorningAlertReport?,
    val errorMessage: String? = null
)

class BackupMonitorRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = BackupDatabase.getInstance(appContext)
    private val orgDao = db.organizationDao()
    private val emailDao = db.backupEmailDao()
    private val syncLogDao = db.syncLogDao()
    val settingsStore = SecureSettingsStore.getInstance(appContext)
    private val imapClient = ImapMailClient()

    private val syncMutex = Mutex()

    private val _syncProgress = MutableStateFlow(SyncProgress())
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    fun observeOrganizationsAndReport(
        tickerFlow: Flow<Long>
    ): Flow<Pair<List<OrganizationSummary>, MorningAlertReport>> {
        val recentCutoff = System.currentTimeMillis() - 14L * 86_400_000L
        return combine(
            orgDao.observeAll(),
            emailDao.observeRecentSince(recentCutoff),
            settingsStore.settingsFlow,
            tickerFlow
        ) { orgEntities, recentEmails, settings, nowMillis ->
            val orgInputs = orgEntities.map { it.toRecordInput() }
            val emailInputs = recentEmails.map { it.toRecentInput() }
            BackupAlertEvaluator.evaluate(
                organizations = orgInputs,
                recentEmails = emailInputs,
                settings = settings,
                nowMillis = nowMillis
            )
        }
    }

    fun observeEmailsForOrganization(orgId: Long): Flow<List<BackupEmailEntity>> =
        emailDao.observeForOrganization(orgId, limit = 50)

    fun observeSyncLogs(): Flow<List<SyncLogEntity>> =
        syncLogDao.observeRecent(limit = 50)

    suspend fun testConnection(candidateSettings: AppSettings): ConnectionTestResult {
        return imapClient.testConnection(candidateSettings)
    }

    suspend fun runSync(
        triggerType: String,
        forceSixMonthScan: Boolean = false
    ): SyncOutcome = syncMutex.withLock {
        val startedAt = System.currentTimeMillis()
        val settings = settingsStore.current

        if (!settings.isConfigured) {
            val msg = "Не задан пароль или адрес почты (${settings.email}). Откройте настройки подключения."
            _syncProgress.value = SyncProgress(
                isRunning = false,
                lastError = msg
            )
            return SyncOutcome(
                success = false,
                wasInitialScan = false,
                scannedMessages = 0,
                report = null,
                errorMessage = msg
            )
        }

        val isInitialScan = forceSixMonthScan || !settings.initialScanCompleted
        val sinceMillis = computeSyncSinceMillis(settings, isInitialScan, startedAt)

        _syncProgress.value = SyncProgress(
            isRunning = true,
            isInitialSixMonthScan = isInitialScan,
            stageText = if (isInitialScan) {
                "Запуск первичного мониторинга за ${settings.initialLookbackMonths} мес.…"
            } else {
                "Проверка новых отчётов о бэкапах…"
            }
        )

        return try {
            val (resolvedEndpoint, fetchedItems) = imapClient.fetchMessagesSince(
                settings = settings,
                sinceEpochMillis = sinceMillis,
                isInitialSixMonthScan = isInitialScan,
                knownUidKeysProvider = { keys -> emailDao.findExistingUidKeys(keys).toSet() },
                onProgress = { processed, total, orgs, stage ->
                    _syncProgress.value = SyncProgress(
                        isRunning = true,
                        isInitialSixMonthScan = isInitialScan,
                        stageText = stage,
                        processedMessages = processed,
                        totalMessages = total,
                        discoveredOrganizations = orgs,
                        lastError = null
                    )
                }
            )

            // Persist auto-discovered IMAP endpoint if it differed from configured host/port
            if (resolvedEndpoint.host != settings.imapHost ||
                resolvedEndpoint.port != settings.imapPort ||
                resolvedEndpoint.securityMode != settings.securityMode
            ) {
                settingsStore.update {
                    it.copy(
                        imapHost = resolvedEndpoint.host,
                        imapPort = resolvedEndpoint.port,
                        securityMode = resolvedEndpoint.securityMode
                    )
                }
            }

            _syncProgress.value = _syncProgress.value.copy(
                stageText = "Сохранение данных по организациям…"
            )

            persistFetchedItems(fetchedItems, isInitialScan)

            val finishedAt = System.currentTimeMillis()
            val updatedSettings = settingsStore.update { current ->
                current.copy(
                    initialScanCompleted = true,
                    previousSyncAtMillis = if (current.lastSyncAtMillis > 0L) current.lastSyncAtMillis else sinceMillis,
                    lastSyncAtMillis = finishedAt,
                    bannerDismissedAtMillis = 0L
                )
            }

            val report = evaluateCurrentReport(updatedSettings, finishedAt)
            val allOrgsCount = orgDao.getAll().count { it.isMonitored }

            syncLogDao.insert(
                SyncLogEntity(
                    startedAtMillis = startedAt,
                    finishedAtMillis = finishedAt,
                    triggerType = if (isInitialScan) {
                        "Первичный сбор (${updatedSettings.initialLookbackMonths} мес.)"
                    } else {
                        triggerType
                    },
                    success = true,
                    scannedMessages = fetchedItems.size,
                    organizationsCount = allOrgsCount,
                    errorOrgsCount = report.errorOrganizations.size,
                    missingOrgsCount = report.missingMailOrganizations.size,
                    bannerShown = report.hasIssues,
                    message = buildSuccessLogMessage(isInitialScan, fetchedItems.size, allOrgsCount, report)
                )
            )
            syncLogDao.trimOld(100)

            _syncProgress.value = SyncProgress(
                isRunning = false,
                isInitialSixMonthScan = false,
                stageText = "Синхронизация завершена",
                processedMessages = fetchedItems.size,
                totalMessages = fetchedItems.size,
                discoveredOrganizations = allOrgsCount,
                lastError = null
            )

            SyncOutcome(
                success = true,
                wasInitialScan = isInitialScan,
                scannedMessages = fetchedItems.size,
                report = report
            )
        } catch (e: Exception) {
            val finishedAt = System.currentTimeMillis()
            val errMsg = e.localizedMessage ?: "Ошибка подключения к почте"

            syncLogDao.insert(
                SyncLogEntity(
                    startedAtMillis = startedAt,
                    finishedAtMillis = finishedAt,
                    triggerType = triggerType,
                    success = false,
                    scannedMessages = 0,
                    organizationsCount = orgDao.getAll().count { it.isMonitored },
                    errorOrgsCount = 0,
                    missingOrgsCount = 0,
                    bannerShown = false,
                    message = errMsg
                )
            )
            syncLogDao.trimOld(100)

            _syncProgress.value = SyncProgress(
                isRunning = false,
                isInitialSixMonthScan = isInitialScan,
                stageText = "",
                lastError = errMsg
            )

            SyncOutcome(
                success = false,
                wasInitialScan = isInitialScan,
                scannedMessages = 0,
                report = null,
                errorMessage = errMsg
            )
        }
    }

    suspend fun evaluateCurrentReport(
        settings: AppSettings = settingsStore.current,
        nowMillis: Long = System.currentTimeMillis()
    ): MorningAlertReport {
        val orgInputs = orgDao.getAll().map { it.toRecordInput() }
        val recentCutoff = nowMillis - 14L * 86_400_000L
        val emailInputs = emailDao.getRecentSince(recentCutoff).map { it.toRecentInput() }
        val (_, report) = BackupAlertEvaluator.evaluate(
            organizations = orgInputs,
            recentEmails = emailInputs,
            settings = settings,
            nowMillis = nowMillis
        )
        return report
    }

    private suspend fun persistFetchedItems(
        items: List<FetchedMailItem>,
        isInitialScan: Boolean
    ) {
        if (items.isEmpty()) return

        val grouped = items.groupBy { it.orgKey }
        for ((orgKey, orgItems) in grouped) {
            val sortedAsc = orgItems.sortedBy { it.receivedAtMillis }
            val latestItem = sortedAsc.last()
            val earliestItem = sortedAsc.first()

            val nonBlankPersonal = sortedAsc.lastOrNull { it.senderPersonal.isNotBlank() }?.senderPersonal
                ?: latestItem.senderPersonal
            val bestSuggestedName = sortedAsc.lastOrNull { it.senderPersonal.isNotBlank() }?.suggestedOrgName
                ?: latestItem.suggestedOrgName

            var existing = orgDao.getByKey(orgKey)
            val orgId: Long
            if (existing == null) {
                val newEntity = OrganizationEntity(
                    orgKey = orgKey,
                    displayName = bestSuggestedName,
                    isCustomName = false,
                    senderEmail = latestItem.senderEmail,
                    senderPersonal = nonBlankPersonal,
                    isMonitored = !latestItem.isLikelySystemBounce,
                    customMissingDays = null,
                    firstSeenAtMillis = earliestItem.receivedAtMillis,
                    lastEmailAtMillis = latestItem.receivedAtMillis,
                    emailCount = sortedAsc.size,
                    lastSubject = latestItem.subject,
                    lastSnippet = latestItem.snippet,
                    lastBackupStatus = latestItem.status,
                    acknowledgedAtMillis = 0L
                )
                val insertedId = orgDao.insertIgnore(newEntity)
                existing = if (insertedId > 0L) {
                    newEntity.copy(id = insertedId)
                } else {
                    orgDao.getByKey(orgKey)
                }
                orgId = existing?.id ?: continue
            } else {
                orgId = existing.id
                val isNewer = latestItem.receivedAtMillis >= existing.lastEmailAtMillis
                val updatedDisplayName = if (existing.isCustomName) {
                    existing.displayName
                } else if (nonBlankPersonal.isNotBlank()) {
                    bestSuggestedName
                } else {
                    existing.displayName
                }

                val updatedCount = if (isInitialScan) {
                    maxOf(existing.emailCount, sortedAsc.size)
                } else {
                    existing.emailCount + sortedAsc.size
                }

                val updatedEntity = existing.copy(
                    displayName = updatedDisplayName,
                    senderPersonal = nonBlankPersonal.ifBlank { existing.senderPersonal },
                    firstSeenAtMillis = minOf(existing.firstSeenAtMillis, earliestItem.receivedAtMillis),
                    lastEmailAtMillis = maxOf(existing.lastEmailAtMillis, latestItem.receivedAtMillis),
                    emailCount = updatedCount,
                    lastSubject = if (isNewer) latestItem.subject else existing.lastSubject,
                    lastSnippet = if (isNewer) latestItem.snippet else existing.lastSnippet,
                    lastBackupStatus = if (isNewer) latestItem.status else existing.lastBackupStatus
                )
                orgDao.update(updatedEntity)
            }

            // Store up to 35 most recent emails per organization for detailed history & multi-job tracking
            val emailsToSave = sortedAsc.takeLast(35).map { mail ->
                BackupEmailEntity(
                    organizationId = orgId,
                    folderUidKey = mail.uidKey,
                    receivedAtMillis = mail.receivedAtMillis,
                    senderRaw = mail.rawFrom,
                    subject = mail.subject,
                    snippet = mail.snippet,
                    status = mail.status,
                    normalizedJobKey = mail.normalizedJobKey
                )
            }
            emailDao.upsertAll(emailsToSave)
            emailDao.trimOldForOrganization(orgId, keepCount = 40)
        }
    }

    private fun computeSyncSinceMillis(
        settings: AppSettings,
        isInitialScan: Boolean,
        nowMillis: Long
    ): Long {
        if (isInitialScan) {
            return ZonedDateTime.now()
                .minusMonths(settings.initialLookbackMonths.coerceIn(1, 24).toLong())
                .toInstant()
                .toEpochMilli()
        }
        val safetyWindowMillis = nowMillis - 7L * 86_400_000L
        return if (settings.lastSyncAtMillis > 0L) {
            // Overlap by 24h (deduplicated via folderUidKey) so delayed mail deliveries are never missed
            maxOf(settings.lastSyncAtMillis - 86_400_000L, safetyWindowMillis)
        } else {
            safetyWindowMillis
        }
    }

    private fun buildSuccessLogMessage(
        wasInitialScan: Boolean,
        messagesCount: Int,
        monitoredOrgs: Int,
        report: MorningAlertReport
    ): String {
        val prefix = if (wasInitialScan) {
            "Первичный сбор завершён: писем $messagesCount, организаций $monitoredOrgs."
        } else {
            "Новых писем: $messagesCount, организаций на контроле: $monitoredOrgs."
        }
        return if (report.hasIssues) {
            "$prefix Ошибки: ${report.errorOrganizations.size}, нет писем: ${report.missingMailOrganizations.size}."
        } else {
            "$prefix Проблем не обнаружено."
        }
    }

    suspend fun renameOrganization(orgId: Long, newName: String) {
        val clean = newName.trim()
        if (clean.isNotEmpty()) {
            orgDao.updateCustomName(orgId, clean)
        }
    }

    suspend fun setOrganizationMonitored(orgId: Long, monitored: Boolean) {
        orgDao.updateMonitored(orgId, monitored)
    }

    suspend fun setOrganizationCustomMissingDays(orgId: Long, days: Int?) {
        orgDao.updateCustomMissingDays(orgId, days?.coerceIn(1, 60))
    }

    suspend fun acknowledgeOrganization(orgId: Long) {
        orgDao.acknowledgeIssues(orgId, System.currentTimeMillis())
    }

    suspend fun dismissMorningBanner() {
        val now = System.currentTimeMillis()
        settingsStore.update { it.copy(bannerDismissedAtMillis = now) }
    }

    suspend fun acknowledgeAllErrors() {
        val now = System.currentTimeMillis()
        orgDao.acknowledgeAllIssues(now)
        settingsStore.update { it.copy(bannerDismissedAtMillis = now) }
    }

    suspend fun addManualOrganization(
        displayName: String,
        senderEmail: String,
        customMissingDays: Int?
    ) {
        val cleanEmail = senderEmail.trim().lowercase()
        val cleanName = displayName.trim().ifBlank { cleanEmail }
        if (cleanName.isBlank()) return
        val key = cleanEmail.ifBlank { "manual#${cleanName.lowercase()}" }
        val now = System.currentTimeMillis()
        orgDao.insertIgnore(
            OrganizationEntity(
                orgKey = key,
                displayName = cleanName,
                isCustomName = true,
                senderEmail = cleanEmail,
                senderPersonal = cleanName,
                isMonitored = true,
                customMissingDays = customMissingDays,
                firstSeenAtMillis = now,
                lastEmailAtMillis = 0L,
                emailCount = 0,
                lastSubject = "Ожидание первого письма",
                lastSnippet = "",
                lastBackupStatus = BackupStatus.UNKNOWN,
                acknowledgedAtMillis = 0L
            )
        )
    }

    suspend fun deleteOrganization(orgId: Long) {
        orgDao.deleteById(orgId)
    }

    suspend fun resetDatabaseAndInitialScan() {
        syncMutex.withLock {
            emailDao.deleteAll()
            orgDao.deleteAll()
            syncLogDao.deleteAll()
            settingsStore.update {
                it.copy(
                    initialScanCompleted = false,
                    lastSyncAtMillis = 0L,
                    previousSyncAtMillis = 0L,
                    bannerDismissedAtMillis = 0L
                )
            }
            _syncProgress.value = SyncProgress()
        }
    }

    private fun OrganizationEntity.toRecordInput() = OrganizationRecordInput(
        id = id,
        orgKey = orgKey,
        displayName = displayName,
        isCustomName = isCustomName,
        senderEmail = senderEmail,
        senderPersonal = senderPersonal,
        isMonitored = isMonitored,
        customMissingDays = customMissingDays,
        lastEmailAtMillis = lastEmailAtMillis,
        firstSeenAtMillis = firstSeenAtMillis,
        emailCount = emailCount,
        lastSubject = lastSubject,
        lastSnippet = lastSnippet,
        lastBackupStatus = lastBackupStatus,
        acknowledgedAtMillis = acknowledgedAtMillis
    )

    private fun BackupEmailEntity.toRecentInput() = RecentEmailInput(
        organizationId = organizationId,
        receivedAtMillis = receivedAtMillis,
        subject = subject,
        snippet = snippet,
        status = status,
        normalizedJobKey = normalizedJobKey
    )

    companion object {
        @Volatile
        private var INSTANCE: BackupMonitorRepository? = null

        fun getInstance(context: Context): BackupMonitorRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BackupMonitorRepository(context).also { INSTANCE = it }
            }
        }
    }
}
