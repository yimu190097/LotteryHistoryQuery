package com.lottery.history.data

import android.content.Context
import com.lottery.history.db.LotteryDao
import com.lottery.history.db.LotteryDatabase
import com.lottery.history.db.LotteryDrawEntity
import com.lottery.history.db.MatchRuleDefDao
import com.lottery.history.db.MatchRuleDefEntity
import com.lottery.history.db.PrizeTierDao
import com.lottery.history.db.PrizeTierEntity
import com.lottery.history.db.RuleVersionCatalogDao
import com.lottery.history.db.RuleVersionCatalogEntity
import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryType
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.model.ParseSource
import com.lottery.history.model.TIER_GROUP_APPEND
import com.lottery.history.model.TIER_GROUP_BASE
import com.lottery.history.model.decodeFlags
import com.lottery.history.model.decodePrizeTiers
import com.lottery.history.model.encodeFlags
import com.lottery.history.model.encodeTiers
import com.lottery.history.network.LotteryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 通用数据管理：支持任意彩种的数据获取、缓存和刷新。
 * 内存缓存以 type code 为 key，DB 持久化同样按 type 区分。
 */
object LotteryDataManager {

    /** 当前解析器版本号。所有版本升级（如修复某个彩种某个时期的字段错位BUG）都应 +1，
     *  以便 refresh 时强制覆盖旧版本入库的脏数据，避免被"更完整MATCH"保护永久保留。 */
    const val PARSER_VERSION_CURRENT = 2

    private val mutex = Mutex()
    @Volatile private var dao: LotteryDao? = null
    @Volatile private var ruleVersionCatalogDao: RuleVersionCatalogDao? = null
    @Volatile private var matchRuleDefDao: MatchRuleDefDao? = null
    @Volatile private var prizeTierDao: PrizeTierDao? = null
    private var lastUpdate: Long = 0L

    /** 每个彩种的内存缓存 */
    private val caches = mutableMapOf<String, List<LotteryDraw>>()

    private fun ensureDaos(context: Context) {
        val db = LotteryDatabase.get(context)
        if (dao == null) dao = db.lotteryDao()
        if (ruleVersionCatalogDao == null) ruleVersionCatalogDao = db.ruleVersionCatalogDao()
        if (matchRuleDefDao == null) matchRuleDefDao = db.matchRuleDefDao()
        if (prizeTierDao == null) prizeTierDao = db.prizeTierDao()
    }

    /**
     * 确保内置 seed 数据已导入（仅 ssq/dlt 有 seed），
     * 然后将规则目录持久化，最后修补 seed 来源但缺元数据的记录。
     * 公共入口：自动获取锁。已持锁的内部调用方应直接调 [ensureInitializedLocked]。
     */
    suspend fun ensureInitialized(context: Context) = mutex.withLock {
        ensureInitializedLocked(context)
    }

    /**
     * 内部实现（调用方必须已持有 [mutex]）。
     * 拆分自 ensureInitialized 以避免 kotlinx.coroutines.sync.Mutex 不可重入导致的死锁。
     */
    private suspend fun ensureInitializedLocked(context: Context) {
        ensureDaos(context)
        val d = dao!!
        val ptDao = prizeTierDao!!

        var didImportSsq = false
        var didImportDlt = false
        if (d.countByType("ssq") == 0) {
            importSeed(context, d, "ssq", com.lottery.history.R.raw.ssq_seed)
            didImportSsq = true
        }
        if (d.countByType("dlt") == 0) {
            importSeed(context, d, "dlt", com.lottery.history.R.raw.dlt_seed)
            didImportDlt = true
        }

        ensureRuleCatalogPersisted()

        val seedTypesToCheck = mutableListOf<String>()
        if (didImportSsq || d.countByType("ssq") > 0) seedTypesToCheck.add("ssq")
        if (didImportDlt || d.countByType("dlt") > 0) seedTypesToCheck.add("dlt")

        for (type in seedTypesToCheck) {
            val draws = d.getAllByType(type)
            val incompleteUpdates = mutableListOf<LotteryDrawEntity>()
            for (draw in draws) {
                val isSeedSource = draw.parseSource == null || draw.parseSource == ParseSource.SEED
                if (isSeedSource) {
                    val ptCount = ptDao.getByDraw(draw.issue, type).size
                    if (ptCount == 0) {
                        incompleteUpdates.add(
                            draw.copy(parseSource = ParseSource.SEED_INCOMPLETE)
                        )
                    }
                }
            }
            if (incompleteUpdates.isNotEmpty()) {
                d.insertAll(incompleteUpdates)
            }
        }
    }

