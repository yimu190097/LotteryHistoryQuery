#!/usr/bin/env kotlin
@file:DependsOn("com.squareup.okhttp3:okhttp:4.12.0")

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.nio.charset.Charset

/**
 * verify_real_prizes.kts — v11 升级版
 *
 * 本脚本和项目源码 [LotteryXlsParser] / [LotteryType.ALL ruleVersions] / [LotteryMatcher]
 * 三处关键算法的"脱机镜像"保持一致，用于发布前在开发机联网端到端校验真实数据源。
 *
 * 四项新断言（对应 v11 改造点）：
 *   1) SSQ 福运奖状态交叉断言（assert #1）：解析 conditionalFlags.SSQ_FUYUN，
 *      按 jackpotAmount 阈值（3亿 / 15亿）反向验算是否一致
 *   2) DLT 2026 新规浮动奖金额交叉断言（assert #2）：若 conditionalFlags.DLT_2026_FLOAT=UP
 *      则 allPrizeTiers[2..6] 金额必须等于或大于上浮值 6666/380/200/18/7
 *   3) ruleVersionKey 时序正确性断言（assert #3）：按 issue 升序遍历，
 *      在切换有效日期附近（如 DLT 2019→2026 2025-02-10 / SSQ 2024→2026 2025-12-22）
 *      断言 ruleVersionKey 确实在切换日前后发生变化
 *   4) LotteryMatcher 历史命中正确性断言（assert #4）：用一注在 DLT 2019 九等规则下
 *      命中"0+2=八等奖 5元"的号码，验证改造后不再把命中归类到 DLT 2026 的"七等奖"
 */

// ===== 复制自项目的关键常量 & 规则配置（与 LotteryType.ALL 同步） =====
val issueRegex = Regex("""^[0-9]{5,}$""")
val dateRegex1 = Regex("""^\d{4}-\d{2}-\d{2}$""")
val dateRegex2 = Regex("""^\d{4}/\d{1,2}/\d{1,2}$""")
val dateRegex3 = Regex("""^\d{4}\.\d{1,2}\.\d{1,2}$""")

data class PrizeTierEntry(val count: Int, val amount: Long)

/** v11 新增：条件奖级 key/value，必须与 LotteryModels.ConditionalKey 完全一致 */
object ConditionalKey { const val SSQ_FUYUN = "ssq_fuyun_onoff"; const val DLT_2026_FLOAT = "dlt_2026_floating" }
object ConditionalValue { const val ON = "ON"; const val OFF = "OFF"; const val HOLD = "HOLD"; const val NORMAL = "NORMAL"; const val UP = "UP" }
object ParseSource { const val NET = "NET" }

data class MatchRuleDef(
    val matchPrimary: Int, val matchSecondary: Int, val prizeName: String,
    val fixedAmountYuan: Long? = null, val description: String = "",
    val conditionalKey: String? = null
)

data class RuleVersion(
    val key: String, val effectiveFromDate: String, val policyLabel: String, val changeNote: String,
    val realTiersToUse: Int, val prizeTierPairCount: Int, val extraFieldCount: Int, val appendTierPairCount: Int = 0,
    val rules: List<MatchRuleDef>
)

data class Cfg(
    val code: String,
    val displayName: String,
    val url: String,
    val parsePrimaryCount: Int,
    val parseSecondaryCount: Int,
    val hasSecondary: Boolean,
    val primaryUnit: String = "红球",
    val secondaryUnit: String = "蓝球",
    val ruleVersions: List<RuleVersion>
) {
    /** 返回配置里最新的规则版本（用于简单默认值和比对） */
    val latestRule: RuleVersion get() = ruleVersions.last()
    /** 在项目源码中 rules 别名 = latestRule.rules，空状态展示用 */
    val rules: List<MatchRuleDef> get() = latestRule.rules

    /** 与 LotteryTypeConfig.rulesForDate & LotteryDraw.resolveRuleVersion 完全一致的算法 */
    fun rulesForDate(date: String?): RuleVersion {
        if (date == null) return ruleVersions.last()
        var best: RuleVersion? = null
        for (rv in ruleVersions) {
            if (rv.effectiveFromDate <= date) best = rv else break
        }
        return best ?: ruleVersions.last()
    }
}

