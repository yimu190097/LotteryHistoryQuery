package com.lottery.history

import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryType
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.model.PrizeTierEntry
import com.lottery.history.network.LotteryXlsParser
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL

/**
 * 简化版校验器：
 *  按用户最新要求——「各个阶段的政策，各取一行元数据解释每个字段什么意思」。
 *  每个彩种 × 每个政策阶段，只取 1 期真实样本，
 *  先打印官方原始字段（带含义标签），再打印解析后元数据（逐字段含义说明，中文奖级）。
 *  总输出精简，直接能看懂每个字段。
 */
class SimpleMetaExplainer {
    companion object {
        const val OUT = "/tmp/simple_meta_explainer.txt"
        val CN = listOf("零","一","二","三","四","五","六","七","八","九","十")
        fun cn(n: Int) = if (n in 1..10) CN[n] else n.toString()
        fun num(s: String?): Long? {
            val t = (s ?: "-").trim()
            if (t == "-" || t.isEmpty()) return null
            return t.replace(",", "").substringBefore('.').toLongOrNull()
        }
    }

    private val fw = File(OUT).bufferedWriter()
    private fun P(i: Int, parts: List<String>): String = parts.getOrNull(i) ?: "—"
    private fun out(s: String = "") { println(s); fw.write(s + "\n"); fw.flush() }

    @Test
    fun run() {
        Runtime.getRuntime().addShutdownHook(Thread { fw.close() })
        // 先把所有彩种数据源下载到 /tmp 缓存
        for (c in LotteryType.ALL) {
            val f = "/tmp/${c.url.substringAfterLast('/')}"
            if (!File(f).exists()) runCatching { File(f).writeBytes(URL(c.url).readBytes()) }
        }
        out()
        out("═══ 彩 票 元 数 据 字 段 含 义 说 明（每彩种每政策阶段 1 行样本）═══")
        out()

        for (config in LotteryType.ALL) {
            val fname = config.url.substringAfterLast('/')
            val bytes = File("/tmp/$fname").takeIf { it.exists() }?.readBytes()
                ?: runCatching { URL(config.url).readBytes() }.getOrNull()
            if (bytes == null) { out("⚠️  ${config.displayName} 无数据源，跳过") ; continue }

            val draws = LotteryXlsParser.parse(config, ByteArrayInputStream(bytes)).associateBy { it.issue }
            val rawMap: Map<String, List<String>> = String(bytes, Charsets.UTF_8)
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .associate { l ->
                    val p = l.split(Regex("""\s+"""))
                    (if (Regex("""^[0-9]{5,}$""").matches(p[0])) p[0] else "_") to p
                }
            val sorted = draws.values.sortedWith(
                compareByDescending<LotteryDraw> { it.date }.thenByDescending { it.issue }
            )

            out()
            out("┌──────────────────────────────────────────────────────────────────────────┐")
            out("│ 【彩种】${config.displayName}（code=${config.code}）  源：${config.url}")
            out("└──────────────────────────────────────────────────────────────────────────┘")

            for (rv in config.ruleVersions) {
                val sample = sorted.firstOrNull { it.ruleVersionKey == rv.key }
                if (sample == null) {
                    out()
                    out("  政策：${rv.key} / ${rv.policyLabel}（生效 ${rv.effectiveFromDate}）  → 暂无样本期")
                    continue
                }
                val parts = rawMap[sample.issue] ?: emptyList()
                printOne(config, rv, sample, parts)
            }
        }
        out()
        out("═══════ 报告文件：$OUT ═══════")
    }

