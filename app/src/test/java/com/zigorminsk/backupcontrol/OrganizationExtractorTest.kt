package com.zigorminsk.backupcontrol

import com.zigorminsk.backupcontrol.domain.analyzer.OrganizationExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OrganizationExtractorTest {

    @Test
    fun `extracts organization display name and email from standard From header`() {
        val res = OrganizationExtractor.extract(
            rawFromHeader = "\"ООО СтройМир\" <backup@stroymir.by>",
            monitoredMailboxEmail = "backup@csl.by"
        )
        assertEquals("backup@stroymir.by", res.orgKey)
        assertEquals("ООО СтройМир", res.suggestedDisplayName)
        assertEquals("backup@stroymir.by", res.senderEmail)
    }

    @Test
    fun `combines corporate domain when personal name is generic Veeam`() {
        val res = OrganizationExtractor.extract(
            rawFromHeader = "\"Veeam Backup\" <veeam@romashka.by>",
            monitoredMailboxEmail = "backup@csl.by"
        )
        assertEquals("veeam@romashka.by", res.orgKey)
        assertEquals("romashka.by (Veeam Backup)", res.suggestedDisplayName)
    }

    @Test
    fun `keeps distinct organizations separate when relaying via shared backup at csl by`() {
        val clientA = OrganizationExtractor.extract(
            rawFromHeader = "\"ООО АльфаТрейд\" <backup@csl.by>",
            monitoredMailboxEmail = "backup@csl.by"
        )
        val clientB = OrganizationExtractor.extract(
            rawFromHeader = "\"ЗАО БетаСервис\" <backup@csl.by>",
            monitoredMailboxEmail = "backup@csl.by"
        )
        assertNotEquals(clientA.orgKey, clientB.orgKey)
        assertEquals("ООО АльфаТрейд", clientA.suggestedDisplayName)
        assertEquals("ЗАО БетаСервис", clientB.suggestedDisplayName)
    }
}
