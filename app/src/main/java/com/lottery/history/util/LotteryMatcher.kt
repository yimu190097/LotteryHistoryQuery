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

        // 2) 查询结果列表：按最新政策 config.rules 的【奖项名】合并展示。
        //    【核心约束】：matches（真实 draws 列表）的所有元数据——期号、开奖日期、真实 ruleVersionKey、
        //    真实奖项信息绝对不变。这里只把不同版本、不同命中条件的 draws 合并到"最新政策对应奖项名行"
        //    用于简单展示；点"查看历史"时 HistoryDialog 依然按 matches 中每期 draw 的真实
        //    ruleVersionKey 分组展示真实政策。
        //
        //    同奖项名合并：如 DLT 2026 六等奖有 3+1 和 2+2 两个条件，命中任一都合并到一行"六等奖"。
        //    matchPrimary/matchSecondary 设为 -1 表示"多条件合并"，UI 层显示 "—"。
        val inOrder = mutableListOf<QueryResultItem>()
        val consumed = mutableSetOf<BucketKey>()
        // 最新政策 key：Pair(matchPrimary, matchSecondary) → 奖项名
        val latestRuleByKey = config.rules.associate { r ->
            (r.matchPrimary to r.matchSecondary) to r.prizeName
        }
        // 按奖项名去重，保留首次出现顺序
        val uniquePrizeNames = linkedSetOf<String>()
        // 每个奖项名对应的全部 (matchPrimary, matchSecondary) 条件集合
        val nameToConditions = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
        for (rule in config.rules) {
            uniquePrizeNames.add(rule.prizeName)
            nameToConditions.getOrPut(rule.prizeName) { mutableListOf() }
                .add(rule.matchPrimary to rule.matchSecondary)
        }

        // 2a) 按最新政策去重后的奖项名顺序：拉取所有版本中 (matchP, matchS) 属于该奖项名
        //     任一条件的 bucket，合并 draws（真实元数据不变），奖名用最新政策奖名。
        for (prizeName in uniquePrizeNames) {
            val conditions = nameToConditions[prizeName]!!
            val matchingEntries = buckets.entries.filter { (k, _) ->
                k.ruleVersionKey != "__UNKNOWN_VERSION__" &&
                conditions.any { it.first == k.matchPrimary && it.second == k.matchSecondary }
            }
            if (matchingEntries.isEmpty()) continue
            matchingEntries.forEach { (k, _) -> consumed.add(k) }

            // 所有真实 LotteryDraw 对象原封不动合并（ruleVersionKey / issue / date 全保留）
            val allDraws = matchingEntries.flatMap { it.value }
            // 多条件合并时 matchPrimary/Secondary 设为 -1（UI 显示 "—"）；单条件时保留实际值
            val (mp, ms) = if (conditions.size == 1) conditions[0] else (-1 to -1)
            inOrder.add(
                QueryResultItem(
                    matchPrimary = mp,
                    matchSecondary = ms,
                    prizeName = prizeName,          // 只改展示的奖名——按最新政策
                    count = allDraws.size.toLong(),
                    matches = allDraws,             // 真实draw元数据 100% 保留不改动
                    sourceRuleVersionKey = config.ruleVersions.lastOrNull()?.key
                )
            )
        }

        // 2b) 旧版本里 (matchP, matchS) 在最新政策中不存在的 bucket：
        //     尝试按最新政策奖级条件就近归属（如 2019 九等奖 2+0 命中 2 球未中后区/1+1），
        //     若最新规则找不到对应 key，就追加到尾部展示原奖项名；matches 元数据始终不变。
        val remaining = buckets.keys - consumed
        for (key in remaining) {
            if (key.ruleVersionKey == "__UNKNOWN_VERSION__") continue
            val draws = buckets[key] ?: continue
            // 找最新政策同(matchP, matchS)的奖名兜底；没找到就保留原奖项名
            val latestName = latestRuleByKey[key.matchPrimary to key.matchSecondary] ?: key.prizeName
            // 同奖名行已存在则合并（matches 真实 draws 元数据不变）
            val existing = inOrder.firstOrNull { it.prizeName == latestName }
            if (existing != null) {
                // 合并：仅把真实 draws 追加到 matches 列表，existing 其他字段不动
                //   → matches 原元数据依旧完整
                //   → 合并后该行一定含多条件，matchPrimary/Secondary 设 -1
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
