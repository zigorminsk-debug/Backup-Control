package by.csl.backupcontrol.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import by.csl.backupcontrol.domain.model.BackupStatus

@Entity(
    tableName = "backup_emails",
    foreignKeys = [
        ForeignKey(
            entity = OrganizationEntity::class,
            parentColumns = ["id"],
            childColumns = ["organizationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["organizationId", "receivedAtMillis"]),
        Index(value = ["folderUidKey"], unique = true)
    ]
)
data class BackupEmailEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val organizationId: Long,
    val folderUidKey: String,
    val receivedAtMillis: Long,
    val senderRaw: String,
    val subject: String,
    val snippet: String,
    val status: BackupStatus,
    val normalizedJobKey: String
)
