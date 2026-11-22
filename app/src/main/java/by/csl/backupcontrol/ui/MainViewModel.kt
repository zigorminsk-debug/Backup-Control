package by.csl.backupcontrol.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import by.csl.backupcontrol.data.local.entity.BackupEmailEntity
import by.csl.backupcontrol.data.local.entity.SyncLogEntity
import by.csl.backupcontrol.data.repository.BackupMonitorRepository
import by.csl.backupcontrol.domain.model.AppSettings
import by.csl.backupcontrol.domain.model.DashboardFilter
import by.csl.backupcontrol.domain.model.MorningAlertReport
import by.csl.backupcontrol.domain.model.OrganizationHealth
import by.csl.backupcontrol.domain.model.OrganizationSummary
import by.csl.backupcontrol.domain.model.SyncProgress
import by.csl.backupcontrol.worker.BackupScheduleManager
import by.csl.backupcontrol.worker.MorningBannerNotifier
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
    val filteredOrganizations: List<OrganizationSummary> = emptyList(),
    val report: MorningAlertReport = MorningAlertReport(0L, emptyList(), emptyList()),
    val showMorningBannerInApp: Boolean = false,
    val syncProgress: SyncProgress = SyncProgress(),
    val syncLogs: List<SyncLogEntity> = emptyList(),
    val selectedFilter: DashboardFilter = DashboardFilter.ALL,
    val searchQuery: String = "",
    val nextScheduledText: String = "",
    val connectionTestState: ConnectionTestState = ConnectionTestState.Idle,
    val snackbarMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val repository = BackupMonitorRepository.getInstance(appContext)

    private val selectedTabFlow = MutableStateFlow(AppTab.DASHBOARD)
    private val selectedFilterFlow = MutableStateFlow(DashboardFilter.ALL)
    private val searchQueryFlow = MutableStateFlow("")
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
        val search: String,
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
        selectedTabFlow,
        selectedFilterFlow,
        searchQueryFlow,
        connectionTestFlow,
        snackbarFlow
    ) { tab, filter, search, connTest, snackbar ->
        UiControlBundle(tab, filter, search, connTest, snackbar)
    }

    val uiState: StateFlow<MainUiState> = combine(
        coreDataFlow,
        uiControlFlow,
        forceShowBannerFlow
    ) { core, ctrl, forceBanner ->
        val filtered = filterOrganizations(
            all = core.orgs,
            filter = ctrl.filter,
            query = ctrl.search
        )

        val nextRun = BackupScheduleManager.scheduleNextAlarm(appContext)
        val bannerNotDismissed = core.settings.bannerDismissedAtMillis <= 0L ||
            core.settings.lastSyncAtMillis > core.settings.bannerDismissedAtMillis

        val showBanner = core.report.hasIssues && (bannerNotDismissed || forceBanner)

        MainUiState(
            selectedTab = ctrl.tab,
            settings = core.settings,
            allOrganizations = core.orgs,
            filteredOrganizations = filtered,
            report = core.report,
            showMorningBannerInApp = showBanner,
            syncProgress = core.progress,
            syncLogs = core.logs,
            selectedFilter = ctrl.filter,
            searchQuery = ctrl.search,
            nextScheduledText = Formatters.formatNextRun(nextRun),
            connectionTestState = ctrl.connTest,
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

    fun selectTab(tab: AppTab) {
        selectedTabFlow.value = tab
    }

    fun selectFilter(filter: DashboardFilter) {
        selectedFilterFlow.value = filter
    }

    fun updateSearchQuery(query: String) {
        searchQueryFlow.value = query
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

    fun clearConnectionTestState() {
        connectionTestFlow.value = ConnectionTestState.Idle
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
            selectedTabFlow.value = AppTab.DASHBOARD
            BackupScheduleManager.enqueueImmediateSync(
                context = appContext,
                isScheduledMorningRun = false,
                forceSixMonthScan = forceSixMonths || !saved.initialScanCompleted
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
                // Update discovered host/port in store if changed
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
        BackupScheduleManager.enqueueImmediateSync(
            context = appContext,
            isScheduledMorningRun = false,
            forceSixMonthScan = forceSixMonths
        )
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

    fun setOrganizationCustomMissingDays(orgId: Long, days: Int?) {
        viewModelScope.launch {
            repository.setOrganizationCustomMissingDays(orgId, days)
        }
    }

    fun acknowledgeOrganization(orgId: Long) {
        viewModelScope.launch {
            repository.acknowledgeOrganization(orgId)
            snackbarFlow.value = "Статус ошибки подтверждён."
        }
    }

    fun addManualOrganization(name: String, senderEmail: String, customDays: Int?) {
        viewModelScope.launch {
            repository.addManualOrganization(name, senderEmail, customDays)
            snackbarFlow.value = "Организация добавлена в список мониторинга."
        }
    }

    fun deleteOrganization(orgId: Long) {
        selectedOrgIdFlow.value = null
        viewModelScope.launch {
            repository.deleteOrganization(orgId)
            snackbarFlow.value = "Организация удалена из списка."
        }
    }

    fun resetDatabase() {
        selectedOrgIdFlow.value = null
        viewModelScope.launch {
            repository.resetDatabaseAndInitialScan()
            snackbarFlow.value = "База очищена. При следующей синхронизации выполнится сбор за 6 месяцев."
        }
    }

    private fun filterOrganizations(
        all: List<OrganizationSummary>,
        filter: DashboardFilter,
        query: String
    ): List<OrganizationSummary> {
        val byFilter = when (filter) {
            DashboardFilter.ALL -> all.filter { it.isMonitored }
            DashboardFilter.PROBLEMS -> all.filter { it.isMonitored && (it.hasErrorAlert || it.isMissingMail) }
            DashboardFilter.ERRORS -> all.filter { it.isMonitored && it.hasErrorAlert }
            DashboardFilter.MISSING -> all.filter { it.isMonitored && it.isMissingMail }
            DashboardFilter.HEALTHY -> all.filter { it.isMonitored && it.health == OrganizationHealth.HEALTHY }
            DashboardFilter.IGNORED -> all.filter { !it.isMonitored }
        }
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return byFilter
        return byFilter.filter { org ->
            org.displayName.lowercase().contains(trimmed) ||
                org.senderEmail.lowercase().contains(trimmed) ||
                org.lastSubject.lowercase().contains(trimmed)
        }
    }
}
