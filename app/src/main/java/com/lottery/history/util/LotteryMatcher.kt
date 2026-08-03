package com.lottery.history.util

import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.model.QueryResultItem

/**
 * 通用匹配引擎：根据彩种配置的规则，统计用户选号在历史开奖中的命中情况。
 */
object LotteryMatcher {

    fun match(
        config: LotteryTypeConfig,
        selectedPrimary: Set<Int>,
        selectedSecondary: Set<Int>,
        history: List<LotteryDraw>
    ): List<QueryResultItem> {
        return config.rules.map { rule ->
            val matched = history.filter { draw ->
                val primaryCount = draw.primaryNumbers.count { it in selectedPrimary }
                val secondaryCount = if (config.hasSecondary) {
                    draw.secondaryNumbers.count { it in selectedSecondary }
                } else {
                    0
                }
                primaryCount == rule.matchPrimary && secondaryCount == rule.matchSecondary
            }
            QueryResultItem(
                matchPrimary = rule.matchPrimary,
                matchSecondary = rule.matchSecondary,
                prizeName = rule.prizeName,
                count = matched.size,
                matches = matched
            )
        }
    }

    fun formatNumbers(list: List<Int>, pad: Boolean = true): String {
        return list.sorted().joinToString(" ") {
            if (pad) String.format("%02d", it) else it.toString()
        }
    }
}