    // ============================================================
    //  单个政策单期样本：先打印【官方原始字段+含义】，再打印【元数据逐字段解释】
    // ============================================================
    private fun printOne(
        c: LotteryTypeConfig, rv: LotteryTypeConfig.RuleVersion,
        d: LotteryDraw, parts: List<String>
    ) {
        out()
        out("  ═══════════════════════════════════════════════════════════════════════")
        out("  政策版本 KEY ：${rv.key}")
        out("  政策标签     ：${rv.policyLabel}    生效日期：${rv.effectiveFromDate}")
        out("  期号 / 日期  ：${d.issue} / ${d.date}    解析器 v${d.parserVersion} / 来源 ${d.parseSource}")
        out()
        out("  ┌【一】官 方 原 始 数 据 逐 字 段（期号 ${d.issue}）───────────────────┐")
        when (c.code) {
            "dlt" -> dltRaw(parts)
            "ssq" -> ssqRaw(parts, rv)
            "3d"  -> d3Raw(parts)
            "7lc" -> qlcRaw(parts)
            "p3"  -> p3Raw(parts)
            "p5"  -> p5Raw(parts)
            "7xc" -> qxcRaw(parts)
            "kl8" -> kl8Raw(parts)
        }
        out("  └──────────────────────────────────────────────────────────────────────┘")
        out()
        out("  ┌【二】解 析 后 元 数 据 逐 字 段 含 义 解 释（LotteryDraw 入库对象） ─┐")
        explainMeta(c, rv, d)
        out("  └──────────────────────────────────────────────────────────────────────┘")
        out()
        out("  ┌【三】官 方 vs 元 数 据 · 逐 字 段 对 照（PASS/FAIL）────────────────┐")
        crossCheck(c, rv, d, parts)
        out("  └──────────────────────────────────────────────────────────────────────┘")
    }

    // ========================= 官方原始字段 =========================
    private fun dltRaw(p: List<String>) {
        if (p.isEmpty()) { out("    （无原始行）"); return }
        out("    F01  期号(唯一)                 = ${P(0,p)}")
        out("    F02  开奖真实日期               = ${P(1,p)}")
        out("    F03-F07 前区5个开奖号码         = ${P(2,p)} ${P(3,p)} ${P(4,p)} ${P(5,p)} ${P(6,p)}")
        out("    F08-F09 后区2个开奖号码         = ${P(7,p)} ${P(8,p)}")
        out("    F10  本期全国总销售额（元）     = ${P(9,p)}")
        out("    F11  当期奖池滚存（元）         = ${P(10,p)}")
        out("    ── 基本投注主体7级（7对=14字段，所有版本通用）─────────────────")
        for (k in 0..6) {
            val cI = 11 + k*2; val aI = 12 + k*2
            out("    F${"%02d".format(cI+1)}-${"%02d".format(aI+1)} 基本${cn(k+1)}等 注数/单注金额 = ${P(cI,p)} 注 / ${P(aI,p)} 元")
        }
        out("    ── 基本投注尾部扩展（4字段，按政策版本使用）──────────────────")
        out("    F26-F27 基本八等 注数/金额      = ${P(25,p)} / ${P(26,p)}   （9级/8级版本使用，7级/6级版本忽略）")
        out("    F28-F29 基本九等 注数/金额      = ${P(27,p)} / ${P(28,p)}   （仅9级版本使用，其他版本忽略）")
        out("    ── 追加投注1~4级（4对=8字段，独立存放）──────────────────────")
        for (k in 0..3) {
            val cI = 29 + k*2; val aI = 30 + k*2
            out("    F${"%02d".format(cI+1)}-${"%02d".format(aI+1)} 追加${cn(k+1)}等 注数/金额 = ${P(cI,p)} / ${P(aI,p)}   （= 基本同级别 × 0.8）")
        }
        out("    ── 追加投注五级尾段（官方省略 amt 单字段存 count）───────────")
        out("    F38 追加五等 中奖注数（单字段）= ${P(37,p)}   （官方省略金额，解析按『基本五等奖金 × 80%』自动推算）")
        out("    （追加六等/追加七等：政策有，官方源不单独给字段。解析默认 count=0，金额 = 基本六/七等 × 80%）")
    }

