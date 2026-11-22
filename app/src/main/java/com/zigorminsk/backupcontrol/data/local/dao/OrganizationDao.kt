package com.zigorminsk.backupcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zigorminsk.backupcontrol.data.local.entity.OrganizationEntity
import com.zigorminsk.backupcontrol.domain.model.OrganizationApprovalState
import kotlinx.coroutines.flow.Flow

@Dao
interface OrganizationDao {

    @Query("SELECT * FROM organizations ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<OrganizationEntity>>

    @Query("SELECT * FROM organizations")
    suspend fun getAll(): List<OrganizationEntity>

    @Query("SELECT * FROM organizations WHERE orgKey = :orgKey LIMIT 1")
    suspend fun getByKey(orgKey: String): OrganizationEntity?

    @Query("SELECT * FROM organizations WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): OrganizationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: OrganizationEntity): Long

    @Update
    suspend fun update(entity: OrganizationEntity)

    @Update
    suspend fun updateAll(entities: List<OrganizationEntity>)

    @Query("UPDATE organizations SET displayName = :newName, isCustomName = 1 WHERE id = :id")
    suspend fun updateCustomName(id: Long, newName: String)

    @Query("UPDATE organizations SET isMonitored = :monitored WHERE id = :id")
    suspend fun updateMonitored(id: Long, monitored: Boolean)

    @Query("UPDATE organizations SET approvalState = :state, isMonitored = :monitored, isNewlyAdded = 0 WHERE id = :id")
    suspend fun updateApprovalAndMonitored(id: Long, state: OrganizationApprovalState, monitored: Boolean)

    @Query("UPDATE organizations SET isNewlyAdded = 0 WHERE id = :id")
    suspend fun clearNewlyAdded(id: Long)

    @Query("UPDATE organizations SET isNewlyAdded = 0 WHERE isNewlyAdded = 1")
    suspend fun clearAllNewlyAdded()

    @Query("UPDATE organizations SET customMissingDays = :days WHERE id = :id")
    suspend fun updateCustomMissingDays(id: Long, days: Int?)

    @Query("UPDATE organizations SET acknowledgedAtMillis = :ackMillis WHERE id = :id")
    suspend fun acknowledgeIssues(id: Long, ackMillis: Long)

    @Query("UPDATE organizations SET acknowledgedAtMillis = :ackMillis")
    suspend fun acknowledgeAllIssues(ackMillis: Long)

    @Query("DELETE FROM organizations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM organizations")
    suspend fun deleteAll()
}
