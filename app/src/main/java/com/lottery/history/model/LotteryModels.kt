package com.lottery.history.model

/**
 * 单张单项奖级：中奖注数 + 单注奖金（元）。
 * 用于存储当期每个等级的真实数据（17500.cn 解析，按奖项顺序排序）。
 */
data class PrizeTierEntry(
    val count: Int,    // 当期该奖级中奖注数（0 表示空开）
    val amount: Long   // 单注奖金（元），0 表示空开无奖金
) {
    /** 序列化格式：count:amount（便于写入 Room 字符串列） */
    fun encode(): String = "${count}:${amount}"

    companion object {
        fun decode(raw: String): PrizeTierEntry? {
            val p = raw.split(':').takeIf { it.size == 2 } ?: return null
            val c = p[0].toIntOrNull() ?: return null
            val a = p[1].toLongOrNull() ?: return null
            return PrizeTierEntry(count = c, amount = a)
        }
    }
}

/**
 * 所有奖级编解码：把 List<PrizeTierEntry?> 序列化成 "3:7852000,125:160800,..." 字符串，
 * 存入 Room.allPrizeTiers 列；null 值编码为 "null"。
 */
fun List<PrizeTierEntry?>.encodeTiers(): String? =
    if (this.isEmpty()) null
    else this.joinToString(",") { if (it == null) "null" else it.encode() }

fun decodePrizeTiers(raw: String?): List<PrizeTierEntry?> =
    if (raw.isNullOrEmpty()) emptyList()
    else raw.split(',').map { part ->
        if (part == "null") null else PrizeTierEntry.decode(part)
    }

data class LotteryDraw(
    val issue: String,
    val primaryNumbers: List<Int>,
    val secondaryNumbers: List<Int>,
    val date: String? = null,
    /** 一等奖注数（便捷字段，保持向后兼容） */
    val firstPrizeCount: Int? = null,
    /** 一等奖单注金额（元）（便捷字段，保持向后兼容） */
    val firstPrizeAmount: Long? = null,
    /** 二等奖注数（便捷字段，保持向后兼容） */
    val secondPrizeCount: Int? = null,
    /** 二等奖单注金额（元）（便捷字段，保持向后兼容） */
    val secondPrizeAmount: Long? = null,
    /**
     * 当期所有奖级，从一等奖往下按顺序排列（17500.cn XLS 真实数据）。
     * DrawDetailDialog 会按 rules 顺序把每个奖项名 + 该 entry 一并渲染。
     */
    val allPrizeTiers: List<PrizeTierEntry?> = emptyList()
)

data class QueryResultItem(
    val matchPrimary: Int,
    val matchSecondary: Int,
    val prizeName: String,
    val count: Int,
    val matches: List<LotteryDraw>
)
