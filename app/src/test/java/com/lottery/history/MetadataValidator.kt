package com.lottery.history

import com.lottery.history.model.LotteryType
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.network.LotteryXlsParser
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL

/**
 * 元数据解析校验器（用户要求）：
 *  1. 按每个彩种去官网（17500.cn）抓真实 txt 数据源。
 *  2. 按该彩种每个规则版本（policyLabel）各取 10 期样本。
 *  3. 对每一期，先输出官方 txt 源的原始行 split(" ") 后每个字段的索引和原始值，
 *     然后输出 XlsParser 解析出来的元数据（期号/日期/号码/销售额/奖池/基本投注各奖级/追加投注各奖级）。
 *
 *  方便用户逐字段对照：哪个字段官方值是什么，我们解析成什么，哪个阶段政策对不上一目了然。
 */
class MetadataValidator {

    @Test
    fun validateAllLotteryTypesAllVersions() {
        for (config in LotteryType.ALL) {
            println("╔══════════════════════════════════════════════════════════════════════════════════╗")
            println("║ 【彩种】 code=${config.code.padEnd(4)} displayName=${config.displayName}       ║")
            println("║ 【数据源】 URL=${config.url}  ║")
            println("╚══════════════════════════════════════════════════════════════════════════════════╝")

            // —— 1. 拉官方 txt 源 ——
            val bytes = try {
                URL(config.url).openStream().use { it.readBytes() }
            } catch (t: Throwable) {
                // 用本地缓存 fallback（如果用户之前跑过 DumpDltLatest10 之类）
                val fname = config.url.substringAfterLast('/')
                val local = File("/tmp/$fname")
                if (local.exists()) {
                    println("  [WARN] 联网失败，改用本地缓存 /tmp/$fname")
                    local.readBytes()
                } else {
                    println("  [ERROR] 拉取失败：${t.message}，跳过该彩种")
                    continue
                }
            }
            val text = String(bytes, Charsets.UTF_8)
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

            // —— 2. 跑一遍 XlsParser，得到 draw 列表（按从新→旧排列）——
            val draws = LotteryXlsParser.parse(config, ByteArrayInputStream(bytes))
                .associateBy { it.issue }

            // —— 3. 构造「期号→官方原始行」映射（用于逐字段展示官方真实源）——
            val issueRegex = Regex("""^[0-9]{5,}$""")
            val rawByIssue: Map<String, String> = lines
                .asSequence()
                .mapNotNull { l ->
                    val first = l.split(Regex("\\s+")).firstOrNull() ?: return@mapNotNull null
                    if (issueRegex.matches(first)) first to l else null
                }
                .toMap()

            // —— 4. 按 ruleVersion 分组后，每版取 10 期 ——
            val rvMap = config.ruleVersions.associateBy { it.key }
            val drawsSorted = draws.values.sortedWith(compareByDescending<com.lottery.history.model.LotteryDraw> { it.date }
                .thenByDescending { it.issue })

            val groupByRv: Map<String, List<com.lottery.history.model.LotteryDraw>> =
                drawsSorted.groupBy { it.ruleVersionKey ?: "__UNKNOWNN__" }

            for (rv in config.ruleVersions) {
                val group = groupByRv[rv.key]?.take(10) ?: emptyList()
                println()
                println("  ┌────────────────────────────────────────────────────────────────────────┐")
                println("  │ 规则版本 key=${rv.key}")
                println("  │ policyLabel=${rv.policyLabel}")
                println("  │ effectiveFromDate=${rv.effectiveFromDate}")
                println("  │ 奖级对配置: realTiersToUse=${rv.realTiersToUse} prizeTierPairCount=${rv.prizeTierPairCount} extra=${rv.extraFieldCount} append=${rv.appendTierPairCount}")
                println("  └────────────────────────────────────────────────────────────────────────┘")

                if (group.isEmpty()) {
                    println("    → 该政策未匹配到任何期（可能effectiveFromDate之后还未开奖，或date解析异常）")
                    continue
                }

                for ((i, d) in group.withIndex()) {
                    val rawLine = rawByIssue[d.issue] ?: "[未找到期号${d.issue}的原始行]"
                    val parts = rawLine.split(Regex("\\s+"))
                    println()
                    println("    【第 ${i + 1} 期】issue=${d.issue}  date=${d.date}  ruleVersionKey=${d.ruleVersionKey}")
                    println("    parserVersion=${d.parserVersion}  parseSource=${d.parseSource}  tierMatchStatus=${d.tierMatchStatus}")
                    println("    ─────────────────────────── 官方原始字段（空格 split 后，1-based） ───────────────────────────")
                    // 对SSQ(红球出球顺序)和DLT(奖级段)用色/标签标明段落含义
                    printFieldsSegmented(parts, config, rv)
                    println()
                    println("    ─────────────────────────── XlsParser 解析后元数据 ───────────────────────────")
                    printParsedMetadata(d, rv, config)
                    // 追加：和官方字段对照的"数值对照断言"
                    printAssertions(d, parts, config, rv)
                }
            }
        }
    }

