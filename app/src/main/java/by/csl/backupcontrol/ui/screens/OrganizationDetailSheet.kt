package by.csl.backupcontrol.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
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
import by.csl.backupcontrol.data.local.entity.BackupEmailEntity
import by.csl.backupcontrol.domain.model.BackupStatus
import by.csl.backupcontrol.domain.model.OrganizationSummary
import by.csl.backupcontrol.ui.Formatters
import by.csl.backupcontrol.ui.theme.StatusErrorRed
import by.csl.backupcontrol.ui.theme.StatusHealthyGreen
import by.csl.backupcontrol.ui.theme.StatusWarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizationDetailSheet(
    org: OrganizationSummary,
    defaultMissingDaysThreshold: Int,
    emails: List<BackupEmailEntity>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onSetMonitored: (Boolean) -> Unit,
    onSetCustomMissingDays: (Int?) -> Unit,
    onAcknowledgeError: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var nameInput by rememberSaveable(org.id) { mutableStateOf(org.displayName) }
    var customDaysInput by rememberSaveable(org.id) {
        mutableStateOf(org.customMissingDays?.toString().orEmpty())
    }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить организацию?") },
            text = {
                Text(
                    "Организация «${org.displayName}» будет удалена из списка. " +
                        "Если от неё снова придёт письмо, она добавится автоматически (либо отключите тумблер «Мониторить», чтобы скрыть её навсегда)."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Отмена")
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
                    text = org.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (org.senderEmail.isNotBlank()) {
                    Text(
                        text = "Отправитель: ${org.senderEmail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Первое письмо: ${Formatters.formatTimestamp(org.firstSeenAtMillis)} • Всего писем: ${org.emailCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Название организации") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onRename(nameInput) },
                        enabled = nameInput.trim().isNotEmpty() && nameInput.trim() != org.displayName
                    ) {
                        Text("Сохранить")
                    }
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Мониторить организацию",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Если выключено — не участвует в утреннем баннере уведомлений",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = org.isMonitored,
                        onCheckedChange = onSetMonitored
                    )
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = customDaysInput,
                        onValueChange = { raw ->
                            customDaysInput = raw.filter(Char::isDigit).take(2)
                        },
                        label = { Text("Свой порог дней без писем (по умолч. $defaultMissingDaysThreshold)") },
                        placeholder = { Text("$defaultMissingDaysThreshold") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            onSetCustomMissingDays(customDaysInput.toIntOrNull())
                        }
                    ) {
                        Text("Применить")
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
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Удалить организацию из списка")
                }
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
    onAdd: (name: String, email: String, customDays: Int?) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var daysText by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить организацию") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    label = { Text("Email отправителя (From)") },
                    placeholder = { Text("backup@romashka.by") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = daysText,
                    onValueChange = { daysText = it.filter(Char::isDigit).take(2) },
                    label = { Text("Порог дней без почты (по умолч. $defaultMissingDays)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(name, email, daysText.toIntOrNull())
                    onDismiss()
                },
                enabled = name.isNotBlank() || email.isNotBlank()
            ) {
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
