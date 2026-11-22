package com.zigorminsk.backupcontrol.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zigorminsk.backupcontrol.domain.model.DashboardFilter
import com.zigorminsk.backupcontrol.domain.model.OrganizationHealth
import com.zigorminsk.backupcontrol.domain.model.OrganizationSummary

@Composable
fun SummaryFilterRow(
    savedOrganizations: List<OrganizationSummary>,
    selectedFilter: DashboardFilter,
    onSelectFilter: (DashboardFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val monitoredCount = savedOrganizations.count { it.isMonitored }
    val errorCount = savedOrganizations.count { it.isMonitored && it.hasErrorAlert }
    val missingCount = savedOrganizations.count { it.isMonitored && it.isMissingMail }
    val problemsCount = savedOrganizations.count { it.isMonitored && (it.hasErrorAlert || it.isMissingMail) }
    val newCount = savedOrganizations.count { it.isNewlyAdded }
    val healthyCount = savedOrganizations.count { it.isMonitored && it.health == OrganizationHealth.HEALTHY }
    val pausedCount = savedOrganizations.count { !it.isMonitored }

    val chips = buildList {
        add(DashboardFilter.ALL to "${DashboardFilter.ALL.labelRu} ($monitoredCount)")
        if (problemsCount > 0 || selectedFilter == DashboardFilter.PROBLEMS) {
            add(DashboardFilter.PROBLEMS to "${DashboardFilter.PROBLEMS.labelRu} ($problemsCount)")
        }
        add(DashboardFilter.ERRORS to "${DashboardFilter.ERRORS.labelRu} ($errorCount)")
        add(DashboardFilter.MISSING to "${DashboardFilter.MISSING.labelRu} ($missingCount)")
        if (newCount > 0 || selectedFilter == DashboardFilter.NEW_ADDED) {
            add(DashboardFilter.NEW_ADDED to "${DashboardFilter.NEW_ADDED.labelRu} ($newCount)")
        }
        add(DashboardFilter.HEALTHY to "${DashboardFilter.HEALTHY.labelRu} ($healthyCount)")
        if (pausedCount > 0 || selectedFilter == DashboardFilter.PAUSED) {
            add(DashboardFilter.PAUSED to "${DashboardFilter.PAUSED.labelRu} ($pausedCount)")
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        chips.forEach { (filter, title) ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onSelectFilter(filter) },
                label = { Text(title) }
            )
        }
    }
}
