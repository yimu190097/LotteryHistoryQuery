package com.lottery.history.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 查询记录：保存用户选号查询历史，用于快速恢复之前的查询。
 * 超过 10 天的记录会被清理。
 */
@Entity(
    tableName = "query_records",
    indices = [androidx.room.Index(value = ["type", "timestamp"])]
)
data class QueryRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "ssq" 或 "dlt" */
    val type: String,
    /** 红球/前区号码，逗号分隔 */
    val primary: String,
    /** 蓝球/后区号码，逗号分隔 */
    val secondary: String,
    /** 查询时间戳（毫秒） */
    val timestamp: Long
)
