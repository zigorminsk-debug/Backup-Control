package com.zigorminsk.backupcontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReleaseRulesTest {
    @Test public void readsMonotonicBuildNumberFromReleaseTags() {
        assertEquals(123, ReleaseRules.versionCode("v1.0.123"));
        assertEquals(123, ReleaseRules.versionCode("v2.4.123"));
        assertEquals(-1, ReleaseRules.versionCode("v1.0.123-beta"));
        assertEquals(-1, ReleaseRules.versionCode("v1.0.0"));
        assertEquals(-1, ReleaseRules.versionCode("v1.0.999999999999"));
    }

    @Test public void onlyAllowsTheExpectedGithubReleaseAsset() {
        assertTrue(ReleaseRules.trustedAssetUrl("https://github.com/zigorminsk-debug/Backup-Control/releases/download/v1.0.12/Backup-Control.apk"));
        assertFalse(ReleaseRules.trustedAssetUrl("http://github.com/zigorminsk-debug/Backup-Control/releases/download/v1.0.12/Backup-Control.apk"));
        assertFalse(ReleaseRules.trustedAssetUrl("https://github.com.evil.test/zigorminsk-debug/Backup-Control/releases/download/v1.0.12/Backup-Control.apk"));
        assertFalse(ReleaseRules.trustedAssetUrl("https://github.com/evil/Backup-Control/releases/download/v1.0.12/Backup-Control.apk"));
        assertFalse(ReleaseRules.trustedAssetUrl("https://github.com/zigorminsk-debug/Backup-Control/releases/download/v1.0.12/Other.apk"));
        assertFalse(ReleaseRules.trustedAssetUrl(null));
    }
}
