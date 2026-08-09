package com.lottery.history.model

/**
 * 单张单项奖级：中奖注数 + 单注奖金（元）。
 * 用于存储当期每个等级的真实开奖数据（按奖项顺序排序）。
 *
 * 注：count 使用 Long 类型（Int 上限 21.47 亿不够覆盖极端情况），
 *   全国每期销量可达数百亿元（3 元/注 × 100 亿注），低等奖级
 *   （如双色球六等奖=中蓝球，理论最多 16 亿注）中奖注数可能超过 Int 上限。
 *   金额 amount 始终用 Long。
 */
data class PrizeTierEntry(
    val count: Long,   // 当期该奖级中奖注数（0 表示空开）— Long，防止销量极大时超 Int(21.47 亿)
    val amount: Long   // 单注奖金（元），0 表示空开无奖金
) {
    /** 序列化格式：count:amount（便于写入 Room 字符串列） */
    fun encode(): String = "${count}:${amount}"

    companion object {
        fun decode(raw: String): PrizeTierEntry? {
            val p = raw.split(':').takeIf { it.size == 2 } ?: return null
            // count 使用 toLongOrNull（超 21.47 亿注兼容）
            val c = p[0].toLongOrNull() ?: return null
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

/** 结构一致性审计状态 */
object TierMatchStatus {
    const val MATCH = "MATCH"         // 实际奖级对数 = 规则版本配置值
    const val FEWER = "FEWER"         // 实际奖级对数 < 规则版本配置（部分未公布或停发）
    const val MORE = "MORE"           // 实际奖级对数 > 规则版本配置（数据源异常或新规则）
    const val MISMATCH = "MISMATCH"   // 完全不匹配（解析失败）
}

/** v11 新增：条件性奖级 key 常量 */
object ConditionalKey {
    const val SSQ_FUYUN = "ssq_fuyun_onoff"
    const val DLT_2026_FLOAT = "dlt_2026_floating"
}

/** v11 新增：条件性奖级 value 常量（福运奖三态、DLT二态） */
object ConditionalValue {
    const val ON = "ON"
    const val OFF = "OFF"
    const val HOLD = "HOLD"
    const val NORMAL = "NORMAL"
    const val UP = "UP"
}

/**
 * v11 新增：条件性奖级标记编解码（简单 key=value&key2=value2 格式，避免引入新依赖）。
 * encodeFlags: Map<String, String> -> String?（空 map 返回 null）
 * decodeFlags: String? -> Map<String, String>（null/空返回 emptyMap）
 */
fun encodeFlags(flags: Map<String, String>): String? =
    if (flags.isEmpty()) null
    else flags.entries.joinToString("&") { (k, v) -> "${k}=${v}" }

fun decodeFlags(raw: String?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    val result = mutableMapOf<String, String>()
    raw.split('&').forEach { part ->
        val idx = part.indexOf('=')
        if (idx > 0 && idx < part.length - 1) {
            result[part.substring(0, idx)] = part.substring(idx + 1)
        }
    }
    return result
}

/** 解析来源常量 */
object ParseSource {
    const val SEED = "SEED"
    const val SEED_INCOMPLETE = "SEED_INCOMPLETE"
    const val NET = "NET"
    const val MIGRATE = "MIGRATE"
}

const val TIER_GROUP_BASE = "BASE"
const val TIER_GROUP_APPEND = "APPEND"

data class LotteryDraw(
    val issue: String,
    val primaryNumbers: List<Int>,
    val secondaryNumbers: List<Int>,
    val date: String? = null,
    /**
     * 一等奖注数（便捷字段，保持向后兼容）。
     * 使用 Long：极端情况下一等奖总注数理论可能大，但实际上一等奖极少有几亿注，
     * 改为 Long 主要是与 PrizeTierEntry.count 保持一致，避免二次转换溢出。
     */
    val firstPrizeCount: Long? = null,
    /** 一等奖单注金额（元）（便捷字段，保持向后兼容） */
    val firstPrizeAmount: Long? = null,
    /** 二等奖注数（便捷字段，保持向后兼容）— Long，对齐 PrizeTierEntry.count */
    val secondPrizeCount: Long? = null,
    /** 二等奖单注金额（元）（便捷字段，保持向后兼容） */
    val secondPrizeAmount: Long? = null,
    /**
     * 当期所有奖级，从一等奖往下按顺序排列（真实开奖数据）。
     * DrawDetailDialog 会按 rules 顺序把每个奖项名 + 该 entry 一并渲染。
     */
    val allPrizeTiers: List<PrizeTierEntry?> = emptyList(),

    // ===== v9 新增：按期自适应与结构审计元数据 =====

    /** 规则版本标识（解析时按当期开奖日期确定，展示时直接用，不依赖日期重算） */
    val ruleVersionKey: String? = null,
    /** 实际解析到的奖级对数（用于展示时自动适配行数，数据少几对就少展示几行） */
    val actualTierCount: Int? = null,
    /** 结构一致性审计标记：MATCH / FEWER / MORE / MISMATCH */
    val tierMatchStatus: String? = null,
    /** 当期奖池金额（元），SSQ福运奖启停、DLT固定奖上浮都基于当期奖池判断 */
    val jackpotAmount: Long? = null,
    /** 当期全国销售额（元），展示给客户参考 */
    val salesAmount: Long? = null,
    /** 追加投注奖级数据（仅大乐透等有追加玩法的彩种有，其他彩种为 emptyList） */
    val appendPrizeTiers: List<PrizeTierEntry?> = emptyList(),

    // ===== v11 新增：解析来源审计 + 条件性奖级标记 =====

    /** 解析来源：SEED | NET | MIGRATE | SEED_INCOMPLETE */
    val parseSource: String? = null,
    /** 解析时间戳（毫秒），null 表示未经过完整解析 */
    val parseAt: Long? = null,
    /** 解析器版本号 */
    val parserVersion: Int? = null,
    /** 条件性奖级开关 Map（福运奖 ON/OFF/HOLD、DLT上浮 NORMAL/UP 等） */
    val conditionalFlags: Map<String, String> = emptyMap()
) {
    /**
     * 基于 ruleVersionKey + issue + date 三级定位拿到当期应展示的 RuleVersion（展示时首选）。
     *
     * 【零兜底·严格模式：只用解析时已持久化的真实 ruleVersionKey】：
     *   ① DB 中 ruleVersionKey 非空，且能在 config.ruleVersions 里找到精确匹配 → 返回该版
     *   ② 其他任何情况（ruleVersionKey 空 / key 已废弃 / date 有值也不行）→ 一律返回 null，
     *     调用方必须显式展示「元数据缺失，无法确定当期规则版本」。
     *
     * ❌ 已废除：
     *   · rulesForDate(date, issue) 兜底 → 废掉（解析时必须已确定 ruleVersionKey 再入库，
     *     不在入库之后再拿 date 推断，拿 issue 更是绝对禁止）
     *   · 任何形式的默认最新版 / 默认最旧版 → 废掉（没有 key 就是元数据缺失）
     */
    fun resolveRuleVersion(config: LotteryTypeConfig): LotteryTypeConfig.RuleVersion? {
        val key = ruleVersionKey ?: return null
        return config.ruleVersions.firstOrNull { it.key == key }
    }
}

data class QueryResultItem(
    var matchPrimary: Int,           // 合并同奖项多条件时设为 -1（UI 显示 "—"）
    var matchSecondary: Int,         // 同上
    val prizeName: String,
    var count: Long,                 // 合并版本时可累加；matches 真实对象从不改动
    var matches: List<LotteryDraw>,  // 真实 draw 对象原封不动，只合并列表引用
    /** 该命中统计桶来源的规则版本 key（=BucketKey.ruleVersionKey），用于 UI 按政策分组 */
    val sourceRuleVersionKey: String? = null
)
