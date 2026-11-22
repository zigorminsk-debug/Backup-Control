package com.zigorminsk.backupcontrol.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zigorminsk.backupcontrol.data.local.entity.BackupEmailEntity
import com.zigorminsk.backupcontrol.domain.model.BackupStatus
import com.zigorminsk.backupcontrol.domain.model.OrganizationApprovalState
import com.zigorminsk.backupcontrol.domain.model.OrganizationSummary
import com.zigorminsk.backupcontrol.ui.Formatters
import com.zigorminsk.backupcontrol.ui.theme.StatusErrorRed
import com.zigorminsk.backupcontrol.ui.theme.StatusHealthyGreen
import com.zigorminsk.backupcontrol.ui.theme.StatusWarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizationDetailSheet(
    org: OrganizationSummary,
    allOrganizations: List<OrganizationSummary>,
    defaultMissingDaysThreshold: Int,
    emails: List<BackupEmailEntity>,
    onDismiss: () -> Unit,
    onSaveDetails: (
        displayName: String,
        senderEmail: String,
        additionalSenderEmails: String,
        notes: String,
        customMissingDays: Int?,
        isMonitored: Boolean
    ) -> Unit,
    onMergeIntoTarget: (targetOrgId: Long) -> Unit,
    onAcknowledgeError: () -> Unit,
    onExclude: () -> Unit,
    onRestoreToSaved: () -> Unit,
    onClearNewBadge: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var nameInput by rememberSaveable(org.id) { mutableStateOf(org.displayName) }
    var primaryEmailInput by rememberSaveable(org.id) { mutableStateOf(org.senderEmail) }
    var additionalEmailsInput by rememberSaveable(org.id) { mutableStateOf(org.additionalSenderEmails) }
    var notesInput by rememberSaveable(org.id) { mutableStateOf(org.notes) }
    var customDaysInput by rememberSaveable(org.id) {
        mutableStateOf(org.customMissingDays?.toString().orEmpty())
    }
    var monitoredInput by rememberSaveable(org.id, org.isMonitored) { mutableStateOf(org.isMonitored) }

    var showExcludeConfirm by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(org.id) {
        if (org.isNewlyAdded) {
            onClearNewBadge()
        }
    }

    if (showExcludeConfirm) {
        AlertDialog(
            onDismissRequest = { showExcludeConfirm = false },
            title = { Text("Исключить организацию из контроля?") },
            text = {
                Text(
                    "Организация «${org.displayName}» будет перенесена в «Исключённые» и больше не будет контролироваться или заново добавляться при чтении почты. Вы всегда сможете вернуть её обратно на вкладке «Организации»."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExcludeConfirm = false
                        onExclude()
                    }
                ) {
                    Text("Исключить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExcludeConfirm = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (showMergeDialog) {
        val mergeCandidates = remember(allOrganizations, org.id) {
            allOrganizations
                .filter { it.id != org.id && it.approvalState != OrganizationApprovalState.EXCLUDED }
                .sortedBy { it.displayName.lowercase() }
        }
        AlertDialog(
            onDismissRequest = { showMergeDialog = false },
            title = { Text("Объединить с другой организацией") },
            text = {
                if (mergeCandidates.isEmpty()) {
                    Text("Нет других организаций для объединения.")
                } else {
                    Column {
                        Text(
                            text = "Выберите организацию, к которой нужно присоединить адрес «${org.senderEmail}»:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(mergeCandidates, key = { it.id }) { candidate ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showMergeDialog = false
                                            onMergeIntoTarget(candidate.id)
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = candidate.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (candidate.allEmailsFormatted.isNotBlank()) {
                                            Text(
                                                text = candidate.allEmailsFormatted,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMergeDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Редактирование организации",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Статус: ${org.approvalState.labelRu} • Писем в истории: ${org.emailCount} • Первое: ${Formatters.formatTimestamp(org.firstSeenAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Название организации") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = primaryEmailInput,
                    onValueChange = { primaryEmailInput = it },
                    label = { Text("Основной Email отправителя (From)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = additionalEmailsInput,
                    onValueChange = { additionalEmailsInput = it },
                    label = { Text("Доп. адреса отправителя (через запятую)") },
                    placeholder = { Text("sql@firma.by, veeam@firma.by") },
                    supportingText = {
                        Text("Письма со всех этих адресов учитываются для этой организации")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = customDaysInput,
                        onValueChange = { raw -> customDaysInput = raw.filter(Char::isDigit).take(2) },
                        label = { Text("Порог дней (умолч. $defaultMissingDaysThreshold)") },
                        placeholder = { Text("$defaultMissingDaysThreshold") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = notesInput,
                        onValueChange = { notesInput = it },
                        label = { Text("Заметка / IP / сервер") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f)
                    )
                }
            }

            if (org.approvalState != OrganizationApprovalState.EXCLUDED) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Контролировать организацию",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Участвует в ежедневной проверке ошибок и отсутствия почты",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = monitoredInput,
                            onCheckedChange = { monitoredInput = it }
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        onSaveDetails(
                            nameInput,
                            primaryEmailInput,
                            additionalEmailsInput,
                            notesInput,
                            customDaysInput.toIntOrNull(),
                            monitoredInput
                        )
                        onDismiss()
                    },
                    enabled = nameInput.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Сохранить изменения организации", fontWeight = FontWeight.Bold)
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { showMergeDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Объединить…")
                    }

                    if (org.approvalState == OrganizationApprovalState.EXCLUDED) {
                        OutlinedButton(
                            onClick = {
                                onRestoreToSaved()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("В контроль")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { showExcludeConfirm = true },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Исключить")
                        }
                    }
                }
            }

            if (org.hasErrorAlert) {
                item {
                    OutlinedButton(
                        onClick = onAcknowledgeError,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Отметить текущую ошибку просмотренной")
                    }
                }
            }

            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Последние письма (${emails.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (emails.isEmpty()) {
                item {
                    Text(
                        text = "Сохранённых писем для этой организации пока нет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(emails, key = { it.id }) { mail ->
                    EmailHistoryRow(mail)
                }
            }

            item {
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun EmailHistoryRow(mail: BackupEmailEntity) {
    val (color, icon) = when (mail.status) {
        BackupStatus.SUCCESS -> StatusHealthyGreen to Icons.Default.CheckCircle
        BackupStatus.WARNING -> StatusWarningAmber to Icons.Default.Warning
        BackupStatus.ERROR -> StatusErrorRed to Icons.Default.Error
        BackupStatus.UNKNOWN -> Color.Gray to Icons.Default.HelpOutline
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = mail.status.labelRu,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
                Text(
                    text = Formatters.formatTimestamp(mail.receivedAtMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (mail.subject.isNotBlank()) {
                Text(
                    text = mail.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (mail.snippet.isNotBlank() && mail.snippet != mail.subject) {
                Text(
                    text = mail.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
fun AddOrganizationDialog(
    defaultMissingDays: Int,
    onDismiss: () -> Unit,
    onAdd: (name: String, email: String, additionalEmails: String, notes: String, customDays: Int?) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var additionalEmails by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var daysText by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить организацию в контроль") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название организации") },
                    placeholder = { Text("ООО «Ромашка»") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Основной Email отправителя (From)") },
                    placeholder = { Text("backup@romashka.by") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = additionalEmails,
                    onValueChange = { additionalEmails = it },
                    label = { Text("Доп. Email-адреса (необязательно)") },
                    placeholder = { Text("1c@romashka.by") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = daysText,
                        onValueChange = { daysText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Дней (умолч. $defaultMissingDays)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Заметка") },
                        singleLine = true,
                        modifier = Modifier.weight(1.2f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(name, email, additionalEmails, notes, daysText.toIntOrNull())
                    onDismiss()
                },
                enabled = name.isNotBlank() || email.isNotBlank()
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
