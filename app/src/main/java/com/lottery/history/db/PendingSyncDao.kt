package com.lottery.history.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PendingSyncEntity): Long

    @Query("SELECT * FROM pending_sync WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getByStatus(status: SyncStatus): List<PendingSyncEntity>

    @Query("SELECT COUNT(*) FROM pending_sync WHERE status = 'PENDING'")
    suspend fun countPending(): Int

    @Query("UPDATE pending_sync SET status = :status, syncedAt = :syncedAt, retryCount = :retryCount, lastError = :error WHERE id = :id")
    suspend fun updateStatus(id: Long, status: SyncStatus, syncedAt: Long?, retryCount: Int, error: String?)
}
