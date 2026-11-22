package by.csl.backupcontrol.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import by.csl.backupcontrol.domain.model.AppSettings
import by.csl.backupcontrol.domain.model.DashboardFilter
import by.csl.backupcontrol.domain.model.MailSecurityMode
import by.csl.backupcontrol.ui.ConnectionTestState
import by.csl.backupcontrol.ui.Formatters
import by.csl.backupcontrol.ui.MainUiState
import by.csl.backupcontrol.ui.components.HealthySummaryStrip
import by.csl.backupcontrol.ui.components.MorningAlertBannerCard
import by.csl.backupcontrol.ui.components.OrganizationItemCard
import by.csl.backupcontrol.ui.components.SummaryFilterRow
import by.csl.backupcontrol.ui.components.SyncProgressCard

@Composable
fun DashboardTab(
    uiState: MainUiState,
    onSaveInitialSetupAndScan: (AppSettings) -> Unit,
    onTestConnection: (AppSettings) -> Unit,
    onManualSyncClick: () -> Unit,
    onSelectFilter: (DashboardFilter) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onOrganizationClick: (Long) -> Unit,
    onAddOrganizationClick: () -> Unit,
    onAcknowledgeAllClick: () -> Unit,
    onDismissBannerClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = uiState.settings

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!settings.isConfigured) {
            item(key = "quick_setup") {
                QuickOnboardingSetupCard(
                    initialSettings = settings,
                    connectionTestState = uiState.connectionTestState,
                    onTestConnection = onTestConnection,
                    onSaveAndRunSixMonths = onSaveInitialSetupAndScan
                )
            }
        } else {
            item(key = "schedule_bar") {
                ScheduleStatusHeader(
                    settings = settings,
                    nextScheduledText = uiState.nextScheduledText,
                    isSyncRunning = uiState.syncProgress.isRunning,
                    onSyncNowClick = onManualSyncClick,
                    onAddOrgClick = onAddOrganizationClick
                )
            }
        }

        if (uiState.syncProgress.isRunning || !uiState.syncProgress.lastError.isNullOrBlank()) {
            item(key = "sync_progress") {
                SyncProgressCard(
                    progress = uiState.syncProgress,
                    onRetryClick = onManualSyncClick,
                    onOpenSettingsClick = onOpenSettingsClick
                )
            }
        }

        if (uiState.showMorningBannerInApp && uiState.report.hasIssues) {
            item(key = "morning_banner") {
                MorningAlertBannerCard(
                    report = uiState.report,
                    lastSyncAtMillis = settings.lastSyncAtMillis,
                    onOrganizationClick = onOrganizationClick,
                    onFilterProblemsClick = { onSelectFilter(DashboardFilter.PROBLEMS) },
                    onAcknowledgeAllClick = onAcknowledgeAllClick,
                    onDismissClick = onDismissBannerClick
                )
            }
        } else if (settings.initialScanCompleted && !uiState.report.hasIssues && uiState.allOrganizations.isNotEmpty()) {
            item(key = "healthy_strip") {
                HealthySummaryStrip(
                    monitoredCount = uiState.allOrganizations.count { it.isMonitored },
                    lastSyncAtMillis = settings.lastSyncAtMillis
                )
            }
        }

        if (settings.isConfigured || uiState.allOrganizations.isNotEmpty()) {
            item(key = "filters_and_search") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryFilterRow(
                        allOrganizations = uiState.allOrganizations,
                        selectedFilter = uiState.selectedFilter,
                        onSelectFilter = onSelectFilter
                    )
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        placeholder = { Text("Поиск по организации, email или теме…") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChange("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Очистить")
                                }
                            }
                        }
                    )
                }
            }

            if (uiState.filteredOrganizations.isEmpty()) {
                item(key = "empty_state") {
                    EmptyOrganizationsCard(
                        hasAnyOrganizations = uiState.allOrganizations.isNotEmpty(),
                        isSyncRunning = uiState.syncProgress.isRunning,
                        initialScanCompleted = settings.initialScanCompleted,
                        onRunSixMonthScan = onManualSyncClick
                    )
                }
            } else {
                items(
                    items = uiState.filteredOrganizations,
                    key = { it.id }
                ) { org ->
                    OrganizationItemCard(
                        org = org,
                        onClick = { onOrganizationClick(org.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickOnboardingSetupCard(
    initialSettings: AppSettings,
    connectionTestState: ConnectionTestState,
    onTestConnection: (AppSettings) -> Unit,
    onSaveAndRunSixMonths: (AppSettings) -> Unit
) {
    var email by rememberSaveable { mutableStateOf(initialSettings.email) }
    var password by rememberSaveable { mutableStateOf(initialSettings.password) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    var imapHost by rememberSaveable { mutableStateOf(initialSettings.imapHost) }
    var imapPortText by rememberSaveable { mutableStateOf(initialSettings.imapPort.toString()) }
    var securityMode by remember { mutableStateOf(initialSettings.securityMode) }
    var trustSelfSigned by rememberSaveable { mutableStateOf(initialSettings.trustSelfSignedCerts) }
    var autoDetectHost by rememberSaveable { mutableStateOf(initialSettings.autoDetectImapHost) }

    fun buildCandidate(): AppSettings {
        val port = imapPortText.toIntOrNull() ?: securityMode.defaultPort
        return initialSettings.copy(
            email = email.trim(),
            password = password,
            imapHost = imapHost.trim().ifBlank { AppSettings.DEFAULT_IMAP_HOST },
            imapPort = port,
            securityMode = securityMode,
            trustSelfSignedCerts = trustSelfSigned,
            autoDetectImapHost = autoDetectHost
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.MailOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Подключение к почте бэкапов",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Первый запуск соберёт организации за 6 месяцев. Далее проверка выполняется Пн–Пт в 09:20.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { newEmail ->
                    email = newEmail
                    val domain = newEmail.substringAfter('@', "").trim()
                    if (domain.isNotBlank() && imapHost == AppSettings.DEFAULT_IMAP_HOST && domain != "csl.by") {
                        imapHost = "mail.$domain"
                    }
                },
                label = { Text("Почта (логин)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Пароль от ${email.ifBlank { "backup@csl.by" }}") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Показать пароль"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(
                    imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Параметры IMAP-сервера ($imapHost:${imapPortText})")
            }

            AnimatedVisibility(visible = showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = imapHost,
                            onValueChange = { imapHost = it },
                            label = { Text("IMAP-сервер") },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = imapPortText,
                            onValueChange = { imapPortText = it.filter(Char::isDigit) },
                            label = { Text("Порт") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MailSecurityMode.entries.forEach { mode ->
                            FilterChip(
                                selected = securityMode == mode,
                                onClick = {
                                    securityMode = mode
                                    imapPortText = mode.defaultPort.toString()
                                },
                                label = { Text(mode.titleRu, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = trustSelfSigned,
                            onCheckedChange = { trustSelfSigned = it }
                        )
                        Text(
                            text = "Доверять самоподписанным SSL-сертификатам",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = autoDetectHost,
                            onCheckedChange = { autoDetectHost = it }
                        )
                        Text(
                            text = "Автопоиск IMAP-сервера при ошибке адреса",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            when (connectionTestState) {
                is ConnectionTestState.Testing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 6.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Проверка подключения к почте…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is ConnectionTestState.Success -> {
                    Text(
                        text = "✅ ${connectionTestState.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                is ConnectionTestState.Error -> {
                    Text(
                        text = "❌ ${connectionTestState.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                ConnectionTestState.Idle -> Unit
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = { onTestConnection(buildCandidate()) },
                    enabled = connectionTestState !is ConnectionTestState.Testing,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Проверить вход")
                }
                Button(
                    onClick = { onSaveAndRunSixMonths(buildCandidate()) },
                    enabled = email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text("Собрать за 6 мес.")
                }
            }
        }
    }
}

@Composable
private fun ScheduleStatusHeader(
    settings: AppSettings,
    nextScheduledText: String,
    isSyncRunning: Boolean,
    onSyncNowClick: () -> Unit,
    onAddOrgClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = settings.email,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = String.format(
                                "Проверка %s в %02d:%02d • %s",
                                Formatters.formatScheduledDaysSummary(settings.scheduledDays),
                                settings.scheduleHour,
                                settings.scheduleMinute,
                                nextScheduledText
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onSyncNowClick,
                    enabled = !isSyncRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (settings.initialScanCompleted) "Проверить сейчас" else "Собрать за 6 мес.")
                }
                OutlinedButton(onClick = onAddOrgClick) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Клиент")
                }
            }
        }
    }
}

@Composable
private fun EmptyOrganizationsCard(
    hasAnyOrganizations: Boolean,
    isSyncRunning: Boolean,
    initialScanCompleted: Boolean,
    onRunSixMonthScan: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                text = when {
                    isSyncRunning -> "Идёт чтение писем и формирование списка организаций…"
                    hasAnyOrganizations -> "По выбранному фильтру организаций нет"
                    !initialScanCompleted -> "Список организаций ещё не собран"
                    else -> "В почтовом ящике не найдено писем за последние 6 месяцев"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (!hasAnyOrganizations && !isSyncRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Нажмите кнопку ниже, чтобы просканировать почту за 6 месяцев и автоматически собрать все организации.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onRunSixMonthScan) {
                    Text("Запустить сбор за 6 месяцев")
                }
            }
        }
    }
}
