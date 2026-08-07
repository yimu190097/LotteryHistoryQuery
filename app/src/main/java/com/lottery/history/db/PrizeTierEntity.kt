package com.lottery.history.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lottery_prize_tier", primaryKeys = ["issue", "type", "tierGroup", "tierIndex"])
data class PrizeTierEntity(
    val issue: String,
    val type: String,
    val tierGroup: String,
    val tierIndex: Int,
    val count: Int,
    val amount: Long,
    val updatedAt: Long
)