    /**
     * 将 LotteryType.ALL.ruleVersions 及其中的 MatchRuleDef 分别 upsert 到
     * rule_version_catalog 和 match_rule_def 表。
     *
     * dedupIndex 算法：同 DrawDetailDialog.mergePrizeTiersWithRules
     *  - 按 prizeName 分组，第一次出现的 index 作为 dedupIndex（同组共享）
     *  - ruleIndex 是该规则在 RuleVersion.rules 中的原始下标（0 起）
     */
    private suspend fun ensureRuleCatalogPersisted() {
        val rvDao = ruleVersionCatalogDao!!
        val mrDao = matchRuleDefDao!!
        val now = System.currentTimeMillis()

        val allRuleVersions = mutableListOf<RuleVersionCatalogEntity>()
        val allMatchRules = mutableListOf<MatchRuleDefEntity>()

        for (config in LotteryType.ALL) {
            for (rv in config.ruleVersions) {
                allRuleVersions.add(
                    RuleVersionCatalogEntity(
                        ruleVersionKey = rv.key,
                        code = config.code,
                        effectiveFromDate = rv.effectiveFromDate,
                        policyLabel = rv.policyLabel,
                        changeNote = rv.changeNote,
                        realTiersToUse = rv.realTiersToUse,
                        prizeTierPairCount = rv.prizeTierPairCount,
                        extraFieldCount = rv.extraFieldCount,
                        appendTierPairCount = rv.appendTierPairCount,
                        snapshotAt = now
                    )
                )

                var dedupIdx = -1
                var lastName: String? = null
                rv.rules.forEachIndexed { ruleIdx, rule ->
                    if (rule.prizeName != lastName) {
                        dedupIdx++
                        lastName = rule.prizeName
                    }
                    allMatchRules.add(
                        MatchRuleDefEntity(
                            ruleVersionKey = rv.key,
                            dedupIndex = dedupIdx,
                            ruleIndex = ruleIdx,
                            matchPrimary = rule.matchPrimary,
                            matchSecondary = rule.matchSecondary,
                            description = rule.description,
                            prizeName = rule.prizeName,
                            fixedAmountYuan = rule.fixedAmountYuan,
                            conditionalKey = rule.conditionalKey
                        )
                    )
                }
            }
        }

        rvDao.upsert(allRuleVersions)
        mrDao.insertAll(allMatchRules)
    }