    private fun ssqRaw(p: List<String>, rv: LotteryTypeConfig.RuleVersion) {
        if (p.isEmpty()) { out("    （无原始行）"); return }
        out("    F01  期号(唯一)                 = ${P(0,p)}")
        out("    F02  开奖真实日期               = ${P(1,p)}")
        out("    F03-F08 开奖红球6个（升序）     = ${(2..7).joinToString(" ") { P(it,p) }}")
        out("    F09-F14 红球出球顺序6个         = ${(8..13).joinToString(" ") { P(it,p) }}")
        out("    F15  本期全国总销售额（元）     = ${P(14,p)}")
        out("    F16  奖池滚存（元）             = ${P(15,p)}")
        out("    ── ${rv.prizeTierPairCount}对基本投注奖级（每对=count/amount）─────────")
        for (k in 0 until rv.prizeTierPairCount) {
            val cI = 16 + k*2; val aI = 17 + k*2
            val role = when {
                k < rv.realTiersToUse -> "（展示为：${cn(k+1)}等奖）"
                else -> "（本政策 realTiersToUse=${rv.realTiersToUse}，该段不展示）"
            }
            out("    F${"%02d".format(cI+1)}-${"%02d".format(aI+1)} 奖段${k+1} $role 注数/金额 = ${P(cI,p)} / ${P(aI,p)}")
        }
    }

    private fun d3Raw(p: List<String>) {
        if (p.isEmpty()) { out("    （无原始行）"); return }
        out("    F01  期号                       = ${P(0,p)}")
        out("    F02  开奖日期                   = ${P(1,p)}")
        out("    F03-F05 开奖号码3位             = ${P(2,p)} ${P(3,p)} ${P(4,p)}")
        out("    F06-F08 试机号（仅供参考）      = ${P(5,p)} ${P(6,p)} ${P(7,p)}")
        out("    F09  extra 预留字段             = ${P(8,p)}")
        out("    F10  销售额（元）               = ${P(9,p)}")
        out("    F11-F12 直选奖 count/amount     = ${P(10,p)} / ${P(11,p)}")
        out("    F13-F14 组选3 count/amount      = ${P(12,p)} / ${P(13,p)}")
        out("    F15-F16 组选6 count/amount      = ${P(14,p)} / ${P(15,p)}")
    }

    private fun qlcRaw(p: List<String>) {
        if (p.isEmpty()) { out("    （无原始行）"); return }
        out("    F01  期号                       = ${P(0,p)}")
        out("    F02  开奖日期                   = ${P(1,p)}")
        out("    F03-F09 基本号7个               = ${(2..8).joinToString(" ") { P(it,p) }}")
        out("    F10  特别号（+1）               = ${P(9,p)}")
        out("    F11  销售额（元）               = ${P(10,p)}")
        out("    F12  奖池滚存（元）             = ${P(11,p)}")
        for (k in 0..6) {
            val cI = 12 + k*2; val aI = 13 + k*2
            out("    F${"%02d".format(cI+1)}-${"%02d".format(aI+1)} ${cn(k+1)}等奖 count/amount = ${P(cI,p)} / ${P(aI,p)}")
        }
    }

    private fun p3Raw(p: List<String>) {
        if (p.isEmpty()) { out("    （无原始行）"); return }
        out("    F01  期号                       = ${P(0,p)}")
        out("    F02  开奖日期                   = ${P(1,p)}")
        out("    F03-F05 开奖号码3位             = ${P(2,p)} ${P(3,p)} ${P(4,p)}")
        out("    F06  销售额（元）               = ${P(5,p)}")
        out("    F07-F08 直选奖 count/amount     = ${P(6,p)} / ${P(7,p)}")
        out("    F09-F10 组选3 count/amount      = ${P(8,p)} / ${P(9,p)}")
        out("    F11-F12 组选6 count/amount      = ${P(10,p)} / ${P(11,p)}")
    }

    private fun p5Raw(p: List<String>) {
        if (p.isEmpty()) { out("    （无原始行）"); return }
        out("    F01  期号                       = ${P(0,p)}")
        out("    F02  开奖日期                   = ${P(1,p)}")
        out("    F03-F07 开奖号码5位             = ${P(2,p)} ${P(3,p)} ${P(4,p)} ${P(5,p)} ${P(6,p)}")
        out("    F08  销售额（元）               = ${P(7,p)}")
        out("    F09-F10 一等奖 count/amount     = ${P(8,p)} / ${P(9,p)}   （排列五只有1级固定奖10万）")
    }

