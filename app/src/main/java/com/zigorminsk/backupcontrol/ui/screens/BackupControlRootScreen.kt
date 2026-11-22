package com.zigorminsk.backupcontrol.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
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
import com.zigorminsk.backupcontrol.ui.AppTab
import com.zigorminsk.backupcontrol.ui.MainViewModel

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
            allOrganizations = uiState.allOrganizations,
            defaultMissingDaysThreshold = uiState.settings.missingDaysThreshold,
            emails = selectedOrgEmails,
            onDismiss = viewModel::closeOrganizationDetail,
            onSaveDetails = { displayName, senderEmail, additionalEmails, notes, customDays, isMonitored ->
                viewModel.updateOrganizationDetails(
                    orgId = selectedOrg.id,
                    displayName = displayName,
                    senderEmail = senderEmail,
                    additionalSenderEmails = additionalEmails,
                    notes = notes,
                    customMissingDays = customDays,
                    isMonitored = isMonitored
                )
            },
            onMergeIntoTarget = { targetId ->
                viewModel.mergeOrganizations(targetOrgId = targetId, sourceOrgId = selectedOrg.id)
            },
            onAcknowledgeError = { viewModel.acknowledgeOrganization(selectedOrg.id) },
            onExclude = { viewModel.excludeOrganization(selectedOrg.id) },
            onRestoreToSaved = { viewModel.restoreOrganizationToSaved(selectedOrg.id) },
            onClearNewBadge = { viewModel.clearNewlyAddedFlag(selectedOrg.id) }
        )
    }

    if (showAddOrgDialog) {
        AddOrganizationDialog(
            defaultMissingDays = uiState.settings.missingDaysThreshold,
            onDismiss = { showAddOrgDialog = false },
            onAdd = { name, email, additionalEmails, notes, days ->
                viewModel.addManualOrganization(
                    name = name,
                    senderEmail = email,
                    additionalEmails = additionalEmails,
                    notes = notes,
                    customDays = days
                )
            }
        )
    }

    val orgBadgeCount = uiState.pendingReviewOrganizations.size + uiState.newlyAddedOrganizations.size

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
                                if (uiState.settings.baselineSaved && uiState.report.totalAffectedCount > 0) {
                                    Badge { Text(uiState.report.totalAffectedCount.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Dashboard, contentDescription = null)
                        }
                    },
                    label = { Text(AppTab.DASHBOARD.titleRu) }
                )
                NavigationBarItem(
                    selected = uiState.selectedTab == AppTab.ORGANIZATIONS,
                    onClick = { viewModel.selectTab(AppTab.ORGANIZATIONS) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (orgBadgeCount > 0) {
                                    Badge { Text(orgBadgeCount.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Business, contentDescription = null)
                        }
                    },
                    label = { Text(AppTab.ORGANIZATIONS.titleRu) }
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
                    icon = {
                        BadgedBox(
                            badge = {
                                if (uiState.appUpdateState.readyVersionName != null) {
                                    Badge { Text("1") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                        }
                    },
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
                onOpenOrgReviewClick = viewModel::openOrganizationsTabForReview,
                onClearAllNewBadgesClick = viewModel::clearAllNewlyAddedFlags,
                onSelectFilter = viewModel::selectFilter,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onOrganizationClick = viewModel::openOrganizationDetail,
                onAddOrganizationClick = { showAddOrgDialog = true },
                onAcknowledgeAllClick = viewModel::acknowledgeAllErrors,
                onDismissBannerClick = viewModel::dismissBanner,
                onOpenSettingsClick = { viewModel.selectTab(AppTab.SETTINGS) },
                modifier = Modifier.padding(innerPadding)
            )
            AppTab.ORGANIZATIONS -> OrganizationsTab(
                uiState = uiState,
                onSearchQueryChange = viewModel::updateOrgSearchQuery,
                onSelectGroupFilter = viewModel::selectOrgGroupFilter,
                onSetBatchEditMode = viewModel::setBatchEditMode,
                onSaveReviewedSelection = viewModel::saveReviewedOrganizations,
                onOrganizationEditClick = viewModel::openOrganizationDetail,
                onAddOrganizationClick = { showAddOrgDialog = true },
                onRestoreExcludedClick = viewModel::restoreOrganizationToSaved,
                onPermanentlyDeleteClick = viewModel::permanentlyDeleteOrganization,
                onClearAllNewBadgesClick = viewModel::clearAllNewlyAddedFlags,
                onRunSixMonthScanClick = { viewModel.triggerManualSync(forceSixMonths = true) },
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
                appUpdateState = uiState.appUpdateState,
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
                onCheckAppUpdates = viewModel::checkAppUpdates,
                onInstallAppUpdate = viewModel::installAppUpdate,
                onResetDatabase = viewModel::resetDatabase,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
