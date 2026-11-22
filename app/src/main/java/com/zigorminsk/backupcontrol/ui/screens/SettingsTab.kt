package com.zigorminsk.backupcontrol.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zigorminsk.backupcontrol.BuildConfig
import com.zigorminsk.backupcontrol.domain.model.AppSettings
import com.zigorminsk.backupcontrol.domain.model.MailSecurityMode
import com.zigorminsk.backupcontrol.ui.ConnectionTestState
import com.zigorminsk.backupcontrol.ui.Formatters
import com.zigorminsk.backupcontrol.update.AppUpdateUiState
import com.zigorminsk.backupcontrol.worker.BackupScheduleManager
import com.zigorminsk.backupcontrol.worker.MorningBannerNotifier
import java.time.DayOfWeek

@SuppressLint("BatteryLife")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTab(
    currentSettings: AppSettings,
    nextScheduledText: String,
    connectionTestState: ConnectionTestState,
    appUpdateState: AppUpdateUiState,
    onTestConnection: (AppSettings) -> Unit,
    onSaveSettings: (AppSettings) -> Unit,
    onRunSixMonthRescan: (AppSettings) -> Unit,
    onTestMorningBanner: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onCheckAppUpdates: () -> Unit,
    onInstallAppUpdate: () -> Unit,
    onResetDatabase: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var email by rememberSaveable(currentSettings.email) { mutableStateOf(currentSettings.email) }
    var password by rememberSaveable(currentSettings.password) { mutableStateOf(currentSettings.password) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var imapHost by rememberSaveable(currentSettings.imapHost) { mutableStateOf(currentSettings.imapHost) }
    var imapPortText by rememberSaveable(currentSettings.imapPort) {
        mutableStateOf(currentSettings.imapPort.toString())
    }
    var securityMode by remember(currentSettings.securityMode) { mutableStateOf(currentSettings.securityMode) }
    var trustSelfSigned by rememberSaveable(currentSettings.trustSelfSignedCerts) {
        mutableStateOf(currentSettings.trustSelfSignedCerts)
    }
    var autoDetectHost by rememberSaveable(currentSettings.autoDetectImapHost) {
        mutableStateOf(currentSettings.autoDetectImapHost)
    }
    var folderName by rememberSaveable(currentSettings.folderName) { mutableStateOf(currentSettings.folderName) }

    var scheduleEnabled by rememberSaveable(currentSettings.scheduleEnabled) {
        mutableStateOf(currentSettings.scheduleEnabled)
    }
    var hourText by rememberSaveable(currentSettings.scheduleHour) {
        mutableStateOf(String.format("%02d", currentSettings.scheduleHour))
    }
    var minuteText by rememberSaveable(currentSettings.scheduleMinute) {
        mutableStateOf(String.format("%02d", currentSettings.scheduleMinute))
    }
    var scheduledDays by remember(currentSettings.scheduledDays) {
        mutableStateOf(currentSettings.scheduledDays)
    }

    var missingDaysText by rememberSaveable(currentSettings.missingDaysThreshold) {
        mutableStateOf(currentSettings.missingDaysThreshold.toString())
    }
    var initialMonthsText by rememberSaveable(currentSettings.initialLookbackMonths) {
        mutableStateOf(currentSettings.initialLookbackMonths.toString())
    }
    var autoAddNewOrgs by rememberSaveable(currentSettings.autoAddNewOrganizations) {
        mutableStateOf(currentSettings.autoAddNewOrganizations)
    }
    var skipWeekends by rememberSaveable(currentSettings.skipWeekendsInSilenceCount) {
        mutableStateOf(currentSettings.skipWeekendsInSilenceCount)
    }
    var treatWarningAsError by rememberSaveable(currentSettings.treatWarningAsError) {
        mutableStateOf(currentSettings.treatWarningAsError)
    }
    var notifyWhenAllHealthy by rememberSaveable(currentSettings.notifyWhenAllHealthy) {
        mutableStateOf(currentSettings.notifyWhenAllHealthy)
    }
    var customErrorKeywords by rememberSaveable(currentSettings.customErrorKeywords) {
        mutableStateOf(currentSettings.customErrorKeywords)
    }
    var customSuccessKeywords by rememberSaveable(currentSettings.customSuccessKeywords) {
        mutableStateOf(currentSettings.customSuccessKeywords)
    }

    var showResetConfirm by remember { mutableStateOf(false) }

    fun buildDraft(): AppSettings {
        return currentSettings.copy(
            email = email.trim(),
            password = password,
            imapHost = imapHost.trim().ifBlank { AppSettings.DEFAULT_IMAP_HOST },
            imapPort = imapPortText.toIntOrNull() ?: securityMode.defaultPort,
            securityMode = securityMode,
            trustSelfSignedCerts = trustSelfSigned,
            autoDetectImapHost = autoDetectHost,
            folderName = folderName.trim().ifBlank { "INBOX" },
            scheduleEnabled = scheduleEnabled,
            scheduleHour = (hourText.toIntOrNull() ?: 9).coerceIn(0, 23),
            scheduleMinute = (minuteText.toIntOrNull() ?: 20).coerceIn(0, 59),
            scheduledDays = scheduledDays,
            missingDaysThreshold = (missingDaysText.toIntOrNull() ?: 3).coerceIn(1, 60),
            initialLookbackMonths = (initialMonthsText.toIntOrNull() ?: 6).coerceIn(1, 24),
            autoAddNewOrganizations = autoAddNewOrgs,
            skipWeekendsInSilenceCount = skipWeekends,
            treatWarningAsError = treatWarningAsError,
            notifyWhenAllHealthy = notifyWhenAllHealthy,
            customErrorKeywords = customErrorKeywords,
            customSuccessKeywords = customSuccessKeywords
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Очистить базу мониторинга?") },
            text = {
                Text("Список сохранённых и исключённых организаций и журнал писем будут очищены. При следующей синхронизации снова выполнится первичный сбор за 6 месяцев с возможностью редактирования списка.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        onResetDatabase()
                    }
                ) {
                    Text("Очистить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section 0: App Update (GitHub Releases)
        item {
            SettingsSectionCard(title = "Обновление приложения") {
                Text(
                    text = "Текущая версия: ${BuildConfig.VERSION_NAME} (код ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = appUpdateState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (appUpdateState.readyVersionName != null) {
                    Button(
                        onClick = onInstallAppUpdate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Установить обновление ${appUpdateState.readyVersionName}")
                    }
                } else {
                    OutlinedButton(
                        onClick = onCheckAppUpdates,
                        enabled = !appUpdateState.isChecking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (appUpdateState.isChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Проверить обновления на GitHub")
                    }
                }
            }
        }

        // Section 1: IMAP Connection
        item {
            SettingsSectionCard(title = "1. Подключение к почте (IMAP)") {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Почта (логин)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
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
                        onValueChange = { imapPortText = it.filter(Char::isDigit).take(5) },
                        label = { Text("Порт") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MailSecurityMode.entries.forEach { mode ->
                        FilterChip(
                            selected = securityMode == mode,
                            onClick = {
                                securityMode = mode
                                imapPortText = mode.defaultPort.toString()
                            },
                            label = { Text(mode.titleRu) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Папка для мониторинга") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                SettingSwitchRow(
                    title = "Доверять самоподписанным SSL-сертификатам",
                    subtitle = "Рекомендуется для корпоративных почтовых серверов",
                    checked = trustSelfSigned,
                    onCheckedChange = { trustSelfSigned = it }
                )

                SettingSwitchRow(
                    title = "Автопоиск IMAP-сервера",
                    subtitle = "Пробует mail/imap/hoster/yandex, если основной адрес недоступен",
                    checked = autoDetectHost,
                    onCheckedChange = { autoDetectHost = it }
                )

                when (connectionTestState) {
                    is ConnectionTestState.Testing -> {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Проверка соединения…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is ConnectionTestState.Success -> {
                        Text(
                            text = "✅ ${connectionTestState.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    is ConnectionTestState.Error -> {
                        Text(
                            text = "❌ ${connectionTestState.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    ConnectionTestState.Idle -> Unit
                }

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { onTestConnection(buildDraft()) },
                    enabled = connectionTestState !is ConnectionTestState.Testing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Проверить подключение к почте")
                }
            }
        }

        // Section 2: Schedule (09:20 Mon–Fri)
        item {
            SettingsSectionCard(title = "2. Расписание утренней проверки") {
                SettingSwitchRow(
                    title = "Автоматический сбор по расписанию",
                    subtitle = nextScheduledText,
                    checked = scheduleEnabled,
                    onCheckedChange = { scheduleEnabled = it }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Часы (0–23)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Text(":", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { minuteText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Минуты (0–59)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("Дни недели (по умолчанию Пн–Пт):", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        val selected = day in scheduledDays
                        FilterChip(
                            selected = selected,
                            onClick = {
                                scheduledDays = if (selected) scheduledDays - day else scheduledDays + day
                            },
                            label = { Text(Formatters.dayShortRu(day)) }
                        )
                    }
                }
            }
        }

        // Section 3: Organization control & alert rules
        item {
            SettingsSectionCard(title = "3. Правила контроля организаций и баннера") {
                SettingSwitchRow(
                    title = "Добавлять новые организации при их появлении",
                    subtitle = "После сохранения списка за 6 мес. контролируются только сохранённые организации, а новые отправители автоматически добавляются в контроль со значком «НОВАЯ»",
                    checked = autoAddNewOrgs,
                    onCheckedChange = { autoAddNewOrgs = it }
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = missingDaysText,
                    onValueChange = { missingDaysText = it.filter(Char::isDigit).take(2) },
                    label = { Text("Порог «несколько дней нет почты» (дней)") },
                    supportingText = {
                        Text("Если от сохранённой организации нет писем указанное число дней и более — она попадёт в утренний баннер")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                SettingSwitchRow(
                    title = "Не учитывать выходные (Сб–Вс) в днях молчания",
                    subtitle = "Считать только рабочие дни Пн–Пт между письмами",
                    checked = skipWeekends,
                    onCheckedChange = { skipWeekends = it }
                )

                SettingSwitchRow(
                    title = "Считать статус Warning (предупреждение) ошибкой",
                    subtitle = "Включать отчёты с предупреждениями в утренний баннер",
                    checked = treatWarningAsError,
                    onCheckedChange = { treatWarningAsError = it }
                )

                SettingSwitchRow(
                    title = "Тихое уведомление, если ошибок нет",
                    subtitle = "По умолчанию выключено — баннер появляется только при ошибках или отсутствии почты",
                    checked = notifyWhenAllHealthy,
                    onCheckedChange = { notifyWhenAllHealthy = it }
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customErrorKeywords,
                    onValueChange = { customErrorKeywords = it },
                    label = { Text("Доп. ключевые слова ошибки (через запятую)") },
                    placeholder = { Text("например: КРИТИЧНО, архив повреждён") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customSuccessKeywords,
                    onValueChange = { customSuccessKeywords = it },
                    label = { Text("Доп. ключевые слова успеха (через запятую)") },
                    placeholder = { Text("например: копия готова, job ok") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onTestMorningBanner,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Тест утреннего баннера уведомлений")
                }
            }
        }

        // Save primary button
        item {
            Button(
                onClick = { onSaveSettings(buildDraft()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Сохранить все настройки", fontWeight = FontWeight.Bold)
            }
        }

        // Section 4: Android Permissions for reliable 09:20 background wake-up
        item {
            val notificationsOk = MorningBannerNotifier.canPostNotifications(context)
            val exactAlarmsOk = BackupScheduleManager.canScheduleExactAlarms(context)
            val powerManager = context.getSystemService(PowerManager::class.java)
            val batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

            SettingsSectionCard(title = "4. Разрешения Android для работы в 09:20") {
                PermissionRow(
                    title = "Всплывающие уведомления (баннер)",
                    isGranted = notificationsOk,
                    actionLabel = "Разрешить",
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsOk) {
                            onRequestNotificationPermission()
                        } else {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            runCatching { context.startActivity(intent) }
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                PermissionRow(
                    title = "Точный будильник в 09:20",
                    isGranted = exactAlarmsOk,
                    actionLabel = "Включить",
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            runCatching { context.startActivity(intent) }
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                PermissionRow(
                    title = "Работа в фоне без экономии заряда",
                    isGranted = batteryUnrestricted,
                    actionLabel = "Отключить экономию",
                    onClick = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        runCatching { context.startActivity(intent) }
                    }
                )
            }
        }

        // Section 5: 6-month scan & database maintenance
        item {
            SettingsSectionCard(title = "5. Первичный сбор и обслуживание базы") {
                OutlinedTextField(
                    value = initialMonthsText,
                    onValueChange = { initialMonthsText = it.filter(Char::isDigit).take(2) },
                    label = { Text("Глубина первичного мониторинга (месяцев)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { onRunSixMonthRescan(buildDraft()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Повторить сбор почты за ${initialMonthsText.ifBlank { "6" }} мес.")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Очистить список организаций и журнал")
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    isGranted: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = if (isGranted) "✅ Активно" else "⚠️ Рекомендуется включить",
                style = MaterialTheme.typography.bodySmall,
                color = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        if (!isGranted) {
            TextButton(onClick = onClick) {
                Text(actionLabel)
            }
        }
    }
}
