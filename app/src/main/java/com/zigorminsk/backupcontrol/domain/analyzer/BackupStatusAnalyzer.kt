package com.zigorminsk.backupcontrol.domain.analyzer

import com.zigorminsk.backupcontrol.domain.model.BackupStatus

data class AnalysisResult(
    val status: BackupStatus,
    val diagnosticSnippet: String,
    val normalizedJobKey: String
)

object BackupStatusAnalyzer {

    // Explicit subject status tags like [Failed], [Error], [Warning], [Success], [OK]
    private val subjectErrorPrefixRegex = Regex(
        pattern = "(?iu)(\\[\\s*(failed|error|critical|fatal|aborted|ошибка|сбой|авария)\\s*]|^(failed|error|ошибка|сбой)\\s*[:\\-])"
    )
    private val subjectWarningPrefixRegex = Regex(
        pattern = "(?iu)(\\[\\s*(warning|warn|внимание|предупреждение)\\s*]|^(warning|предупреждение)\\s*[:\\-])"
    )
    private val subjectSuccessPrefixRegex = Regex(
        pattern = "(?iu)(\\[\\s*(success|succeeded|ok|completed|успешно|норма)\\s*]|^(success|ok|успешно)\\s*[:\\-])"
    )

    // Negated / zero-error expressions to sanitize before keyword scanning
    private val zeroErrorSanitizePatterns = listOf(
        Regex("(?iu)\\b(errors?|failed|failures?|ошибок|ошибки|сбоев|неудачно)\\s*[:=\\-–—]?\\s*0\\b"),
        Regex("(?iu)\\b0\\s+(errors?|failed|failures?|ошибок|сбоев)\\b"),
        Regex("(?iu)\\b(warnings?|предупреждений|предупреждения)\\s*[:=\\-–—]?\\s*0\\b"),
        Regex("(?iu)\\b0\\s+(warnings?|предупреждений)\\b"),
        Regex("(?iu)\\b(exit\\s*code|return\\s*code|errorlevel|код\\s*(возврата|завершения|ошибки))\\s*[:=\\-–—]?\\s*0\\b"),
        Regex("(?iu)\\b(без\\s+ошибок|ошибок\\s+нет|ошибок\\s+не\\s+обнаружено|no\\s+errors?(\\s+found|\\s+detected)?|without\\s+errors?|0\\s+error\\(s\\))\\b")
    )

    // Explicit zero-error markers that indicate success when no non-zero errors exist
    private val zeroErrorPositiveEvidence = listOf(
        Regex("(?iu)\\b(errors?|ошибок|сбоев)\\s*[:=\\-–—]\\s*0\\b"),
        Regex("(?iu)\\b0\\s+(errors?|ошибок)\\b"),
        Regex("(?iu)\\b(без\\s+ошибок|ошибок\\s+нет|ошибок\\s+не\\s+обнаружено|no\\s+errors?)\\b"),
        Regex("(?iu)\\b(exit\\s*code|return\\s*code|код\\s*(возврата|завершения))\\s*[:=\\-–—]?\\s*0\\b")
    )

    // Non-zero error counters: e.g. "Errors: 2", "Ошибок: 1", "3 errors", "Failed: 1", "exit code: 2"
    private val nonZeroErrorCounterPatterns = listOf(
        Regex("(?iu)\\b(errors?|failed|failures?|ошибок|сбоев)\\s*[:=\\-–—]\\s*[1-9]\\d*\\b"),
        Regex("(?iu)\\b[1-9]\\d*\\s+(errors?|failures?|ошибок|ошибки|ошибка|сбоев|сбоя)\\b"),
        Regex("(?iu)\\b(exit\\s*code|return\\s*code|errorlevel|код\\s*(возврата|завершения|ошибки))\\s*[:=\\-–—]?\\s*[1-9]\\d*\\b"),
        Regex("(?iu)\\bfailed\\s*\\t+\\s*[1-9]\\d*\\b")
    )

    private val nonZeroWarningCounterPatterns = listOf(
        Regex("(?iu)\\b(warnings?|предупреждений)\\s*[:=\\-–—]\\s*[1-9]\\d*\\b"),
        Regex("(?iu)\\b[1-9]\\d*\\s+(warnings?|предупреждений|предупреждения|предупреждение)\\b")
    )

