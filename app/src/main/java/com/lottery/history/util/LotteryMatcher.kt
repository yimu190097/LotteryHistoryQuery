package com.lottery.history.util

import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.model.QueryResultItem

/**
 * 通用匹配引擎：根据**每期开奖当时适用的规则版本**，统计用户选号在历史开奖中的命中情况。
 *
 * v11 改造（P0 修复）：之前固定用 `config.rules`（最新版规则）匹配全部历史期，
 * 导致跨越规则版本的奖级被归类到错误的奖项名（如 DLT 2019 九级规则的"0+2=八等奖 5 元"
 * 被新版 7 级规则归类成"0+2=七等奖 5 元"）。现在每期 draw 独立使用自己的规则版本，
 * 结果按「ruleVersionKey + prizeName」合并，避免跨阶段奖级错位。
 */
object LotteryMatcher {

    /**
     * 命中结果聚合桶 key：以【规则版本 key + 命中条件 + 奖项名】三元组为准合并。
     * 同一个奖级名在不同规则版本中（如 DLT 2019 七等奖 vs DLT 2026 七等奖）命中条件
     * 已不同（或者同名不同义），分开统计更准确，也能让 UI 层按 policyLabel 分组展示。
     * 如果用户希望在 UI 上合并展示同名奖级，展示层可以再把这些 bucket 做二次合并。
     */
    private data class BucketKey(
        val ruleVersionKey: String,
        val matchPrimary: Int,
        val matchSecondary: Int,
        val prizeName: String
    )

    fun match(
        config: LotteryTypeConfig,
        selectedPrimary: Set<Int>,
        selectedSecondary: Set<Int>,
        history: List<LotteryDraw>
    ): List<QueryResultItem> {
        // 1) 每期用自己的 ruleVersion 算命中，写入聚合桶
        val buckets = linkedMapOf<BucketKey, MutableList<LotteryDraw>>()
        for (draw in history) {
            val ruleVersion = draw.resolveRuleVersion(config)
            for (rule in ruleVersion.rules) {
                val primaryCount = draw.primaryNumbers.count { it in selectedPrimary }
                val secondaryCount = if (config.hasSecondary) {
                    draw.secondaryNumbers.count { it in selectedSecondary }
                } else {
                    0
                }
                if (primaryCount == rule.matchPrimary && secondaryCount == rule.matchSecondary) {
                    val key = BucketKey(
                        ruleVersionKey = ruleVersion.key,
                        matchPrimary = rule.matchPrimary,
                        matchSecondary = rule.matchSecondary,
                        prizeName = rule.prizeName
                    )
                    buckets.getOrPut(key) { mutableListOf() }.add(draw)
                    // 每期最多命中一条规则；为兼容历史行为（一等奖只算一等奖，不再下探到二等奖）
                    // 命中后直接 break 本 draw 的 rules 循环
                    break
                }
            }
        }

        // 2) 按全局 config.rules 顺序输出（旧版规则的奖项名如果在最新规则中不存在，
        //    会追加到尾部避免丢失）
        val inOrder = mutableListOf<QueryResultItem>()
        val visited = linkedSetOf<BucketKey>()

        // 2a) 先按最新规则的顺序，找对应 bucket（matchPrimary+matchSecondary+prizeName 完全对得上的）
        for (rule in config.rules) {
            val latestVersionKey = config.ruleVersions.lastOrNull()?.key ?: ""
            val key = BucketKey(latestVersionKey, rule.matchPrimary, rule.matchSecondary, rule.prizeName)
            val directMatches = buckets[key] ?: emptyList()

            // 2b) 合并旧规则版本中同"命中条件+奖项名"的其他版本的命中
            val extraFromOlder = buckets.entries
                .filter { (k, _) ->
                    k.matchPrimary == rule.matchPrimary &&
                        k.matchSecondary == rule.matchSecondary &&
                        k.prizeName == rule.prizeName &&
                        k.ruleVersionKey != latestVersionKey
                }
                .flatMap { (_, v) -> v }

            val allDraws = directMatches + extraFromOlder
            visited.add(key)
            inOrder.add(
                QueryResultItem(
                    matchPrimary = rule.matchPrimary,
                    matchSecondary = rule.matchSecondary,
                    prizeName = rule.prizeName,
                    count = allDraws.size,
                    matches = allDraws
                )
            )
        }

        // 2c) 旧规则版本中剩余、在最新规则里已不存在的奖级（如 DLT 2019 八等奖/九等奖），
        //     追加到尾部，带 ruleVersionKey 标识以保证用户可区分；这些奖级在最新版里
        //     已不存在（官方规则变更），**必须展示**，避免老数据命中被丢弃。
        val remaining = buckets.keys - visited
        for (key in remaining) {
            val draws = buckets[key] ?: continue
            val policyLabel = config.ruleVersions.firstOrNull { it.key == key.ruleVersionKey }?.policyLabel
            val displayName = if (policyLabel != null) "${key.prizeName}（${policyLabel}）" else key.prizeName
            inOrder.add(
                QueryResultItem(
                    matchPrimary = key.matchPrimary,
                    matchSecondary = key.matchSecondary,
                    prizeName = displayName,
                    count = draws.size,
                    matches = draws
                )
            )
        }

        return inOrder
    }

    fun formatNumbers(list: List<Int>, pad: Boolean = true): String {
        return list.sorted().joinToString(" ") {
            if (pad) String.format("%02d", it) else it.toString()
        }
    }
}