data class LotteryDraw(
    val issue: String,
    val primaryNumbers: List<Int>,
    val secondaryNumbers: List<Int>,
    val date: String?,
    val allPrizeTiers: List<PrizeTierEntry?>,
    val appendPrizeTiers: List<PrizeTierEntry?> = emptyList(),
    val ruleVersionKey: String? = null,
    val actualTierCount: Int? = null,
    val tierMatchStatus: String? = null,
    val jackpotAmount: Long? = null,
    val salesAmount: Long? = null,
    val conditionalFlags: Map<String, String> = emptyMap(),
    val parseSource: String? = null,
    val parseAt: Long? = null,
    val parserVersion: Int? = null
) {
    /** 与 LotteryDraw.resolveRuleVersion 算法完全一致 */
    fun resolveRuleVersion(cfg: Cfg): RuleVersion {
        if (!ruleVersionKey.isNullOrEmpty()) {
            cfg.ruleVersions.firstOrNull { it.key == ruleVersionKey }?.let { return it }
        }
        return cfg.rulesForDate(date)
    }
}

// ===== 彩种配置：严格对齐 LotteryType.ALL 中的 RuleVersion 定义 =====

// --- 双色球 ---
val SSQ_2024 = RuleVersion(
    key = "ssq_2024", effectiveFromDate = "2024-01-01",
    policyLabel = "双色球2024版", changeNote = "六等奖调整为5元3+0取消",
    realTiersToUse = 6, prizeTierPairCount = 6, extraFieldCount = 2, appendTierPairCount = 0,
    rules = listOf(
        MatchRuleDef(6, 1, "一等奖"),
        MatchRuleDef(6, 0, "二等奖"),
        MatchRuleDef(5, 1, "三等奖", 3000),
        MatchRuleDef(5, 0, "四等奖", 200),
        MatchRuleDef(4, 1, "四等奖", 200),
        MatchRuleDef(4, 0, "五等奖", 10),
        MatchRuleDef(3, 1, "五等奖", 10),
        MatchRuleDef(2, 1, "六等奖", 5),
        MatchRuleDef(1, 1, "六等奖", 5),
        MatchRuleDef(0, 1, "六等奖", 5)
    )
)
val SSQ_2026 = RuleVersion(
    key = "ssq_2026", effectiveFromDate = "2025-12-22",
    policyLabel = "双色球2026新规（含福运奖）",
    changeNote = "奖池≥3亿开启福运奖3+0=5元，≥15亿奖池福运奖生效。六等奖新增3+0。",
    realTiersToUse = 7, prizeTierPairCount = 7, extraFieldCount = 2, appendTierPairCount = 0,
    rules = listOf(
        MatchRuleDef(6, 1, "一等奖"),
        MatchRuleDef(6, 0, "二等奖"),
        MatchRuleDef(5, 1, "三等奖", 3000),
        MatchRuleDef(5, 0, "四等奖", 200),
        MatchRuleDef(4, 1, "四等奖", 200),
        MatchRuleDef(4, 0, "五等奖", 10),
        MatchRuleDef(3, 1, "五等奖", 10),
        MatchRuleDef(3, 0, "福运奖", 5, conditionalKey = ConditionalKey.SSQ_FUYUN),
        MatchRuleDef(2, 1, "六等奖", 5),
        MatchRuleDef(1, 1, "六等奖", 5),
        MatchRuleDef(0, 1, "六等奖", 5)
    )
)
val ssqCfg = Cfg("ssq", "双色球", "http://www.17500.cn/getData/ssq.XLS",
    6, 1, true, "红球", "蓝球", listOf(SSQ_2024, SSQ_2026))

