package com.zigorminsk.backupcontrol.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zigorminsk.backupcontrol.data.local.entity.BackupEmailEntity
import com.zigorminsk.backupcontrol.data.local.entity.SyncLogEntity
import com.zigorminsk.backupcontrol.data.repository.BackupMonitorRepository
import com.zigorminsk.backupcontrol.domain.model.AppSettings
import com.zigorminsk.backupcontrol.domain.model.DashboardFilter
import com.zigorminsk.backupcontrol.domain.model.MorningAlertReport
import com.zigorminsk.backupcontrol.domain.model.OrganizationApprovalState
import com.zigorminsk.backupcontrol.domain.model.OrganizationGroupFilter
import com.zigorminsk.backupcontrol.domain.model.OrganizationHealth
import com.zigorminsk.backupcontrol.domain.model.OrganizationSummary
import com.zigorminsk.backupcontrol.domain.model.SyncProgress
import com.zigorminsk.backupcontrol.update.AppUpdateManager
import com.zigorminsk.backupcontrol.update.AppUpdateUiState
import com.zigorminsk.backupcontrol.worker.BackupScheduleManager
import com.zigorminsk.backupcontrol.worker.MorningBannerNotifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppTab(val titleRu: String) {
    DASHBOARD("Мониторинг"),
    ORGANIZATIONS("Организации"),
    HISTORY("Журнал"),
    SETTINGS("Настройки")
}

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data class Success(val message: String) : ConnectionTestState
    data class Error(val message: String) : ConnectionTestState
}

