package com.pagerelay.bot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditLogDao {

    @Query("SELECT * FROM audit_logs ORDER BY timestampMillis DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 250): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs ORDER BY timestampMillis DESC, id DESC LIMIT :limit")
    suspend fun getRecentSnapshot(limit: Int = 100): List<AuditLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AuditLogEntity): Long

    @Query("DELETE FROM audit_logs")
    suspend fun clearAll()

    @Query(
        """
        DELETE FROM audit_logs
        WHERE id NOT IN (
            SELECT id FROM audit_logs ORDER BY timestampMillis DESC, id DESC LIMIT :keepCount
        )
        """
    )
    suspend fun pruneOlderThan(keepCount: Int = 500)
}