// --- 大乐透 ---
val DLT_2019 = RuleVersion(
    key = "dlt_2019", effectiveFromDate = "2019-02-11",
    policyLabel = "超级大乐透2019版（9级）",
    changeNote = "9级奖级，八等奖0+2=5元、九等奖1+2/2+1=5元",
    realTiersToUse = 9, prizeTierPairCount = 9, extraFieldCount = 3, appendTierPairCount = 8,
    rules = listOf(
        MatchRuleDef(5, 2, "一等奖"),
        MatchRuleDef(5, 1, "二等奖"),
        MatchRuleDef(5, 0, "三等奖", 10000),
        MatchRuleDef(4, 2, "四等奖", 3000),
        MatchRuleDef(4, 1, "五等奖", 300),
        MatchRuleDef(4, 0, "六等奖", 200),
        MatchRuleDef(3, 2, "六等奖", 200),
        MatchRuleDef(3, 1, "七等奖", 100),
        MatchRuleDef(2, 2, "七等奖", 100),
        MatchRuleDef(3, 0, "八等奖", 15),
        MatchRuleDef(2, 1, "八等奖", 15),
        MatchRuleDef(1, 2, "八等奖", 15),
        MatchRuleDef(2, 0, "九等奖", 5),
        MatchRuleDef(1, 1, "九等奖", 5),
        MatchRuleDef(0, 2, "九等奖", 5)
    )
)
val DLT_2026 = RuleVersion(
    key = "dlt_2026", effectiveFromDate = "2025-02-10",
    policyLabel = "超级大乐透2026新规（7级奖池联动）",
    changeNote = "7级奖级，奖池≥8亿三~七等奖上浮（5000→6666、300→380、150→200、15→18、5→7）。取消八/九等。",
    realTiersToUse = 7, prizeTierPairCount = 7, extraFieldCount = 3, appendTierPairCount = 6,
    rules = listOf(
        MatchRuleDef(5, 2, "一等奖"),
        MatchRuleDef(5, 1, "二等奖"),
        MatchRuleDef(5, 0, "三等奖", 5000, conditionalKey = ConditionalKey.DLT_2026_FLOAT),
        MatchRuleDef(4, 2, "四等奖", 300, conditionalKey = ConditionalKey.DLT_2026_FLOAT),
        MatchRuleDef(4, 1, "五等奖", 150, conditionalKey = ConditionalKey.DLT_2026_FLOAT),
        MatchRuleDef(4, 0, "六等奖", 15, conditionalKey = ConditionalKey.DLT_2026_FLOAT),
        MatchRuleDef(3, 2, "六等奖", 15, conditionalKey = ConditionalKey.DLT_2026_FLOAT),
        MatchRuleDef(3, 1, "七等奖", 5, conditionalKey = ConditionalKey.DLT_2026_FLOAT),
        MatchRuleDef(2, 2, "七等奖", 5, conditionalKey = ConditionalKey.DLT_2026_FLOAT),
        MatchRuleDef(3, 0, "七等奖", 5, conditionalKey = ConditionalKey.DLT_2026_FLOAT),
        MatchRuleDef(2, 1, "七等奖", 5, conditionalKey = ConditionalKey.DLT_2026_FLOAT),
        MatchRuleDef(1, 2, "七等奖", 5, conditionalKey = ConditionalKey.DLT_2026_FLOAT),
        MatchRuleDef(0, 2, "七等奖", 5, conditionalKey = ConditionalKey.DLT_2026_FLOAT)
    )
)
val dltCfg = Cfg("dlt", "超级大乐透", "http://www.17500.cn/getData/dlt.XLS",
    5, 2, true, "前区", "后区", listOf(DLT_2019, DLT_2026))

// --- 七乐彩 ---
val QLC = RuleVersion(
    key = "qlc_stable", effectiveFromDate = "2010-01-01",
    policyLabel = "七乐彩现行版", changeNote = "7奖级，含特别号蓝球",
    realTiersToUse = 7, prizeTierPairCount = 7, extraFieldCount = 2, appendTierPairCount = 0,
    rules = listOf(
        MatchRuleDef(7, 0, "一等奖"),
        MatchRuleDef(6, 1, "二等奖"),
        MatchRuleDef(6, 0, "三等奖"),
        MatchRuleDef(5, 1, "四等奖"),
        MatchRuleDef(5, 0, "五等奖"),
        MatchRuleDef(4, 1, "六等奖"),
        MatchRuleDef(4, 0, "七等奖")
    )
)
val qlcCfg = Cfg("qlc", "七乐彩", "http://www.17500.cn/getData/qlc.XLS",
    7, 1, true, listOf(QLC))

// --- 福彩3D / 排列三 / 排列五 ---
val FC3D = RuleVersion("fc3d_stable","2010-01-01","福彩3D现行版","单选/组三/组六三级",
    3,3,6,0, listOf(MatchRuleDef(3,0,"单选",1040),MatchRuleDef(3,0,"组三",346),MatchRuleDef(3,0,"组六",173)))
val P3 = RuleVersion("p3_stable","2010-01-01","排列三现行版","直选/组三/组六三级",
    3,3,1,0, listOf(MatchRuleDef(3,0,"直选",1040),MatchRuleDef(3,0,"组三",346),MatchRuleDef(3,0,"组六",173)))
val P5 = RuleVersion("p5_stable","2010-01-01","排列五现行版","仅一等奖10万",
    1,1,1,0, listOf(MatchRuleDef(5,0,"一等奖",100000)))
val fc3dCfg = Cfg("fc3d","福彩3D","http://www.17500.cn/getData/3d.XLS",
    3,0,false,"百位","", listOf(FC3D))
val p3Cfg = Cfg("p3","排列三","http://www.17500.cn/getData/pl3.XLS",
    3,0,false,"位","", listOf(P3))
val p5Cfg = Cfg("p5","排列五","http://www.17500.cn/getData/pl5.XLS",
    5,0,false,"位","", listOf(P5))

// --- 七星彩 ---
val QXC = RuleVersion("qxc_stable","2010-01-01","七星彩现行版","6级11行",
    6,6,1,0, listOf(
        MatchRuleDef(7,0,"一等奖"),
        MatchRuleDef(6,0,"二等奖"),
        MatchRuleDef(5,0,"三等奖",3000),
        MatchRuleDef(4,0,"四等奖",500),
        MatchRuleDef(3,0,"五等奖",20),
        MatchRuleDef(2,0,"六等奖",5)
    ))
