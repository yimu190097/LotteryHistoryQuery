package com.lottery.history.db

import androidx.room.Entity

/**
 * 开奖数据持久化实体。
 *
 * 【为什么要保存元数据字段】：
 *  不同阶段奖项设立可能不同（DLT2026-01-31由9级改7级、SSQ福运奖奖池双门槛启停、
 *  QXC2020-10-11提高固定奖）。如果只保存 "allPrizeTiers 奖级对字符串"，每次展示时
 *  再按"日期→规则版本"做匹配，一旦日期格式异常/未来规则再变，历史数据的展示会错位。
 *
 *  【架构设计】：解析当期数据时一次性确定 ruleVersionKey + 结构一致性审计 + 奖池销售额，
 *  与奖级对一起持久化。展示时直接用已保存的 ruleVersionKey 取对应规则版本展示，
 *  不依赖运行时的日期计算，保证任何时候展示的都是解析那一刻确定的正确版本。
 */
@Entity(tableName = "lottery_draws", primaryKeys = ["issue", "type"])
data class LotteryDrawEntity(
    val issue: String,
    val type: String,          // "ssq" or "dlt"
    val primary: String,   // comma-separated numbers sorted
    val secondary: String, // comma-separated numbers sorted
    val date: String? = null,

    // ===== 便捷兼容字段（旧UI不升级时仍可读一/二等奖）=====
    val firstPrizeCount: Int? = null,
    val firstPrizeAmount: Long? = null,
    val secondPrizeCount: Int? = null,
    val secondPrizeAmount: Long? = null,

    // ===== 全部奖级（一等奖～最低奖级）："count:amount,count:amount,..." 字符串 =====
    // 例："3:7852000,125:160800,1590:3000,5190:200,53880:10,86700:5"
    val allPrizeTiers: String? = null,

    // ===== v9 新增：按期自适应与结构审计元数据 =====

    /** 规则版本标识（解析时确定，展示时直接用，不依赖日期重算），如 "ssq_20260201" */
    val ruleVersionKey: String? = null,
    /** 实际解析到的奖级对数（用于展示时自动适配行数，数据少几对就少展示几行） */
    val actualTierCount: Int? = null,
    /** 结构一致性审计标记：MATCH / FEWER（实际<配置） / MORE（实际>配置，异常） / MISMATCH */
    val tierMatchStatus: String? = null,
    /** 当期奖池金额（元），SSQ福运奖、DLT固定奖上浮都基于当期奖池判断 */
    val jackpotAmount: Long? = null,
    /** 当期全国销售额（元），展示给客户参考 */
    val salesAmount: Long? = null,
    /** 追加投注奖级数据（仅大乐透等有追加玩法的彩种有，其他彩种为null） */
    val appendPrizeTiers: String? = null,

    // ===== v11 新增：解析来源审计 + 条件性奖级标记 =====

    /** 解析来源：SEED | NET | MIGRATE */
    val parseSource: String? = null,
    /** 解析时间戳（毫秒），null 表示未经过完整解析（如 seed 原始数据） */
    val parseAt: Long? = null,
    /** 解析器版本号，用于日后解析器升级时可追溯 */
    val parserVersion: Int? = null,
    /** 条件性奖级开关：JSON 字符串格式（Map<String, String> 编码），如福运奖ON/OFF/HOLD、DLT上浮NORMAL/UP */
    val conditionalFlagsJson: String? = null
)