    private suspend fun importSeed(
        context: Context,
        d: LotteryDao,
        type: String,
        rawId: Int
    ) = withContext(Dispatchers.IO) {
        val list = mutableListOf<LotteryDrawEntity>()
        runCatching {
            context.resources.openRawResource(rawId).bufferedReader().useLines { seq ->
                for (line in seq) {
                    val parts = line.trim().split(',')
                    if (parts.size < 4) continue
                    val issue = parts[0]
                    val primary: List<Int>
                    val secondary: List<Int>
                    if (type == "ssq") {
                        if (parts.size < 8) continue
                        primary = (1..6).map { parts[it].toInt() }.sorted()
                        secondary = listOf(parts[7].toInt())
                    } else {
                        if (parts.size < 8) continue
                        primary = (1..5).map { parts[it].toInt() }.sorted()
                        secondary = listOf(parts[6].toInt(), parts[7].toInt()).sorted()
                    }
                    // ——【零兜底·严格模式】seed 没有真实 date → rvKey 直接存 null——
                    //   绝对禁止用 issue 前缀拼 fakeDate 去推断规则版本（那是猜的，会错！）：
                    //   例 SSQ 2026 新规生效日 = 2026-02-01，第 2026016 期（2月8号前后）
                    //   拿 fakeDate 2026-06-15 去判断，一整年的都会被错归成新版。
                    //   seed 没有真实 date，就老老实实记 null，展示层按 resolveRuleVersion()
                    //   报「元数据缺失，无法确定当期规则版本」，绝不用任何规则硬套。
                    val rvKey: String? = null
                    list.add(
                        LotteryDrawEntity(
                            issue = issue,
                            type = type,
                            primary = primary.joinToString(","),
                            secondary = secondary.joinToString(","),
                            ruleVersionKey = rvKey,
                            parseSource = ParseSource.SEED,
                            parseAt = null,
                            parserVersion = 1,
                            conditionalFlagsJson = null
                        )
                    )
                }
            }
        }
        if (list.isNotEmpty()) d.insertAll(list)
    }

    /** 获取指定彩种的缓存数据（同步，可能为空）。
     *  如果缓存中存在任何解析器版本 < PARSER_VERSION_CURRENT 的记录（包括旧版本脏数据），
     *  返回空列表以触发上层 refresh 强制重拉，保证用户不再看到字段错位的失真数据。 */
    fun getCached(code: String): List<LotteryDraw> {
        val list = caches[code] ?: return emptyList()
        val dirty = list.any { (it.parserVersion ?: 0) < PARSER_VERSION_CURRENT }
        return if (dirty) emptyList() else list
    }

    /** 获取指定彩种配置的缓存数据 */
    fun getCached(config: LotteryTypeConfig): List<LotteryDraw> =
        getCached(config.code)

    /** 加载指定彩种到内存缓存 */
    suspend fun loadCache(context: Context, config: LotteryTypeConfig) = mutex.withLock {
        ensureInitializedLocked(context)
        val d = dao!!
        caches[config.code] = d.getAllByType(config.code).map { it.toModel() }
    }

    /** 加载全部彩种到内存缓存 */
    suspend fun loadCaches(context: Context) = mutex.withLock {
        ensureInitializedLocked(context)
        val d = dao!!
        for (config in LotteryType.ALL) {
            caches[config.code] = d.getAllByType(config.code).map { e -> e.toModel() }
        }
    }

    fun getLatestUpdateMs(): Long = lastUpdate