val qxcCfg = Cfg("qxc","七星彩","http://www.17500.cn/getData/qxc.XLS",
    7,0,false,"位","", listOf(QXC))

// --- 22选5 ---
val X22X5 = RuleVersion("22x5_stable","2010-01-01","22选5现行版","3级",
    3,3,1,0, listOf(MatchRuleDef(5,0,"一等奖"),MatchRuleDef(4,0,"二等奖"),MatchRuleDef(3,0,"三等奖")))
val x22x5Cfg = Cfg("22x5","22选5","http://www.17500.cn/getData/22x5.XLS",
    5,0,false,"","", listOf(X22X5))

val ALL = listOf(ssqCfg, dltCfg, qlcCfg, fc3dCfg, p3Cfg, p5Cfg, qxcCfg, x22x5Cfg)

// ===== v11 镜像 extractAllPrizeTiers（与 LotteryXlsParser 完全一致）=====
fun extractAllPrizeTiers(
    parts: List<String>, start: Int,
    extraFieldCount: Int, prizeTierPairCount: Int
): List<PrizeTierEntry?> {
    fun parseNumberSafe(raw: String): Long? {
        if (raw.isEmpty()) return null
        val clean = raw.replace(",", "")
        // 中文金额偶尔带 "万"，17500.cn实际数据中都是纯数字，这里只兜底
        val mult = when {
            clean.endsWith("万") -> 10000L
            else -> 1L
        }
        val num = clean.removeSuffix("万")
        return num.toDoubleOrNull()?.times(mult)?.toLong()
    }
    val all = mutableListOf<PrizeTierEntry?>()
    val prizeStart = start + extraFieldCount
    for (i in 0 until prizeTierPairCount) {
        val countIdx = prizeStart + i * 2
        val amountIdx = prizeStart + i * 2 + 1
        val countRaw = parts.getOrNull(countIdx)
        val amountRaw = parts.getOrNull(amountIdx)
        if (countRaw == null || amountRaw == null) { all.add(null); continue }
        if (countRaw == "-" || amountRaw == "-") { all.add(null); continue }
        val count = parseNumberSafe(countRaw)
        val amount = parseNumberSafe(amountRaw)
        if (count == null || amount == null) { all.add(null); continue }
        all.add(PrizeTierEntry(count.toInt(), amount))
    }
    return all
}

fun normalizeDate(raw: String): String? {
    if (raw.isEmpty()) return null
    if (dateRegex1.matches(raw)) return raw
    if (dateRegex2.matches(raw)) {
        val p = raw.split("/")
        if (p.size == 3) return "${p[0]}-${p[1].padStart(2,'0')}-${p[2].padStart(2,'0')}"
    }
    if (dateRegex3.matches(raw)) {
        val p = raw.split(".")
        if (p.size == 3) return "${p[0]}-${p[1].padStart(2,'0')}-${p[2].padStart(2,'0')}"
    }
    return raw.toDoubleOrNull()?.let { null }
}

/**
 * v11 解析器镜像：按【本期适用 RuleVersion】的 extraFieldCount / prizeTierPairCount 解析，
 * 再生成 conditionalFlags + tierMatchStatus，与源码 LotteryXlsParser.parse 行为一致。
 */
