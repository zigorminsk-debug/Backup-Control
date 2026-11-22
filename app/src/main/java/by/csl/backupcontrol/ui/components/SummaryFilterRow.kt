package by.csl.backupcontrol.ui.components

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
import by.csl.backupcontrol.domain.model.DashboardFilter
import by.csl.backupcontrol.domain.model.OrganizationHealth
import by.csl.backupcontrol.domain.model.OrganizationSummary

@Composable
fun SummaryFilterRow(
    allOrganizations: List<OrganizationSummary>,
    selectedFilter: DashboardFilter,
    onSelectFilter: (DashboardFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val monitoredCount = allOrganizations.count { it.isMonitored }
    val errorCount = allOrganizations.count { it.isMonitored && it.hasErrorAlert }
    val missingCount = allOrganizations.count { it.isMonitored && it.isMissingMail }
    val problemsCount = allOrganizations.count { it.isMonitored && (it.hasErrorAlert || it.isMissingMail) }
    val healthyCount = allOrganizations.count { it.isMonitored && it.health == OrganizationHealth.HEALTHY }
    val ignoredCount = allOrganizations.count { !it.isMonitored }

    val chips = buildList {
        add(DashboardFilter.ALL to "${DashboardFilter.ALL.labelRu} ($monitoredCount)")
        if (problemsCount > 0 || selectedFilter == DashboardFilter.PROBLEMS) {
            add(DashboardFilter.PROBLEMS to "${DashboardFilter.PROBLEMS.labelRu} ($problemsCount)")
        }
        add(DashboardFilter.ERRORS to "${DashboardFilter.ERRORS.labelRu} ($errorCount)")
        add(DashboardFilter.MISSING to "${DashboardFilter.MISSING.labelRu} ($missingCount)")
        add(DashboardFilter.HEALTHY to "${DashboardFilter.HEALTHY.labelRu} ($healthyCount)")
        if (ignoredCount > 0 || selectedFilter == DashboardFilter.IGNORED) {
            add(DashboardFilter.IGNORED to "${DashboardFilter.IGNORED.labelRu} ($ignoredCount)")
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
