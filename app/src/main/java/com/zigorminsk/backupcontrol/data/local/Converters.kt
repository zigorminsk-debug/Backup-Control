package com.zigorminsk.backupcontrol.data.local

import androidx.room.TypeConverter
import com.zigorminsk.backupcontrol.domain.model.BackupStatus
import com.zigorminsk.backupcontrol.domain.model.OrganizationApprovalState

class Converters {
    @TypeConverter
    fun fromBackupStatus(status: BackupStatus): String = status.name

    @TypeConverter
    fun toBackupStatus(value: String): BackupStatus =
        runCatching { BackupStatus.valueOf(value) }.getOrDefault(BackupStatus.UNKNOWN)

    @TypeConverter
    fun fromApprovalState(state: OrganizationApprovalState): String = state.name

    @TypeConverter
    fun toApprovalState(value: String): OrganizationApprovalState =
        runCatching { OrganizationApprovalState.valueOf(value) }.getOrDefault(OrganizationApprovalState.SAVED)
}
