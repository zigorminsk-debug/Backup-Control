package by.csl.backupcontrol.domain.analyzer

data class ExtractedOrganization(
    val orgKey: String,
    val suggestedDisplayName: String,
    val senderEmail: String,
    val senderPersonal: String
)

object OrganizationExtractor {

    private val angleEmailRegex = Regex("^(.*?)<([^<>@\\s]+@[^<>@\\s]+)>\\s*$")
    private val parenNameRegex = Regex("^([^()\\s]+@[^()\\s]+)\\s*\\((.+)\\)\\s*$")
    private val plainEmailRegex = Regex("([a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,})")

    private val genericSenderNames = setOf(
        "backup", "backups", "veeam", "veeam backup", "veeam one",
        "cobian", "cobian backup", "cobian reflector", "acronis",
        "acronis cyber backup", "acronis cyber protect", "uranium",
        "uranium backup", "1c", "1с", "sql", "sql server", "postgres",
        "postgresql", "proxmox", "vzdump", "synology", "hyper backup",
        "root", "admin", "administrator", "администратор", "бэкап",
        "резервное копирование", "архивация", "no-reply", "noreply",
        "notify", "notification", "alert", "alerts", "postmaster",
        "mailer-daemon", "cron", "cron daemon", "system"
    )

    private val publicMailDomains = setOf(
        "gmail.com", "yandex.by", "yandex.ru", "ya.ru", "mail.ru",
        "bk.ru", "inbox.ru", "list.ru", "tut.by", "outlook.com",
        "hotmail.com", "icloud.com", "rambler.ru"
    )

    fun extract(
        rawFromHeader: String?,
        structuredEmail: String? = null,
        structuredPersonal: String? = null,
        monitoredMailboxEmail: String = "backup@csl.by"
    ): ExtractedOrganization {
        val decodedPersonal = EmailTextDecoder.decodeHeader(structuredPersonal)
        val decodedRaw = EmailTextDecoder.decodeHeader(rawFromHeader)

        var email = structuredEmail?.trim()?.lowercase().orEmpty()
        var personal = cleanPersonal(decodedPersonal)

        if (email.isBlank() || personal.isBlank()) {
            val parsed = parseRawFrom(decodedRaw)
            if (email.isBlank()) email = parsed.first
            if (personal.isBlank()) personal = parsed.second
        }

        val normalizedMailbox = monitoredMailboxEmail.trim().lowercase()
        val mailboxDomain = normalizedMailbox.substringAfter('@', "")
        val senderDomain = email.substringAfter('@', "")

        val isSharedRelayAddress = email.isNotBlank() && (
            email == normalizedMailbox ||
                (mailboxDomain.isNotBlank() && senderDomain == mailboxDomain)
            )

        val orgKey = when {
            isSharedRelayAddress && personal.isNotBlank() ->
                "$email#${personal.lowercase()}"
            email.isNotBlank() ->
                email
            personal.isNotBlank() ->
                "name#${personal.lowercase()}"
            else ->
                "unknown"
        }

        val displayName = buildDisplayName(
            email = email,
            personal = personal,
            senderDomain = senderDomain,
            isSharedRelayAddress = isSharedRelayAddress
        )

        return ExtractedOrganization(
            orgKey = orgKey,
            suggestedDisplayName = displayName,
            senderEmail = email,
            senderPersonal = personal
        )
    }

    fun isLikelySystemBounceSender(senderEmail: String): Boolean {
        val local = senderEmail.substringBefore('@', "").lowercase()
        return local == "mailer-daemon" || local == "postmaster"
    }

    private fun parseRawFrom(decodedRaw: String): Pair<String, String> {
        if (decodedRaw.isBlank()) return "" to ""

        angleEmailRegex.find(decodedRaw)?.let { match ->
            val personal = cleanPersonal(match.groupValues[1])
            val email = match.groupValues[2].trim().lowercase()
            return email to personal
        }

        parenNameRegex.find(decodedRaw)?.let { match ->
            val email = match.groupValues[1].trim().lowercase()
            val personal = cleanPersonal(match.groupValues[2])
            return email to personal
        }

        plainEmailRegex.find(decodedRaw)?.let { match ->
            val email = match.groupValues[1].trim().lowercase()
            val rest = cleanPersonal(decodedRaw.replace(match.value, ""))
            return email to rest
        }

        return "" to cleanPersonal(decodedRaw)
    }

    private fun cleanPersonal(raw: String): String {
        val trimmed = raw
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
        return if (trimmed.contains('@') && !trimmed.contains(' ')) {
            ""
        } else {
            trimmed.replace(Regex("\\s+"), " ")
        }
    }

    private fun buildDisplayName(
        email: String,
        personal: String,
        senderDomain: String,
        isSharedRelayAddress: Boolean
    ): String {
        if (personal.isNotBlank()) {
            val normalizedPersonal = personal.lowercase()
            val isGeneric = normalizedPersonal in genericSenderNames
            if (!isGeneric || isSharedRelayAddress) {
                return personal
            }
            // Personal name is generic (e.g. "Veeam Backup") — combine with corporate domain or local-part
            if (senderDomain.isNotBlank() && senderDomain !in publicMailDomains) {
                return "$senderDomain ($personal)"
            }
            if (email.isNotBlank()) {
                return "$email ($personal)"
            }
            return personal
        }

        if (email.isNotBlank()) {
            val local = email.substringBefore('@')
            return if (senderDomain.isNotBlank() && senderDomain !in publicMailDomains) {
                if (local.lowercase() in genericSenderNames) {
                    senderDomain
                } else {
                    "$senderDomain ($local)"
                }
            } else {
                email
            }
        }

        return "Неизвестный отправитель"
    }
}