data class MainUiState(
    val selectedTab: AppTab = AppTab.DASHBOARD,
    val settings: AppSettings = AppSettings(),
    val allOrganizations: List<OrganizationSummary> = emptyList(),
    val savedOrganizations: List<OrganizationSummary> = emptyList(),
    val pendingReviewOrganizations: List<OrganizationSummary> = emptyList(),
    val newlyAddedOrganizations: List<OrganizationSummary> = emptyList(),
    val excludedOrganizations: List<OrganizationSummary> = emptyList(),
    val filteredOrganizations: List<OrganizationSummary> = emptyList(),
    val orgGroupFilter: OrganizationGroupFilter = OrganizationGroupFilter.SAVED,
    val isBatchEditMode: Boolean = false,
    val needsInitialOrganizationSave: Boolean = false,
    val report: MorningAlertReport = MorningAlertReport(0L, emptyList(), emptyList()),
    val showMorningBannerInApp: Boolean = false,
    val syncProgress: SyncProgress = SyncProgress(),
    val syncLogs: List<SyncLogEntity> = emptyList(),
    val selectedFilter: DashboardFilter = DashboardFilter.ALL,
    val searchQuery: String = "",
    val orgSearchQuery: String = "",
    val nextScheduledText: String = "",
    val connectionTestState: ConnectionTestState = ConnectionTestState.Idle,
    val appUpdateState: AppUpdateUiState = AppUpdateUiState(),
    val snackbarMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val repository = BackupMonitorRepository.getInstance(appContext)

    private val selectedTabFlow = MutableStateFlow(AppTab.DASHBOARD)
    private val selectedFilterFlow = MutableStateFlow(DashboardFilter.ALL)
    private val orgGroupFilterFlow = MutableStateFlow(OrganizationGroupFilter.SAVED)
    private val manualBatchEditModeFlow = MutableStateFlow(false)
    private val searchQueryFlow = MutableStateFlow("")
    private val orgSearchQueryFlow = MutableStateFlow("")
    private val connectionTestFlow = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    private val snackbarFlow = MutableStateFlow<String?>(null)
    private val forceShowBannerFlow = MutableStateFlow(false)

    private val selectedOrgIdFlow = MutableStateFlow<Long?>(null)
    val selectedOrgId: StateFlow<Long?> = selectedOrgIdFlow.asStateFlow()

    val selectedOrgEmails: StateFlow<List<BackupEmailEntity>> = selectedOrgIdFlow
        .flatMapLatest { orgId ->
            if (orgId == null) flowOf(emptyList()) else repository.observeEmailsForOrganization(orgId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val minuteTickerFlow: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000L)
        }
    }

    private data class CoreDataBundle(
        val orgs: List<OrganizationSummary>,
        val report: MorningAlertReport,
        val settings: AppSettings,
        val progress: SyncProgress,
        val logs: List<SyncLogEntity>
    )

    private data class UiControlBundle(
        val tab: AppTab,
        val filter: DashboardFilter,
        val orgGroupFilter: OrganizationGroupFilter,
        val manualBatchEdit: Boolean,
        val search: String,
        val orgSearch: String,
        val connTest: ConnectionTestState,
        val snackbar: String?
    )

    private val coreDataFlow = combine(
        repository.observeOrganizationsAndReport(minuteTickerFlow),
        repository.settingsStore.settingsFlow,
        repository.syncProgress,
        repository.observeSyncLogs()
    ) { (orgs, report), settings, progress, logs ->
        CoreDataBundle(orgs, report, settings, progress, logs)
    }

    private val uiControlFlow = combine(
        combine(selectedTabFlow, selectedFilterFlow, orgGroupFilterFlow, manualBatchEditModeFlow) { t, f, og, mb ->
            listOf(t, f, og, mb)
        },
        combine(searchQueryFlow, orgSearchQueryFlow, connectionTestFlow, snackbarFlow) { s, os, ct, sb ->
            listOf(s, os, ct, sb)
        }
    ) { left, right ->
        UiControlBundle(
            tab = left[0] as AppTab,
            filter = left[1] as DashboardFilter,
            orgGroupFilter = left[2] as OrganizationGroupFilter,
            manualBatchEdit = left[3] as Boolean,
            search = right[0] as String,
            orgSearch = right[1] as String,
            connTest = right[2] as ConnectionTestState,
            snackbar = right[3] as String?
        )
    }

    val uiState: StateFlow<MainUiState> = combine(
        coreDataFlow,
        uiControlFlow,
        forceShowBannerFlow,
        AppUpdateManager.state
    ) { core, ctrl, forceBanner, updateState ->
        val savedOrgs = core.orgs.filter { it.approvalState == OrganizationApprovalState.SAVED }
        val pendingOrgs = core.orgs.filter { it.approvalState == OrganizationApprovalState.PENDING_REVIEW }
        val newlyAddedOrgs = savedOrgs.filter { it.isNewlyAdded }
        val excludedOrgs = core.orgs.filter { it.approvalState == OrganizationApprovalState.EXCLUDED }

        val needsInitialSave = (!core.settings.baselineSaved && core.orgs.isNotEmpty()) ||
            pendingOrgs.isNotEmpty()

        val filteredDashboard = filterDashboardOrganizations(
            saved = savedOrgs,
            filter = ctrl.filter,
            query = ctrl.search
        )

        val nextRun = BackupScheduleManager.scheduleNextAlarm(appContext)
        val bannerNotDismissed = core.settings.bannerDismissedAtMillis <= 0L ||
            core.settings.lastSyncAtMillis > core.settings.bannerDismissedAtMillis

        val showBanner = core.settings.baselineSaved &&
            core.report.hasIssues &&
            (bannerNotDismissed || forceBanner)

        MainUiState(
            selectedTab = ctrl.tab,
            settings = core.settings,
            allOrganizations = core.orgs,
            savedOrganizations = savedOrgs,
            pendingReviewOrganizations = pendingOrgs,
            newlyAddedOrganizations = newlyAddedOrgs,
            excludedOrganizations = excludedOrgs,
            filteredOrganizations = filteredDashboard,
            orgGroupFilter = ctrl.orgGroupFilter,
            isBatchEditMode = ctrl.manualBatchEdit || needsInitialSave,
            needsInitialOrganizationSave = needsInitialSave,
            report = core.report,
            showMorningBannerInApp = showBanner,
            syncProgress = core.progress,
            syncLogs = core.logs,
            selectedFilter = ctrl.filter,
            searchQuery = ctrl.search,
            orgSearchQuery = ctrl.orgSearch,
            nextScheduledText = Formatters.formatNextRun(nextRun),
            connectionTestState = ctrl.connTest,
            appUpdateState = updateState,
            snackbarMessage = ctrl.snackbar
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(settings = repository.settingsStore.current)
    )

    fun onOpenedFromMorningBanner() {
        selectedTabFlow.value = AppTab.DASHBOARD
        selectedFilterFlow.value = DashboardFilter.PROBLEMS
        forceShowBannerFlow.value = true
    }

    fun openOrganizationsTabForReview() {
        selectedTabFlow.value = AppTab.ORGANIZATIONS
        val current = uiState.value
        if (current.needsInitialOrganizationSave) {
            manualBatchEditModeFlow.value = true
        } else if (current.newlyAddedOrganizations.isNotEmpty()) {
            orgGroupFilterFlow.value = OrganizationGroupFilter.NEW_ADDED
        }
    }

    fun selectTab(tab: AppTab) {
        selectedTabFlow.value = tab
    }

    fun selectFilter(filter: DashboardFilter) {
        selectedFilterFlow.value = filter
    }

    fun selectOrgGroupFilter(filter: OrganizationGroupFilter) {
        orgGroupFilterFlow.value = filter
    }

    fun setBatchEditMode(enabled: Boolean) {
        manualBatchEditModeFlow.value = enabled
    }

    fun updateSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    fun updateOrgSearchQuery(query: String) {
        orgSearchQueryFlow.value = query
    }

    fun openOrganizationDetail(orgId: Long) {
        selectedOrgIdFlow.value = orgId
    }

    fun closeOrganizationDetail() {
        selectedOrgIdFlow.value = null
    }

    fun clearSnackbar() {
        snackbarFlow.value = null
    }

    fun saveSettings(updated: AppSettings, runSyncNow: Boolean = false, forceSixMonths: Boolean = false) {
        val saved = repository.settingsStore.update {
            updated.copy(
                email = updated.email.trim(),
                imapHost = updated.imapHost.trim().ifBlank { AppSettings.DEFAULT_IMAP_HOST },
                folderName = updated.folderName.trim().ifBlank { "INBOX" }
            )
        }
        val nextRun = BackupScheduleManager.scheduleNextAlarm(appContext)
        snackbarFlow.value = if (saved.scheduleEnabled && nextRun != null) {
            "Настройки сохранены. Следующая проверка: ${Formatters.formatNextRun(nextRun)}"
        } else {
            "Настройки сохранены."
        }

        if (runSyncNow) {
            val isSixMonths = forceSixMonths || !saved.initialScanCompleted
            selectedTabFlow.value = if (isSixMonths && !saved.baselineSaved) {
                AppTab.ORGANIZATIONS
            } else {
                AppTab.DASHBOARD
            }
            BackupScheduleManager.enqueueImmediateSync(
                context = appContext,
                isScheduledMorningRun = false,
                forceSixMonthScan = isSixMonths
            )
        }
    }

    fun testImapConnection(candidateSettings: AppSettings) {
        if (candidateSettings.email.isBlank() || candidateSettings.password.isBlank()) {
            connectionTestFlow.value = ConnectionTestState.Error("Укажите логин (email) и пароль почты.")
            return
        }
        connectionTestFlow.value = ConnectionTestState.Testing
        viewModelScope.launch {
            runCatching {
                repository.testConnection(candidateSettings)
            }.onSuccess { res ->
                if (res.endpoint.host != candidateSettings.imapHost ||
                    res.endpoint.port != candidateSettings.imapPort ||
                    res.endpoint.securityMode != candidateSettings.securityMode
                ) {
                    repository.settingsStore.update {
                        it.copy(
                            imapHost = res.endpoint.host,
                            imapPort = res.endpoint.port,
                            securityMode = res.endpoint.securityMode
                        )
                    }
                }
                connectionTestFlow.value = ConnectionTestState.Success(
                    "Подключено к ${res.endpoint.host}:${res.endpoint.port} (${res.endpoint.securityMode.titleRu}). " +
                        "Писем в папке ${candidateSettings.folderName.ifBlank { "INBOX" }}: ${res.totalMessagesInFolder}."
                )
            }.onFailure { err ->
                connectionTestFlow.value = ConnectionTestState.Error(
                    err.localizedMessage ?: "Не удалось подключиться к почтовому серверу."
                )
            }
        }
    }

    fun triggerManualSync(forceSixMonths: Boolean = false) {
        val current = repository.settingsStore.current
        if (!current.isConfigured) {
            snackbarFlow.value = "Сначала введите пароль для ${current.email}."
            selectedTabFlow.value = AppTab.SETTINGS
            return
        }
        if (forceSixMonths && !current.baselineSaved) {
            selectedTabFlow.value = AppTab.ORGANIZATIONS
        }
        BackupScheduleManager.enqueueImmediateSync(
            context = appContext,
            isScheduledMorningRun = false,
            forceSixMonthScan = forceSixMonths
        )
    }

    fun saveReviewedOrganizations(selectedIds: Set<Long>) {
        viewModelScope.launch {
            val savedCount = repository.saveReviewedOrganizationSelection(selectedIds)
            manualBatchEditModeFlow.value = false
            orgGroupFilterFlow.value = OrganizationGroupFilter.SAVED
            selectedTabFlow.value = AppTab.DASHBOARD
            snackbarFlow.value = "Список сохранён ($savedCount орг.). Контролируются только сохранённые и новые организации."
        }
    }

    fun updateOrganizationDetails(
        orgId: Long,
        displayName: String,
        senderEmail: String,
        additionalSenderEmails: String,
        notes: String,
        customMissingDays: Int?,
        isMonitored: Boolean
    ) {
        viewModelScope.launch {
            repository.updateOrganizationDetails(
                orgId = orgId,
                displayName = displayName,
                senderEmail = senderEmail,
                additionalSenderEmails = additionalSenderEmails,
                notes = notes,
                customMissingDays = customMissingDays,
                isMonitored = isMonitored,
                markSaved = repository.settingsStore.current.baselineSaved
            )
            snackbarFlow.value = "Данные организации сохранены."
        }
    }

    fun mergeOrganizations(targetOrgId: Long, sourceOrgId: Long) {
        if (selectedOrgIdFlow.value == sourceOrgId) {
            selectedOrgIdFlow.value = targetOrgId
        }
        viewModelScope.launch {
            repository.mergeOrganizations(targetOrgId = targetOrgId, sourceOrgId = sourceOrgId)
            snackbarFlow.value = "Организации объединены (адреса объединены в одну карточку)."
        }
    }

    fun renameOrganization(orgId: Long, newName: String) {
        viewModelScope.launch {
            repository.renameOrganization(orgId, newName)
            snackbarFlow.value = "Название организации обновлено."
        }
    }

    fun setOrganizationMonitored(orgId: Long, monitored: Boolean) {
        viewModelScope.launch {
            repository.setOrganizationMonitored(orgId, monitored)
        }
    }

    fun excludeOrganization(orgId: Long) {
        if (selectedOrgIdFlow.value == orgId) {
            selectedOrgIdFlow.value = null
        }
        viewModelScope.launch {
            repository.excludeOrganization(orgId)
            snackbarFlow.value = "Организация перенесена в исключённые и снята с контроля."
        }
    }

    fun restoreOrganizationToSaved(orgId: Long) {
        viewModelScope.launch {
            repository.restoreOrganizationToSaved(orgId)
            snackbarFlow.value = "Организация возвращена в список контроля."
        }
    }

    fun permanentlyDeleteOrganization(orgId: Long) {
        if (selectedOrgIdFlow.value == orgId) {
            selectedOrgIdFlow.value = null
        }
        viewModelScope.launch {
            repository.permanentlyDeleteOrganization(orgId)
            snackbarFlow.value = "Запись удалена."
        }
    }

    fun clearNewlyAddedFlag(orgId: Long) {
        viewModelScope.launch {
            repository.clearNewlyAddedFlag(orgId)
        }
    }

    fun clearAllNewlyAddedFlags() {
        viewModelScope.launch {
            repository.clearAllNewlyAddedFlags()
            snackbarFlow.value = "Новые организации отмечены просмотренными."
        }
    }

    fun acknowledgeOrganization(orgId: Long) {
        viewModelScope.launch {
            repository.acknowledgeOrganization(orgId)
            snackbarFlow.value = "Статус ошибки подтверждён."
        }
    }

    fun addManualOrganization(
        name: String,
        senderEmail: String,
        additionalEmails: String = "",
        notes: String = "",
        customDays: Int?
    ) {
        viewModelScope.launch {
            repository.addManualOrganization(
                displayName = name,
                senderEmail = senderEmail,
                additionalEmails = additionalEmails,
                notes = notes,
                customMissingDays = customDays
            )
            snackbarFlow.value = "Организация сохранена в список контроля."
        }
    }

    fun triggerTestMorningBanner() {
        val report = uiState.value.report
        if (!MorningBannerNotifier.canPostNotifications(appContext)) {
            snackbarFlow.value = "Разрешите показ уведомлений в системных настройках Android."
            return
        }
        MorningBannerNotifier.showTestBanner(appContext, report)
        snackbarFlow.value = "Тестовый утренний баннер отправлен в шторку уведомлений."
    }

    fun dismissBanner() {
        forceShowBannerFlow.value = false
        MorningBannerNotifier.cancelMorningBanner(appContext)
        viewModelScope.launch {
            repository.dismissMorningBanner()
        }
    }

    fun acknowledgeAllErrors() {
        forceShowBannerFlow.value = false
        MorningBannerNotifier.cancelMorningBanner(appContext)
        viewModelScope.launch {
            repository.acknowledgeAllErrors()
            snackbarFlow.value = "Текущие ошибки отмечены просмотренными."
        }
    }

    fun checkAppUpdates() {
        AppUpdateManager.checkAsync(appContext, force = true) { msg ->
            snackbarFlow.value = msg
        }
    }

    fun installAppUpdate() {
        val started = AppUpdateManager.launchInstall(appContext)
        if (!started) {
            snackbarFlow.value = "Разрешите установку обновлений из этого источника и нажмите «Установить» повторно."
        }
    }

    fun resetDatabase() {
        selectedOrgIdFlow.value = null
        manualBatchEditModeFlow.value = false
        viewModelScope.launch {
            repository.resetDatabaseAndInitialScan()
            snackbarFlow.value = "База очищена. При следующей синхронизации выполнится сбор за 6 месяцев."
        }
    }

    private fun filterDashboardOrganizations(
        saved: List<OrganizationSummary>,
        filter: DashboardFilter,
        query: String
    ): List<OrganizationSummary> {
        val byFilter = when (filter) {
            DashboardFilter.ALL -> saved.filter { it.isMonitored }
            DashboardFilter.PROBLEMS -> saved.filter { it.isMonitored && (it.hasErrorAlert || it.isMissingMail) }
            DashboardFilter.ERRORS -> saved.filter { it.isMonitored && it.hasErrorAlert }
            DashboardFilter.MISSING -> saved.filter { it.isMonitored && it.isMissingMail }
            DashboardFilter.NEW_ADDED -> saved.filter { it.isNewlyAdded }
            DashboardFilter.HEALTHY -> saved.filter { it.isMonitored && it.health == OrganizationHealth.HEALTHY }
            DashboardFilter.PAUSED -> saved.filter { !it.isMonitored }
        }
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return byFilter
        return byFilter.filter { org ->
            org.displayName.lowercase().contains(trimmed) ||
                org.allEmailsFormatted.lowercase().contains(trimmed) ||
                org.notes.lowercase().contains(trimmed) ||
                org.lastSubject.lowercase().contains(trimmed)
        }
    }
}
