package com.zigorminsk.backupcontrol.domain.model

data class SyncProgress(
    val isRunning: Boolean = false,
    val isInitialSixMonthScan: Boolean = false,
    val stageText: String = "",
    val processedMessages: Int = 0,
    val totalMessages: Int = 0,
    val discoveredOrganizations: Int = 0,
    val lastError: String? = null
) {
    val progressFraction: Float?
        get() = if (totalMessages > 0) {
            (processedMessages.toFloat() / totalMessages.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
}