    /**
     * 从网络刷新全部彩种数据。
     * 每个彩种独立拉取，某个失败不影响其他。
     */
    suspend fun refresh(context: Context): RefreshResult = mutex.withLock {
        ensureInitializedLocked(context)
        val d = dao!!
        val ptDao = prizeTierDao!!
        val successTypes = mutableListOf<String>()
        val failedTypes = mutableListOf<String>()
        val now = System.currentTimeMillis()

        for (config in LotteryType.ALL) {
            try {
                val netList = withContext(Dispatchers.IO) { LotteryRepository.fetchHistory(config) }
                if (netList.isNotEmpty()) {
                    // ===== v11 增量刷新保护策略（P1 修复：避免 '-' 回滚覆盖完整数据）=====
                    //  官方数据源有时先出前几对、后面是 '-'，次日补全。如果新数据里有 '-',
                    //  本地已有一条 tierMatchStatus=MATCH 且 nonNullTiers 更多的完整版本，
                    //  就保留本地、不被 '-' 版本覆盖。
                    val existingByIssue: Map<String, LotteryDrawEntity> = d.getAllByType(config.code)
                        .associateBy { it.issue }

                    val entities = mutableListOf<LotteryDrawEntity>()
                    val ptInserts = mutableListOf<PrizeTierEntity>()

                    for (draw in netList) {
                        val local = existingByIssue[draw.issue]
                        val netNonNullTiers = draw.allPrizeTiers.count { it != null }
                        val localNonNullTiers =
                            local?.let { decodePrizeTiers(it.allPrizeTiers).count { e -> e != null } }
                                ?: 0

                        // 【parserVersion < PARSER_VERSION_CURRENT → 旧解析脏数据 强制覆盖】
                        //   v1→v2 修复：DLT 2019/2009 等规则版本在 v1 中常被"看似 MATCH 实则错位"地
                        //   解析（基本八等奖被当成追加一等），被 MATCH 完整性保护永远保留，用户看到
                        //   追加一等奖=8,285,244注/15元这种严重失真数据。提升到v2后，所有旧版本缓存
                        //   无条件被新的正确解析覆盖。
                        val forceOverride = local != null && (local.parserVersion ?: 0) < PARSER_VERSION_CURRENT
                        val keepLocal = !forceOverride &&
                            local != null &&
                                local.tierMatchStatus == com.lottery.history.model.TierMatchStatus.MATCH &&
                                draw.tierMatchStatus != com.lottery.history.model.TierMatchStatus.MATCH &&
                                localNonNullTiers >= netNonNullTiers

                        if (keepLocal) continue // 保留本地"更完整且同版本"的残缺期，其他一律覆写

                        // —— 新写入/覆盖 ——
                        entities.add(
                            LotteryDrawEntity(
                                issue = draw.issue, type = config.code,
                                primary = draw.primaryNumbers.joinToString(","),
                                secondary = draw.secondaryNumbers.joinToString(","),
                                date = draw.date,
                                firstPrizeCount = draw.firstPrizeCount,
                                firstPrizeAmount = draw.firstPrizeAmount,
                                secondPrizeCount = draw.secondPrizeCount,
                                secondPrizeAmount = draw.secondPrizeAmount,
                                allPrizeTiers = draw.allPrizeTiers.encodeTiers(),
                                ruleVersionKey = draw.ruleVersionKey,
                                actualTierCount = draw.actualTierCount,
                                tierMatchStatus = draw.tierMatchStatus,
                                jackpotAmount = draw.jackpotAmount,
                                salesAmount = draw.salesAmount,
                                appendPrizeTiers = draw.appendPrizeTiers.encodeTiers(),
                                parseSource = ParseSource.NET,
                                parseAt = now,
                                parserVersion = PARSER_VERSION_CURRENT,
                                conditionalFlagsJson = encodeFlags(draw.conditionalFlags)
                            )
                        )

                        // 同时写入规范化新表 lottery_prize_tier（先删本期旧，再写新，保证原子性思路）
                        ptDao.deleteForDraw(draw.issue, config.code)
                        draw.allPrizeTiers.forEachIndexed { idx, entry ->
                            if (entry != null) {
                                ptInserts.add(
                                    PrizeTierEntity(
                                        issue = draw.issue,
                                        type = config.code,
                                        tierGroup = TIER_GROUP_BASE,
                                        tierIndex = idx,
                                        count = entry.count,
                                        amount = entry.amount,
                                        updatedAt = now
                                    )
                                )
                            }
                        }
                        draw.appendPrizeTiers.forEachIndexed { idx, entry ->
                            if (entry != null) {
                                ptInserts.add(
                                    PrizeTierEntity(
                                        issue = draw.issue,
                                        type = config.code,
                                        tierGroup = TIER_GROUP_APPEND,
                                        tierIndex = idx,
                                        count = entry.count,
                                        amount = entry.amount,
                                        updatedAt = now
                                    )
                                )
                            }
                        }
                    }

                    if (entities.isNotEmpty()) d.insertAll(entities)
                    if (ptInserts.isNotEmpty()) ptDao.insertReplace(ptInserts)

                    caches[config.code] = d.getAllByType(config.code).map { e -> e.toModel() }
                    successTypes.add(config.code)
                }
            } catch (_: Exception) {
                if (caches[config.code] == null) {
                    caches[config.code] = d.getAllByType(config.code).map { e -> e.toModel() }
                }
                failedTypes.add(config.code)
            }
        }
        lastUpdate = System.currentTimeMillis()
        saveMeta(context, lastUpdate, successTypes.size, failedTypes.size)
        RefreshResult(
            success = failedTypes.isEmpty(),
            successCount = successTypes.size,
            failedCount = failedTypes.size,
            failedTypes = failedTypes,
            error = if (failedTypes.isNotEmpty()) "部分彩种更新失败: ${failedTypes.joinToString()}" else null
        )
    }

