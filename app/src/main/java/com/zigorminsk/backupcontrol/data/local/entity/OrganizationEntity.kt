package com.zigorminsk.backupcontrol.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zigorminsk.backupcontrol.domain.model.BackupStatus
import com.zigorminsk.backupcontrol.domain.model.OrganizationApprovalState

@Entity(
    tableName = "organizations",
    indices = [Index(value = ["orgKey"], unique = true)]
)
data class OrganizationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val orgKey: String,
    val displayName: String,
    val isCustomName: Boolean = false,
    val senderEmail: String,
    val additionalSenderEmails: String = "",
    val senderPersonal: String = "",
    val notes: String = "",
    val approvalState: OrganizationApprovalState = OrganizationApprovalState.SAVED,
    val isMonitored: Boolean = true,
    val isNewlyAdded: Boolean = false,
    val customMissingDays: Int? = null,
    val firstSeenAtMillis: Long,
    val lastEmailAtMillis: Long,
    val emailCount: Int = 0,
    val lastSubject: String = "",
    val lastSnippet: String = "",
    val lastBackupStatus: BackupStatus = BackupStatus.UNKNOWN,
    val acknowledgedAtMillis: Long = 0L
)