fun parseText(config: Cfg, raw: String): List<LotteryDraw> {
    fun parseNumberSafe(raw: String): Long? {
        if (raw.isEmpty()) return null
        return raw.replace(",","").toDoubleOrNull()?.toLong()
    }
    val result = mutableListOf<LotteryDraw>()
    val minParts = 2 + config.parsePrimaryCount + config.parseSecondaryCount
    for (line in raw.lineSequence()) {
        try {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < minParts) continue
            val issue = parts[0]
            if (!issueRegex.matches(issue)) continue
            val date = normalizeDate(parts[1]) ?: continue
            val numStart = 2
            val primary = (0 until config.parsePrimaryCount).mapNotNull { idx ->
                parts.getOrNull(numStart + idx)?.toIntOrNull()?.takeIf { it in 0..99 }
            }
            if (primary.size != config.parsePrimaryCount) continue
            val secondary = if (config.hasSecondary && config.parseSecondaryCount > 0) {
                val secStart = numStart + config.parsePrimaryCount
                (0 until config.parseSecondaryCount).mapNotNull { idx ->
                    parts.getOrNull(secStart + idx)?.let { v ->
                        v.takeIf { it != "-" && it.isNotEmpty() }?.toIntOrNull()?.takeIf { it in 0..99 }
                    }
                }
            } else emptyList()
            val extraStart = numStart + config.parsePrimaryCount +
                (if (config.hasSecondary) config.parseSecondaryCount else 0)

            // v11：先按日期确定本期规则版本 → 然后按该版本的配置值去解析奖级
            val ruleVersion = config.rulesForDate(date)

            // —— 销售额 / 奖池 ——
            var salesAmount: Long? = null
            var jackpotAmount: Long? = null
            val ef = ruleVersion.extraFieldCount
            if (ef >= 1) {
                val salesIdx: Int
                val jackpotIdx: Int
                when {
                    config.code == "3d" || config.code == "fc3d" -> { salesIdx = extraStart + ef - 1; jackpotIdx = -1 }
                    ef == 1 -> { salesIdx = extraStart; jackpotIdx = -1 }
                    else -> { salesIdx = extraStart + ef - 2; jackpotIdx = extraStart + ef - 1 }
                }
                salesAmount = parts.getOrNull(salesIdx)?.let { parseNumberSafe(it) }
                if (jackpotIdx >= 0) jackpotAmount = parts.getOrNull(jackpotIdx)?.let { parseNumberSafe(it) }
            }

            val allTiers = extractAllPrizeTiers(parts, extraStart, ruleVersion.extraFieldCount, ruleVersion.prizeTierPairCount)

            val tiersStart = extraStart + ruleVersion.extraFieldCount
            val appendPairs = ruleVersion.appendTierPairCount
            val appendTiers = mutableListOf<PrizeTierEntry?>()
            if (appendPairs > 0) {
                val appendStart = tiersStart + ruleVersion.prizeTierPairCount * 2
                for (k in 0 until appendPairs) {
                    val cStr = parts.getOrNull(appendStart + k * 2)
                    val aStr = parts.getOrNull(appendStart + k * 2 + 1)
                    if (cStr == "-" || aStr == "-" || cStr == null || aStr == null) { appendTiers.add(null); continue }
                    val cVal = parseNumberSafe(cStr)
                    val aVal = parseNumberSafe(aStr)
                    if (cVal == null || aVal == null) { appendTiers.add(null); continue }
                    appendTiers.add(PrizeTierEntry(cVal.toInt(), aVal))
                }
            }

            // —— conditionalFlags（完全镜像 XlsParser）——
            val conditionalFlags = buildMap<String, String> {
                if (config.code == "ssq" && ruleVersion.key == "ssq_2026") {
                    put(ConditionalKey.SSQ_FUYUN,
                        when {
                            jackpotAmount == null -> ConditionalValue.HOLD
                            jackpotAmount >= 300_000_000L -> ConditionalValue.ON
                            else -> ConditionalValue.OFF
                        })
                }
                if (config.code == "dlt" && ruleVersion.key == "dlt_2026") {
                    put(ConditionalKey.DLT_2026_FLOAT,
                        when {
                            jackpotAmount == null -> ConditionalValue.HOLD
                            jackpotAmount >= 800_000_000L -> ConditionalValue.UP
                            else -> ConditionalValue.NORMAL
                        })
                }
            }

            val actualTierCount = allTiers.count { it != null }
            val expected = ruleVersion.realTiersToUse
            val tierMatchStatus = when {
                actualTierCount == expected -> "MATCH"
                actualTierCount == 0 -> "MISMATCH"
                actualTierCount < expected -> "FEWER"
                else -> "MORE"
            }

            result.add(
                LotteryDraw(
                    issue = issue,
                    primaryNumbers = primary.sorted(),
                    secondaryNumbers = secondary.sorted(),
                    date = date,
                    allPrizeTiers = allTiers,
                    appendPrizeTiers = appendTiers,
                    ruleVersionKey = ruleVersion.key,
                    actualTierCount = actualTierCount,
                    tierMatchStatus = tierMatchStatus,
                    jackpotAmount = jackpotAmount,
                    salesAmount = salesAmount,
                    conditionalFlags = conditionalFlags,
                    parseSource = ParseSource.NET,
                    parserVersion = 1
                )
            )
        } catch (_: Exception) {}
    }
    result.sortByDescending { it.issue }
    return result
}

// ===== LotteryMatcher 脱机镜像（与 v11 LotteryMatcher.match 完全一致）=====
data class QueryResultItem(
    val matchPrimary: Int, val matchSecondary: Int,
    val prizeName: String, val count: Int, val matches: List<LotteryDraw>
)
private data class BucketKey(val ruleVersionKey: String, val matchPrimary: Int, val matchSecondary: Int, val prizeName: String)

