package com.lottery.history

import com.lottery.history.model.LotteryType
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.model.PrizeTierEntry
import com.lottery.history.network.LotteryXlsParser
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL

/**
 * 元数据解析校验器 · 用户定制版输出格式：
 *  要求：① 根据每个阶段政策和期数，每个彩种去官方读10期；
 *        ② 一行「解析元数据（中文奖级）」下一行「当期官方原始数据+政策」严格对照；
 *        ③ 每个阶段（规则版本）都要有；
 *        ④ 中文数字都要有（一/二/三/四/五/六/七/八/九等奖）。
 */
class MetadataValidatorTable {

    companion object {
        const val OUT_TXT = "/tmp/metadata_validator_report.txt"
        val 中文数字 = listOf("零","一","二","三","四","五","六","七","八","九","十")
        fun toChinese(n: Int): String = if (n in 1..10) 中文数字[n] else n.toString()
    }

    private val outFile = File(OUT_TXT).also { it.parentFile.mkdirs() }.bufferedWriter()
    private fun out(s: String = "") { println(s); outFile.write(s + "\n") }
    private fun outBytes(bytes: ByteArray) { outFile.write(bytes.toString(Charsets.UTF_8)) }
    fun close() { runCatching { outFile.close() } }

    @Test
    fun validateAll() {
        Runtime.getRuntime().addShutdownHook(Thread { close() })
        // 先缓存所有官方源到 /tmp，保证断网 fallback
        for (c in LotteryType.ALL) {
            val fname = c.url.substringAfterLast('/')
            val f = File("/tmp/$fname")
            if (!f.exists()) runCatching { f.writeBytes(URL(c.url).readBytes()) }
        }
        out()
        out("╔══════════════════════════════════════════════════════════════════════════════════════════════════╗")
        out("║                彩 票 开 奖 数 据 · 解 析 元 数 据 vs 官 方 原 始 数 据  对 照 表                    ║")
        out("║                （每个彩种 × 每个政策阶段 × 每阶段 10 期 · 逐字段严格对照）                         ║")
        out("╚══════════════════════════════════════════════════════════════════════════════════════════════════╝")
        out()

        for (config in LotteryType.ALL) {
            validateOne(config)
        }
        out()
        out("═══════════════════════════════════════════════════════════════════════════════════════════════════")
        out("报告输出文件：$OUT_TXT")
        close()
    }

    // ========================== 单个彩种 ==========================
    private fun validateOne(config: LotteryTypeConfig) {
        out()
        out("┌──────────────────────────────────────────────────────────────────────────────────────────────────┐")
        out("│ 彩 种：${config.displayName.padEnd(8)}   code=${config.code.padEnd(5)}   官方源：${config.url}")
        out("└──────────────────────────────────────────────────────────────────────────────────────────────────┘")

        val fname = config.url.substringAfterLast('/')
        val bytes = File("/tmp/$fname").takeIf { it.exists() }?.readBytes()
            ?: runCatching { URL(config.url).readBytes() }.getOrElse {
                out("  ⚠️  无法获取官方源数据，跳过：$fname"); return
            }
        val text = String(bytes, Charsets.UTF_8)
        val draws = LotteryXlsParser.parse(config, ByteArrayInputStream(bytes)).associateBy { it.issue }
        val issueRegex = Regex("""^[0-9]{5,}$""")
        val rawLineByIssue: Map<String, List<String>> = text.lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }
            .associate { l ->
                val p = l.split(Regex("\\s+"))
                (if (issueRegex.matches(p[0])) p[0] else "__NA__") to p
            }
        val drawsSorted = draws.values.sortedWith(compareByDescending<com.lottery.history.model.LotteryDraw> { it.date }
            .thenByDescending { it.issue })

