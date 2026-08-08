package com.lottery.history.util

import com.lottery.history.model.ConditionalKey
import com.lottery.history.model.ConditionalValue
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
 *
 * v11.1 修复：
 *  - 修复多版本彩种重复计数（visited 漏标 + latestVersionKey 取错端）
 *  - 尊重 conditionalFlags：福运奖 OFF 时 3+0 不计为中奖
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
        // ===== 额外桶：元数据缺失（resolveRuleVersion 返回 null）的期数统一归类 =====
        //   这些期无法确定是哪一版规则，但用户选号命中了也必须展示（显式标注版本不明），
        //   绝不"悄悄扔掉"导致用户看到的命中总期数 < 实际。
        val unknownVersionBuckets = linkedMapOf<BucketKey, MutableList<LotteryDraw>>()

        for (draw in history) {
            val ruleVersion = draw.resolveRuleVersion(config)
            // 读取该期条件奖级标志，用于跳过停发奖项
            val flags = draw.conditionalFlags

            // ===== 严格模式：ruleVersion 为 null 时，独立写未知版本桶，绝不跳过 =====
            if (ruleVersion == null) {
                // 用 config.ruleVersions.last()（最旧版）的规则做"保底匹配"仅为让用户
                // 看到有命中，但 key 用 "__UNKNOWN__" 标识，UI 上显式标"版本不明"。
                // 不跳过、不丢弃，保证数据完整性。
                val fallbackRules = config.ruleVersions.last().rules
                for (rule in fallbackRules) {
                    val condKey = rule.conditionalKey
                    if (condKey != null && flags[condKey] == ConditionalValue.OFF) continue
                    val primaryCount = draw.primaryNumbers.count { it in selectedPrimary }
                    val secondaryCount = if (config.hasSecondary) {
                        draw.secondaryNumbers.count { it in selectedSecondary }
                    } else 0
                    if (primaryCount == rule.matchPrimary && secondaryCount == rule.matchSecondary) {
                        val key = BucketKey(
                            ruleVersionKey = "__UNKNOWN_VERSION__",
                            matchPrimary = rule.matchPrimary,
                            matchSecondary = rule.matchSecondary,
                            prizeName = rule.prizeName
                        )
                        unknownVersionBuckets.getOrPut(key) { mutableListOf() }.add(draw)
                        break
                    }
                }
                continue
            }

            for (rule in ruleVersion.rules) {
                // v11.1: 条件奖级 OFF 时跳过该规则（如福运奖停发 → 3+0 不计中奖）
                val condKey = rule.conditionalKey
                if (condKey != null && flags[condKey] == ConditionalValue.OFF) continue

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

        // 2) 按最新政策 config.rules 顺序输出，跨版本同名同条件奖项合并为一行。
        //    查询结果列表只用最新政策奖项名简单展示；点"查看历史"时 HistoryDialog
        //    会按每期真实 ruleVersionKey 分组展示真实情况（政策徽章+生效日+说明）。
        val inOrder = mutableListOf<QueryResultItem>()
        val visited = mutableSetOf<BucketKey>()

        // 2a) 按最新规则顺序逐条匹配，合并所有版本的同(matchP, matchS, prizeName) bucket 为一行
        for (rule in config.rules) {
            val matchingEntries = buckets.entries.filter { (k, _) ->
                k.matchPrimary == rule.matchPrimary &&
                    k.matchSecondary == rule.matchSecondary &&
                    k.prizeName == rule.prizeName
            }
            if (matchingEntries.isEmpty()) continue

            matchingEntries.forEach { (k, _) -> visited.add(k) }

            // 合并跨版本的所有 draws，count 取总和
            val allDraws = matchingEntries.flatMap { it.value }
            inOrder.add(
                QueryResultItem(
                    matchPrimary = rule.matchPrimary,
                    matchSecondary = rule.matchSecondary,
                    prizeName = rule.prizeName,  // 只用最新政策奖项名，不加 policyLabel 前缀
                    count = allDraws.size.toLong(),
                    matches = allDraws,
                    sourceRuleVersionKey = rule.let { r ->
                        // 标记为最新版 key，便于 UI 排序/标识
                        config.ruleVersions.firstOrNull { rv ->
                            rv.rules.any { it.matchPrimary == r.matchPrimary && it.matchSecondary == r.matchSecondary && it.prizeName == r.prizeName }
                        }?.key
                    }
                )
            )
        }

        // 2b) 旧规则版本中剩余、在最新规则里已不存在的奖级（如 DLT 2019 八等奖/九等奖），
        //     追加到尾部。奖项名不加 policyLabel 前缀（保持列表简洁），点查看历史时
        //     HistoryDialog 会展示这些期对应的真实政策版本。
        val remaining = buckets.keys - visited
        for (key in remaining) {
            val draws = buckets[key] ?: continue
            inOrder.add(
                QueryResultItem(
                    matchPrimary = key.matchPrimary,
                    matchSecondary = key.matchSecondary,
                    prizeName = key.prizeName,
                    count = draws.size.toLong(),
                    matches = draws,
                    sourceRuleVersionKey = key.ruleVersionKey
                )
            )
        }

        // 2c) 追加：元数据缺失（规则版本不明）的命中 → 统一放尾部、显式标注
        for ((bk, draws) in unknownVersionBuckets) {
            inOrder.add(
                QueryResultItem(
                    matchPrimary = bk.matchPrimary,
                    matchSecondary = bk.matchSecondary,
                    prizeName = "【元数据缺失·版本不明】${bk.prizeName}",
                    count = draws.size.toLong(),
                    matches = draws,
                    sourceRuleVersionKey = null
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