    /** 打印官方源字段，分段标注含义。1-based 索引方便和用户说的"字段30"对应。 */
    private fun printFieldsSegmented(
        parts: List<String>,
        config: LotteryTypeConfig,
        rv: LotteryTypeConfig.RuleVersion
    ) {
        val tag = buildString {
            for ((idx, v) in parts.withIndex()) {
                val i = idx + 1
                val mark: String = when (config.code) {
                    "ssq" -> when {
                        i == 1 -> "【期号】"
                        i == 2 -> "【日期】"
                        i in 3..8 -> "【红球开奖(i=${i-2})】"
                        i in 9..14 -> "【红球出球顺序${i-8}】"
                        i == 15 -> "【销售额】"
                        i == 16 -> "【奖池】"
                        i >= 17 -> {
                            val pairNo = (i - 17) / 2 + 1
                            val typ = if ((i - 17) % 2 == 0) "count" else "amount"
                            "【基本${pairNo}等_$typ】"
                        }
                        else -> ""
                    }
                    "dlt" -> when {
                        i == 1 -> "【期号】"
                        i == 2 -> "【日期】"
                        i in 3..7 -> "【前区号码${i-2}】"
                        i in 8..9 -> "【后区号码${i-7}】"
                        i == 10 -> "【销售额】"
                        i == 11 -> "【奖池】"
                        i in 12..25 -> {
                            val pairNo = (i - 12) / 2 + 1
                            val typ = if ((i - 12) % 2 == 0) "count" else "amount"
                            "【基本${pairNo}等主体_$typ】"
                        }
                        i in 26..29 -> {
                            val pairNo = (i - 26) / 2 + 8
                            val typ = if ((i - 26) % 2 == 0) "count" else "amount"
                            "【基本尾扩展${pairNo}等_$typ】（9级版本=8/9等；8级版本28-29忽略）"
                        }
                        i in 30..37 -> {
                            val pairNo = (i - 30) / 2 + 1
                            val typ = if ((i - 30) % 2 == 0) "count" else "amount"
                            "【追加${pairNo}等_$typ】"
                        }
                        i == 38 -> "【追加5级_count单字段】（amount=基本5级×0.8官方省略）"
                        else -> ""
                    }
                    "3d" -> when {
                        i == 1 -> "【期号】"
                        i == 2 -> "【日期】"
                        i in 3..5 -> "【开奖号码${i-2}】"
                        i in 6..8 -> "【试机号${i-5}】"
                        i == 9 -> "【extra-1】"
                        i == 10 -> "【extra-2销售额】"
                        i >= 11 -> {
                            val pairNo = (i - 11) / 2 + 1
                            val typ = if ((i - 11) % 2 == 0) "count" else "amount"
                            "【奖级${pairNo}_$typ】"
                        }
                        else -> ""
                    }
                    "p3" -> when {
                        i == 1 -> "【期号】"
                        i == 2 -> "【日期】"
                        i in 3..5 -> "【号码${i-2}】"
                        i == 6 -> "【销售额】"
                        i >= 7 -> {
                            val pairNo = (i - 7) / 2 + 1
                            val typ = if ((i - 7) % 2 == 0) "count" else "amount"
                            "【奖级${pairNo}_$typ】"
                        }
                        else -> ""
                    }
                    "p5" -> when {
                        i == 1 -> "【期号】"
                        i == 2 -> "【日期】"
                        i in 3..7 -> "【号码${i-2}】"
                        i == 8 -> "【销售额】"
                        i >= 9 -> {
                            val pairNo = (i - 9) / 2 + 1
                            val typ = if ((i - 9) % 2 == 0) "count" else "amount"
                            "【奖级${pairNo}_$typ】"
                        }
                        else -> ""
                    }
                    "7lc" -> when {
                        i == 1 -> "【期号】"
                        i == 2 -> "【日期】"
                        i in 3..9 -> "【基本号${i-2}】"
                        i == 10 -> "【特别号】"
                        i == 11 -> "【销售额】"
                        i == 12 -> "【奖池】"
                        i >= 13 -> {
                            val pairNo = (i - 13) / 2 + 1
                            val typ = if ((i - 13) % 2 == 0) "count" else "amount"
                            "【基本${pairNo}等_$typ】"
                        }
                        else -> ""
                    }
                    "7xc" -> when {
                        i == 1 -> "【期号】"
                        i == 2 -> "【日期】"
                        i in 3..8 -> "【前6位${i-2}】"
                        i == 9 -> "【后1位】"
                        i == 10 -> "【销售额】"
                        i == 11 -> "【奖池】"
                        i >= 12 -> {
                            val pairNo = (i - 12) / 2 + 1
                            val typ = if ((i - 12) % 2 == 0) "count" else "amount"
                            "【基本${pairNo}等_$typ】"
                        }
                        else -> ""
                    }
                    "kl8" -> when {
                        i == 1 -> "【期号】"
                        i == 2 -> "【日期】"
                        i in 3..22 -> "【20开奖号码${i-2}】"
                        i == 23 -> "【销售额】"
                        i == 24 -> "【奖池】"
                        i >= 25 -> {
                            val subPlayIdx = (i - 25) / 14  // 每种子玩法共7对=14字段
                            val within = (i - 25) % 14
                            val pairNo = within / 2 + 1
                            val typ = if (within % 2 == 0) "count" else "amount"
                            "【子玩法${subPlayIdx+1}_${pairNo}等_$typ】（选十=第1种，只看前7对=14字段）"
                        }
                        else -> ""
                    }
                    else -> ""
                }
                append(String.format("      F%02d %-40s %s\n", i, mark, v.take(32)))
            }
        }
        print(tag)
    }