        for (rv in config.ruleVersions) {
            val matched = drawsSorted.filter { it.ruleVersionKey == rv.key }.take(10)
            printPolicyHeader(config, rv, matched.size)
            if (matched.isEmpty()) { out("    → 该政策当前未匹配到样本期（生效日期之后尚无开奖数据）") ; continue }

            for ((idx, draw) in matched.withIndex()) {
                val parts = rawLineByIssue[draw.issue] ?: emptyList()
                printOneSample(idx + 1, draw, parts, config, rv)
            }
        }
    }

    // ========================== 政策表头 ==========================
    private fun printPolicyHeader(config: LotteryTypeConfig, rv: LotteryTypeConfig.RuleVersion, sampleCount: Int) {
        out()
        out("  ══════════════════════════════════════════════════════════════════════════════════════════════════")
        out("  规则版本 KEY : ${rv.key}")
        out("  政策标签     : ${rv.policyLabel}")
        out("  生效日期     : ${rv.effectiveFromDate}")
        out("  变更说明     : ${rv.changeNote.lineSequence().first()}")
        if (rv.changeNote.count { it == '\n' } > 0) {
            rv.changeNote.lineSequence().drop(1).forEach { out("                 $it") }
        }
        out("  奖级结构     : 真实展示 ${rv.realTiersToUse} 级 / 解析奖级对 ${rv.prizeTierPairCount} 对 / extra=${rv.extraFieldCount} / 追加=${rv.appendTierPairCount} 级")
        out("  本期政策命中规则（中文奖级）：")
        val collapsed = ArrayList<Triple<String, String, Long?>>()
        for (r in rv.rules) {
            if (collapsed.isEmpty() || collapsed.last().second != r.prizeName) {
                collapsed.add(Triple("${r.matchPrimary}+${r.matchSecondary}", r.prizeName, r.fixedAmountYuan))
            } else {
                val last = collapsed.removeAt(collapsed.lastIndex)
                collapsed.add(Triple("${last.first}, ${r.matchPrimary}+${r.matchSecondary}", r.prizeName, r.fixedAmountYuan))
            }
        }
        collapsed.forEachIndexed { i, (conds, name, fixed) ->
            val amt = fixed?.let { "（固定${it}元）" } ?: "（浮动）"
            out("     ${toChinese(i+1)}、$name：命中条件 [$conds] $amt")
        }
        out("  样本数量     : $sampleCount 期")
        out("  ══════════════════════════════════════════════════════════════════════════════════════════════════")
    }

    // ========================== 单期对照：一行元数据一行官方 ==========================
    private fun printOneSample(
        n: Int,
        d: com.lottery.history.model.LotteryDraw,
        parts: List<String>,
        config: LotteryTypeConfig,
        rv: LotteryTypeConfig.RuleVersion
    ) {
        out()
        out("  ────────────────────────────────────────────────────────────────────────────────────────────────")
        out("  【第 $n 期样本】 期号=${d.issue}   开奖日期=${d.date}   ruleVersionKey=${d.ruleVersionKey}   解析器版本=v${d.parserVersion}   来源=${d.parseSource}")
        out()
        out("    ┌ 解析后元数据（APP 入库 & 展示用）─────────────────────────────────────────────────────────┐")
        out("    │ 期号        │ ${d.issue}")
        out("    │ 开奖日期    │ ${d.date}")
        out("    │ 主号码(${config.primaryLabel}) │ ${d.primaryNumbers.joinToString(" ") { "%02d".format(it) }}")
        if (config.hasSecondary) out("    │ 副号码(${config.secondaryLabel}) │ ${d.secondaryNumbers.joinToString(" ") { "%02d".format(it) }}")
        out("    │ 销售额      │ ${formatMoney(d.salesAmount)} 元")
        out("    │ 奖池金额    │ ${formatMoney(d.jackpotAmount)} 元")
        out("    │ 结构审计    │ tierMatchStatus=${d.tierMatchStatus}   actualTierCount=${d.actualTierCount}")
        out("    │ ─ 基本投注 ────────────────────────────────────────────────────────────────────────────── │")
        val baseNames = rv.rules.map { it.prizeName }.distinct()
        d.allPrizeTiers.withIndex().take(rv.realTiersToUse).forEachIndexed { _i, pair ->
            val (i, t) = pair
            val idxZh = toChinese(i+1)
            val name = baseNames.getOrNull(i) ?: "(第${idxZh}段)"
            val fixed = rv.rules.firstOrNull { it.prizeName == name }?.fixedAmountYuan
            val fixedTag = fixed?.let { "【政策固定$it 元】" } ?: "【政策浮动】"
            val count = t?.count?.toString() ?: "—"
            val amt = t?.amount?.let { formatMoney(it) + "元" } ?: "—"
            val diff = if (t != null && fixed != null && t.count > 0L && t.amount > 0L && t.amount != fixed) {
                " ⚠️  实际金额${t.amount}≠政策固定${fixed}"
            } else ""
            out("    │   ${idxZh}等奖 $name$fixedTag   注数=$count   单注金额=$amt$diff")
        }
        if (d.appendPrizeTiers.isNotEmpty()) {
            out("    │ ─ 追加投注 ────────────────────────────────────────────────────────────────────────────── │")
            d.appendPrizeTiers.withIndex().take(rv.appendTierPairCount).forEachIndexed { _i, pair ->
                val (i, t) = pair
                val idxZh = toChinese(i+1)
                val count = t?.count?.toString() ?: "0"
                val amt = t?.amount?.let { if (it==0L)"—" else formatMoney(it)+"元" } ?: "—"
                // 追加公式：追加i等 = 基本i等 × 80% 校验
                val sameLevelBase = d.allPrizeTiers.getOrNull(i)
                val expect = sameLevelBase?.amount?.let { (it * 0.8).toLong() }
                val check = if (t != null && expect != null && t.amount > 0L && expect > 0L) {
                    if (t.amount == expect) "  ✔ 基本×0.8验证通过(${expect})"
                    else "  ⚠️  基本×0.8应得${expect}，实际${t.amount}"
                } else ""
                val empty = if (t?.count == 0L && t?.amount == 0L) "  （本期空开）" else ""
                out("    │   ${idxZh}追加（追加$idxZh 等）   注数=$count   单注金额=$amt$check$empty")
            }
        }
        if (d.conditionalFlags.isNotEmpty()) {
            out("    │ 条件奖级旗标：" + d.conditionalFlags.entries.joinToString { (k,v) -> "$k=$v" })
        }
        out("    └──────────────────────────────────────────────────────────────────────────────────────────────┘")

        out()
        out("    ┌ 当期官方原始数据（空格 split 后按列标注含义，严格来源于 ${config.url.substringAfterLast('/')}） ─┐")
        when (config.code) {
            "dlt" -> dumpDltRawLine(parts, d, rv)
            "ssq" -> dumpSsqRawLine(parts, d, rv)
            "3d" -> dump3dRawLine(parts, d, rv)
            "7lc" -> dump7lcRawLine(parts, d, rv)
            "p3"  -> dumpP3RawLine(parts, d, rv)
            "p5"  -> dumpP5RawLine(parts, d, rv)
            "7xc" -> dump7xcRawLine(parts, d, rv)
            "kl8" -> dumpKl8RawLine(parts, d, rv)
        }
        out("    │ ─ 当 期 政 策 摘 录 ───────────────────────────────────────────────────────────────────── │")
        out("    │ policyLabel  : ${rv.policyLabel}")
        out("    │ 生效起始日   : ${rv.effectiveFromDate}")
        rv.rules.map { it.prizeName }.distinct().forEachIndexed { i, name ->
            val conds = rv.rules.filter { it.prizeName == name }.joinToString("/") { "${it.matchPrimary}+${it.matchSecondary}" }
            val fixed = rv.rules.first { it.prizeName == name }.fixedAmountYuan
            val tag = fixed?.let { "固定${it}元" } ?: "浮动奖"
            out("    │  ${toChinese(i+1)}等奖 $name：命中条件 [$conds]  奖金政策：$tag")
        }
        out("    └──────────────────────────────────────────────────────────────────────────────────────────────┘")

        // 数值断言汇总：OK/FAIL
        out()
        out("      ── 逐字段数值断言（解析值 vs 官方源字段）─────────────────────────────────────")
        assertOne(d, parts, config, rv)
    }

    // ================================================
    //          官方原始字段分类标注（按彩种）
    // ================================================
    private fun dumpDltRawLine(parts: List<String>, d: com.lottery.history.model.LotteryDraw, rv: LotteryTypeConfig.RuleVersion) {
        if (parts.isEmpty()) { out("    │ （未匹配到该期原始行）"); return }
        out("    │ F01 期号                = ${P(0,parts)}")
        out("    │ F02 开奖日期            = ${P(1,parts)}")
        out("    │ F03-F07 前区5个号码     = ${P(2,parts)} ${P(3,parts)} ${P(4,parts)} ${P(5,parts)} ${P(6,parts)}")
        out("    │ F08-F09 后区2个号码     = ${P(7,parts)} ${P(8,parts)}")
        out("    │ F10 销售额              = ${P(9,parts)}   → 解析值=${d.salesAmount}  ${chk(d.salesAmount, parts, 9)}")
        out("    │ F11 奖池                = ${P(10,parts)}   → 解析值=${d.jackpotAmount}  ${chk(d.jackpotAmount, parts, 10)}")
        out("    │ ─ 基本投注主体（所有版本通用 7×2=14 格）─────")
        for (k in 0..6) {
            val cIdx = 11 + k*2; val aIdx = 12 + k*2
            val t = d.allPrizeTiers.getOrNull(k)
            out("    │ F${"%02d".format(cIdx+1)}-${"%02d".format(aIdx+1)} 基本${toChinese(k+1)}等 count/amt = ${P(cIdx,parts)} / ${P(aIdx,parts)}   → 解析 count=${t?.count} amt=${t?.amount}  ${chk2(t, parts, cIdx, aIdx)}")
        }
        out("    │ ─ 基本投注尾部扩展（2×2=4 格，9级版本=8/9等，8级版本=8等 28-29 忽略，7级/6级全忽略）─────")
        for (k in 0..1) {
            val cIdx = 25 + k*2; val aIdx = 26 + k*2
            val prizeName = listOf("基本八等", "基本九等")[k]
            val inUse = (rv.realTiersToUse == 9) || (rv.realTiersToUse == 8 && k == 0)
            val t = d.allPrizeTiers.getOrNull(7 + k)
            val mark = if (!inUse) "  （本政策不使用）" else ""
            val chkTag = if (inUse) chk2(t, parts, cIdx, aIdx) else ""
            out("    │ F${"%02d".format(cIdx+1)}-${"%02d".format(aIdx+1)} $prizeName count/amt = ${P(cIdx,parts)} / ${P(aIdx,parts)}$mark   → 解析 count=${t?.count} amt=${t?.amount}  $chkTag")
        }
        out("    │ ─ 追加投注 1~4 等（4×2=8 格）─────")
        for (k in 0..3) {
            val cIdx = 29 + k*2; val aIdx = 30 + k*2
            val t = d.appendPrizeTiers.getOrNull(k)
            out("    │ F${"%02d".format(cIdx+1)}-${"%02d".format(aIdx+1)} 追加${toChinese(k+1)}等 count/amt = ${P(cIdx,parts)} / ${P(aIdx,parts)}   → 解析 count=${t?.count} amt=${t?.amount}  ${chk2(t, parts, cIdx, aIdx)}")
        }
        out("    │ ─ 追加投注五级尾段（1 格 count 单字段 + amt=基本五等×80%，官方省略 amt）─────")
        val a5 = d.appendPrizeTiers.getOrNull(4)
        val b5 = d.allPrizeTiers.getOrNull(4)?.amount
        val exp = b5?.let { (it * 0.8).toLong() }
        val a5Str = run {
            if (a5?.count != null && P(37, parts) != "—") {
                val pnum = parseNum(P(37, parts))
                if (a5.count == pnum && a5.amount == exp) "✔ 全部通过"
                else "⚠️  不通过(count=${pnum} / amt期望=${exp})"
            } else ""
        }
        out("    │ F38 追加五等 count(单字段) = ${P(37,parts)}   → 解析 count=${a5?.count} amt=${a5?.amount}  amt验证：基本五等$b5 × 80% 预期=$exp  整体$a5Str")
        if (rv.appendTierPairCount >= 6) {
            val a6 = d.appendPrizeTiers.getOrNull(5); val b6 = d.allPrizeTiers.getOrNull(5)?.amount; val exp6 = b6?.let { (it * 0.8).toLong() }
            val a7 = d.appendPrizeTiers.getOrNull(6); val b7 = d.allPrizeTiers.getOrNull(6)?.amount; val exp7 = b7?.let { (it * 0.8).toLong() }
            out("    │ （官方无独立字段）追加六等   → 解析 count=${a6?.count} amt=${a6?.amount}   验证：基本六等$b6 × 80% 期望=$exp6  预期count=0  ${if(a6?.count==0L && a6.amount==exp6)"✔ 通过" else "⚠️  不通过"}")
            if (rv.appendTierPairCount >= 7) {
                out("    │ （官方无独立字段）追加七等   → 解析 count=${a7?.count} amt=${a7?.amount}   验证：基本七等$b7 × 80% 期望=$exp7  预期count=0  ${if(a7?.count==0L && a7.amount==exp7)"✔ 通过" else "⚠️  不通过"}")
            }
        }
    }

    private fun dumpSsqRawLine(parts: List<String>, d: com.lottery.history.model.LotteryDraw, rv: LotteryTypeConfig.RuleVersion) {
        if (parts.isEmpty()) { out("    │ （未匹配到该期原始行）"); return }
        out("    │ F01 期号                = ${P(0,parts)}")
        out("    │ F02 开奖日期            = ${P(1,parts)}")
        out("    │ F03-F08 红球6个         = ${(2..7).joinToString(" ") { P(it,parts) }}")
        out("    │ F09-F14 红球出球顺序6个 = ${(8..13).joinToString(" ") { P(it,parts) }}")
        out("    │ F15 销售额              = ${P(14,parts)}   → 解析值=${d.salesAmount}  ${chk(d.salesAmount, parts, 14)}")
        out("    │ F16 奖池                = ${P(15,parts)}   → 解析值=${d.jackpotAmount}  ${chk(d.jackpotAmount, parts, 15)}")
        out("    │ ─ 基本投注（${rv.prizeTierPairCount}×2 格，按规则版本定义）─────")
        for (k in 0 until rv.prizeTierPairCount) {
            val cIdx = 16 + k*2; val aIdx = 17 + k*2
            val t = d.allPrizeTiers.getOrNull(k)
            val usedBy = if (k < rv.realTiersToUse) {
                val names = rv.rules.map { it.prizeName }.distinct()
                "  展示为：${toChinese(k+1)}等奖 ${names.getOrNull(k)?:""}"
            } else "  （本政策不展示 realTiersToUse=${rv.realTiersToUse}）"
            out("    │ F${"%02d".format(cIdx+1)}-${"%02d".format(aIdx+1)} 第${toChinese(k+1)}奖段 count/amt = ${P(cIdx,parts)} / ${P(aIdx,parts)}$usedBy   → 解析 count=${t?.count} amt=${t?.amount}  ${chk2(t, parts, cIdx, aIdx)}")
        }
    }

    private fun dump3dRawLine(parts: List<String>, d: com.lottery.history.model.LotteryDraw, rv: LotteryTypeConfig.RuleVersion) {
        if (parts.isEmpty()) { out("    │ （未匹配到该期原始行）"); return }
        out("    │ F01 期号                = ${P(0,parts)}")
        out("    │ F02 开奖日期            = ${P(1,parts)}")
        out("    │ F03-F05 开奖号码3位     = ${(2..4).joinToString(" ") { P(it,parts) }}")
        out("    │ F06-F08 试机号          = ${(5..7).joinToString(" ") { P(it,parts) }}")
        out("    │ F09 extra-1             = ${P(8,parts)}")
        out("    │ F10 销售额              = ${P(9,parts)}   → 解析值=${d.salesAmount}  ${chk(d.salesAmount, parts, 9)}")
        out("    │ ─ 三档奖级 ─────")
        for (k in 0..2) {
            val cIdx = 10 + k*2; val aIdx = 11 + k*2
            val t = d.allPrizeTiers.getOrNull(k)
            out("    │ F${"%02d".format(cIdx+1)}-${"%02d".format(aIdx+1)} ${listOf("直选","组3","组6")[k]} count/amt = ${P(cIdx,parts)} / ${P(aIdx,parts)}   → 解析 count=${t?.count} amt=${t?.amount}  ${chk2(t, parts, cIdx, aIdx)}")
        }
    }

    private fun dump7lcRawLine(parts: List<String>, d: com.lottery.history.model.LotteryDraw, rv: LotteryTypeConfig.RuleVersion) {
        if (parts.isEmpty()) { out("    │ （未匹配到该期原始行）"); return }
        out("    │ F01 期号                = ${P(0,parts)}")
        out("    │ F02 开奖日期            = ${P(1,parts)}")
        out("    │ F03-F09 基本号7个       = ${(2..8).joinToString(" ") { P(it,parts) }}")
        out("    │ F10 特别号              = ${P(9,parts)}")
        out("    │ F11 销售额              = ${P(10,parts)}   → 解析值=${d.salesAmount}  ${chk(d.salesAmount, parts, 10)}")
        out("    │ F12 奖池                = ${P(11,parts)}   → 解析值=${d.jackpotAmount}  ${chk(d.jackpotAmount, parts, 11)}")
        for (k in 0 until rv.prizeTierPairCount) {
            val cIdx = 12 + k*2; val aIdx = 13 + k*2
            val t = d.allPrizeTiers.getOrNull(k)
            out("    │ F${"%02d".format(cIdx+1)}-${"%02d".format(aIdx+1)} ${toChinese(k+1)}等奖 count/amt = ${P(cIdx,parts)} / ${P(aIdx,parts)}   → 解析 count=${t?.count} amt=${t?.amount}  ${chk2(t, parts, cIdx, aIdx)}")
        }
    }

    private fun dumpP3RawLine(parts: List<String>, d: com.lottery.history.model.LotteryDraw, rv: LotteryTypeConfig.RuleVersion) {
        if (parts.isEmpty()) { out("    │ （未匹配到该期原始行）"); return }
        out("    │ F01 期号                = ${P(0,parts)}")
        out("    │ F02 开奖日期            = ${P(1,parts)}")
        out("    │ F03-F05 开奖号码3位     = ${(2..4).joinToString(" ") { P(it,parts) }}")
        out("    │ F06 销售额              = ${P(5,parts)}   → 解析值=${d.salesAmount}  ${chk(d.salesAmount, parts, 5)}")
        for (k in 0..2) {
            val cIdx = 6 + k*2; val aIdx = 7 + k*2
            val t = d.allPrizeTiers.getOrNull(k)
            out("    │ F${"%02d".format(cIdx+1)}-${"%02d".format(aIdx+1)} ${listOf("直选","组3","组6")[k]} count/amt = ${P(cIdx,parts)} / ${P(aIdx,parts)}   → 解析 count=${t?.count} amt=${t?.amount}  ${chk2(t, parts, cIdx, aIdx)}")
        }
    }

    private fun dumpP5RawLine(parts: List<String>, d: com.lottery.history.model.LotteryDraw, rv: LotteryTypeConfig.RuleVersion) {
        if (parts.isEmpty()) { out("    │ （未匹配到该期原始行）"); return }
        out("    │ F01 期号                = ${P(0,parts)}")
        out("    │ F02 开奖日期            = ${P(1,parts)}")
        out("    │ F03-F07 开奖号码5位     = ${(2..6).joinToString(" ") { P(it,parts) }}")
        out("    │ F08 销售额              = ${P(7,parts)}   → 解析值=${d.salesAmount}  ${chk(d.salesAmount, parts, 7)}")
        val t = d.allPrizeTiers.getOrNull(0)
        out("    │ F09-F10 一等奖 count/amt = ${P(8,parts)} / ${P(9,parts)}   → 解析 count=${t?.count} amt=${t?.amount}  ${chk2(t, parts, 8, 9)}")
    }

    private fun dump7xcRawLine(parts: List<String>, d: com.lottery.history.model.LotteryDraw, rv: LotteryTypeConfig.RuleVersion) {
        if (parts.isEmpty()) { out("    │ （未匹配到该期原始行）"); return }
        out("    │ F01 期号                = ${P(0,parts)}")
        out("    │ F02 开奖日期            = ${P(1,parts)}")
        out("    │ F03-F08 前6位号码       = ${(2..7).joinToString(" ") { P(it,parts) }}")
        out("    │ F09 后1位号码           = ${P(8,parts)}")
        out("    │ F10 销售额              = ${P(9,parts)}   → 解析值=${d.salesAmount}  ${chk(d.salesAmount, parts, 9)}")
        out("    │ F11 奖池                = ${P(10,parts)}   → 解析值=${d.jackpotAmount}  ${chk(d.jackpotAmount, parts, 10)}")
        for (k in 0 until rv.prizeTierPairCount) {
            val cIdx = 11 + k*2; val aIdx = 12 + k*2
            val t = d.allPrizeTiers.getOrNull(k)
            out("    │ F${"%02d".format(cIdx+1)}-${"%02d".format(aIdx+1)} ${toChinese(k+1)}等奖 count/amt = ${P(cIdx,parts)} / ${P(aIdx,parts)}   → 解析 count=${t?.count} amt=${t?.amount}  ${chk2(t, parts, cIdx, aIdx)}")
        }
    }

    private fun dumpKl8RawLine(parts: List<String>, d: com.lottery.history.model.LotteryDraw, rv: LotteryTypeConfig.RuleVersion) {
        if (parts.isEmpty()) { out("    │ （未匹配到该期原始行）"); return }
        out("    │ F01 期号                = ${P(0,parts)}")
        out("    │ F02 开奖日期            = ${P(1,parts)}")
        out("    │ F03-F22 20个开奖号码    = ${(2..21).joinToString(" ") { "%02d".format(P(it,parts).toIntOrNull()?:-1) }}")
        out("    │ F23 销售额              = ${P(22,parts)}   → 解析值=${d.salesAmount}  ${chk(d.salesAmount, parts, 22)}")
        out("    │ F24 奖池                = ${P(23,parts)}   → 解析值=${d.jackpotAmount}  ${chk(d.jackpotAmount, parts, 23)}")
        out("    │ ─ 选十玩法 前7对奖级（每种子玩法7×2=14字段，官方源共输出多子玩法，解析只取选十=第1种）")
        for (k in 0 until rv.prizeTierPairCount) {
            val cIdx = 24 + k*2; val aIdx = 25 + k*2
            val t = d.allPrizeTiers.getOrNull(k)
            out("    │ F${"%02d".format(cIdx+1)}-${"%02d".format(aIdx+1)} 选十${toChinese(k+1)}等奖 count/amt = ${P(cIdx,parts)} / ${P(aIdx,parts)}   → 解析 count=${t?.count} amt=${t?.amount}  ${chk2(t, parts, cIdx, aIdx)}")
        }
    }

    // ================================================
    //              逐字段断言汇总
    // ================================================
    private fun assertOne(d: com.lottery.history.model.LotteryDraw, parts: List<String>, c: LotteryTypeConfig, rv: LotteryTypeConfig.RuleVersion) {
        val ok = mutableListOf<String>(); val fail = mutableListOf<String>()
        fun chkItem(desc: String, parsed: Long?, rawIdx: Int) {
            val rv0 = parseNum(parts.getOrNull(rawIdx) ?: "-")
            val m = "  [$desc] 解析=$parsed   官方F${rawIdx+1}=${parts.getOrNull(rawIdx)}（→Long=$rv0）"
            if (parsed != null && rv0 != null && parsed == rv0) ok.add("✔ $m") else {
                // 允许 parsed=null+官方=="-" 不算 fail
                if ((parsed == null) && (parts.getOrNull(rawIdx) == null || parts.getOrNull(rawIdx) == "-")) ok.add("✔ ${m}  [双方均为缺失]")
                else fail.add("❌ $m")
            }
        }
        fun chkT(desc: String, t: PrizeTierEntry?, cI: Int, aI: Int) {
            val rc = parseNum(parts.getOrNull(cI) ?: "-")
            val ra = parseNum(parts.getOrNull(aI) ?: "-")
            val mc = t?.count; val ma = t?.amount
            val cOk = (mc == rc) || (mc == null && (parts.getOrNull(cI) == "-" || parts.getOrNull(cI) == null))
            val aOk = (ma == ra) || (ma == null && (parts.getOrNull(aI) == "-" || parts.getOrNull(aI) == null))
            val m = "  [$desc] count: 解析=$mc 官方F${cI+1}=${parts.getOrNull(cI)}(Long=$rc) / amt: 解析=$ma 官方F${aI+1}=${parts.getOrNull(aI)}(Long=$ra)"
            if (cOk && aOk) ok.add("✔ $m") else fail.add("❌ $m")
        }
        when (c.code) {
            "dlt" -> {
                chkItem("销售额", d.salesAmount, 9)
                chkItem("奖池", d.jackpotAmount, 10)
                for (k in 0..6) chkT("基本${toChinese(k+1)}等", d.allPrizeTiers.getOrNull(k), 11+k*2, 12+k*2)
                if (rv.realTiersToUse >= 8) chkT("基本八等", d.allPrizeTiers.getOrNull(7), 25, 26)
                if (rv.realTiersToUse >= 9) chkT("基本九等", d.allPrizeTiers.getOrNull(8), 27, 28)
                for (k in 0..3) chkT("追加${toChinese(k+1)}等", d.appendPrizeTiers.getOrNull(k), 29+k*2, 30+k*2)
                // 追加5级：count单字段字段38
                val ap5 = d.appendPrizeTiers.getOrNull(4); val pc = parseNum(parts.getOrNull(37)?:"-")
                val b5 = d.allPrizeTiers.getOrNull(4)?.amount; val e5 = b5?.let { (it * 0.8).toLong() }
                val cOk5 = (ap5?.count == pc) || (ap5?.count == null && parts.getOrNull(37) == "-")
                val aOk5 = ap5?.amount == e5
                val m5 = "  [追加五等] count: 解析=${ap5?.count} 官方F38=${parts.getOrNull(37)}(Long=$pc) / amt: 解析=${ap5?.amount} 基本五等×80%期望=$e5"
                if (cOk5 && aOk5) ok.add("✔ $m5") else fail.add("❌ $m5")
                for (k in 5..6) if (rv.appendTierPairCount > k) {
                    val ap = d.appendPrizeTiers.getOrNull(k); val bk = d.allPrizeTiers.getOrNull(k)?.amount
                    val ek = bk?.let { (it*0.8).toLong() }
                    val m = "  [追加${toChinese(k+1)}等] count: 解析=${ap?.count} (预期=0) / amt: 解析=${ap?.amount} 基本${toChinese(k+1)}等×80%期望=$ek"
                    if (ap?.count == 0L && ap?.amount == ek) ok.add("✔ $m") else fail.add("❌ $m")
                }
            }
            "ssq" -> {
                chkItem("销售额", d.salesAmount, 14); chkItem("奖池", d.jackpotAmount, 15)
                for (k in 0 until rv.prizeTierPairCount) chkT("基本${toChinese(k+1)}等", d.allPrizeTiers.getOrNull(k), 16+k*2, 17+k*2)
            }
            "3d" -> {
                chkItem("销售额", d.salesAmount, 9)
                for (k in 0..2) chkT("${listOf("直选","组3","组6")[k]}奖", d.allPrizeTiers.getOrNull(k), 10+k*2, 11+k*2)
            }
            "7lc" -> {
                chkItem("销售额", d.salesAmount, 10); chkItem("奖池", d.jackpotAmount, 11)
                for (k in 0 until rv.prizeTierPairCount) chkT("基本${toChinese(k+1)}等", d.allPrizeTiers.getOrNull(k), 12+k*2, 13+k*2)
            }
            "p3" -> {
                chkItem("销售额", d.salesAmount, 5)
                for (k in 0..2) chkT("${listOf("直选","组3","组6")[k]}奖", d.allPrizeTiers.getOrNull(k), 6+k*2, 7+k*2)
            }
            "p5" -> {
                chkItem("销售额", d.salesAmount, 7)
                chkT("一等奖", d.allPrizeTiers.getOrNull(0), 8, 9)
            }
            "7xc" -> {
                chkItem("销售额", d.salesAmount, 9); chkItem("奖池", d.jackpotAmount, 10)
                for (k in 0 until rv.prizeTierPairCount) chkT("基本${toChinese(k+1)}等", d.allPrizeTiers.getOrNull(k), 11+k*2, 12+k*2)
            }
            "kl8" -> {
                chkItem("销售额", d.salesAmount, 22); chkItem("奖池", d.jackpotAmount, 23)
                for (k in 0 until rv.prizeTierPairCount) chkT("选十${toChinese(k+1)}等", d.allPrizeTiers.getOrNull(k), 24+k*2, 25+k*2)
            }
        }
        ok.forEach { out("      $it") }
        if (fail.isNotEmpty()) { fail.forEach { out("      $it") }; out("      ▌ FAIL ${fail.size} 项，PASS ${ok.size} 项") }
        else out("      ▌ 全部 ${ok.size} 项字段对照均通过 ✔")
    }

    // ================================================
    //                  小辅助函数
    // ================================================
    private fun P(i: Int, parts: List<String>): String = parts.getOrNull(i) ?: "—"
    private fun parseNum(s: String): Long? {
        val t = s.trim()
        if (t == "-" || t.isEmpty()) return null
        val c = t.replace(",", "")
        return (if ('.' in c) c.substringBefore('.') else c).toLongOrNull()
    }
    private fun chk(parsed: Long?, parts: List<String>, rawIdx: Int): String {
        val rv0 = parseNum(parts.getOrNull(rawIdx) ?: "-")
        return if (parsed != null && rv0 != null && parsed == rv0) "✔一致"
        else if ((parsed == null) && (parts.getOrNull(rawIdx) == "-" || parts.getOrNull(rawIdx) == null)) "✔双方缺失"
        else "⚠️  不一致"
    }
    private fun chk2(t: PrizeTierEntry?, parts: List<String>, cIdx: Int, aIdx: Int): String {
        val rc = parseNum(parts.getOrNull(cIdx) ?: "-"); val ra = parseNum(parts.getOrNull(aIdx) ?: "-")
        val cOk = (t?.count == rc) || (t?.count == null && (parts.getOrNull(cIdx) == "-" || parts.getOrNull(cIdx) == null))
        val aOk = (t?.amount == ra) || (t?.amount == null && (parts.getOrNull(aIdx) == "-" || parts.getOrNull(aIdx) == null))
        val tagCount = if (!cOk) "count错" else ""
        val tagAmt = if (!aOk) "amt错" else ""
        return if (cOk && aOk) "✔一致" else "⚠️  不一致(${tagCount}${tagAmt})"
    }
    private fun formatMoney(n: Long?): String = when {
        n == null -> "—"
        n >= 100_000_000L -> String.format("%.2f亿", n / 1.0e8)
        n >= 10_000L -> String.format("%.2f万", n / 1.0e4)
        else -> String.format("%,d", n)
    }
}