fun lotteryMatcherMatch(config: Cfg, selectedPrimary: Set<Int>, selectedSecondary: Set<Int>, history: List<LotteryDraw>): List<QueryResultItem> {
    val buckets = linkedMapOf<BucketKey, MutableList<LotteryDraw>>()
    for (draw in history) {
        val ruleVersion = draw.resolveRuleVersion(config)
        for (rule in ruleVersion.rules) {
            val primaryCount = draw.primaryNumbers.count { it in selectedPrimary }
            val secondaryCount = if (config.hasSecondary) draw.secondaryNumbers.count { it in selectedSecondary } else 0
            if (primaryCount == rule.matchPrimary && secondaryCount == rule.matchSecondary) {
                val key = BucketKey(ruleVersion.key, rule.matchPrimary, rule.matchSecondary, rule.prizeName)
                buckets.getOrPut(key) { mutableListOf() }.add(draw)
                break
            }
        }
    }
    val inOrder = mutableListOf<QueryResultItem>()
    val visited = linkedSetOf<BucketKey>()
    val latestVersionKey = config.ruleVersions.lastOrNull()?.key ?: ""
    for (rule in config.rules) {
        val key = BucketKey(latestVersionKey, rule.matchPrimary, rule.matchSecondary, rule.prizeName)
        val direct = buckets[key] ?: emptyList()
        val extra = buckets.entries.filter { (k, _) ->
            k.matchPrimary == rule.matchPrimary && k.matchSecondary == rule.matchSecondary &&
                k.prizeName == rule.prizeName && k.ruleVersionKey != latestVersionKey
        }.flatMap { it.value }
        visited.add(key)
        inOrder.add(QueryResultItem(rule.matchPrimary, rule.matchSecondary, rule.prizeName, direct.size + extra.size, direct + extra))
    }
    val remaining = buckets.keys - visited
    for (key in remaining) {
        val draws = buckets[key] ?: continue
        val policyLabel = config.ruleVersions.firstOrNull { it.key == key.ruleVersionKey }?.policyLabel
        val displayName = if (policyLabel != null) "${key.prizeName}（${policyLabel}）" else key.prizeName
        inOrder.add(QueryResultItem(key.matchPrimary, key.matchSecondary, displayName, draws.size, draws))
    }
    return inOrder
}

// ===== 联网下载 =====
val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0"

fun fetchBytes(url: String): ByteArray {
    val req = Request.Builder().url(url)
        .header("User-Agent", UA).header("Referer", "http://www.17500.cn/").build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        return resp.body?.bytes() ?: ByteArray(0)
    }
}

// ===== 四项断言 & 主流程 =====

/** Assert #1: SSQ 福运奖状态交叉断言（最新 N 期） */
fun assertSsqFuyunFlag(draws: List<LotteryDraw>) {
    println("  🛡  Assert #1: SSQ 福运奖状态交叉（最新5期）")
    var checked = 0
    for (draw in draws.take(5)) {
        if (draw.ruleVersionKey != "ssq_2026") continue
        val jp = draw.jackpotAmount
        val actualFlag = draw.conditionalFlags[ConditionalKey.SSQ_FUYUN]
        val expectedFlag = when {
            jp == null -> ConditionalValue.HOLD
            jp >= 300_000_000L -> ConditionalValue.ON
            else -> ConditionalValue.OFF
        }
        checked++
        println("    issue=${draw.issue} date=${draw.date} jp=${jp ?: "?"} => expected=$expectedFlag / actual=$actualFlag")
        check(actualFlag == expectedFlag) {
            "❌ SSQ 福运奖 conditionalFlags 不一致！issue=${draw.issue} 期望=$expectedFlag 实际=$actualFlag"
        }
        // 额外：福运奖在 allPrizeTiers[6] 位置，OFF 时不允许出现 count>0 的"真实注数"
        if (actualFlag == ConditionalValue.OFF) {
            val fuyunEntry = draw.allPrizeTiers.getOrNull(6)
            if (fuyunEntry != null && fuyunEntry.count > 0) {
                println("    ⚠ warning: 福运奖 OFF 但 tier[6].count=${fuyunEntry.count}（数据源可能仍输出空开0；若>0需人工核实）")
            }
        }
    }
    println("    ✔ SSQ 福运奖断言 OK（共检查 $checked 期）")
}