    private val strongErrorPatterns = listOf(
        Regex("(?iu)\\b(backup|task|job|operation|archive|copy|dump|задача|копирование|архивация|выгрузка|бэкап)\\b[^.\\n]{0,60}\\b(failed|aborted|unsuccessful|terminated\\s+abnormally|не\\s+выполнен[аоы]?|завершен[аоы]?\\s+с\\s+ошибк|прерван[аоы]?|аварийно)\\b"),
        Regex("(?iu)\\b(status|статус|state|результат)\\s*[:=\\-–—]\\s*(failed|error|critical|aborted|ошибка|сбой|неудачно)\\b"),
        Regex("(?iu)\\b(не\\s+удалось|недостаточно\\s+места|отказано\\s+в\\s+доступе|ошибка\\s+(при|резервного|архивации|копирования|подключения|записи|чтения|выгрузки|бэкапа)|сбой\\s+(выполнения|резервного|архивации|копирования|задачи)|экстренное\\s+завершение|аварийное\\s+завершение)\\b"),
        Regex("(?iu)\\b(no\\s+space\\s+left\\s+on\\s+device|disk\\s+is\\s+full|access\\s+is\\s+denied|permission\\s+denied|connection\\s+(refused|timed\\s+out|failed)|terminating\\s+abnormally|unhandled\\s+exception|fatal\\s+error|critical\\s+error|cannot\\s+open\\s+backup\\s+device|corrupt(ed)?)\\b"),
        Regex("(?iu)\\b(ошибка|сбой|failed|failure|fatal|exception)\\s*[:!]")
    )

    private val generalErrorWordRegex = Regex(
        "(?iu)\\b(ошибка|ошибки|сбой|авария|failed|failure|fatal|aborted)\\b"
    )

    private val warningPatterns = listOf(
        Regex("(?iu)\\b(status|статус|результат)\\s*[:=\\-–—]\\s*(warning|warn|предупреждение|внимание)\\b"),
        Regex("(?iu)\\b(completed\\s+with\\s+warnings?|finished\\s+with\\s+warnings?|выполнен[аоы]?\\s+с\\s+предупреждени|завершен[аоы]?\\s+с\\s+предупреждени)\\b"),
        Regex("(?iu)\\b(warning|предупреждение)\\s*[:!]")
    )

    private val successPatterns = listOf(
        Regex("(?iu)\\b(status|статус|результат)\\s*[:=\\-–—]\\s*(success|succeeded|ok|completed|успешно|норма|выполнено)\\b"),
        Regex("(?iu)\\b(успешно\\s+(завершен[аоы]?|выполнен[аоы]?|создан[аоы]?|скопирован[аоы]?)|выполнен[аоы]?\\s+успешно|завершен[аоы]?\\s+успешно|резервное\\s+копирование\\s+завершено|архив\\s+(успешно\\s+)?создан|выгрузка\\s+завершена)\\b"),
        Regex("(?iu)\\b(completed\\s+successfully|finished\\s+successfully|backup\\s+(succeeded|successful|completed|ok|done)|everything\\s+is\\s+ok|successfully\\s+processed|task\\s+completed)\\b"),
        Regex("(?iu)\\b(бэкап\\s+ок|backup\\s+ok|успешно)\\b")
    )

