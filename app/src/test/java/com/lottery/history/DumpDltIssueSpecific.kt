package com.lottery.history

import com.lottery.history.model.LotteryType
import com.lottery.history.network.LotteryXlsParser
import org.junit.Test
import java.io.File

class DumpDltIssueSpecific {
    @Test
    fun dump26012() {
        val dlt = LotteryType.ALL.first { it.code == "dlt" }
        val file = File("/tmp/dlt2_desc.txt")
        val issue = "26012"
        val d = file.inputStream().use { LotteryXlsParser.parse(dlt, it) }
            .firstOrNull { x -> x.issue == issue }
        if (d == null) {
            println("期号$issue 没找到")
            return
        }
        val ruleVersionMap = dlt.ruleVersions.associateBy { it.key }
        val rv = ruleVersionMap[d.ruleVersionKey]
        println("======= 期号 $issue 旧BUG期验证（之前追加1等错成82万注/15元）=======")
        println("日期: " + d.date + " 规则版本key: " + d.ruleVersionKey)
        println("policyLabel: " + rv?.policyLabel)
        println("基本投注 allPrizeTiers (" + d.allPrizeTiers.size + "): " +
            d.allPrizeTiers.withIndex().joinToString { (i, t) ->
                val idx = i + 1
                if (t == null) "${idx}等=-null" else "${idx}等=${t.count}注/${t.amount}元"
            })
        println("追加投注 appendPrizeTiers (" + d.appendPrizeTiers.size + "): " +
            d.appendPrizeTiers.withIndex().joinToString { (i, t) ->
                val idx = i + 1
                if (t == null) "${idx}等=-null" else "${idx}等=${t.count}注/${t.amount}元"
            })
        println("firstPrize(legacy)=" + d.firstPrizeCount + "/" + d.firstPrizeAmount +
            "  secondPrize(legacy)=" + d.secondPrizeCount + "/" + d.secondPrizeAmount)
    }
}
