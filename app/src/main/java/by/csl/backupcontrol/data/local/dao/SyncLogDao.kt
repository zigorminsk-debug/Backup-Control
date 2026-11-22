package by.csl.backupcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import by.csl.backupcontrol.data.local.entity.SyncLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncLogDao {

    @Query("SELECT * FROM sync_logs ORDER BY startedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<SyncLogEntity>>

    @Insert
    suspend fun insert(log: SyncLogEntity): Long

    @Query(
        """
        DELETE FROM sync_logs
        WHERE id NOT IN (
            SELECT id FROM sync_logs
            ORDER BY startedAtMillis DESC
            LIMIT :keepCount
        )
        """
    )
    suspend fun trimOld(keepCount: Int = 100)

    @Query("DELETE FROM sync_logs")
    suspend fun deleteAll()
}