    fun analyze(
        subject: String?,
        body: String?,
        customErrorKeywords: List<String> = emptyList(),
        customSuccessKeywords: List<String> = emptyList()
    ): AnalysisResult {
        val cleanSubject = subject?.trim().orEmpty()
        val cleanBody = body?.trim().orEmpty()
        val combinedRaw = buildString {
            if (cleanSubject.isNotBlank()) append(cleanSubject)
            if (cleanBody.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(cleanBody.take(32_000))
            }
        }

        val normalizedJobKey = normalizeJobKey(cleanSubject)

        if (combinedRaw.isBlank()) {
            return AnalysisResult(
                status = BackupStatus.UNKNOWN,
                diagnosticSnippet = "",
                normalizedJobKey = normalizedJobKey
            )
        }

        // 1. Custom user-configured keywords take highest precedence if matched
        for (kw in customErrorKeywords) {
            val trimmed = kw.trim()
            if (trimmed.isNotEmpty() && combinedRaw.contains(trimmed, ignoreCase = true)) {
                return AnalysisResult(
                    status = BackupStatus.ERROR,
                    diagnosticSnippet = extractRelevantLine(cleanSubject, cleanBody, trimmed),
                    normalizedJobKey = normalizedJobKey
                )
            }
        }

        // 2. Sanitize negated / zero-error phrases so "Errors: 0" or "без ошибок" never trigger false positives
        var sanitized = combinedRaw
        for (zeroRegex in zeroErrorSanitizePatterns) {
            sanitized = zeroRegex.replace(sanitized, " ")
        }
        var sanitizedSubject = cleanSubject
        for (zeroRegex in zeroErrorSanitizePatterns) {
            sanitizedSubject = zeroRegex.replace(sanitizedSubject, " ")
        }

        // 3. Explicit subject status prefix check
        if (subjectErrorPrefixRegex.containsMatchIn(sanitizedSubject)) {
            return AnalysisResult(
                status = BackupStatus.ERROR,
                diagnosticSnippet = findBestErrorSnippet(cleanSubject, cleanBody),
                normalizedJobKey = normalizedJobKey
            )
        }

        // 4. Non-zero error counters in subject or body (e.g. Cobian "Ошибок: 3", Veeam "2 errors", "exit code: 1")
        for (counterRegex in nonZeroErrorCounterPatterns) {
            val match = counterRegex.find(sanitized)
            if (match != null) {
                return AnalysisResult(
                    status = BackupStatus.ERROR,
                    diagnosticSnippet = extractRelevantLine(cleanSubject, cleanBody, match.value),
                    normalizedJobKey = normalizedJobKey
                )
            }
        }

        // 5. Strong contextual error patterns in subject or body
        for (errRegex in strongErrorPatterns) {
            val match = errRegex.find(sanitized)
            if (match != null) {
                return AnalysisResult(
                    status = BackupStatus.ERROR,
                    diagnosticSnippet = extractRelevantLine(cleanSubject, cleanBody, match.value),
                    normalizedJobKey = normalizedJobKey
                )
            }
        }

        // 6. If subject explicitly says [Success] / [OK] and there were NO non-zero errors or strong body errors
        if (subjectSuccessPrefixRegex.containsMatchIn(cleanSubject)) {
            if (nonZeroWarningCounterPatterns.any { it.containsMatchIn(sanitized) }) {
                return AnalysisResult(
                    status = BackupStatus.WARNING,
                    diagnosticSnippet = extractFirstMeaningfulLine(cleanBody, cleanSubject),
                    normalizedJobKey = normalizedJobKey
                )
            }
            return AnalysisResult(
                status = BackupStatus.SUCCESS,
                diagnosticSnippet = extractFirstMeaningfulLine(cleanBody, cleanSubject),
                normalizedJobKey = normalizedJobKey
            )
        }

        // 7. Subject warning tag or warning counters / phrases
        if (subjectWarningPrefixRegex.containsMatchIn(cleanSubject) ||
            nonZeroWarningCounterPatterns.any { it.containsMatchIn(sanitized) } ||
            warningPatterns.any { it.containsMatchIn(sanitized) }
        ) {
            // Check that no standalone error word in subject overrides warning
            val subjErrMatch = generalErrorWordRegex.find(sanitizedSubject)
            if (subjErrMatch != null) {
                return AnalysisResult(
                    status = BackupStatus.ERROR,
                    diagnosticSnippet = extractRelevantLine(cleanSubject, cleanBody, subjErrMatch.value),
                    normalizedJobKey = normalizedJobKey
                )
            }
            return AnalysisResult(
                status = BackupStatus.WARNING,
                diagnosticSnippet = findBestWarningSnippet(cleanSubject, cleanBody),
                normalizedJobKey = normalizedJobKey
            )
        }

        // 8. General error word in subject (after zero-error sanitization)
        generalErrorWordRegex.find(sanitizedSubject)?.let { match ->
            return AnalysisResult(
                status = BackupStatus.ERROR,
                diagnosticSnippet = extractRelevantLine(cleanSubject, cleanBody, match.value),
                normalizedJobKey = normalizedJobKey
            )
        }

        // 9. Custom success keywords
        for (kw in customSuccessKeywords) {
            val trimmed = kw.trim()
            if (trimmed.isNotEmpty() && combinedRaw.contains(trimmed, ignoreCase = true)) {
                return AnalysisResult(
                    status = BackupStatus.SUCCESS,
                    diagnosticSnippet = extractRelevantLine(cleanSubject, cleanBody, trimmed),
                    normalizedJobKey = normalizedJobKey
                )
            }
        }

        // 10. General error word in body (only if no explicit success indicator)
        val hasSuccessEvidence = successPatterns.any { it.containsMatchIn(combinedRaw) } ||
            zeroErrorPositiveEvidence.any { it.containsMatchIn(combinedRaw) }

        val bodyErrorMatch = generalErrorWordRegex.find(sanitized)
        if (bodyErrorMatch != null && !hasSuccessEvidence) {
            return AnalysisResult(
                status = BackupStatus.ERROR,
                diagnosticSnippet = extractRelevantLine(cleanSubject, cleanBody, bodyErrorMatch.value),
                normalizedJobKey = normalizedJobKey
            )
        }

        // 11. Success indicators
        if (hasSuccessEvidence) {
            return AnalysisResult(
                status = BackupStatus.SUCCESS,
                diagnosticSnippet = extractFirstMeaningfulLine(cleanBody, cleanSubject),
                normalizedJobKey = normalizedJobKey
            )
        }

        return AnalysisResult(
            status = BackupStatus.UNKNOWN,
            diagnosticSnippet = extractFirstMeaningfulLine(cleanBody, cleanSubject),
            normalizedJobKey = normalizedJobKey
        )
    }

