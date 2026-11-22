package by.csl.backupcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import by.csl.backupcontrol.data.local.entity.BackupEmailEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupEmailDao {

    @Query("SELECT * FROM backup_emails WHERE organizationId = :organizationId ORDER BY receivedAtMillis DESC LIMIT :limit")
    fun observeForOrganization(organizationId: Long, limit: Int = 50): Flow<List<BackupEmailEntity>>

    @Query("SELECT * FROM backup_emails WHERE receivedAtMillis >= :sinceMillis ORDER BY receivedAtMillis DESC")
    fun observeRecentSince(sinceMillis: Long): Flow<List<BackupEmailEntity>>

    @Query("SELECT * FROM backup_emails WHERE receivedAtMillis >= :sinceMillis ORDER BY receivedAtMillis DESC")
    suspend fun getRecentSince(sinceMillis: Long): List<BackupEmailEntity>

    @Query("SELECT folderUidKey FROM backup_emails WHERE folderUidKey IN (:keys)")
    suspend fun findExistingUidKeys(keys: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<BackupEmailEntity>)

    @Query(
        """
        DELETE FROM backup_emails
        WHERE organizationId = :orgId
          AND id NOT IN (
              SELECT id FROM backup_emails
              WHERE organizationId = :orgId
              ORDER BY receivedAtMillis DESC
              LIMIT :keepCount
          )
        """
    )
    suspend fun trimOldForOrganization(orgId: Long, keepCount: Int = 40)

    @Query("DELETE FROM backup_emails")
    suspend fun deleteAll()
}
