package com.lottery.history.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LotteryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<LotteryDrawEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: LotteryDrawEntity)

    @Query("SELECT * FROM lottery_draws WHERE type = :type ORDER BY issue DESC")
    suspend fun getAllByType(type: String): List<LotteryDrawEntity>

    @Query("SELECT issue FROM lottery_draws WHERE type = :type ORDER BY issue DESC LIMIT 1")
    suspend fun getLatestIssue(type: String): String?

    @Query("SELECT COUNT(*) FROM lottery_draws WHERE type = :type")
    suspend fun countByType(type: String): Int

    @Query("DELETE FROM lottery_draws WHERE type = :type")
    suspend fun clearByType(type: String)
}
