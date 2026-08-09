package com.lottery.history
import com.lottery.history.model.*
import com.lottery.history.network.*
import org.junit.Test
import java.io.File
import java.net.URL
class DumpDltLatestMeta {
    @Test fun dump() {
        val f = File("/tmp/dlt2_desc.txt").takeIf{ it.exists() }
            ?: run { URL("http://data.17500.cn/dlt2_desc.txt").openStream().use { s ->
                val b = s.readBytes(); File("/tmp/dlt2_desc.txt").writeBytes(b) }
                File("/tmp/dlt2_desc.txt") }
        val dlt = LotteryType.ALL.first{ it.code=="dlt" }
        val d = f.inputStream().use{ LotteryXlsParser.parse(dlt, it) }.first()
        val rv = dlt.ruleVersions.first{ it.key == d.ruleVersionKey }
        val CN = listOf("零","一","二","三","四","五","六","七","八","九","十")
        fun cn(n:Int)=CN[n]
        fun fmt(n:Long?):String = when{ n==null -> "—"
            n>=100_000_000L -> "%.2f亿".format(n/1e8)
            n>=10_000L -> "%.2f万".format(n/1e4)
            else -> "%,d".format(n) }
        println("========= 大乐透最新一期【${d.issue} / ${d.date}】元数据逐字段解释 =========")
        println("政策版本：${rv.key}  ${rv.policyLabel}  生效日期：${rv.effectiveFromDate}")
        println()
        println("【字段 1】issue              = ${d.issue}            → 【含义】期号（唯一主键，与官方源F01完全一致）")
        println("【字段 2】date               = ${d.date}       → 【含义】官方真实开奖日期，用它锁定政策版本，绝不用期号猜")
        println("【字段 3】ruleVersionKey     = ${d.ruleVersionKey}  → 【含义】解析时已锁定死的当期政策版本KEY，展示时直接查表，不再重算")
        println("【字段 4】parserVersion      = v${d.parserVersion}          → 【含义】解析器版本号（v5=字段布局终极修版，旧脏数据强制覆盖）")
        println("【字段 5】parseSource        = ${d.parseSource}          → 【含义】解析来源（NET=官方线上真实拉取 ✓ / SEED=内置种子）")
        println("【字段 6】tierMatchStatus    = ${d.tierMatchStatus}        → 【含义】结构审计：MATCH(✓)/FEWER(官方少字段)/MORE(异常)/MISMATCH(错版)")
        println("【字段 7】actualTierCount    = ${d.actualTierCount}          → 【含义】实际解析到 ${d.actualTierCount} 个奖级对（本政策 realTiersToUse=${rv.realTiersToUse}）")
        println("【字段 8】primaryNumbers     = [${d.primaryNumbers.joinToString(" ") { "%02d".format(it) }}]  → 【含义】前区5个开奖号码（升序去重展示）")
        println("【字段 9】secondaryNumbers   = [${d.secondaryNumbers.joinToString(" ") { "%02d".format(it) }}]   → 【含义】后区2个开奖号码（升序去重展示）")
        println("【字段10】salesAmount        = ${d.salesAmount} (${fmt(d.salesAmount)}) → 【含义】本期全国总销售额（元），来自官方F10")
        println("【字段11】jackpotAmount      = ${d.jackpotAmount} (${fmt(d.jackpotAmount)}) → 【含义】奖池滚存金额（元），来自官方F11，用于判断浮动奖是否上浮/福运奖开停")
        println("【字段12】firstPrizeCount    = ${d.firstPrizeCount}           → 【含义】向后兼容字段：一等奖中奖注数（等于 allPrizeTiers[0].count）")
        println("【字段13】firstPrizeAmount   = ${d.firstPrizeAmount} (${fmt(d.firstPrizeAmount)}) → 【含义】向后兼容字段：一等奖单注金额（等于 allPrizeTiers[0].amount）")
        println("【字段14】secondPrizeCount   = ${d.secondPrizeCount}         → 【含义】向后兼容字段：二等奖注数（等于 allPrizeTiers[1].count）")
        println("【字段15】secondPrizeAmount  = ${d.secondPrizeAmount} (${fmt(d.secondPrizeAmount)})  → 【含义】向后兼容字段：二等奖单注金额（等于 allPrizeTiers[1].amount）")
        println()
        println("【字段16】allPrizeTiers 基本投注（${rv.realTiersToUse}级，对应中文一～${cn(rv.realTiersToUse)}等奖）：")
        val names = rv.rules.map{ it.prizeName }.distinct()
        d.allPrizeTiers.withIndex().take(rv.realTiersToUse).forEach{(i, t) ->
            val nm = names.getOrNull(i) ?: ""
            val fixed = rv.rules.firstOrNull{ it.prizeName == nm }?.fixedAmountYuan
            val tag = fixed?.let { "政策固定 ${it} 元" } ?: "政策浮动"
            println("   下标[$i] ${cn(i+1)}等奖 [$nm]  count=${t?.count ?: "-"}注   amount=${fmt(t?.amount)}元   【$tag】")
        }
        println()
        println("【字段17】appendPrizeTiers 追加投注（${rv.appendTierPairCount}级，仅大乐透有，其他彩种=空）：")
        println("   【DLT追加公式】追加k等奖金 = 基本k等 × 0.8（2019起官方通用追加规则）")
        d.appendPrizeTiers.withIndex().take(rv.appendTierPairCount).forEach{(i, t) ->
            val base = d.allPrizeTiers.getOrNull(i)?.amount
            val expect = base?.let { (it * 0.8).toLong() }
            val chk = if (t!=null && base!=null && expect!=null && t.amount>0) {
                if (t.amount == expect) "✔ 基本${cn(i+1)}等$base × 0.8 = $expect 验证通过"
                else "⚠️ 公式不符（期望$expect ≠ 实际${t.amount}）"
            } else ""
            val empty = if (t?.count==0L && t?.amount==0L) "  【本期空开：官方源无独立count字段，解析默认count=0】" else ""
            println("   下标[$i] 追加${cn(i+1)}等  count=${t?.count ?: 0}注   amount=${fmt(t?.amount)}元   $chk$empty")
        }
        println()
        println("【字段18】conditionalFlags 条件性奖级开关（${d.conditionalFlags.size}项）：")
        if (d.conditionalFlags.isEmpty()) println("   （本政策/本期无条件奖级旗标）")
        d.conditionalFlags.forEach{(k,v)->
            val meaning = when(k){
                "DLT_2026_FLOAT" -> "DLT2026新规三～七等奖浮动：NORMAL=奖池<8亿(三5000四300五150六15七5) / UP=≥8亿上浮(三6666四380五200六18七7)"
                "SSQ_FUYUN"     -> "SSQ福运奖中3红=5元：OFF<3亿停 / ON≥15亿开 / HOLD在中间区间保持"
                else -> ""
            }
            println("   $k = $v    → 含义：$meaning")
        }
    }
}
