package by.csl.backupcontrol.data.local

import androidx.room.TypeConverter
import by.csl.backupcontrol.domain.model.BackupStatus

class Converters {
    @TypeConverter
    fun fromBackupStatus(status: BackupStatus): String = status.name

    @TypeConverter
    fun toBackupStatus(value: String): BackupStatus =
        runCatching { BackupStatus.valueOf(value) }.getOrDefault(BackupStatus.UNKNOWN)
}