    private fun qxcRaw(p: List<String>) {
        if (p.isEmpty()) { out("    （无原始行）"); return }
        out("    F01  期号                       = ${P(0,p)}")
        out("    F02  开奖日期                   = ${P(1,p)}")
        out("    F03-F08 前6位号码               = ${(2..7).joinToString(" ") { P(it,p) }}")
        out("    F09  后1位号码                  = ${P(8,p)}")
        out("    F10  销售额（元）               = ${P(9,p)}")
        out("    F11  奖池滚存（元）             = ${P(10,p)}")
        for (k in 0..5) {
            val cI = 11 + k*2; val aI = 12 + k*2
            out("    F${"%02d".format(cI+1)}-${"%02d".format(aI+1)} ${cn(k+1)}等奖 count/amount = ${P(cI,p)} / ${P(aI,p)}")
        }
    }

    private fun kl8Raw(p: List<String>) {
        if (p.isEmpty()) { out("    （无原始行）"); return }
        out("    F01  期号                       = ${P(0,p)}")
        out("    F02  开奖日期                   = ${P(1,p)}")
        out("    F03-F22 20个开奖号码            = ${(2..21).joinToString(" ") { P(it,p) }}")
        out("    F23  销售额（元）               = ${P(22,p)}")
        out("    F24  奖池滚存（元）             = ${P(23,p)}")
        out("    ── 选十玩法前7对（官方一共输出多子玩法，解析只取选十第1种）")
        for (k in 0..6) {
            val cI = 24 + k*2; val aI = 25 + k*2
            out("    F${"%02d".format(cI+1)}-${"%02d".format(aI+1)} 选十${cn(k+1)}等奖 count/amount = ${P(cI,p)} / ${P(aI,p)}")
        }
    }