    /** 打印解析后的 LotteryDraw 元数据 */
    private fun printParsedMetadata(
        d: com.lottery.history.model.LotteryDraw,
        rv: LotteryTypeConfig.RuleVersion,
        config: LotteryTypeConfig
    ) {
        println("      期号:         ${d.issue}")
        println("      开奖日期:     ${d.date}")
        println("      主号码(${config.primaryLabel}): ${d.primaryNumbers.joinToString(" ") { "%02d".format(it) }}")
        if (config.hasSecondary) {
            println("      副号码(${config.secondaryLabel}): ${d.secondaryNumbers.joinToString(" ") { "%02d".format(it) }}")
        }
        println("      销售额:       ${d.salesAmount} 元")
        println("      奖池金额:     ${d.jackpotAmount} 元")
        println("      一等(兼容):   count=${d.firstPrizeCount} / amount=${d.firstPrizeAmount}")
        println("      二等(兼容):   count=${d.secondPrizeCount} / amount=${d.secondPrizeAmount}")
        println("      actualTierCount=${d.actualTierCount} / realTiersToUse=${rv.realTiersToUse}")
        println()
        println("      ┌─基本投注 allPrizeTiers (共 ${d.allPrizeTiers.size} 个元素) ─┐")
        val prizeIdxNames = rv.rules.distinctBy { it.prizeName }.map { it.prizeName }.let { distinct ->
            // 把合并规则（比如"4+0=四等奖"和"4+1=四等奖"）去重后，按索引给allPrizeTiers展示用
            // allPrizeTiers size = realTiersToUse，按 policy 一一对应 distinct 名
            distinct
        }
        d.allPrizeTiers.withIndex().forEach { (i, t) ->
            val idx = i + 1
            val name = prizeIdxNames.getOrNull(i) ?: "(第${idx}段)"
            if (t == null) {
                println("      │ ${"%02d".format(idx)} $name  ——  null（官方 '-' 或缺字段）")
            } else {
                println("      │ ${"%02d".format(idx)} $name   count=${t.count}  金额=${t.amount}元")
            }
        }
        println("      └──────────────────────────────────────────────┘")
        if (d.appendPrizeTiers.isNotEmpty()) {
            println()
            println("      ┌─追加投注 appendPrizeTiers (共 ${d.appendPrizeTiers.size} 个元素) ─┐")
            val appendNames = (1..d.appendPrizeTiers.size).map { "追加${it}等" }
            d.appendPrizeTiers.withIndex().forEach { (i, t) ->
                val idx = i + 1
                val name = appendNames.getOrNull(i) ?: "(追加第${idx}段)"
                if (t == null) {
                    println("      │ ${"%02d".format(idx)} $name  ——  null")
                } else {
                    println("      │ ${"%02d".format(idx)} $name   count=${t.count}  金额=${t.amount}元")
                }
            }
            println("      └──────────────────────────────────────────────┘")
        }
        if (d.conditionalFlags.isNotEmpty()) {
            println("      条件性奖级标志: ${d.conditionalFlags.entries.joinToString { (k,v) -> "$k=$v" }}")
        }
    }

