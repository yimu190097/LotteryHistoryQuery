package com.lottery.history.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface QueryRecordDao {

    @Insert
    suspend fun insert(record: QueryRecordEntity): Long

    /** 按类型倒序取最近 N 条（最新在前） */
    @Query("SELECT * FROM query_records WHERE type = :type ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentByType(type: String, limit: Int = 50): List<QueryRecordEntity>

    /** 删除 10 天前的旧记录 */
    @Query("DELETE FROM query_records WHERE timestamp < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    /** 删除指定单条记录 */
    @Query("DELETE FROM query_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 清空全部 */
    @Query("DELETE FROM query_records WHERE type = :type")
    suspend fun clearByType(type: String)
}
