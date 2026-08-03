package com.lottery.history.db

import androidx.room.Entity

@Entity(tableName = "lottery_draws", primaryKeys = ["issue", "type"])
data class LotteryDrawEntity(
    val issue: String,
    val type: String,          // "ssq" or "dlt"
    val primary: String,   // comma-separated numbers sorted
    val secondary: String, // comma-separated numbers sorted
    val date: String? = null
)
