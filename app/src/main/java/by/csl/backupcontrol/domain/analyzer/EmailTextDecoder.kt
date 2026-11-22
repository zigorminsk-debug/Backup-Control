package by.csl.backupcontrol.domain.analyzer

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64

object EmailTextDecoder {

    private val rfc2047Regex = Regex(
        pattern = "=\\?([^?\\s]+)\\?([bBqQ])\\?([^?]*)\\?=",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val betweenEncodedWordsRegex = Regex("\\?=\\s+=\\?")

    fun decodeHeader(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val collapsed = raw.replace(betweenEncodedWordsRegex, "?==?")
        if (!collapsed.contains("=?")) {
            return collapsed.trim()
        }
        return rfc2047Regex.replace(collapsed) { match ->
            val charsetName = match.groupValues[1]
            val encoding = match.groupValues[2].uppercase()
            val payload = match.groupValues[3]
            runCatching {
                val charset = resolveCharset(charsetName)
                val bytes = when (encoding) {
                    "B" -> Base64.getMimeDecoder().decode(payload)
                    "Q" -> decodeQuotedPrintableHeader(payload)
                    else -> payload.toByteArray(Charsets.UTF_8)
                }
                String(bytes, charset)
            }.getOrDefault(match.value)
        }.replace(Regex("\\s+"), " ").trim()
    }

    fun resolveCharset(name: String?): Charset {
        if (name.isNullOrBlank()) return Charsets.UTF_8
        val normalized = name.trim().lowercase().removeSurrounding("\"").removeSurrounding("'")
        val mapped = when (normalized) {
            "cp1251", "win-1251", "win1251", "windows1251" -> "windows-1251"
            "koi8r", "koi8" -> "KOI8-R"
            "koi8u" -> "KOI8-U"
            "utf8" -> "UTF-8"
            "cp866", "ibm866", "dos-866" -> "IBM866"
            else -> name.trim()
        }
        return runCatching { Charset.forName(mapped) }.getOrDefault(Charsets.UTF_8)
    }

    private fun decodeQuotedPrintableHeader(input: String): ByteArray {
        val out = ByteArrayOutputStream(input.length)
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                ch == '_' -> {
                    out.write(' '.code)
                    i++
                }
                ch == '=' && i + 2 < input.length -> {
                    val hex = input.substring(i + 1, i + 3)
                    val value = hex.toIntOrNull(16)
                    if (value != null) {
                        out.write(value)
                        i += 3
                    } else {
                        out.write(ch.code)
                        i++
                    }
                }
                else -> {
                    out.write(ch.code)
                    i++
                }
            }
        }
        return out.toByteArray()
    }

    fun htmlToPlainText(html: String): String {
        if (html.isBlank()) return ""
        var text = html
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<head[^>]*>.*?</head>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|tr|li|h[1-6]|table)>"), "\n")
            .replace(Regex("(?i)</t[dh]>"), " : ")
            .replace(Regex("(?is)<[^>]+>"), " ")

        text = decodeHtmlEntities(text)

        return text
            .lines()
            .map { line ->
                line.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
                    .replace(Regex("(:\\s*){2,}"), ": ")
                    .trim()
                    .trimEnd(':')
                    .trim()
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun decodeHtmlEntities(input: String): String {
        var result = input
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&mdash;", "—", ignoreCase = true)
            .replace("&ndash;", "–", ignoreCase = true)

        result = Regex("&#(\\d+);").replace(result) { match ->
            val code = match.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
        }
        result = Regex("&#x([0-9a-fA-F]+);").replace(result) { match ->
            val code = match.groupValues[1].toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
        }
        return result
    }
}
