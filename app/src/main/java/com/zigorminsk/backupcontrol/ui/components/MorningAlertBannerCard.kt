package com.zigorminsk.backupcontrol.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zigorminsk.backupcontrol.domain.model.MorningAlertReport
import com.zigorminsk.backupcontrol.domain.model.OrganizationSummary
import com.zigorminsk.backupcontrol.ui.Formatters
import com.zigorminsk.backupcontrol.ui.theme.StatusErrorBgDark
import com.zigorminsk.backupcontrol.ui.theme.StatusErrorBgLight
import com.zigorminsk.backupcontrol.ui.theme.StatusErrorRed
import com.zigorminsk.backupcontrol.ui.theme.StatusHealthyBgDark
import com.zigorminsk.backupcontrol.ui.theme.StatusHealthyBgLight
import com.zigorminsk.backupcontrol.ui.theme.StatusHealthyGreen
import com.zigorminsk.backupcontrol.ui.theme.StatusWarningAmber

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MorningAlertBannerCard(
    report: MorningAlertReport,
    lastSyncAtMillis: Long,
    onOrganizationClick: (Long) -> Unit,
    onFilterProblemsClick: () -> Unit,
    onAcknowledgeAllClick: () -> Unit,
    onDismissClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val containerColor = if (isDark) StatusErrorBgDark else StatusErrorBgLight
    val accentRed = if (isDark) Color(0xFFFF8A80) else StatusErrorRed
    val accentAmber = if (isDark) Color(0xFFFFB74D) else StatusWarningAmber

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.5.dp, accentRed.copy(alpha = 0.65f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = accentRed,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Утренний баннер: внимание к ${report.totalAffectedCount} орг.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentRed
                    )
                    if (lastSyncAtMillis > 0L) {
                        Text(
                            text = "Сводка на ${Formatters.formatTimestamp(lastSyncAtMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }
            }

            if (report.errorOrganizations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = accentRed,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Ошибка резервного копирования (${report.errorOrganizations.size}):",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = accentRed
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                report.errorOrganizations.forEach { org ->
                    BannerProblemRow(
                        org = org,
                        subtitle = (org.errorSummarySubject ?: org.lastSubject).ifBlank {
                            org.errorSummarySnippet ?: "Обнаружен маркер ошибки в отчёте"
                        },
                        badgeText = "ОШИБКА",
                        badgeColor = accentRed,
                        onClick = { onOrganizationClick(org.id) }
                    )
                }
            }

            if (report.missingMailOrganizations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                if (report.errorOrganizations.isNotEmpty()) {
                    HorizontalDivider(color = accentRed.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.MarkEmailUnread,
                        contentDescription = null,
                        tint = accentAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Нет почты от клиента несколько дней (${report.missingMailOrganizations.size}):",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = accentAmber
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                report.missingMailOrganizations.forEach { org ->
                    val subtitle = if (org.lastEmailAtMillis > 0L) {
                        "Последнее письмо: ${Formatters.formatTimestamp(org.lastEmailAtMillis)} (порог ${org.effectiveMissingDaysThreshold} дн.)"
                    } else {
                        "Писем от организации ещё не поступало"
                    }
                    val badge = if (org.lastEmailAtMillis > 0L) {
                        "${org.daysSinceLastEmail} ДН."
                    } else {
                        "НЕТ ПИСЕМ"
                    }
                    BannerProblemRow(
                        org = org,
                        subtitle = subtitle,
                        badgeText = badge,
                        badgeColor = accentAmber,
                        onClick = { onOrganizationClick(org.id) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(onClick = onFilterProblemsClick) {
                    Text("Показать только проблемные")
                }
                if (report.errorOrganizations.isNotEmpty()) {
                    TextButton(onClick = onAcknowledgeAllClick) {
                        Text("Подтвердить ошибки")
                    }
                }
                TextButton(onClick = onDismissClick) {
                    Text("Скрыть баннер")
                }
            }
        }
    }
}

@Composable
private fun BannerProblemRow(
    org: OrganizationSummary,
    subtitle: String,
    badgeText: String,
    badgeColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = org.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = badgeColor.copy(alpha = 0.16f)
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
fun HealthySummaryStrip(
    monitoredCount: Int,
    lastSyncAtMillis: Long,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) StatusHealthyBgDark else StatusHealthyBgLight
    val green = if (isDark) Color(0xFF81C784) else StatusHealthyGreen

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, green.copy(alpha = 0.45f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = green,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Все организации в норме ($monitoredCount)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = green
                )
                Text(
                    text = "Последняя проверка: ${Formatters.formatTimestamp(lastSyncAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
        }
    }
}
