package com.zigorminsk.backupcontrol.data.mail

import com.zigorminsk.backupcontrol.domain.analyzer.BackupStatusAnalyzer
import com.zigorminsk.backupcontrol.domain.analyzer.OrganizationExtractor
import com.zigorminsk.backupcontrol.domain.model.AppSettings
import com.zigorminsk.backupcontrol.domain.model.BackupStatus
import com.zigorminsk.backupcontrol.domain.model.MailSecurityMode
import com.sun.mail.util.MailConnectException
import com.sun.mail.util.MailSSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Date
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm
import kotlin.coroutines.coroutineContext

data class ResolvedImapEndpoint(
    val host: String,
    val port: Int,
    val securityMode: MailSecurityMode
)

data class ConnectionTestResult(
    val endpoint: ResolvedImapEndpoint,
    val totalMessagesInFolder: Int,
    val availableFolders: List<String>
)

data class FetchedMailItem(
    val uidKey: String,
    val receivedAtMillis: Long,
    val orgKey: String,
    val suggestedOrgName: String,
    val senderEmail: String,
    val senderPersonal: String,
    val rawFrom: String,
    val isLikelySystemBounce: Boolean,
    val subject: String,
    val snippet: String,
    val status: BackupStatus,
    val normalizedJobKey: String
)

class ImapMailClient {

    suspend fun testConnection(settings: AppSettings): ConnectionTestResult = withContext(Dispatchers.IO) {
        val (store, endpoint) = connectWithOptionalDiscovery(settings)
        store.use { activeStore ->
            val folderNames = runCatching {
                activeStore.defaultFolder.list("*")
                    .mapNotNull { it.fullName }
                    .take(20)
            }.getOrDefault(listOf("INBOX"))

            val folder = activeStore.getFolder(settings.folderName.ifBlank { "INBOX" })
            if (!folder.exists()) {
                throw MessagingException("Папка '${settings.folderName}' не найдена. Доступные папки: ${folderNames.joinToString(", ")}")
            }
            folder.open(Folder.READ_ONLY)
            try {
                val count = folder.messageCount
                ConnectionTestResult(
                    endpoint = endpoint,
                    totalMessagesInFolder = count,
                    availableFolders = folderNames
                )
            } finally {
                runCatching { folder.close(false) }
            }
        }
    }

