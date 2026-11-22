package com.zigorminsk.backupcontrol

import com.zigorminsk.backupcontrol.domain.analyzer.EmailTextDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.util.Base64

class EmailTextDecoderTest {

    @Test
    fun `decodes utf8 and windows1251 rfc2047 headers`() {
        val utf8Encoded = "=?UTF-8?B?" +
            Base64.getEncoder().encodeToString("Ошибка бэкапа 1С".toByteArray(Charsets.UTF_8)) +
            "?="
        assertEquals("Ошибка бэкапа 1С", EmailTextDecoder.decodeHeader(utf8Encoded))

        val cp1251 = Charset.forName("windows-1251")
        val winEncoded = "=?windows-1251?B?" +
            Base64.getEncoder().encodeToString("ООО Ромашка".toByteArray(cp1251)) +
            "?="
        assertEquals("ООО Ромашка", EmailTextDecoder.decodeHeader(winEncoded))
    }

    @Test
    fun `converts html backup report table into readable text`() {
        val html = """
            <html>
              <head><style>body { color: black; }</style></head>
              <body>
                <table>
                  <tr><td>Job Name</td><td>SQL_Nightly</td></tr>
                  <tr><td>Status</td><td>Failed</td></tr>
                  <tr><td>Details</td><td>Disk D: is full</td></tr>
                </table>
              </body>
            </html>
        """.trimIndent()

        val plain = EmailTextDecoder.htmlToPlainText(html)
        assertTrue(plain.contains("Status : Failed"))
        assertTrue(plain.contains("Disk D: is full"))
    }
}
