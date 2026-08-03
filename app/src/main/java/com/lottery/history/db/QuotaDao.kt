package com.lottery.history.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface QuotaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(quota: QuotaEntity)

    @Update
    suspend fun update(quota: QuotaEntity)

    @Query("SELECT * FROM quotas WHERE userPhone = :phone LIMIT 1")
    suspend fun getByUser(phone: String): QuotaEntity?

    @Query("SELECT * FROM quotas WHERE userPhone = :phone LIMIT 1")
    fun observeByUser(phone: String): Flow<QuotaEntity?>
}