    suspend fun fetchMessagesSince(
        settings: AppSettings,
        sinceEpochMillis: Long,
        isInitialSixMonthScan: Boolean,
        knownUidKeysProvider: suspend (List<String>) -> Set<String>,
        onProgress: suspend (processed: Int, total: Int, discoveredOrgs: Int, stage: String) -> Unit
    ): Pair<ResolvedImapEndpoint, List<FetchedMailItem>> = withContext(Dispatchers.IO) {
        onProgress(0, 0, 0, "Подключение к почтовому серверу ${settings.imapHost}…")
        val (store, endpoint) = connectWithOptionalDiscovery(settings)

        store.use { activeStore ->
            val folderName = settings.folderName.ifBlank { "INBOX" }
            val folder = activeStore.getFolder(folderName)
            if (!folder.exists()) {
                throw MessagingException("Папка '$folderName' не найдена в почтовом ящике ${settings.email}")
            }

            folder.open(Folder.READ_ONLY)
            try {
                onProgress(0, 0, 0, "Поиск писем в папке $folderName…")
                val sinceDate = Date(sinceEpochMillis)
                val messages: Array<Message> = runCatching {
                    folder.search(ReceivedDateTerm(ComparisonTerm.GE, sinceDate))
                }.getOrElse {
                    // Fallback if an older IMAP server does not support SINCE search properly
                    val count = folder.messageCount
                    if (count <= 0) emptyArray() else folder.getMessages(maxOf(1, count - 3000), count)
                }

                val total = messages.size
                if (total == 0) {
                    onProgress(0, 0, 0, "Новых писем за выбранный период не найдено")
                    return@use endpoint to emptyList()
                }

                val customErrKw = parseKeywords(settings.customErrorKeywords)
                val customOkKw = parseKeywords(settings.customSuccessKeywords)

                val uidFolder = folder as? UIDFolder
                val envelopeStageItems = ArrayList<StagingEnvelopeItem>(total)
                val seenOrgs = HashSet<String>()

                val batchSize = 150
                var processed = 0

                // Pass 1: Fast batch fetch of ENVELOPE + UID across all messages
                for (batchStart in messages.indices step batchSize) {
                    coroutineContext.ensureActive()
                    val batchEnd = minOf(batchStart + batchSize, total)
                    val batch = messages.sliceArray(batchStart until batchEnd)

                    val fetchProfile = FetchProfile().apply {
                        add(FetchProfile.Item.ENVELOPE)
                        add(FetchProfile.Item.CONTENT_INFO)
                        if (uidFolder != null) {
                            add(UIDFolder.FetchProfileItem.UID)
                        }
                    }
                    folder.fetch(batch, fetchProfile)

                    for (msg in batch) {
                        coroutineContext.ensureActive()
                        val uid = runCatching { uidFolder?.getUID(msg) }.getOrNull()
                        val env = MimeMessageExtractor.extractEnvelope(msg, folderName, uid)
                        if (env.receivedAtMillis < sinceEpochMillis - 86_400_000L) {
                            continue
                        }
                        val extractedOrg = OrganizationExtractor.extract(
                            rawFromHeader = env.rawFrom,
                            structuredEmail = env.structuredEmail,
                            structuredPersonal = env.structuredPersonal,
                            monitoredMailboxEmail = settings.email
                        )
                        seenOrgs += extractedOrg.orgKey
                        envelopeStageItems += StagingEnvelopeItem(
                            message = msg,
                            envelope = env,
                            orgKey = extractedOrg.orgKey,
                            suggestedOrgName = extractedOrg.suggestedDisplayName,
                            senderEmail = extractedOrg.senderEmail,
                            senderPersonal = extractedOrg.senderPersonal,
                            isSystemBounce = OrganizationExtractor.isLikelySystemBounceSender(extractedOrg.senderEmail)
                        )
                    }

                    processed = batchEnd
                    val stageLabel = if (isInitialSixMonthScan) {
                        "Сбор за ${settings.initialLookbackMonths} мес.: заголовки $processed из $total"
                    } else {
                        "Чтение заголовков: $processed из $total"
                    }
                    onProgress(processed, total, seenOrgs.size, stageLabel)
                }

                // Filter out messages already stored in DB during incremental daily sync
                val itemsToProcess = if (!isInitialSixMonthScan && envelopeStageItems.isNotEmpty()) {
                    val allKeys = envelopeStageItems.map { it.envelope.uidKey }
                    val existing = HashSet<String>()
                    for (chunk in allKeys.chunked(400)) {
                        existing += knownUidKeysProvider(chunk)
                    }
                    envelopeStageItems.filterNot { it.envelope.uidKey in existing }
                } else {
                    envelopeStageItems
                }

                // Determine which messages require downloading the inline text body:
                // - Incremental check: ALL new messages!
                // - Initial 6-month scan: latest 3 messages of EVERY organization + all messages from last 10 days
                val bodyTargetUidKeys: Set<String> = if (!isInitialSixMonthScan) {
                    itemsToProcess.mapTo(HashSet()) { it.envelope.uidKey }
                } else {
                    val recentCutoff = System.currentTimeMillis() - 10L * 86_400_000L
                    val keys = HashSet<String>()
                    itemsToProcess
                        .groupBy { it.orgKey }
                        .values
                        .forEach { orgMsgs ->
                            orgMsgs.sortedByDescending { it.envelope.receivedAtMillis }
                                .take(3)
                                .forEach { keys += it.envelope.uidKey }
                        }
                    itemsToProcess.forEach { item ->
                        if (item.envelope.receivedAtMillis >= recentCutoff) {
                            keys += item.envelope.uidKey
                        }
                    }
                    keys
                }

                val results = ArrayList<FetchedMailItem>(itemsToProcess.size)
                var bodyProcessed = 0
                val bodyTotal = bodyTargetUidKeys.size.coerceAtLeast(1)

                for (item in itemsToProcess) {
                    coroutineContext.ensureActive()
                    val needBody = item.envelope.uidKey in bodyTargetUidKeys
                    val bodyText = if (needBody) {
                        bodyProcessed++
                        if (bodyProcessed == 1 || bodyProcessed % 10 == 0 || bodyProcessed == bodyTotal) {
                            onProgress(
                                bodyProcessed,
                                bodyTotal,
                                seenOrgs.size,
                                "Анализ содержимого отчётов: $bodyProcessed из $bodyTotal"
                            )
                        }
                        runCatching { MimeMessageExtractor.extractBodyText(item.message) }.getOrDefault("")
                    } else {
                        ""
                    }

                    val analysis = BackupStatusAnalyzer.analyze(
                        subject = item.envelope.subject,
                        body = bodyText,
                        customErrorKeywords = customErrKw,
                        customSuccessKeywords = customOkKw
                    )

                    results += FetchedMailItem(
                        uidKey = item.envelope.uidKey,
                        receivedAtMillis = item.envelope.receivedAtMillis,
                        orgKey = item.orgKey,
                        suggestedOrgName = item.suggestedOrgName,
                        senderEmail = item.senderEmail,
                        senderPersonal = item.senderPersonal,
                        rawFrom = item.envelope.rawFrom,
                        isLikelySystemBounce = item.isSystemBounce,
                        subject = item.envelope.subject,
                        snippet = analysis.diagnosticSnippet,
                        status = analysis.status,
                        normalizedJobKey = analysis.normalizedJobKey
                    )
                }

                endpoint to results
            } finally {
                runCatching { folder.close(false) }
            }
        }
    }