    // ========================= 元数据含义解释 =========================
    private fun explainMeta(c: LotteryTypeConfig, rv: LotteryTypeConfig.RuleVersion, d: LotteryDraw) {
        val baseNames = rv.rules.map { it.prizeName }.distinct()
        out("    【字段：issue】                 → 字符串，唯一期号（如 ${d.issue}），主键用")
        out("    【字段：date】                  → 字符串 YYYY-MM-DD，真实开奖日期（如 ${d.date}），用于匹配政策版本")
        out("    【字段：ruleVersionKey】        → 字符串，解析时按 date 落定的政策版本 key（如 ${d.ruleVersionKey}），展示用，绝不二次猜")
        out("    【字段：parserVersion】         → Int，解析器版本（当前=${d.parserVersion}），用于强制覆盖脏数据")
        out("    【字段：parseSource】           → 枚举，解析来源 SEED/NET/MIGRATE（当前=${d.parseSource}）")
        out("    【字段：tierMatchStatus】       → 枚举，结构审计 MATCH/FEWER/MORE/MISMATCH（当前=${d.tierMatchStatus}）")
        out("    【字段：actualTierCount】       → Int，实际解析到的奖级对数=${d.actualTierCount}，对比配置 realTiersToUse=${rv.realTiersToUse}")
        out("    【字段：primaryNumbers】        → List<Int>，主号码（${c.primaryLabel}）升序 → ${d.primaryNumbers.joinToString(" ") { "%02d".format(it) }}")
        if (c.hasSecondary) {
            out("    【字段：secondaryNumbers】      → List<Int>，副号码（${c.secondaryLabel}）升序 → ${d.secondaryNumbers.joinToString(" ") { "%02d".format(it) }}")
        }
        out("    【字段：salesAmount】           → Long?，全国总销售额（元）→ ${fmt(d.salesAmount)}")
        out("    【字段：jackpotAmount】         → Long?，奖池滚存（元）→ ${fmt(d.jackpotAmount)}")
        out("    【字段：firstPrizeCount/Amount】→ Long?，向后兼容的一等奖快捷字段 → count=${d.firstPrizeCount} / amt=${fmt(d.firstPrizeAmount)}")
        out("    【字段：secondPrizeCount/Amt】  → Long?，向后兼容的二等奖快捷字段 → count=${d.secondPrizeCount} / amt=${fmt(d.secondPrizeAmount)}")
        out()
        out("    ── 【字段：allPrizeTiers】List<PrizeTierEntry>，基本投注奖级（真实期对应政策）──────────")
        d.allPrizeTiers.withIndex().take(rv.realTiersToUse).forEach { (i, t) ->
            val name = baseNames.getOrNull(i) ?: "段${i+1}"
            val fixed = rv.rules.firstOrNull { it.prizeName == name }?.fixedAmountYuan
            val tag = fixed?.let { "政策固定 $it 元" } ?: "政策浮动"
            val warn = if (t != null && fixed != null && t.amount in 1..Long.MAX_VALUE && t.amount != fixed) {
                "   ⚠️  实际奖金${t.amount}≠政策固定$fixed（可能奖池浮动触发）"
            } else ""
            out("      下标[$i] ${cn(i+1)}等奖 [$name]  → count=${t?.count ?: "-"}注，单注 amt=${fmt(t?.amount)}元   【$tag】$warn")
        }
        if (d.appendPrizeTiers.isNotEmpty()) {
            out()
            out("    ── 【字段：appendPrizeTiers】List<PrizeTierEntry>，追加投注奖级（大乐透等专用） ──────")
            d.appendPrizeTiers.withIndex().take(rv.appendTierPairCount).forEach { (i, t) ->
                val base = d.allPrizeTiers.getOrNull(i)?.amount
                val exp = base?.let { (it * 0.8).toLong() }
                val check = if (t != null && base != null && exp != null && t.amount > 0L) {
                    if (t.amount == exp) "   ✔ 基本${cn(i+1)}等×0.8公式验证" else "   ⚠️  公式不符（基本${cn(i+1)}等$base × 0.8 = $exp ≠ ${t.amount}）"
                } else ""
                val empty = if (t?.count == 0L && t?.amount == 0L) "   （本期空开，count=0 官方无专门字段）" else ""
                out("      下标[$i] 追加${cn(i+1)}等  → count=${t?.count ?: 0}注，单注 amt=${fmt(t?.amount)}元$check$empty")
            }
        }
        if (d.conditionalFlags.isNotEmpty()) {
            out()
            out("    ── 【字段：conditionalFlags】Map<String,String>，条件性奖级开关 ──")
            d.conditionalFlags.forEach { (k, v) ->
                val meaning = when (k) {
                    "SSQ_FUYUN" -> "SSQ福运奖(中3红=5元)：OFF=奖池<3亿停发，ON=≥15亿开启，HOLD=≥3亿且<15亿保持状态"
                    "DLT_2026_FLOAT" -> "DLT2026新规三～七等奖金额：NORMAL=奖池<8亿(三5000四300五150六15七5)，UP=≥8亿上浮(三6666四380五200六18七7)"
                    else -> ""
                }
                out("      $k = $v    $meaning")
            }
        }
    }

