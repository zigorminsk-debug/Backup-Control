package com.zigorminsk.backupcontrol.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zigorminsk.backupcontrol.domain.model.OrganizationApprovalState
import com.zigorminsk.backupcontrol.domain.model.OrganizationGroupFilter
import com.zigorminsk.backupcontrol.domain.model.OrganizationSummary
import com.zigorminsk.backupcontrol.ui.Formatters
import com.zigorminsk.backupcontrol.ui.MainUiState
import com.zigorminsk.backupcontrol.ui.components.OrganizationItemCard
import com.zigorminsk.backupcontrol.ui.components.SyncProgressCard

@Composable
fun OrganizationsTab(
    uiState: MainUiState,
    onSearchQueryChange: (String) -> Unit,
    onSelectGroupFilter: (OrganizationGroupFilter) -> Unit,
    onSetBatchEditMode: (Boolean) -> Unit,
    onSaveReviewedSelection: (Set<Long>) -> Unit,
    onOrganizationEditClick: (Long) -> Unit,
    onAddOrganizationClick: () -> Unit,
    onRestoreExcludedClick: (Long) -> Unit,
    onPermanentlyDeleteClick: (Long) -> Unit,
    onClearAllNewBadgesClick: () -> Unit,
    onRunSixMonthScanClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (uiState.isBatchEditMode) {
        BatchReviewAndSaveScreen(
            uiState = uiState,
            onSearchQueryChange = onSearchQueryChange,
            onCancelBatchEdit = if (uiState.settings.baselineSaved && uiState.pendingReviewOrganizations.isEmpty()) {
                { onSetBatchEditMode(false) }
            } else {
                null
            },
            onSaveSelection = onSaveReviewedSelection,
            onEditOrganizationClick = onOrganizationEditClick,
            onAddOrganizationClick = onAddOrganizationClick,
            onOpenSettingsClick = onOpenSettingsClick,
            onRetrySyncClick = onRunSixMonthScanClick,
            modifier = modifier
        )
        return
    }

    val groupItems = remember(
        uiState.savedOrganizations,
        uiState.newlyAddedOrganizations,
        uiState.pendingReviewOrganizations,
        uiState.excludedOrganizations,
        uiState.orgGroupFilter,
        uiState.orgSearchQuery
    ) {
        val base = when (uiState.orgGroupFilter) {
            OrganizationGroupFilter.SAVED -> uiState.savedOrganizations
            OrganizationGroupFilter.NEW_ADDED -> uiState.newlyAddedOrganizations
            OrganizationGroupFilter.PENDING -> uiState.pendingReviewOrganizations
            OrganizationGroupFilter.EXCLUDED -> uiState.excludedOrganizations
        }
        val q = uiState.orgSearchQuery.trim().lowercase()
        if (q.isEmpty()) base else base.filter {
            it.displayName.lowercase().contains(q) ||
                it.allEmailsFormatted.lowercase().contains(q) ||
                it.notes.lowercase().contains(q)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "org_header") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Управление списком организаций",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (uiState.settings.autoAddNewOrganizations) {
                            "На контроле: ${uiState.savedOrganizations.count { it.isMonitored }} орг. • Новые отправители добавляются автоматически при появлении."
                        } else {
                            "На контроле: ${uiState.savedOrganizations.count { it.isMonitored }} орг. • Новые отправители ожидают подтверждения."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilledTonalButton(
                            onClick = { onSetBatchEditMode(true) },
                            enabled = uiState.allOrganizations.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Выбрать список")
                        }
                        OutlinedButton(onClick = onAddOrganizationClick) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Добавить")
                        }
                    }
                }
            }
        }

        item(key = "org_filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = uiState.orgGroupFilter == OrganizationGroupFilter.SAVED,
                        onClick = { onSelectGroupFilter(OrganizationGroupFilter.SAVED) },
                        label = { Text("Сохранённые (${uiState.savedOrganizations.size})") }
                    )
                    FilterChip(
                        selected = uiState.orgGroupFilter == OrganizationGroupFilter.NEW_ADDED,
                        onClick = { onSelectGroupFilter(OrganizationGroupFilter.NEW_ADDED) },
                        label = { Text("Новые (${uiState.newlyAddedOrganizations.size})") }
                    )
                    if (uiState.pendingReviewOrganizations.isNotEmpty()) {
                        FilterChip(
                            selected = uiState.orgGroupFilter == OrganizationGroupFilter.PENDING,
                            onClick = { onSelectGroupFilter(OrganizationGroupFilter.PENDING) },
                            label = { Text("На проверке (${uiState.pendingReviewOrganizations.size})") }
                        )
                    }
                    FilterChip(
                        selected = uiState.orgGroupFilter == OrganizationGroupFilter.EXCLUDED,
                        onClick = { onSelectGroupFilter(OrganizationGroupFilter.EXCLUDED) },
                        label = { Text("Исключённые (${uiState.excludedOrganizations.size})") }
                    )
                }

                if (uiState.orgGroupFilter == OrganizationGroupFilter.NEW_ADDED &&
                    uiState.newlyAddedOrganizations.isNotEmpty()
                ) {
                    OutlinedButton(
                        onClick = onClearAllNewBadgesClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Отметить все новые организации просмотренными")
                    }
                }

                OutlinedTextField(
                    value = uiState.orgSearchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("Поиск организации или email…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.orgSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    }
                )
            }
        }

        if (groupItems.isEmpty()) {
            item(key = "org_empty") {
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
                                uiState.allOrganizations.isEmpty() ->
                                    "Организации ещё не собраны из почты"
                                uiState.orgGroupFilter == OrganizationGroupFilter.NEW_ADDED ->
                                    "Новых организаций после сохранения списка пока не появлялось"
                                uiState.orgGroupFilter == OrganizationGroupFilter.EXCLUDED ->
                                    "Список исключённых отправителей пуст"
                                else -> "По запросу ничего не найдено"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (uiState.allOrganizations.isEmpty() && uiState.settings.isConfigured) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = onRunSixMonthScanClick) {
                                Text("Запустить сбор за 6 месяцев")
                            }
                        }
                    }
                }
            }
        } else {
            items(groupItems, key = { it.id }) { org ->
                if (org.approvalState == OrganizationApprovalState.EXCLUDED) {
                    ExcludedOrganizationRowCard(
                        org = org,
                        onEditClick = { onOrganizationEditClick(org.id) },
                        onRestoreClick = { onRestoreExcludedClick(org.id) },
                        onDeleteForeverClick = { onPermanentlyDeleteClick(org.id) }
                    )
                } else {
                    OrganizationItemCard(
                        org = org,
                        onClick = { onOrganizationEditClick(org.id) },
                        onEditClick = { onOrganizationEditClick(org.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchReviewAndSaveScreen(
    uiState: MainUiState,
    onSearchQueryChange: (String) -> Unit,
    onCancelBatchEdit: (() -> Unit)?,
    onSaveSelection: (Set<Long>) -> Unit,
    onEditOrganizationClick: (Long) -> Unit,
    onAddOrganizationClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    onRetrySyncClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val editablePool = remember(uiState.allOrganizations) {
        uiState.allOrganizations
            .filter { it.approvalState != OrganizationApprovalState.EXCLUDED }
            .sortedBy { it.displayName.lowercase() }
    }

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var initializedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // Автоматически отмечаем новые найденные организации по мере чтения писем за 6 месяцев
    LaunchedEffect(editablePool) {
        val newlySeen = editablePool.filter { it.id !in initializedIds }
        if (newlySeen.isNotEmpty()) {
            val defaultChecked = newlySeen
                .filter {
                    (it.approvalState == OrganizationApprovalState.SAVED && it.isMonitored) ||
                        (it.approvalState == OrganizationApprovalState.PENDING_REVIEW && it.isMonitored)
                }
                .map { it.id }
                .toSet()
            selectedIds = (selectedIds + defaultChecked).intersect(editablePool.map { it.id }.toSet())
            initializedIds = initializedIds + newlySeen.map { it.id }
        } else {
            selectedIds = selectedIds.intersect(editablePool.map { it.id }.toSet())
        }
    }

    val filteredPool = remember(editablePool, uiState.orgSearchQuery) {
        val q = uiState.orgSearchQuery.trim().lowercase()
        if (q.isEmpty()) editablePool else editablePool.filter {
            it.displayName.lowercase().contains(q) ||
                it.allEmailsFormatted.lowercase().contains(q) ||
                it.notes.lowercase().contains(q)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (uiState.syncProgress.isRunning || !uiState.syncProgress.lastError.isNullOrBlank()) {
                item(key = "batch_sync_progress") {
                    SyncProgressCard(
                        progress = uiState.syncProgress,
                        onRetryClick = onRetrySyncClick,
                        onOpenSettingsClick = onOpenSettingsClick
                    )
                }
            }

            item(key = "batch_intro") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = if (!uiState.settings.baselineSaved) {
                                "Редактирование организаций после сбора за 6 месяцев"
                            } else {
                                "Редактирование контролируемого списка организаций"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Переименуйте организации (кнопка ✏️), объедините дубликаты, снимите галочки с ненужных адресов и нажмите «Сохранить список». После этого контролироваться будут только сохранённые организации, а новые будут добавляться при появлении.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { selectedIds = editablePool.map { it.id }.toSet() }) {
                                Text("Выбрать все")
                            }
                            TextButton(onClick = { selectedIds = emptySet() }) {
                                Text("Снять все")
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = onAddOrganizationClick) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Клиент")
                            }
                        }
                    }
                }
            }

            item(key = "batch_search") {
                OutlinedTextField(
                    value = uiState.orgSearchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("Поиск в собранном списке…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.orgSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    }
                )
            }

            items(filteredPool, key = { it.id }) { org ->
                val isChecked = org.id in selectedIds
                BatchSelectableOrgCard(
                    org = org,
                    checked = isChecked,
                    onCheckedChange = { checked ->
                        selectedIds = if (checked) selectedIds + org.id else selectedIds - org.id
                    },
                    onEditClick = { onEditOrganizationClick(org.id) }
                )
            }
        }

        Surface(
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (onCancelBatchEdit != null) {
                        OutlinedButton(
                            onClick = onCancelBatchEdit,
                            modifier = Modifier.weight(0.8f)
                        ) {
                            Text("Отмена")
                        }
                    }
                    Button(
                        onClick = { onSaveSelection(selectedIds) },
                        enabled = !uiState.syncProgress.isRunning && editablePool.isNotEmpty(),
                        modifier = Modifier.weight(1.6f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Сохранить для контроля (${selectedIds.size} из ${editablePool.size})",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchSelectableOrgCard(
    org: OrganizationSummary,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onEditClick: () -> Unit
) {
    val border = if (checked) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
    } else {
        BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = border
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = org.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (org.isCustomName) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                if (org.allEmailsFormatted.isNotBlank()) {
                    Text(
                        text = org.allEmailsFormatted,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (org.lastEmailAtMillis > 0L) {
                        "Писем за период: ${org.emailCount} • посл.: ${Formatters.formatTimestamp(org.lastEmailAtMillis)}"
                    } else {
                        "Добавлена вручную"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEditClick) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Редактировать организацию",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ExcludedOrganizationRowCard(
    org: OrganizationSummary,
    onEditClick: () -> Unit,
    onRestoreClick: () -> Unit,
    onDeleteForeverClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEditClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = org.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (org.allEmailsFormatted.isNotBlank()) {
                Text(
                    text = org.allEmailsFormatted,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onRestoreClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Вернуть в контроль")
                }
                IconButton(onClick = onDeleteForeverClick) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = "Удалить окончательно",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