    /** 断言：把解析值和官方源对应位置字段直接做数值比较，OK 绿色 PASS / 红色 FAIL */
    private fun printAssertions(
        d: com.lottery.history.model.LotteryDraw,
        parts: List<String>,
        config: LotteryTypeConfig,
        rv: LotteryTypeConfig.RuleVersion
    ) {
        println()
        println("    ─────────────────────────── 数值对照断言（解析值 vs 官方原始字段位置）───────────────────────────")
        val passes = mutableListOf<String>()
        val fails = mutableListOf<String>()

        fun assert(desc: String, parsed: Long?, rawIdx0Based: Int, extra: (String) -> Unit = {}) {
            val raw = parts.getOrNull(rawIdx0Based)?.let { parseNum(it) }
            val ok = parsed != null && raw != null && parsed == raw
            val msg = "      [${if (ok) "OK" else "FAIL"}] $desc：解析值=$parsed  官方F${rawIdx0Based+1}=${parts.getOrNull(rawIdx0Based)}（→Long=$raw）"
            if (ok) passes.add(msg) else fails.add(msg)
            extra(msg)
        }

        when (config.code) {
            "dlt" -> {
                // 官方源字段（1-based→0-based）：销售额=10→9，奖池=11→10
                assert("销售额", d.salesAmount, 9)
                assert("奖池", d.jackpotAmount, 10)
                // 基本1-7等：字段12-25 → 0-based 11..24
                for (k in 0..6) {
                    val t = d.allPrizeTiers.getOrNull(k)
                    assert("基本${k+1}等count", t?.count, 11 + k*2)
                    assert("基本${k+1}等amount", t?.amount, 12 + k*2)
                }
                // 尾扩展 基本8=26-27→25/26  基本9=28-29→27/28
                if (rv.realTiersToUse >= 8) {
                    assert("基本8等count", d.allPrizeTiers.getOrNull(7)?.count, 25)
                    assert("基本8等amount", d.allPrizeTiers.getOrNull(7)?.amount, 26)
                }
                if (rv.realTiersToUse >= 9) {
                    assert("基本9等count", d.allPrizeTiers.getOrNull(8)?.count, 27)
                    assert("基本9等amount", d.allPrizeTiers.getOrNull(8)?.amount, 28)
                }
                // 追加1-4等：字段30-37 → 0-based 29..36
                for (k in 0..3) {
                    val t = d.appendPrizeTiers.getOrNull(k)
                    assert("追加${k+1}等count", t?.count, 29 + k*2)
                    assert("追加${k+1}等amount", t?.amount, 30 + k*2)
                }
                // 追加5级count单字段：字段38→37；amount=基本5级×0.8
                val ap5 = d.appendPrizeTiers.getOrNull(4)
                assert("追加5级count(官方单字段38)", ap5?.count, 37)
                val base5Amt = d.allPrizeTiers.getOrNull(4)?.amount
                val expect5 = if (base5Amt != null) (base5Amt * 0.8).toLong() else null
                if (ap5?.amount != null && expect5 != null) {
                    val ok = ap5.amount == expect5
                    val msg = "      [${if (ok) "OK" else "FAIL"}] 追加5级amount：解析值=${ap5.amount}  期望值(基本5级$base5Amt × 0.8)=$expect5"
                    if (ok) passes.add(msg) else fails.add(msg)
                }
                // 追加6/7级：count=0 固定 amount=基本6/7×0.8
                for (k in 5..6) {
                    if (rv.appendTierPairCount > k) {
                        val ap = d.appendPrizeTiers.getOrNull(k)
                        val baseK = d.allPrizeTiers.getOrNull(k)?.amount
                        val expect = if (baseK != null) (baseK * 0.8).toLong() else null
                        val cOk = ap?.count == 0L
                        val aOk = ap != null && expect != null && ap.amount == expect
                        val m1 = "      [${if (cOk) "OK" else "FAIL"}] 追加${k+1}级count：解析值=${ap?.count}  预期=0（官方无独立字段，固定0）"
                        val m2 = "      [${if (aOk) "OK" else "FAIL"}] 追加${k+1}级amount：解析值=${ap?.amount}  期望值(基本${k+1}级$baseK × 0.8)=$expect"
                        if (cOk) passes.add(m1) else fails.add(m1)
                        if (aOk) passes.add(m2) else fails.add(m2)
                    }
                }
            }
            "ssq" -> {
                // 字段：15=销售额 16=奖池 0-based 14 15
                assert("销售额", d.salesAmount, 14)
                assert("奖池", d.jackpotAmount, 15)
                for (k in 0 until rv.realTiersToUse) {
                    val t = d.allPrizeTiers.getOrNull(k)
                    assert("基本${k+1}等count", t?.count, 16 + k*2)
                    assert("基本${k+1}等amount", t?.amount, 17 + k*2)
                }
            }
            "7lc" -> {
                assert("销售额", d.salesAmount, 10)
                assert("奖池", d.jackpotAmount, 11)
                for (k in 0 until rv.realTiersToUse) {
                    val t = d.allPrizeTiers.getOrNull(k)
                    assert("基本${k+1}等count", t?.count, 12 + k*2)
                    assert("基本${k+1}等amount", t?.amount, 13 + k*2)
                }
            }
            "7xc" -> {
                assert("销售额", d.salesAmount, 9)
                assert("奖池", d.jackpotAmount, 10)
                for (k in 0 until rv.realTiersToUse) {
                    val t = d.allPrizeTiers.getOrNull(k)
                    assert("基本${k+1}等count", t?.count, 11 + k*2)
                    assert("基本${k+1}等amount", t?.amount, 12 + k*2)
                }
            }
            "3d" -> {
                // extraFieldCount=6：最后一个=销售额（10→0-based 9）
                assert("销售额", d.salesAmount, 9)
                for (k in 0 until rv.realTiersToUse) {
                    val t = d.allPrizeTiers.getOrNull(k)
                    assert("奖级${k+1}count", t?.count, 10 + k*2)
                    assert("奖级${k+1}amount", t?.amount, 11 + k*2)
                }
            }
            "p3" -> {
                assert("销售额", d.salesAmount, 5)
                for (k in 0 until rv.realTiersToUse) {
                    val t = d.allPrizeTiers.getOrNull(k)
                    assert("奖级${k+1}count", t?.count, 6 + k*2)
                    assert("奖级${k+1}amount", t?.amount, 7 + k*2)
                }
            }
            "p5" -> {
                assert("销售额", d.salesAmount, 7)
                for (k in 0 until rv.realTiersToUse) {
                    val t = d.allPrizeTiers.getOrNull(k)
                    assert("奖级${k+1}count", t?.count, 8 + k*2)
                    assert("奖级${k+1}amount", t?.amount, 9 + k*2)
                }
            }
            "kl8" -> {
                assert("销售额", d.salesAmount, 22)
                assert("奖池", d.jackpotAmount, 23)
                for (k in 0 until rv.realTiersToUse) {
                    val t = d.allPrizeTiers.getOrNull(k)
                    assert("选十奖级${k+1}count", t?.count, 24 + k*2)
                    assert("选十奖级${k+1}amount", t?.amount, 25 + k*2)
                }
            }
        }

        // 简单 OK/FAIL 统计
        passes.forEach { println(it) }
        if (fails.isNotEmpty()) {
            println("      ═══════════ FAIL ═══════════")
            fails.forEach { println(it) }
            println("      FAIL总数=${fails.size}  OK总数=${passes.size}")
        } else {
            println("      ✔︎ 所有断言全部通过 (共${passes.size}项)")
        }
    }

    private fun parseNum(s: String): Long? {
        val v = s.trim()
        if (v == "-" || v.isEmpty()) return null
        // 美式千分位
        if (',' in v) return v.replace(",", "").substringBefore('.').toLongOrNull()
        if ('.' in v) return v.substringBefore('.').toLongOrNull()
        return v.toLongOrNull()
    }
}
