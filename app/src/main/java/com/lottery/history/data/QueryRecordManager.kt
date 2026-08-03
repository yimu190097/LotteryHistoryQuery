package com.lottery.history.data

import android.content.Context
import com.lottery.history.db.LotteryDatabase
import com.lottery.history.db.QueryRecordEntity

/**
 * 查询记录管理：保存用户最近 10 天内的选号查询记录。
 * 用于快速恢复之前的查询，避免重复选号。
 */
object QueryRecordManager {

    private const val RETENTION_DAYS = 10
    private const val MS_PER_DAY = 24L * 60 * 60 * 1000

    data class QueryRecord(
        val id: Long,
        val primaryNumbers: List<Int>,
        val secondaryNumbers: List<Int>,
        val timestamp: Long
    )

    suspend fun saveQuery(context: Context, type: String, primary: Set<Int>, secondary: Set<Int>) {
        val dao = LotteryDatabase.get(context).queryRecordDao()
        dao.insert(
            QueryRecordEntity(
                type = type,
                primary = primary.sorted().joinToString(","),
                secondary = secondary.sorted().joinToString(","),
                timestamp = System.currentTimeMillis()
            )
        )
        // 写入后清理 10 天前的过期记录
        dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_DAYS * MS_PER_DAY)
    }

    suspend fun getRecent(context: Context, type: String, limit: Int = 50): List<QueryRecord> {
        val dao = LotteryDatabase.get(context).queryRecordDao()
        // 读之前顺手清理过期记录，保证数据新鲜
        dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_DAYS * MS_PER_DAY)
        return dao.getRecentByType(type, limit).map {
            QueryRecord(
                id = it.id,
                primaryNumbers = it.primary.split(',').mapNotNull { n -> n.toIntOrNull() },
                secondaryNumbers = it.secondary.split(',').mapNotNull { n -> n.toIntOrNull() },
                timestamp = it.timestamp
            )
        }
    }

    suspend fun deleteById(context: Context, id: Long) {
        LotteryDatabase.get(context).queryRecordDao().deleteById(id)
    }
}
