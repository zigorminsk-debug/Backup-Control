package by.csl.backupcontrol.data.mail

import by.csl.backupcontrol.domain.analyzer.EmailTextDecoder
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeUtility

data class ExtractedEnvelope(
    val uidKey: String,
    val receivedAtMillis: Long,
    val rawFrom: String,
    val structuredEmail: String?,
    val structuredPersonal: String?,
    val subject: String
)

object MimeMessageExtractor {

    private const val MAX_BODY_BYTES = 65_536
    private val charsetParamRegex = Regex("(?i)charset\\s*=\\s*\"?([^;\"\\s]+)\"?")

    fun extractEnvelope(
        message: Message,
        folderName: String,
        uid: Long?
    ): ExtractedEnvelope {
        val receivedDate = runCatching { message.receivedDate }.getOrNull()
            ?: runCatching { message.sentDate }.getOrNull()
        val receivedMillis = receivedDate?.time ?: System.currentTimeMillis()

        val fromAddresses = runCatching { message.from }.getOrNull()
        val firstAddr = fromAddresses?.firstOrNull() as? InternetAddress
        val structuredEmail = firstAddr?.address?.trim()
        val structuredPersonal = firstAddr?.personal?.let { raw ->
            decodeMimeString(raw)
        }

        val rawFromHeader = runCatching {
            message.getHeader("From")?.firstOrNull()
        }.getOrNull()?.let { decodeMimeString(it) }.orEmpty()

        val rawSubjectHeader = runCatching {
            message.getHeader("Subject")?.firstOrNull()
        }.getOrNull()

        val subject = when {
            !rawSubjectHeader.isNullOrBlank() -> decodeMimeString(rawSubjectHeader)
            else -> runCatching { decodeMimeString(message.subject ?: "") }.getOrDefault("")
        }

        val effectiveUidKey = if (uid != null && uid > 0L) {
            "$folderName:uid:$uid"
        } else {
            val msgId = runCatching { message.getHeader("Message-ID")?.firstOrNull() }.getOrNull()
            if (!msgId.isNullOrBlank()) {
                "$folderName:msgid:${msgId.trim()}"
            } else {
                "$folderName:fallback:${structuredEmail.orEmpty()}:$receivedMillis:${subject.hashCode()}"
            }
        }

        return ExtractedEnvelope(
            uidKey = effectiveUidKey,
            receivedAtMillis = receivedMillis,
            rawFrom = rawFromHeader.ifBlank { firstAddr?.toUnicodeString().orEmpty() },
            structuredEmail = structuredEmail,
            structuredPersonal = structuredPersonal,
            subject = subject
        )
    }

    /**
     * Extracts inline text body from [Part] while skipping binary/file attachments
     * and capping stream size to [MAX_BODY_BYTES].
     */
    fun extractBodyText(part: Part): String {
        val plainParts = mutableListOf<String>()
        val htmlParts = mutableListOf<String>()
        collectTextParts(part, plainParts, htmlParts, depth = 0)

        val plainCombined = plainParts.joinToString("\n").trim()
        val htmlCombined = htmlParts.joinToString("\n") { EmailTextDecoder.htmlToPlainText(it) }.trim()

        return when {
            plainCombined.length >= 20 && htmlCombined.isEmpty() -> plainCombined
            plainCombined.isNotEmpty() && htmlCombined.isNotEmpty() -> {
                // Veeam & Acronis sometimes put a tiny plain stub and full table in HTML
                if (plainCombined.length < 40 && htmlCombined.length > plainCombined.length) {
                    "$plainCombined\n$htmlCombined"
                } else {
                    plainCombined
                }
            }
            plainCombined.isNotEmpty() -> plainCombined
            else -> htmlCombined
        }.take(32_000)
    }

    private fun collectTextParts(
        part: Part,
        plainCollector: MutableList<String>,
        htmlCollector: MutableList<String>,
        depth: Int
    ) {
        if (depth > 8) return
        if (plainCollector.sumOf { it.length } > 32_000) return

        val disposition = runCatching { part.disposition }.getOrNull()
        val fileName = runCatching { part.fileName }.getOrNull()

        // Skip binary attachments (archives, databases, large logs), except small .txt/.log text reports
        if (disposition != null && disposition.equals(Part.ATTACHMENT, ignoreCase = true)) {
            val lowerFile = fileName?.lowercase().orEmpty()
            val isSmallTextLog = (lowerFile.endsWith(".txt") || lowerFile.endsWith(".log")) &&
                runCatching { part.size }.getOrDefault(Int.MAX_VALUE) in 1..MAX_BODY_BYTES
            if (!isSmallTextLog) return
        }

        val contentType = runCatching { part.contentType }.getOrNull()?.lowercase().orEmpty()

        when {
            contentType.startsWith("text/plain") || (contentType.isBlank() && depth == 0) -> {
                readTextFromPart(part, contentType)?.let {
                    if (it.isNotBlank()) plainCollector += it
                }
            }
            contentType.startsWith("text/html") -> {
                readTextFromPart(part, contentType)?.let {
                    if (it.isNotBlank()) htmlCollector += it
                }
            }
            contentType.startsWith("multipart/") -> {
                val content = runCatching { part.content }.getOrNull()
                if (content is Multipart) {
                    val count = runCatching { content.count }.getOrDefault(0)
                    for (i in 0 until count) {
                        val subPart = runCatching { content.getBodyPart(i) }.getOrNull() ?: continue
                        collectTextParts(subPart, plainCollector, htmlCollector, depth + 1)
                    }
                }
            }
        }
    }

    private fun readTextFromPart(part: Part, contentTypeHeader: String): String? {
        return runCatching {
            val bytes = part.inputStream.use { readLimitedBytes(it, MAX_BODY_BYTES) }
            if (bytes.isEmpty()) return@runCatching ""

            val explicitCharset = charsetParamRegex.find(contentTypeHeader)?.groupValues?.getOrNull(1)
            if (!explicitCharset.isNullOrBlank()) {
                return@runCatching String(bytes, EmailTextDecoder.resolveCharset(explicitCharset))
            }

            // Auto-detect UTF-8 vs Windows-1251 when sender omitted charset parameter
            decodeBytesSmartCyrillic(bytes)
        }.orElseGet {
            runCatching { (part.content as? String)?.take(MAX_BODY_BYTES) }.getOrNull()
        }
    }

    private fun decodeBytesSmartCyrillic(bytes: ByteArray): String {
        val utf8Decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return try {
            utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, EmailTextDecoder.resolveCharset("windows-1251"))
        }
    }

    private fun readLimitedBytes(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(4096)
        val out = ByteArrayOutputStream(minOf(maxBytes, 8192))
        var total = 0
        while (total < maxBytes) {
            val toRead = minOf(buffer.size, maxBytes - total)
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun decodeMimeString(raw: String): String {
        if (raw.isBlank()) return ""
        val firstPass = runCatching { MimeUtility.decodeText(raw) }.getOrDefault(raw)
        return EmailTextDecoder.decodeHeader(firstPass)
    }

    private fun <T> Result<T>.orElseGet(fallback: () -> T?): T? {
        return getOrNull() ?: fallback()
    }
}