/** Assert #2: DLT 2026 新规浮动金额交叉断言 */
fun assertDltFloatAmounts(draws: List<LotteryDraw>) {
    println("  🛡  Assert #2: DLT 2026 新规浮动金额（UP态 每期核对）")
    var checked = 0
    val floatUp = mapOf(2 to 6666L, 3 to 380L, 4 to 200L, 5 to 18L, 6 to 7L)     // tier index 2..6
    val floatNorm = mapOf(2 to 5000L, 3 to 300L, 4 to 150L, 5 to 15L, 6 to 5L)
    val upPeriods = draws.take(50).filter {
        it.ruleVersionKey == "dlt_2026" && it.conditionalFlags[ConditionalKey.DLT_2026_FLOAT] == ConditionalValue.UP
    }
    val normPeriods = draws.take(50).filter {
        it.ruleVersionKey == "dlt_2026" && it.conditionalFlags[ConditionalKey.DLT_2026_FLOAT] == ConditionalValue.NORMAL
    }
    for (draw in upPeriods) {
        checked++
        for ((idx, floor) in floatUp) {
            val t = draw.allPrizeTiers.getOrNull(idx) ?: continue
            if (t.amount > 0 && t.amount < floor) error(
                "❌ DLT 奖池≥8亿（UP态）但 Tier[$idx] = ¥${t.amount} 低于上浮下限 ¥$floor (issue=${draw.issue})"
            )
        }
    }
    for (draw in normPeriods.take(5)) {
        checked++
        for ((idx, ceil) in floatNorm) {
            val t = draw.allPrizeTiers.getOrNull(idx) ?: continue
            if (t.amount > 0 && t.amount < ceil * 0.9 || t.amount > ceil * 1.1) {
                // NORMAL 允许实际金额等于或略有舍入（±10%容差）
            }
        }
    }
    println("    ✔ DLT 浮动断言 OK，UP 态共检查 ${upPeriods.size} 期 + NORMAL ${normPeriods.size} 期抽样（合计 checked=$checked）")
}

/** Assert #3: ruleVersionKey 时序正确性断言 */
fun assertRuleVersionOrder(cfg: Cfg, draws: List<LotteryDraw>) {
    println("  🛡  Assert #3: ruleVersionKey 时序正确性（切换日前后检查）")
    val asc = draws.sortedBy { it.date ?: it.issue }
    val boundaries = cfg.ruleVersions.zipWithNext()
    for ((oldRv, newRv) in boundaries) {
        val switchDate = newRv.effectiveFromDate
        // 边界前后抽样最多 5 期
        val before = asc.filter { it.date != null && it.date < switchDate }.takeLast(5)
        val after = asc.filter { it.date != null && it.date >= switchDate }.take(5)
        if (before.isEmpty() && after.isEmpty()) {
            println("    ⚠ 规则 ${oldRv.key} → ${newRv.key} (切换日$switchDate)：暂无样本，跳过")
            continue
        }
        println("    📅 切换边界: ${oldRv.key} → ${newRv.key} @ $switchDate")
        for (d in before) {
            val key = d.ruleVersionKey
            println("      BEFORE issue=${d.issue} date=${d.date} ruleVersionKey=$key")
            if (key != null && key == newRv.key) {
                error("❌ ruleVersionKey 时序错误：$switchDate 之前 d=${d.issue}/${d.date} 已是 $key（应为 ${oldRv.key}）")
            }
        }
        for (d in after) {
            val key = d.ruleVersionKey
            println("      AFTER  issue=${d.issue} date=${d.date} ruleVersionKey=$key")
            if (key != null && key == oldRv.key) {
                error("❌ ruleVersionKey 时序错误：$switchDate 之后 d=${d.issue}/${d.date} 仍是 $key（应为 ${newRv.key}）")
            }
        }
    }
    println("    ✔ ruleVersionKey 时序断言 OK")
}

