package com.lottery.history.util

import com.lottery.history.model.ConditionalKey
import com.lottery.history.model.ConditionalValue
import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.model.MatchMode
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
 *
 * v13 FC3D/P3 组选：selectedPrimary 改 List<Int> 保留位置；matchMode 区分直选/组3/组6。
 *   - 直选：逐位比较 selected[i] == drawNumbers[i]（3 位全同位置才中）
 *   - 组选3：多重集相等（号码全中不限位置）且用户号码为 2 同 1 异结构
 *   - 组选6：多重集相等且用户号码 3 个数字全不同
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

    /**
     * 通用匹配入口。
     *
     * @param selectedPrimary 用户选号（前区）。普通彩种用 set.toList() 即可；FC3D/P3
     *   必须按位置传入 List（index 0=百位, 1=十位, 2=个位）。
     * @param selectedSecondary 后区选号
     * @param matchMode 匹配模式，FC3D/P3 需指定，其他彩种忽略
     */
    fun match(
        config: LotteryTypeConfig,
        selectedPrimary: List<Int>,
        selectedSecondary: Set<Int>,
        history: List<LotteryDraw>,
        matchMode: MatchMode = MatchMode.DIRECT
    ): List<QueryResultItem> {
        val isPositional = config.code == "3d" || config.code == "p3"
        val selectedPrimarySet = selectedPrimary.toSet()

        // 1) 每期用自己的 ruleVersion 算命中，写入聚合桶
        val buckets = linkedMapOf<BucketKey, MutableList<LotteryDraw>>()
        // ===== 额外桶：元数据缺失（resolveRuleVersion 返回 null）的期数统一归类 =====
        val unknownVersionBuckets = linkedMapOf<BucketKey, MutableList<LotteryDraw>>()

        for (draw in history) {
            val ruleVersion = draw.resolveRuleVersion(config)
            val flags = draw.conditionalFlags

            // ===== 严格模式：ruleVersion 为 null 时，独立写未知版本桶 =====
            if (ruleVersion == null) {
                val fallbackRules = config.ruleVersions.last().rules
                for (rule in fallbackRules) {
                    val condKey = rule.conditionalKey
                    if (condKey != null && flags[condKey] == ConditionalValue.OFF) continue
                    val (primaryCount, secondaryCount, matched) = countHitAndCheck(
                        config, isPositional, matchMode, rule.matchPrimary, rule.matchSecondary,
                        selectedPrimary, selectedPrimarySet, selectedSecondary, draw
                    )
                    if (matched) {
                        val key = BucketKey(
                            ruleVersionKey = "__UNKNOWN_VERSION__",
                            matchPrimary = primaryCount,
                            matchSecondary = secondaryCount,
                            prizeName = rule.prizeName
                        )
                        unknownVersionBuckets.getOrPut(key) { mutableListOf() }.add(draw)
                        break
                    }
                }
                continue
            }

            for (rule in ruleVersion.rules) {
                val condKey = rule.conditionalKey
                if (condKey != null && flags[condKey] == ConditionalValue.OFF) continue

                val (primaryCount, secondaryCount, matched) = countHitAndCheck(
                    config, isPositional, matchMode, rule.matchPrimary, rule.matchSecondary,
                    selectedPrimary, selectedPrimarySet, selectedSecondary, draw
                )
                if (matched) {
                    val key = BucketKey(
                        ruleVersionKey = ruleVersion.key,
                        matchPrimary = primaryCount,
                        matchSecondary = secondaryCount,
                        prizeName = rule.prizeName
                    )
                    buckets.getOrPut(key) { mutableListOf() }.add(draw)
                    // 每期最多命中一条规则；命中后直接 break
                    break
                }
            }
        }

        // 2) 查询结果列表：按最新政策 config.rules 的【奖项名】合并展示。
        val inOrder = mutableListOf<QueryResultItem>()
        val consumed = mutableSetOf<BucketKey>()
        val latestRuleByKey = config.rules.associate { r ->
            (r.matchPrimary to r.matchSecondary) to r.prizeName
        }
        val uniquePrizeNames = linkedSetOf<String>()
        val nameToConditions = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
        for (rule in config.rules) {
            uniquePrizeNames.add(rule.prizeName)
            nameToConditions.getOrPut(rule.prizeName) { mutableListOf() }
                .add(rule.matchPrimary to rule.matchSecondary)
        }

        for (prizeName in uniquePrizeNames) {
            val conditions = nameToConditions[prizeName]!!
            val matchingEntries = buckets.entries.filter { (k, _) ->
                k.ruleVersionKey != "__UNKNOWN_VERSION__" &&
                conditions.any { it.first == k.matchPrimary && it.second == k.matchSecondary }
            }
            if (matchingEntries.isEmpty()) continue
            matchingEntries.forEach { (k, _) -> consumed.add(k) }

            val allDraws = matchingEntries.flatMap { it.value }
            val (mp, ms) = if (conditions.size == 1) conditions[0] else (-1 to -1)
            inOrder.add(
                QueryResultItem(
                    matchPrimary = mp,
                    matchSecondary = ms,
                    prizeName = prizeName,
                    count = allDraws.size.toLong(),
                    matches = allDraws,
                    sourceRuleVersionKey = config.ruleVersions.lastOrNull()?.key
                )
            )
        }

        // 2b) 剩余 bucket 按最新政策奖级条件就近归属
        val remaining = buckets.keys - consumed
        for (key in remaining) {
            if (key.ruleVersionKey == "__UNKNOWN_VERSION__") continue
            val draws = buckets[key] ?: continue
            val latestName = latestRuleByKey[key.matchPrimary to key.matchSecondary] ?: key.prizeName
            val existing = inOrder.firstOrNull { it.prizeName == latestName }
            if (existing != null) {
                (existing.matches as MutableList<LotteryDraw>).addAll(draws)
                existing.count += draws.size.toLong()
                existing.matchPrimary = -1
                existing.matchSecondary = -1
            } else {
                inOrder.add(
                    QueryResultItem(
                        matchPrimary = key.matchPrimary,
                        matchSecondary = key.matchSecondary,
                        prizeName = latestName,
                        count = draws.size.toLong(),
                        matches = draws.toMutableList(),
                        sourceRuleVersionKey = key.ruleVersionKey
                    )
                )
            }
        }

        // 2c) 追加：元数据缺失（规则版本不明）的命中
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

    /**
     * 单次命中检测：返回 Triple(命中前区数, 命中后区数, 是否满足该规则)。
     * - 非 FC3D/P3：规则 matchPrimary=X 要求集合命中 X 个数。
     * - FC3D/P3：
     *     DIRECT → 要求逐位相等，命中前区数=3，后区=0。
     *     GROUP_3 → 要求 multiset 相等 且 用户号码结构为 2同1异，命中前区=3。
     *     GROUP_6 → 要求 multiset 相等 且 用户号码 3 个不同，命中前区=3。
     *   规则中 group3/group6 的 matchPrimary 也是 3，所以最终 matchPrimary=3。
     */
    private fun countHitAndCheck(
        config: LotteryTypeConfig,
        isPositional: Boolean,
        matchMode: MatchMode,
        ruleMatchPrimary: Int,
        ruleMatchSecondary: Int,
        selectedPrimary: List<Int>,
        selectedPrimarySet: Set<Int>,
        selectedSecondary: Set<Int>,
        draw: LotteryDraw
    ): Triple<Int, Int, Boolean> {
        // ===== FC3D/P3 模式：按位置 / 组选匹配 =====
        if (isPositional && selectedPrimary.size == 3 && draw.primaryNumbers.size == 3) {
            // 后区恒为 0
            val secondaryCount = 0
            val primaryCount = when (matchMode) {
                MatchMode.DIRECT -> {
                    // 直选：逐位比较
                    var equal = 0
                    for (i in 0..2) {
                        if (draw.primaryNumbers[i] == selectedPrimary[i]) equal++
                    }
                    equal
                }
                MatchMode.GROUP_3, MatchMode.GROUP_6 -> {
                    // 组选：先判断多重集相等（号码全中、不限位置），再校验结构
                    val drawSorted = draw.primaryNumbers.sorted()
                    val selSorted = selectedPrimary.sorted()
                    val multisetMatch = drawSorted == selSorted
                    if (!multisetMatch) {
                        // 号码不全中，组选也不成立
                        draw.primaryNumbers.count { it in selectedPrimarySet }
                    } else {
                        // 号码全中：3 个球
                        val a = selSorted[0]; val b = selSorted[1]; val c = selSorted[2]
                        val hasTwoSame = (a == b && b != c) || (b == c && a != b)
                        val allDiff = a != b && b != c
                        val ok = when (matchMode) {
                            MatchMode.GROUP_3 -> hasTwoSame
                            MatchMode.GROUP_6 -> allDiff
                            else -> true
                        }
                        if (ok) 3 else 2   // 号码全中但结构不匹配 → 仍显示为命中2
                    }
                }
            }
            val ok = (primaryCount == ruleMatchPrimary) && (secondaryCount == ruleMatchSecondary)
            return Triple(primaryCount, secondaryCount, ok)
        }

        // ===== 普通彩种：集合交集计数 =====
        val primaryCount = draw.primaryNumbers.count { it in selectedPrimarySet }
        val secondaryCount = if (config.hasSecondary) {
            draw.secondaryNumbers.count { it in selectedSecondary }
        } else 0
        val ok = (primaryCount == ruleMatchPrimary) && (secondaryCount == ruleMatchSecondary)
        return Triple(primaryCount, secondaryCount, ok)
    }

    fun formatNumbers(list: List<Int>, pad: Boolean = true): String {
        return list.sorted().joinToString(" ") {
            if (pad) String.format("%02d", it) else it.toString()
        }
    }
}
