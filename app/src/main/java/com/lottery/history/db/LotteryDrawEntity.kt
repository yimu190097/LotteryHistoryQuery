package com.lottery.history.db

import androidx.room.Entity

@Entity(tableName = "lottery_draws", primaryKeys = ["issue", "type"])
data class LotteryDrawEntity(
    val issue: String,
    val type: String,          // "ssq" or "dlt"
    val primary: String,   // comma-separated numbers sorted
    val secondary: String, // comma-separated numbers sorted
    val date: String? = null,
    // 当期一等奖/二等奖中奖注数与单注奖金（来自 17500.cn xls 表格真实数据）
    val firstPrizeCount: Int? = null,
    val firstPrizeAmount: Long? = null,
    val secondPrizeCount: Int? = null,
    val secondPrizeAmount: Long? = null
)