    private fun saveMeta(context: Context, ms: Long, successN: Int, failedN: Int) {
        context.getSharedPreferences("lottery_data", Context.MODE_PRIVATE).edit()
            .putLong("last_update", ms)
            .putInt("success_count", successN)
            .putInt("failed_count", failedN)
            .apply()
    }

    fun readMeta(context: Context): Triple<Long, Int, Int> {
        val p = context.getSharedPreferences("lottery_data", Context.MODE_PRIVATE)
        return Triple(
            p.getLong("last_update", 0L),
            p.getInt("success_count", 0),
            p.getInt("failed_count", 0)
        )
    }

    private fun LotteryDrawEntity.toModel(): LotteryDraw =
        LotteryDraw(
            issue = issue,
            primaryNumbers = primary.split(',').mapNotNull { it.toIntOrNull() },
            secondaryNumbers = secondary.split(',').mapNotNull { it.toIntOrNull() },
            date = date,
            firstPrizeCount = firstPrizeCount,
            firstPrizeAmount = firstPrizeAmount,
            secondPrizeCount = secondPrizeCount,
            secondPrizeAmount = secondPrizeAmount,
            allPrizeTiers = decodePrizeTiers(allPrizeTiers),
            ruleVersionKey = ruleVersionKey,
            actualTierCount = actualTierCount,
            tierMatchStatus = tierMatchStatus,
            jackpotAmount = jackpotAmount,
            salesAmount = salesAmount,
            appendPrizeTiers = decodePrizeTiers(appendPrizeTiers),
            parseSource = parseSource,
            parseAt = parseAt,
            parserVersion = parserVersion,
            conditionalFlags = decodeFlags(conditionalFlagsJson)
        )

    /**
     * 按「期号」查找某一期开奖结果：
     *   1) 先完全匹配 issue == query
     *   2) 再用 endsWith 模糊匹配（用户可能只输后几位）
     *   3) 最后再用 startsWith 匹配（用户可能输"26087"而完整 issue 是"26087001"等容错）
     */
    fun findDrawByIssue(config: LotteryTypeConfig, queryRaw: String): LotteryDraw? {
        val q = queryRaw.trim()
        if (q.isEmpty()) return null
        val list = getCached(config)
        list.firstOrNull { it.issue == q }?.let { return it }
        list.firstOrNull { it.issue.endsWith(q) }?.let { return it }
        list.firstOrNull { it.issue.startsWith(q) }?.let { return it }
        val compact = q.replace("""[第期\s\-_]""".toRegex(), "")
        if (compact.isEmpty()) return null
        list.firstOrNull { draw ->
            val di = draw.issue.replace("""[\-_ ]""".toRegex(), "")
            di == compact || di.endsWith(compact) || di.startsWith(compact)
        }?.let { return it }
        return null
    }
}

data class RefreshResult(
    val success: Boolean,
    val successCount: Int,
    val failedCount: Int,
    val failedTypes: List<String> = emptyList(),
    val error: String?
) {
    /** 兼容旧调用：返回成功彩种数量作为 ssqCount，总数-fail 作为 dltCount 占位 */
    val ssqCount: Int get() = successCount
    val dltCount: Int get() = successCount
}
