package com.lottery.history.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lottery_prize_tier", primaryKeys = ["issue", "type", "tierGroup", "tierIndex"])
data class PrizeTierEntity(
    val issue: String,
    val type: String,
    val tierGroup: String,
    val tierIndex: Int,
    /**
     * 中奖注数 — Long 类型（SQLite INTEGER 列存变长整数）。
     * 注：Int 上限 2,147,483,647（≈21.47 亿）不够安全。
     *   全国单期销量可达数百亿元（按 2~3 元/注 = 100 亿+ 注），
     *   低等奖级（如双色球中蓝球 1/16 概率 × 总注数）中奖注数
     *   理论上可能达到 16 亿注，极端玩法（快乐8选五中五）更可能
     *   超 21.47 亿。count 统一 Long 防止溢出截断负数。
     */
    val count: Long,
    /** 单注奖金（元），Long 类型（防金额超 Int 21 亿） */
    val amount: Long,
    val updatedAt: Long
)
