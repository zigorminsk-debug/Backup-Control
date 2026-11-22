package com.zigorminsk.backupcontrol.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zigorminsk.backupcontrol.domain.model.OrganizationHealth
import com.zigorminsk.backupcontrol.domain.model.OrganizationSummary
import com.zigorminsk.backupcontrol.ui.Formatters
import com.zigorminsk.backupcontrol.ui.theme.StatusErrorRed
import com.zigorminsk.backupcontrol.ui.theme.StatusHealthyGreen
import com.zigorminsk.backupcontrol.ui.theme.StatusWarningAmber

@Composable
fun OrganizationItemCard(
    org: OrganizationSummary,
    onClick: () -> Unit,
    onEditClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val errorColor = if (isDark) Color(0xFFFF8A80) else StatusErrorRed
    val warnColor = if (isDark) Color(0xFFFFB74D) else StatusWarningAmber
    val okColor = if (isDark) Color(0xFF81C784) else StatusHealthyGreen
    val newBadgeColor = if (isDark) Color(0xFF64B5F6) else Color(0xFF1565C0)
    val mutedColor = MaterialTheme.colorScheme.outline

    val (accentColor, statusLabel, icon) = when (org.health) {
        OrganizationHealth.ERROR -> Triple(errorColor, "ОШИБКА БЭКАПА", Icons.Default.Error)
        OrganizationHealth.WARNING -> Triple(warnColor, "ПРЕДУПРЕЖДЕНИЕ", Icons.Default.Warning)
        OrganizationHealth.MISSING_MAIL -> {
            val txt = if (org.lastEmailAtMillis > 0L) {
                "НЕТ ПИСЕМ ${org.daysSinceLastEmail} ДН."
            } else {
                "НЕТ ПИСЕМ"
            }
            Triple(warnColor, txt, Icons.Default.Schedule)
        }
        OrganizationHealth.HEALTHY -> Triple(okColor, "В НОРМЕ", Icons.Default.CheckCircle)
        OrganizationHealth.PAUSED -> Triple(mutedColor, "ПАУЗА", Icons.Default.PauseCircleOutline)
        OrganizationHealth.PENDING_REVIEW -> Triple(newBadgeColor, "НА ПРОВЕРКЕ", Icons.Default.HourglassTop)
        OrganizationHealth.EXCLUDED -> Triple(mutedColor, "ИСКЛЮЧЕНА", Icons.Default.Block)
    }

    val borderStroke = when (org.health) {
        OrganizationHealth.ERROR -> BorderStroke(1.2.dp, errorColor.copy(alpha = 0.55f))
        OrganizationHealth.WARNING, OrganizationHealth.MISSING_MAIL ->
            BorderStroke(1.dp, warnColor.copy(alpha = 0.45f))
        else -> if (org.isNewlyAdded) {
            BorderStroke(1.dp, newBadgeColor.copy(alpha = 0.5f))
        } else {
            BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = borderStroke,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = org.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (org.isNewlyAdded) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = newBadgeColor.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "НОВАЯ",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = newBadgeColor,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = accentColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    if (onEditClick != null) {
                        IconButton(
                            onClick = onEditClick,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Редактировать",
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            if (org.allEmailsFormatted.isNotBlank()) {
                Text(
                    text = org.allEmailsFormatted,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (org.notes.isNotBlank()) {
                Text(
                    text = "📌 ${org.notes}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (org.hasErrorAlert && org.isMissingMail && org.lastEmailAtMillis > 0L) {
                Text(
                    text = "Также нет новых писем ${org.daysSinceLastEmail} дн. (порог ${org.effectiveMissingDaysThreshold} дн.)",
                    style = MaterialTheme.typography.labelSmall,
                    color = warnColor,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            val subjectToShow = org.errorSummarySubject ?: org.lastSubject
            if (subjectToShow.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subjectToShow,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (org.hasErrorAlert) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val snippetToShow = org.errorSummarySnippet ?: org.lastSnippet
            if (snippetToShow.isNotBlank() && snippetToShow != subjectToShow) {
                Text(
                    text = snippetToShow,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (org.lastEmailAtMillis > 0L) {
                        "Письмо: ${Formatters.formatTimestamp(org.lastEmailAtMillis)}"
                    } else {
                        "Писем ещё не было"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Писем: ${org.emailCount} • порог ${org.effectiveMissingDaysThreshold} дн.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