    suspend fun autoDiscoverEndpoint(settings: AppSettings): ResolvedImapEndpoint = withContext(Dispatchers.IO) {
        val (store, endpoint) = connectWithOptionalDiscovery(settings, forceDiscovery = true)
        runCatching { store.close() }
        endpoint
    }

    private fun connectWithOptionalDiscovery(
        settings: AppSettings,
        forceDiscovery: Boolean = false
    ): Pair<Store, ResolvedImapEndpoint> {
        val primary = ResolvedImapEndpoint(
            host = settings.imapHost.trim(),
            port = settings.imapPort,
            securityMode = settings.securityMode
        )

        if (!forceDiscovery) {
            try {
                val store = openConnectedStore(settings, primary)
                return store to primary
            } catch (authEx: AuthenticationFailedException) {
                // Wrong login/password on a reachable server -> report immediately, don't retry wrong password elsewhere
                throw MessagingException(
                    "Ошибка авторизации для ${settings.email} на сервере ${primary.host}:${primary.port}. Проверьте логин и пароль.",
                    authEx
                )
            } catch (connectEx: Exception) {
                if (!settings.autoDetectImapHost || !isConnectionLevelFailure(connectEx)) {
                    throw MessagingException(buildReadableConnectionError(primary, connectEx), connectEx)
                }
            }
        }

        val candidates = buildCandidateEndpoints(settings.email, primary)
        var lastError: Exception? = null

        for (candidate in candidates) {
            try {
                val store = openConnectedStore(settings, candidate)
                return store to candidate
            } catch (authEx: AuthenticationFailedException) {
                // Server responded to IMAP and checked credentials — if host matches domain, stop immediately
                val domain = settings.email.substringAfter('@', "").lowercase()
                if (domain.isNotBlank() && candidate.host.lowercase().contains(domain)) {
                    throw MessagingException(
                        "Сервер ${candidate.host}:${candidate.port} найден, но отклонил логин или пароль для ${settings.email}.",
                        authEx
                    )
                }
                lastError = authEx
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw MessagingException(
            buildReadableConnectionError(primary, lastError ?: MessagingException("Не удалось подключиться к IMAP")),
            lastError
        )
    }

    private fun openConnectedStore(
        settings: AppSettings,
        endpoint: ResolvedImapEndpoint
    ): Store {
        val protocol = if (endpoint.securityMode == MailSecurityMode.SSL_TLS) "imaps" else "imap"
        val props = Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.host", endpoint.host)
            put("mail.$protocol.port", endpoint.port.toString())
            put("mail.$protocol.connectiontimeout", "15000")
            put("mail.$protocol.timeout", "30000")
            put("mail.$protocol.fetchsize", "65536")
            put("mail.$protocol.partialfetch", "true")

            when (endpoint.securityMode) {
                MailSecurityMode.SSL_TLS -> {
                    put("mail.imaps.ssl.enable", "true")
                    configureSslSocketFactory(this, "mail.imaps", settings.trustSelfSignedCerts, endpoint.host)
                }
                MailSecurityMode.STARTTLS -> {
                    put("mail.imap.starttls.enable", "true")
                    put("mail.imap.starttls.required", "true")
                    configureSslSocketFactory(this, "mail.imap", settings.trustSelfSignedCerts, endpoint.host)
                }
                MailSecurityMode.PLAIN -> {
                    put("mail.imap.starttls.enable", "false")
                }
            }
        }

        val session = Session.getInstance(props, null)
        val store = session.getStore(protocol)
        store.connect(endpoint.host, endpoint.port, settings.email.trim(), settings.password)
        return store
    }

    private fun configureSslSocketFactory(
        props: Properties,
        prefix: String,
        trustSelfSigned: Boolean,
        host: String
    ) {
        if (trustSelfSigned) {
            val sslFactory = MailSSLSocketFactory().apply {
                isTrustAllHosts = true
            }
            props["$prefix.ssl.socketFactory"] = sslFactory
            props["$prefix.ssl.socketFactory.fallback"] = "false"
            props["$prefix.ssl.checkserveridentity"] = "false"
            props["$prefix.ssl.trust"] = "*"
        } else {
            props["$prefix.ssl.checkserveridentity"] = "true"
            props["$prefix.ssl.trust"] = host
        }
    }

    private fun buildCandidateEndpoints(
        email: String,
        primary: ResolvedImapEndpoint
    ): List<ResolvedImapEndpoint> {
        val domain = email.substringAfter('@', "csl.by").trim().lowercase().ifBlank { "csl.by" }
        val rawCandidates = listOf(
            primary,
            ResolvedImapEndpoint("mail.$domain", 993, MailSecurityMode.SSL_TLS),
            ResolvedImapEndpoint("imap.$domain", 993, MailSecurityMode.SSL_TLS),
            ResolvedImapEndpoint(domain, 993, MailSecurityMode.SSL_TLS),
            ResolvedImapEndpoint("mail.$domain", 143, MailSecurityMode.STARTTLS),
            ResolvedImapEndpoint("imap.$domain", 143, MailSecurityMode.STARTTLS),
            ResolvedImapEndpoint("mail.hoster.by", 993, MailSecurityMode.SSL_TLS),
            ResolvedImapEndpoint("imap.yandex.by", 993, MailSecurityMode.SSL_TLS),
            ResolvedImapEndpoint("imap.yandex.ru", 993, MailSecurityMode.SSL_TLS),
            ResolvedImapEndpoint("imap.mail.ru", 993, MailSecurityMode.SSL_TLS),
            ResolvedImapEndpoint("outlook.office365.com", 993, MailSecurityMode.SSL_TLS)
        )
        return rawCandidates.distinct()
    }

    private fun isConnectionLevelFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is UnknownHostException ||
                current is ConnectException ||
                current is SocketTimeoutException ||
                current is MailConnectException
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun buildReadableConnectionError(endpoint: ResolvedImapEndpoint, error: Throwable): String {
        val detail = error.localizedMessage ?: error.javaClass.simpleName
        return "Не удалось подключиться к IMAP (${endpoint.host}:${endpoint.port}, ${endpoint.securityMode.titleRu}): $detail"
    }

    private fun parseKeywords(csv: String): List<String> {
        if (csv.isBlank()) return emptyList()
        return csv.split(",", ";", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private inline fun <R> Store.use(block: (Store) -> R): R {
        try {
            return block(this)
        } finally {
            runCatching { close() }
        }
    }

    private data class StagingEnvelopeItem(
        val message: Message,
        val envelope: ExtractedEnvelope,
        val orgKey: String,
        val suggestedOrgName: String,
        val senderEmail: String,
        val senderPersonal: String,
        val isSystemBounce: Boolean
    )
}
