package com.zigorminsk.backupcontrol

import com.zigorminsk.backupcontrol.update.ReleaseRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseRulesTest {

    @Test
    fun `parses valid release tag versionCode`() {
        assertEquals(54, ReleaseRules.versionCode("v1.0.54"))
        assertEquals(102, ReleaseRules.versionCode("v1.0.102"))
        assertEquals(-1, ReleaseRules.versionCode("1.0.54"))
        assertEquals(-1, ReleaseRules.versionCode("v1.0.0"))
        assertEquals(-1, ReleaseRules.versionCode("v1.0.beta"))
    }

    @Test
    fun `accepts only official Backup-Control github release asset url`() {
        assertTrue(
            ReleaseRules.trustedAssetUrl(
                "https://github.com/zigorminsk-debug/Backup-Control/releases/download/v1.0.102/Backup-Control.apk"
            )
        )
        assertFalse(
            ReleaseRules.trustedAssetUrl(
                "http://github.com/zigorminsk-debug/Backup-Control/releases/download/v1.0.102/Backup-Control.apk"
            )
        )
        assertFalse(
            ReleaseRules.trustedAssetUrl(
                "https://evil.example.com/zigorminsk-debug/Backup-Control/releases/download/v1.0.102/Backup-Control.apk"
            )
        )
    }
}