    fun normalizeJobKey(subject: String): String {
        if (subject.isBlank()) return "default"
        var normalized = subject.lowercase()
            // Strip leading bracketed status tags like [Success], [Failed], [Warning], [OK]
            .replace(
                Regex("^(\\s*\\[\\s*(success|succeeded|failed|error|warning|warn|ok|успешно|ошибка|сбой|предупреждение)\\s*]\\s*)+"),
                ""
            )
            // Strip trailing status words after colon/dash
            .replace(
                Regex("[:\\-–—]\\s*(success|succeeded|failed|error|warning|ok|успешно|ошибка|сбой|выполнено.*|завершено.*)$"),
                ""
            )
            // Strip dates & times (e.g. 2026-11-22, 22.11.2026, 04:15:30)
            .replace(Regex("\\b\\d{4}[.\\-/]\\d{2}[.\\-/]\\d{2}\\b"), "")
            .replace(Regex("\\b\\d{2}[.\\-/]\\d{2}[.\\-/]\\d{2,4}\\b"), "")
            .replace(Regex("\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b"), "")
            // Strip error/file counters in parentheses or trailing
            .replace(Regex("\\(\\d+\\s*(errors?|warnings?|ошибок|предупреждений).*?\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        return normalized.ifBlank { "default" }.take(100)
    }

    private fun findBestErrorSnippet(subject: String, body: String): String {
        val errorLineKeywords = listOf(
            "ошибка", "сбой", "не удалось", "недостаточно", "отказано",
            "error", "failed", "failure", "exception", "fatal", "cannot", "denied", "full"
        )
        for (line in body.lines()) {
            val trimmed = line.trim()
            if (trimmed.length in 5..240) {
                val lower = trimmed.lowercase()
                val isZeroCounter = zeroErrorSanitizePatterns.any { it.containsMatchIn(trimmed) }
                if (!isZeroCounter && errorLineKeywords.any { lower.contains(it) }) {
                    return trimmed.take(200)
                }
            }
        }
        return extractFirstMeaningfulLine(body, subject)
    }

    private fun findBestWarningSnippet(subject: String, body: String): String {
        val warnKeywords = listOf("warning", "warn", "предупрежден", "внимание")
        for (line in body.lines()) {
            val trimmed = line.trim()
            if (trimmed.length in 5..240 && warnKeywords.any { trimmed.lowercase().contains(it) }) {
                return trimmed.take(200)
            }
        }
        return extractFirstMeaningfulLine(body, subject)
    }

    private fun extractRelevantLine(subject: String, body: String, matchedFragment: String): String {
        val needle = matchedFragment.trim()
        if (needle.isNotEmpty()) {
            body.lines().firstOrNull { it.contains(needle, ignoreCase = true) }?.trim()?.let { line ->
                if (line.isNotBlank()) return line.take(200)
            }
        }
        return findBestErrorSnippet(subject, body)
    }

    private fun extractFirstMeaningfulLine(body: String, fallbackSubject: String): String {
        val candidate = body.lines()
            .map { it.trim() }
            .firstOrNull { it.length >= 4 && !it.startsWith("---") && !it.startsWith("===") }
        return (candidate ?: fallbackSubject).take(200)
    }
}