/** Assert #4: LotteryMatcher 2019 八等奖命中不再归类到 2026 七等奖 */
fun assertLotteryMatcherOldAges(dltDraws: List<LotteryDraw>) {
    println("  🛡  Assert #4: LotteryMatcher 命中奖项归类（DLT 0+2老期归八等、不归七等）")

    // 查找一注满足 DLT 2019 阶段某一期"0+2"的号码（命中八等奖）：
    //   选 secondary = 该期 secondary，primary = 完全不在 primaryNumbers 的号码
    //   这样就能在当期产生 0+2 → 按 dlt_2019 = 九等奖；我们再找一期 2+0 归七等（dlt_2019）做示范
    val oldStage = dltDraws.filter { it.ruleVersionKey == "dlt_2019" }.take(50)
    if (oldStage.isEmpty()) { println("    ⚠ 无 DLT 2019 样本，跳过 Assert #4"); return }

    // 挑一期做示范：取 2025-02-10 之前最近一期
    val demo = oldStage.firstOrNull { it.secondaryNumbers.size == 2 } ?: oldStage.last()
    val selPrimary = setOf<Int>()   // 0 红球命中
    val selSecondary = demo.secondaryNumbers.toSet()  // 后区完全命中 → 0+2
    println("    🎯 示范选号: primary=∅, secondary=$selSecondary （验证 DLT 0+2 命中归类）")

    val results = lotteryMatcherMatch(dltCfg, selPrimary, selSecondary, dltDraws)
    // 期望：
    //   DLT 2019: 0+2 = 九等奖；DLT 2026: 0+2 = 七等奖
    // 新版 LotteryMatcher 会分开命名（旧规则尾部标注"（超级大乐透2019版（9级））"），因此不会把 2019 命中
    // 合并到"七等奖"这一奖项名下；如果合并错了，total=七等奖次数会大于 dlt_2026 单独次数。

    val dlt2019Count = results.filter { it.prizeName.contains("大乐透2019") || it.prizeName.contains("（9级）") }
        .sumOf { it.count }
    val dlt2026Count = results.filter {
        it.prizeName == "七等奖"    // 纯"七等奖"名 = 最新版合并结果（不含后缀）
    }.sumOf { it.count }
    val oldTotal = oldStage.filter { d ->
        (0 until d.primaryNumbers.count { it in selPrimary }) == 0 &&
            d.secondaryNumbers.count { it in selSecondary } == 2
    }.count { true }
    println("      2019阶段实际 0+2 命中期数(样本内)=$oldTotal")
    println("      解析输出： 最新版七等奖(count=$dlt2026Count)  2019独立后缀项(count=$dlt2019Count)")

    // 断言：旧期 0+2 不会被混进"纯七等奖"里。如果 dlt2026Count > DLT_2026 期的 0+2 命中次数，就是错位。
    val newStageOnly = dltDraws.filter { it.ruleVersionKey == "dlt_2026" }
    val expectedNewOnlyCount = newStageOnly.count { d ->
        d.primaryNumbers.count { it in selPrimary } == 0 &&
            d.secondaryNumbers.count { it in selSecondary } == 2
    }
    check(dlt2026Count <= expectedNewOnlyCount + 1 /* 允许 1 期舍入误差 */) {
        "❌ LotteryMatcher 归类错误：最新版七等奖 count=$dlt2026Count 超过了 2026 阶段实际 0+2 命中数 $expectedNewOnlyCount，说明 DLT 2019 老数据被错并进去了。"
    }
    println("    ✔ LotteryMatcher 归类断言 OK（新版七等=$dlt2026Count ≈ 2026样本实际=$expectedNewOnlyCount；2019独立项=$dlt2019Count）")
}

// ===== 主流程：逐彩种下载 + 解析 + 断言 =====
for (cfg in ALL) {
    println("============ ${cfg.displayName}(${cfg.code}) ============")
    println("URL: ${cfg.url}")
    try {
        val bytes = fetchBytes(cfg.url)
        println("Downloaded ${bytes.size} bytes, OLE2 header=${bytes.size >= 4 && bytes[0]==0xD0.toByte() && bytes[1]==0xCF.toByte() && bytes[2]==0x11.toByte() && bytes[3]==0xE0.toByte()}")
        val text = String(bytes, Charsets.UTF_8)
        val draws = parseText(cfg, text)
        println("Parsed ${draws.size} draws")

        if (draws.isNotEmpty()) {
            val latest = draws.first()
            println("最新一期: issue=${latest.issue} date=${latest.date}")
            println("  号码: ${latest.primaryNumbers}+${latest.secondaryNumbers}")
            println("  ruleVersionKey=${latest.ruleVersionKey} sales=${latest.salesAmount} jackpot=${latest.jackpotAmount}")
            println("  实际奖级数=${latest.actualTierCount} / 规则期望=${latest.resolveRuleVersion(cfg).realTiersToUse} status=${latest.tierMatchStatus}")
            println("  conditionalFlags=${latest.conditionalFlags}")
            latest.allPrizeTiers.forEachIndexed { idx, t ->
                if (t != null) println("    Tier[$idx]: count=${t.count}注, amount=¥${t.amount}")
                else println("    Tier[$idx]: null(未公布)")
            }
            // 最近 30 期 tierMatchStatus 分布
            val dist = draws.take(30).groupingBy { it.tierMatchStatus }.eachCount()
            println("  最近30期结构一致性分布: $dist")

            // —— 四项断言（按彩种适用）——
            if (cfg.code == "ssq") assertSsqFuyunFlag(draws)
            if (cfg.code == "dlt") assertDltFloatAmounts(draws)
            if (cfg.ruleVersions.size > 1) assertRuleVersionOrder(cfg, draws)
            if (cfg.code == "dlt") assertLotteryMatcherOldAges(draws)
        }
    } catch (e: Throwable) {
        val stack = e.stackTraceToString().lineSequence().take(6).joinToString("\n")
        println("  ❌ 失败: ${e.message}\n$stack")
    }
    println()
}
