package com.lottery.history.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PrizeTierDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReplace(items: List<PrizeTierEntity>)

    @Query("SELECT * FROM lottery_prize_tier WHERE issue = :issue AND type = :type ORDER BY tierGroup, tierIndex")
    suspend fun getByDraw(issue: String, type: String): List<PrizeTierEntity>

    @Query("SELECT * FROM lottery_prize_tier WHERE issue = :issue AND type = :type AND tierGroup = :tierGroup ORDER BY tierIndex")
    suspend fun getByDrawGroup(issue: String, type: String, tierGroup: String): List<PrizeTierEntity>

    @Query("DELETE FROM lottery_prize_tier WHERE issue = :issue AND type = :type")
    suspend fun deleteForDraw(issue: String, type: String)

    @Query("DELETE FROM lottery_prize_tier WHERE type = :type")
    suspend fun deleteAllByType(type: String)
}
