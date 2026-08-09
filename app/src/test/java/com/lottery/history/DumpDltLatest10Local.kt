package com.lottery.history

import com.lottery.history.model.LotteryType
import com.lottery.history.network.LotteryXlsParser
import org.junit.Test
import java.io.File

class DumpDltLatest10Local {
    @Test
    fun dumpLatest10() {
        val dlt = LotteryType.ALL.first { it.code == "dlt" }
        val file = File("/tmp/dlt2_desc.txt")
        val latest = file.inputStream().use { LotteryXlsParser.parse(dlt, it) }.take(10)
        val ruleVersionMap = dlt.ruleVersions.associateBy { it.key }
        latest.forEach { d ->
            val rv = ruleVersionMap[d.ruleVersionKey]
            println("==========================================")
            println("期号: " + d.issue + "  日期: " + d.date + "  规则版本key: " + d.ruleVersionKey)
            println("  policyLabel: " + rv?.policyLabel)
            println("  prizeTierPairCount=" + rv?.prizeTierPairCount + " appendTierPairCount=" + rv?.appendTierPairCount)
            println("  基本投注 allPrizeTiers (" + d.allPrizeTiers.size + "): " +
                d.allPrizeTiers.withIndex().joinToString { (i, t) ->
                    val idx = i + 1
                    if (t == null) "${idx}等=-null" else "${idx}等=count-${t.count}/amt-${t.amount}"
                })
            println("  追加投注 appendPrizeTiers (" + d.appendPrizeTiers.size + "): " +
                d.appendPrizeTiers.withIndex().joinToString { (i, t) ->
                    val idx = i + 1
                    if (t == null) "${idx}等=-null" else "${idx}等=count-${t.count}/amt-${t.amount}"
                })
            println("  firstPrize: count=" + d.firstPrizeCount + "/amt=" + d.firstPrizeAmount +
                "  secondPrize: count=" + d.secondPrizeCount + "/amt=" + d.secondPrizeAmount)
            println("  sales=" + d.salesAmount + "  jackpot=" + d.jackpotAmount)
            println("  parserVersion=" + d.parserVersion + " parseSource=" + d.parseSource)
            println("  conditionalFlags=" + d.conditionalFlags)
        }
    }
}
