package by.csl.backupcontrol.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import by.csl.backupcontrol.ui.AppTab
import by.csl.backupcontrol.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupControlRootScreen(
    viewModel: MainViewModel,
    onRequestNotificationPermission: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedOrgId by viewModel.selectedOrgId.collectAsStateWithLifecycle()
    val selectedOrgEmails by viewModel.selectedOrgEmails.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showAddOrgDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val selectedOrg = remember(uiState.allOrganizations, selectedOrgId) {
        selectedOrgId?.let { id -> uiState.allOrganizations.find { it.id == id } }
    }

    if (selectedOrg != null) {
        OrganizationDetailSheet(
            org = selectedOrg,
            defaultMissingDaysThreshold = uiState.settings.missingDaysThreshold,
            emails = selectedOrgEmails,
            onDismiss = viewModel::closeOrganizationDetail,
            onRename = { newName -> viewModel.renameOrganization(selectedOrg.id, newName) },
            onSetMonitored = { monitored -> viewModel.setOrganizationMonitored(selectedOrg.id, monitored) },
            onSetCustomMissingDays = { days -> viewModel.setOrganizationCustomMissingDays(selectedOrg.id, days) },
            onAcknowledgeError = { viewModel.acknowledgeOrganization(selectedOrg.id) },
            onDelete = { viewModel.deleteOrganization(selectedOrg.id) }
        )
    }

    if (showAddOrgDialog) {
        AddOrganizationDialog(
            defaultMissingDays = uiState.settings.missingDaysThreshold,
            onDismiss = { showAddOrgDialog = false },
            onAdd = { name, email, days ->
                viewModel.addManualOrganization(name, email, days)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Backup Control",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = uiState.selectedTab == AppTab.DASHBOARD,
                    onClick = { viewModel.selectTab(AppTab.DASHBOARD) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (uiState.report.totalAffectedCount > 0) {
                                    Badge {
                                        Text(uiState.report.totalAffectedCount.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Dashboard, contentDescription = null)
                        }
                    },
                    label = { Text(AppTab.DASHBOARD.titleRu) }
                )
                NavigationBarItem(
                    selected = uiState.selectedTab == AppTab.HISTORY,
                    onClick = { viewModel.selectTab(AppTab.HISTORY) },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text(AppTab.HISTORY.titleRu) }
                )
                NavigationBarItem(
                    selected = uiState.selectedTab == AppTab.SETTINGS,
                    onClick = { viewModel.selectTab(AppTab.SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(AppTab.SETTINGS.titleRu) }
                )
            }
        }
    ) { innerPadding ->
        when (uiState.selectedTab) {
            AppTab.DASHBOARD -> DashboardTab(
                uiState = uiState,
                onSaveInitialSetupAndScan = { draft ->
                    onRequestNotificationPermission()
                    viewModel.saveSettings(draft, runSyncNow = true, forceSixMonths = true)
                },
                onTestConnection = viewModel::testImapConnection,
                onManualSyncClick = { viewModel.triggerManualSync(forceSixMonths = false) },
                onSelectFilter = viewModel::selectFilter,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onOrganizationClick = viewModel::openOrganizationDetail,
                onAddOrganizationClick = { showAddOrgDialog = true },
                onAcknowledgeAllClick = viewModel::acknowledgeAllErrors,
                onDismissBannerClick = viewModel::dismissBanner,
                onOpenSettingsClick = { viewModel.selectTab(AppTab.SETTINGS) },
                modifier = Modifier.padding(innerPadding)
            )
            AppTab.HISTORY -> SyncHistoryTab(
                logs = uiState.syncLogs,
                modifier = Modifier.padding(innerPadding)
            )
            AppTab.SETTINGS -> SettingsTab(
                currentSettings = uiState.settings,
                nextScheduledText = uiState.nextScheduledText,
                connectionTestState = uiState.connectionTestState,
                onTestConnection = viewModel::testImapConnection,
                onSaveSettings = { draft ->
                    onRequestNotificationPermission()
                    viewModel.saveSettings(draft, runSyncNow = false)
                },
                onRunSixMonthRescan = { draft ->
                    onRequestNotificationPermission()
                    viewModel.saveSettings(draft, runSyncNow = true, forceSixMonths = true)
                },
                onTestMorningBanner = {
                    onRequestNotificationPermission()
                    viewModel.triggerTestMorningBanner()
                },
                onRequestNotificationPermission = onRequestNotificationPermission,
                onResetDatabase = viewModel::resetDatabase,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
