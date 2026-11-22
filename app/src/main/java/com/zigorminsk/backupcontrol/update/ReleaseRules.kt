package com.zigorminsk.backupcontrol.update

import java.net.URI

object ReleaseRules {
    private val RELEASE_TAG = Regex("^v[0-9]+\\.[0-9]+\\.([0-9]+)$")
    private val RELEASE_PATH = Regex("^/zigorminsk-debug/Backup-Control/releases/download/v[0-9]+\\.[0-9]+\\.[0-9]+/Backup-Control\\.apk$")

    fun versionCode(tag: String?): Int {
        if (tag.isNullOrBlank()) return -1
        val match = RELEASE_TAG.matchEntire(tag.trim()) ?: return -1
        val code = match.groupValues[1].toIntOrNull() ?: return -1
        return if (code > 0) code else -1
    }

    fun trustedAssetUrl(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return try {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("github.com", ignoreCase = true) &&
                uri.userInfo == null &&
                uri.port == -1 &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawPath != null &&
                RELEASE_PATH.matches(uri.rawPath)
        } catch (_: Exception) {
            false
        }
    }
}