    // ========================= 对照核查 =========================
    private fun crossCheck(c: LotteryTypeConfig, rv: LotteryTypeConfig.RuleVersion, d: LotteryDraw, parts: List<String>) {
        val ok = mutableListOf<String>(); val fail = mutableListOf<String>()
        fun chk(desc: String, parsed: Long?, rawIdx: Int) {
            val rv0 = num(parts.getOrNull(rawIdx))
            val m = "[$desc] 解析=$parsed   官方F${rawIdx+1}=${P(rawIdx,parts)}(Long=$rv0)"
            if (parsed == rv0) ok.add("✔ $m")
            else if ((parsed == null) && (P(rawIdx, parts) == "—")) ok.add("✔ $m (双方缺失)")
            else fail.add("❌ $m")
        }
        fun chkT(desc: String, t: PrizeTierEntry?, cI: Int, aI: Int) {
            val rc = num(P(cI, parts)); val ra = num(P(aI, parts))
            val mc = t?.count; val ma = t?.amount
            val cOk = (mc == rc) || (mc == null && P(cI, parts) == "—")
            val aOk = (ma == ra) || (ma == null && P(aI, parts) == "—")
            val m = "[$desc] count: 解析=$mc vs 官方=$rc   /   amount: 解析=$ma vs 官方=$ra"
            if (cOk && aOk) ok.add("✔ $m") else fail.add("❌ $m")
        }
        when (c.code) {
            "dlt" -> {
                chk("销售额", d.salesAmount, 9); chk("奖池", d.jackpotAmount, 10)
                for (k in 0..6) chkT("基本${cn(k+1)}等", d.allPrizeTiers.getOrNull(k), 11+k*2, 12+k*2)
                if (rv.realTiersToUse >= 8) chkT("基本八等", d.allPrizeTiers.getOrNull(7), 25, 26)
                if (rv.realTiersToUse >= 9) chkT("基本九等", d.allPrizeTiers.getOrNull(8), 27, 28)
                for (k in 0..3) chkT("追加${cn(k+1)}等", d.appendPrizeTiers.getOrNull(k), 29+k*2, 30+k*2)
                val ap5 = d.appendPrizeTiers.getOrNull(4); val pc = num(P(37, parts))
                val b5 = d.allPrizeTiers.getOrNull(4)?.amount; val e5 = b5?.let { (it*0.8).toLong() }
                val m5 = "[追加五等] count解析=${ap5?.count} vs 官方F38=$pc   amount解析=${ap5?.amount} vs 基本五等×0.8期望=$e5"
                if (ap5?.count == pc && ap5?.amount == e5) ok.add("✔ $m5") else fail.add("❌ $m5")
                for (k in 5..6) if (rv.appendTierPairCount > k) {
                    val ap = d.appendPrizeTiers.getOrNull(k); val bk = d.allPrizeTiers.getOrNull(k)?.amount
                    val ek = bk?.let { (it*0.8).toLong() }
                    val m = "[追加${cn(k+1)}等] count解析=${ap?.count}（预期=0） amount解析=${ap?.amount} vs 基本${cn(k+1)}等×0.8期望=$ek"
                    if (ap?.count == 0L && ap?.amount == ek) ok.add("✔ $m") else fail.add("❌ $m")
                }
            }
            "ssq" -> {
                chk("销售额", d.salesAmount, 14); chk("奖池", d.jackpotAmount, 15)
                for (k in 0 until rv.prizeTierPairCount) chkT("基本${cn(k+1)}等", d.allPrizeTiers.getOrNull(k), 16+k*2, 17+k*2)
            }
            "3d"  -> { chk("销售额", d.salesAmount, 9); for(k in 0..2) chkT(listOf("直选","组3","组6")[k], d.allPrizeTiers.getOrNull(k),10+k*2,11+k*2) }
            "7lc" -> { chk("销售额", d.salesAmount, 10); chk("奖池", d.jackpotAmount, 11); for(k in 0..6) chkT("基本${cn(k+1)}等", d.allPrizeTiers.getOrNull(k),12+k*2,13+k*2) }
            "p3"  -> { chk("销售额", d.salesAmount, 5); for(k in 0..2) chkT(listOf("直选","组3","组6")[k], d.allPrizeTiers.getOrNull(k),6+k*2,7+k*2) }
            "p5"  -> { chk("销售额", d.salesAmount, 7); chkT("一等奖", d.allPrizeTiers.getOrNull(0), 8, 9) }
            "7xc" -> { chk("销售额", d.salesAmount, 9); chk("奖池", d.jackpotAmount, 10); for(k in 0..5) chkT("基本${cn(k+1)}等", d.allPrizeTiers.getOrNull(k),11+k*2,12+k*2) }
            "kl8" -> { chk("销售额", d.salesAmount, 22); chk("奖池", d.jackpotAmount, 23); for(k in 0..6) chkT("选十${cn(k+1)}等", d.allPrizeTiers.getOrNull(k),24+k*2,25+k*2) }
        }
        ok.forEach { out("    $it") }
        if (fail.isNotEmpty()) {
            fail.forEach { out("    $it") }
            out("    ─────── FAIL=${fail.size} / PASS=${ok.size} ───────")
        } else out("    ✅ 全部 ${ok.size} 项字段对照一致")
    }

    private fun fmt(n: Long?): String = when {
        n == null -> "—"
        n >= 100_000_000L -> String.format("%.2f亿", n/1e8)
        n >= 10_000L      -> String.format("%.2f万", n/1e4)
        else              -> String.format("%,d", n)
    }
}
