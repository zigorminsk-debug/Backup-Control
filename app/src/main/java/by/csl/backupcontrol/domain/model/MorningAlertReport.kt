package by.csl.backupcontrol.domain.model

data class MorningAlertReport(
    val evaluatedAtMillis: Long,
    val errorOrganizations: List<OrganizationSummary>,
    val missingMailOrganizations: List<OrganizationSummary>
) {
    val hasIssues: Boolean
        get() = errorOrganizations.isNotEmpty() || missingMailOrganizations.isNotEmpty()

    val distinctProblemOrganizations: List<OrganizationSummary>
        get() = (errorOrganizations + missingMailOrganizations).distinctBy { it.id }

    val totalAffectedCount: Int
        get() = distinctProblemOrganizations.size

    fun buildNotificationTitle(): String {
        if (!hasIssues) return "Все бэкапы в норме"
        return when {
            errorOrganizations.isNotEmpty() && missingMailOrganizations.isNotEmpty() ->
                "Проблемы с бэкапами: ${totalAffectedCount} орг."
            errorOrganizations.isNotEmpty() ->
                "Ошибка бэкапа: ${errorOrganizations.size} орг."
            else ->
                "Нет писем от клиентов: ${missingMailOrganizations.size} орг."
        }
    }

    fun buildCompactNotificationSummary(): String {
        val parts = mutableListOf<String>()
        if (errorOrganizations.isNotEmpty()) {
            val names = errorOrganizations.take(3).joinToString(", ") { it.displayName }
            val suffix = if (errorOrganizations.size > 3) " и ещё ${errorOrganizations.size - 3}" else ""
            parts += "Ошибки (${errorOrganizations.size}): $names$suffix"
        }
        if (missingMailOrganizations.isNotEmpty()) {
            val names = missingMailOrganizations.take(3).joinToString(", ") {
                "${it.displayName} (${it.daysSinceLastEmail} дн.)"
            }
            val suffix = if (missingMailOrganizations.size > 3) " и ещё ${missingMailOrganizations.size - 3}" else ""
            parts += "Нет писем (${missingMailOrganizations.size}): $names$suffix"
        }
        return parts.joinToString(" • ")
    }

    fun buildExpandedNotificationLines(): List<String> {
        val lines = mutableListOf<String>()
        if (errorOrganizations.isNotEmpty()) {
            lines += "❌ Ошибка бэкапа (${errorOrganizations.size}):"
            errorOrganizations.forEach { org ->
                val detail = (org.errorSummarySubject ?: org.lastSubject).take(60)
                lines += if (detail.isNotBlank()) {
                    "• ${org.displayName} — $detail"
                } else {
                    "• ${org.displayName}"
                }
            }
        }
        if (missingMailOrganizations.isNotEmpty()) {
            if (lines.isNotEmpty()) lines += ""
            lines += "📭 Нет почты несколько дней (${missingMailOrganizations.size}):"
            missingMailOrganizations.forEach { org ->
                val daysText = if (org.lastEmailAtMillis > 0L) {
                    "${org.daysSinceLastEmail} дн. без писем (порог ${org.effectiveMissingDaysThreshold} дн.)"
                } else {
                    "писем не поступало"
                }
                lines += "• ${org.displayName} — $daysText"
            }
        }
        return lines
    }
}
