package by.csl.backupcontrol.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_logs")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val triggerType: String,
    val success: Boolean,
    val scannedMessages: Int,
    val organizationsCount: Int,
    val errorOrgsCount: Int,
    val missingOrgsCount: Int,
    val bannerShown: Boolean,
    val message: String
)
