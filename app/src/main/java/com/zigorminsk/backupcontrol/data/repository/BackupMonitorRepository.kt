package com.zigorminsk.backupcontrol.data.repository

import android.content.Context
import com.zigorminsk.backupcontrol.data.local.BackupDatabase
import com.zigorminsk.backupcontrol.data.local.entity.BackupEmailEntity
import com.zigorminsk.backupcontrol.data.local.entity.OrganizationEntity
import com.zigorminsk.backupcontrol.data.local.entity.SyncLogEntity
import com.zigorminsk.backupcontrol.data.mail.ConnectionTestResult
import com.zigorminsk.backupcontrol.data.mail.FetchedMailItem
import com.zigorminsk.backupcontrol.data.mail.ImapMailClient
import com.zigorminsk.backupcontrol.data.preferences.SecureSettingsStore
import com.zigorminsk.backupcontrol.domain.analyzer.BackupAlertEvaluator
import com.zigorminsk.backupcontrol.domain.analyzer.OrganizationRecordInput
import com.zigorminsk.backupcontrol.domain.analyzer.RecentEmailInput
import com.zigorminsk.backupcontrol.domain.model.AppSettings
import com.zigorminsk.backupcontrol.domain.model.BackupStatus
import com.zigorminsk.backupcontrol.domain.model.MorningAlertReport
import com.zigorminsk.backupcontrol.domain.model.OrganizationApprovalState
import com.zigorminsk.backupcontrol.domain.model.OrganizationSummary
import com.zigorminsk.backupcontrol.domain.model.SyncProgress
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
    val awaitsOrganizationReview: Boolean,
    val newlyAddedCount: Int,
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
                awaitsOrganizationReview = false,
                newlyAddedCount = 0,
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
                "Сбор данных за ${settings.initialLookbackMonths} мес. для формирования списка организаций…"
            } else {
                "Проверка новых отчётов и новых отправителей…"
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
                stageText = "Обработка и сопоставление организаций…"
            )

            val persistStats = persistFetchedItems(
                items = fetchedItems,
                isInitialScan = isInitialScan,
                settings = settingsStore.current
            )

            val finishedAt = System.currentTimeMillis()
            val updatedSettings = settingsStore.update { current ->
                current.copy(
                    initialScanCompleted = true,
                    previousSyncAtMillis = if (current.lastSyncAtMillis > 0L) current.lastSyncAtMillis else sinceMillis,
                    lastSyncAtMillis = finishedAt,
                    bannerDismissedAtMillis = 0L
                )
            }

            val allOrgs = orgDao.getAll()
            val pendingCount = allOrgs.count { it.approvalState == OrganizationApprovalState.PENDING_REVIEW }
            val savedControlledCount = allOrgs.count {
                it.approvalState == OrganizationApprovalState.SAVED && it.isMonitored
            }
            val awaitsReview = !updatedSettings.baselineSaved || pendingCount > 0

            val report = evaluateCurrentReport(updatedSettings, finishedAt)

            syncLogDao.insert(
                SyncLogEntity(
                    startedAtMillis = startedAt,
                    finishedAtMillis = finishedAt,
                    triggerType = if (isInitialScan) {
                        "Сбор за ${updatedSettings.initialLookbackMonths} мес."
                    } else {
                        triggerType
                    },
                    success = true,
                    scannedMessages = fetchedItems.size,
                    organizationsCount = if (updatedSettings.baselineSaved) savedControlledCount else pendingCount,
                    errorOrgsCount = report.errorOrganizations.size,
                    missingOrgsCount = report.missingMailOrganizations.size,
                    bannerShown = updatedSettings.baselineSaved && report.hasIssues,
                    message = buildSuccessLogMessage(
                        wasInitialScan = isInitialScan,
                        baselineSaved = updatedSettings.baselineSaved,
                        pendingCount = pendingCount,
                        messagesCount = fetchedItems.size,
                        monitoredOrgs = savedControlledCount,
                        newlyAddedCount = persistStats.newlyAddedSavedCount,
                        report = report
                    )
                )
            )
            syncLogDao.trimOld(100)

            _syncProgress.value = SyncProgress(
                isRunning = false,
                isInitialSixMonthScan = false,
                stageText = if (awaitsReview && !updatedSettings.baselineSaved) {
                    "Сбор завершён: проверьте и сохраните список организаций ($pendingCount)"
                } else {
                    "Синхронизация завершена"
                },
                processedMessages = fetchedItems.size,
                totalMessages = fetchedItems.size,
                discoveredOrganizations = if (updatedSettings.baselineSaved) savedControlledCount else pendingCount,
                lastError = null
            )

            SyncOutcome(
                success = true,
                wasInitialScan = isInitialScan,
                awaitsOrganizationReview = awaitsReview,
                newlyAddedCount = persistStats.newlyAddedSavedCount,
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
                    organizationsCount = orgDao.getAll().count {
                        it.approvalState == OrganizationApprovalState.SAVED && it.isMonitored
                    },
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
                awaitsOrganizationReview = false,
                newlyAddedCount = 0,
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

    private data class PersistStats(
        val newlyAddedSavedCount: Int
    )

    private suspend fun persistFetchedItems(
        items: List<FetchedMailItem>,
        isInitialScan: Boolean,
        settings: AppSettings
    ): PersistStats {
        if (items.isEmpty()) return PersistStats(0)

        var newlyAddedSavedCount = 0

        // Строим карту всех известных адресов (включая отредактированные и объединённые адреса),
        // чтобы сохранённые и исключённые организации сопоставлялись безошибочно.
        val existingOrgs = orgDao.getAll().toMutableList()
        val emailLookup = mutableMapOf<String, OrganizationEntity>()

        fun indexOrg(entity: OrganizationEntity) {
            emailLookup[entity.orgKey.trim().lowercase()] = entity
            if (entity.senderEmail.isNotBlank()) {
                emailLookup[entity.senderEmail.trim().lowercase()] = entity
            }
            entity.additionalSenderEmails
                .split(",", ";")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .forEach { extra -> emailLookup[extra] = entity }
        }
        existingOrgs.forEach(::indexOrg)

        val grouped = items.groupBy { it.orgKey }
        for ((orgKey, orgItems) in grouped) {
            val sortedAsc = orgItems.sortedBy { it.receivedAtMillis }
            val latestItem = sortedAsc.last()
            val earliestItem = sortedAsc.first()

            val nonBlankPersonal = sortedAsc.lastOrNull { it.senderPersonal.isNotBlank() }?.senderPersonal
                ?: latestItem.senderPersonal
            val bestSuggestedName = sortedAsc.lastOrNull { it.senderPersonal.isNotBlank() }?.suggestedOrgName
                ?: latestItem.suggestedOrgName

            val normalizedSender = latestItem.senderEmail.trim().lowercase()
            var existing = emailLookup[orgKey.trim().lowercase()]
                ?: (if (normalizedSender.isNotEmpty()) emailLookup[normalizedSender] else null)
                ?: orgDao.getByKey(orgKey)

            val orgId: Long
            if (existing == null) {
                // Новый отправитель, которого ещё нет ни в сохранённых, ни в исключённых:
                // 1) При сборе за 6 месяцев (или до сохранения базового списка) -> PENDING_REVIEW
                //    (чтобы пользователь отредактировал и сохранил нужные организации).
                // 2) После сохранения базового списка (baselineSaved == true) и при появлении нового письма ->
                //    автоматически добавляем новую организацию на контроль (SAVED + isNewlyAdded = true),
                //    если это не системный mailer-daemon отлуп.
                val approvalState: OrganizationApprovalState
                val isMonitored: Boolean
                val isNewlyAdded: Boolean

                when {
                    !settings.baselineSaved || isInitialScan -> {
                        approvalState = OrganizationApprovalState.PENDING_REVIEW
                        isMonitored = !latestItem.isLikelySystemBounce
                        isNewlyAdded = false
                    }
                    latestItem.isLikelySystemBounce -> {
                        approvalState = OrganizationApprovalState.EXCLUDED
                        isMonitored = false
                        isNewlyAdded = false
                    }
                    settings.autoAddNewOrganizations -> {
                        approvalState = OrganizationApprovalState.SAVED
                        isMonitored = true
                        isNewlyAdded = true
                        newlyAddedSavedCount++
                    }
                    else -> {
                        approvalState = OrganizationApprovalState.PENDING_REVIEW
                        isMonitored = true
                        isNewlyAdded = true
                    }
                }

                val newEntity = OrganizationEntity(
                    orgKey = orgKey,
                    displayName = bestSuggestedName,
                    isCustomName = false,
                    senderEmail = latestItem.senderEmail,
                    additionalSenderEmails = "",
                    senderPersonal = nonBlankPersonal,
                    notes = "",
                    approvalState = approvalState,
                    isMonitored = isMonitored,
                    isNewlyAdded = isNewlyAdded,
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
                val savedEntity = existing ?: continue
                indexOrg(savedEntity)
                orgId = savedEntity.id
            } else {
                orgId = existing.id
                // Если организация была исключена пользователем (EXCLUDED), оставляем её исключённой
                // и не возвращаем в контроль.
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
                indexOrg(updatedEntity)
            }

            if (existing.approvalState != OrganizationApprovalState.EXCLUDED) {
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

        return PersistStats(newlyAddedSavedCount = newlyAddedSavedCount)
    }

    /**
     * Сохраняет отредактированный список организаций после первичного (или повторного) сбора за 6 месяцев:
     * - Выбранные организации получают статус SAVED и берутся под ежедневный контроль;
     * - Невыбранные организации переводятся в EXCLUDED, чтобы не контролироваться и не добавляться повторно из старых писем;
     * - После этого baselineSaved = true: контролируются только сохранённые организации + автоматически добавляются новые при их появлении.
     */
    suspend fun saveReviewedOrganizationSelection(selectedIds: Set<Long>): Int {
        val all = orgDao.getAll()
        val updated = all.mapNotNull { entity ->
            when {
                entity.id in selectedIds -> {
                    entity.copy(
                        approvalState = OrganizationApprovalState.SAVED,
                        isMonitored = true,
                        isNewlyAdded = false
                    )
                }
                entity.approvalState != OrganizationApprovalState.EXCLUDED -> {
                    entity.copy(
                        approvalState = OrganizationApprovalState.EXCLUDED,
                        isMonitored = false,
                        isNewlyAdded = false
                    )
                }
                else -> null
            }
        }
        if (updated.isNotEmpty()) {
            orgDao.updateAll(updated)
        }
        val now = System.currentTimeMillis()
        settingsStore.update {
            it.copy(
                baselineSaved = true,
                baselineSavedAtMillis = if (it.baselineSavedAtMillis > 0L) it.baselineSavedAtMillis else now
            )
        }
        return selectedIds.size
    }

    /**
     * Полное редактирование карточки организации (название, основной и доп. email-адреса, заметка, порог дней, флаг контроля).
     */
    suspend fun updateOrganizationDetails(
        orgId: Long,
        displayName: String,
        senderEmail: String,
        additionalSenderEmails: String,
        notes: String,
        customMissingDays: Int?,
        isMonitored: Boolean,
        markSaved: Boolean = true
    ) {
        val existing = orgDao.getById(orgId) ?: return
        val cleanName = displayName.trim().ifBlank { existing.displayName }
        val cleanPrimaryEmail = senderEmail.trim().lowercase()

        // Сохраняем прежний email в дополнительных адресах, если пользователь сменил основной email,
        // чтобы старый адрес не создал дубликат при чтении писем.
        val extraSet = linkedSetOf<String>()
        if (existing.senderEmail.isNotBlank() &&
            cleanPrimaryEmail.isNotBlank() &&
            !existing.senderEmail.equals(cleanPrimaryEmail, ignoreCase = true)
        ) {
            extraSet += existing.senderEmail.trim().lowercase()
        }
        additionalSenderEmails
            .split(",", ";", "\n")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != cleanPrimaryEmail }
            .forEach { extraSet += it }

        val newApprovalState = if (markSaved && existing.approvalState == OrganizationApprovalState.PENDING_REVIEW) {
            OrganizationApprovalState.SAVED
        } else {
            existing.approvalState
        }

        orgDao.update(
            existing.copy(
                displayName = cleanName,
                isCustomName = true,
                senderEmail = cleanPrimaryEmail.ifBlank { existing.senderEmail },
                additionalSenderEmails = extraSet.joinToString(", "),
                notes = notes.trim(),
                customMissingDays = customMissingDays?.coerceIn(1, 60),
                isMonitored = isMonitored,
                approvalState = newApprovalState,
                isNewlyAdded = false
            )
        )
    }

    /**
     * Объединяет две организации в одну (например, когда у одной организации несколько почтовых ящиков/серверов).
     * Все адреса второй организации переносятся в additionalSenderEmails целевой организации.
     */
    suspend fun mergeOrganizations(targetOrgId: Long, sourceOrgId: Long) {
        if (targetOrgId == sourceOrgId) return
        val target = orgDao.getById(targetOrgId) ?: return
        val source = orgDao.getById(sourceOrgId) ?: return

        val combinedExtras = linkedSetOf<String>()
        listOf(target.additionalSenderEmails, source.senderEmail, source.orgKey, source.additionalSenderEmails)
            .flatMap { raw -> raw.split(",", ";") }
            .map { it.trim().lowercase() }
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("manual#") &&
                    !it.equals(target.senderEmail, ignoreCase = true)
            }
            .forEach { combinedExtras += it }

        val targetIsNewer = target.lastEmailAtMillis >= source.lastEmailAtMillis

        emailDao.reassignOrganization(sourceOrgId = source.id, targetOrgId = target.id)
        emailDao.trimOldForOrganization(target.id, keepCount = 45)

        orgDao.update(
            target.copy(
                additionalSenderEmails = combinedExtras.joinToString(", "),
                approvalState = if (target.approvalState == OrganizationApprovalState.EXCLUDED) {
                    source.approvalState
                } else {
                    target.approvalState
                },
                firstSeenAtMillis = minOf(target.firstSeenAtMillis, source.firstSeenAtMillis),
                lastEmailAtMillis = maxOf(target.lastEmailAtMillis, source.lastEmailAtMillis),
                emailCount = target.emailCount + source.emailCount,
                lastSubject = if (targetIsNewer) target.lastSubject else source.lastSubject,
                lastSnippet = if (targetIsNewer) target.lastSnippet else source.lastSnippet,
                lastBackupStatus = if (targetIsNewer) target.lastBackupStatus else source.lastBackupStatus
            )
        )
        orgDao.deleteById(source.id)
    }

    suspend fun renameOrganization(orgId: Long, newName: String) {
        val clean = newName.trim()
        if (clean.isNotEmpty()) {
            orgDao.updateCustomName(orgId, clean)
            orgDao.clearNewlyAdded(orgId)
        }
    }

    suspend fun setOrganizationMonitored(orgId: Long, monitored: Boolean) {
        orgDao.updateMonitored(orgId, monitored)
    }

    suspend fun setOrganizationCustomMissingDays(orgId: Long, days: Int?) {
        orgDao.updateCustomMissingDays(orgId, days?.coerceIn(1, 60))
    }

    suspend fun excludeOrganization(orgId: Long) {
        orgDao.updateApprovalAndMonitored(
            id = orgId,
            state = OrganizationApprovalState.EXCLUDED,
            monitored = false
        )
    }

    suspend fun restoreOrganizationToSaved(orgId: Long) {
        orgDao.updateApprovalAndMonitored(
            id = orgId,
            state = OrganizationApprovalState.SAVED,
            monitored = true
        )
        if (!settingsStore.current.baselineSaved) {
            settingsStore.update {
                it.copy(baselineSaved = true, baselineSavedAtMillis = System.currentTimeMillis())
            }
        }
    }

    suspend fun permanentlyDeleteOrganization(orgId: Long) {
        orgDao.deleteById(orgId)
    }

    suspend fun clearNewlyAddedFlag(orgId: Long) {
        orgDao.clearNewlyAdded(orgId)
    }

    suspend fun clearAllNewlyAddedFlags() {
        orgDao.clearAllNewlyAdded()
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
        additionalEmails: String = "",
        notes: String = "",
        customMissingDays: Int?
    ) {
        val cleanEmail = senderEmail.trim().lowercase()
        val cleanName = displayName.trim().ifBlank { cleanEmail }
        if (cleanName.isBlank()) return
        val key = cleanEmail.ifBlank { "manual#${cleanName.lowercase()}-${System.currentTimeMillis()}" }
        val now = System.currentTimeMillis()
        val cleanExtras = additionalEmails
            .split(",", ";")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != cleanEmail }
            .distinct()
            .joinToString(", ")

        // Если такой адрес уже был в исключённых — восстанавливаем и обновляем его
        val existing = orgDao.getByKey(key)
        if (existing != null) {
            orgDao.update(
                existing.copy(
                    displayName = cleanName,
                    isCustomName = true,
                    senderEmail = cleanEmail.ifBlank { existing.senderEmail },
                    additionalSenderEmails = cleanExtras.ifBlank { existing.additionalSenderEmails },
                    notes = notes.trim().ifBlank { existing.notes },
                    approvalState = OrganizationApprovalState.SAVED,
                    isMonitored = true,
                    isNewlyAdded = false,
                    customMissingDays = customMissingDays ?: existing.customMissingDays
                )
            )
            return
        }

        val pendingExists = orgDao.getAll().any { it.approvalState == OrganizationApprovalState.PENDING_REVIEW }
        val targetState = if (!settingsStore.current.baselineSaved && pendingExists) {
            OrganizationApprovalState.PENDING_REVIEW
        } else {
            OrganizationApprovalState.SAVED
        }

        orgDao.insertIgnore(
            OrganizationEntity(
                orgKey = key,
                displayName = cleanName,
                isCustomName = true,
                senderEmail = cleanEmail,
                additionalSenderEmails = cleanExtras,
                senderPersonal = cleanName,
                notes = notes.trim(),
                approvalState = targetState,
                isMonitored = true,
                isNewlyAdded = false,
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

        if (targetState == OrganizationApprovalState.SAVED && !settingsStore.current.baselineSaved) {
            settingsStore.update {
                it.copy(baselineSaved = true, baselineSavedAtMillis = now)
            }
        }
    }

    suspend fun resetDatabaseAndInitialScan() {
        syncMutex.withLock {
            emailDao.deleteAll()
            orgDao.deleteAll()
            syncLogDao.deleteAll()
            settingsStore.update {
                it.copy(
                    initialScanCompleted = false,
                    baselineSaved = false,
                    baselineSavedAtMillis = 0L,
                    lastSyncAtMillis = 0L,
                    previousSyncAtMillis = 0L,
                    bannerDismissedAtMillis = 0L
                )
            }
            _syncProgress.value = SyncProgress()
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
            maxOf(settings.lastSyncAtMillis - 86_400_000L, safetyWindowMillis)
        } else {
            safetyWindowMillis
        }
    }

    private fun buildSuccessLogMessage(
        wasInitialScan: Boolean,
        baselineSaved: Boolean,
        pendingCount: Int,
        messagesCount: Int,
        monitoredOrgs: Int,
        newlyAddedCount: Int,
        report: MorningAlertReport
    ): String {
        if (!baselineSaved) {
            return "Сбор за 6 месяцев завершён: писем $messagesCount, найдено организаций: $pendingCount. Отредактируйте и сохраните список для запуска контроля."
        }
        val prefix = buildString {
            if (wasInitialScan) {
                append("Повторный сбор завершён: писем $messagesCount, сохранённых на контроле: $monitoredOrgs.")
                if (pendingCount > 0) append(" На проверке новых: $pendingCount.")
            } else {
                append("Писем проверено: $messagesCount, на контроле: $monitoredOrgs.")
                if (newlyAddedCount > 0) append(" Добавлено новых организаций: $newlyAddedCount.")
            }
        }
        return if (report.hasIssues) {
            "$prefix Ошибки: ${report.errorOrganizations.size}, нет писем: ${report.missingMailOrganizations.size}."
        } else {
            "$prefix Проблем не обнаружено."
        }
    }

    private fun OrganizationEntity.toRecordInput() = OrganizationRecordInput(
        id = id,
        orgKey = orgKey,
        displayName = displayName,
        isCustomName = isCustomName,
        senderEmail = senderEmail,
        additionalSenderEmails = additionalSenderEmails,
        senderPersonal = senderPersonal,
        notes = notes,
        approvalState = approvalState,
        isMonitored = isMonitored,
        isNewlyAdded = isNewlyAdded,
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
