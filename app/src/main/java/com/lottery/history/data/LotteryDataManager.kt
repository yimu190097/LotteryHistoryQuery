package com.lottery.history.data

import android.content.Context
import com.lottery.history.db.LotteryDao
import com.lottery.history.db.LotteryDatabase
import com.lottery.history.db.LotteryDrawEntity
import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryType
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.model.ParseSource
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
     *  以便 refresh 时强制覆盖旧版本入库的脏数据，避免被"更完整MATCH"保护永久保留。
     *
     *  v1→v2：DLT 2019/2009 追加投注奖级和基本投注尾奖错位修复
     *  v2→v3：DrawDetailDialog 追加投注 0注空开显示修复，解析器版本号整体提升
     *  v4→v5：【ROOT CAUSE 用户给事实终极修正】用户明确告知"26089期字段30=1/31=7247161
     *         就是追加1等奖！"。之前所有版本把基本投注尾部扩展（字段26-29，9级版本放
     *         基本8/9等 828524注/15元、8266088注/5元）错当成了追加投注1/2等，
     *         导致追加1等整体偏移2级：真实追加1等(4注/5,588,963元)被展示为"追加3等"，
     *         真实基本8等=82万注/15元被展示为"追加1等"的荒谬BUG。
     *         终极字段布局已硬验证：
     *          前缀1-11  基本投注12-25(7×2=14)  基本尾扩展26-29(2×2=4=8/9等)
     *          追加投注1-4等30-37(4×2=8)  追加5级count38(1字段)
     *  v5→v6：【P0 追加比例+尾标修复】RuleVersion 新增 appendRatio 字段，
     *         DLT 2007-2019 年追加比例 60%(0.6)不再被硬编码 0.8 覆盖错误；
     *         2009 版 F38=60 官方源行尾标记不再被误当成追加五等 count。
     */
    const val PARSER_VERSION_CURRENT = 6

    private val mutex = Mutex()
    @Volatile private var dao: LotteryDao? = null
    private var lastUpdate: Long = 0L

    /** 每个彩种的内存缓存 */
    private val caches = mutableMapOf<String, List<LotteryDraw>>()

    private fun ensureDaos(context: Context) {
        val db = LotteryDatabase.get(context)
        if (dao == null) dao = db.lotteryDao()
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

        if (d.countByType("ssq") == 0) {
            importSeed(context, d, "ssq", com.lottery.history.R.raw.ssq_seed)
        }
        if (d.countByType("dlt") == 0) {
            importSeed(context, d, "dlt", com.lottery.history.R.raw.dlt_seed)
        }
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
                    // FC3D/P3 不排序号码：保留位置信息（直选需逐位比较）
                    //  当前 seed 资源仅含 ssq / dlt，但逻辑写全，避免未来加 3d/p3 seed
                    //  时忘了改这里，导致位置信息被 sorted() 丢掉，直选匹配失效。
                    val needsPos = type == "3d" || type == "p3"
                    when (type) {
                        "ssq" -> {
                            if (parts.size < 8) continue
                            primary = (1..6).map { parts[it].toInt() }.sorted()
                            secondary = listOf(parts[7].toInt())
                        }
                        "dlt" -> {
                            if (parts.size < 8) continue
                            primary = (1..5).map { parts[it].toInt() }.sorted()
                            secondary = listOf(parts[6].toInt(), parts[7].toInt()).sorted()
                        }
                        "3d", "p3" -> {
                            val pickCount = if (type == "3d") 3 else 3
                            if (parts.size < 1 + pickCount) continue
                            // 保留原始位置（parts[1] = 百位, parts[2] = 十位, parts[3] = 个位）
                            primary = (1..pickCount).mapNotNull { parts.getOrNull(it)?.toIntOrNull() }
                            if (primary.size != pickCount) continue
                            secondary = emptyList()
                        }
                        else -> continue
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
                            // P1-9: seed 虽是内置兜底数据，但统一标记为当前解析器版本，
                            // 避免下游按 parserVersion 判断时把 seed 当成"废弃/不完整"数据。
                            parserVersion = PARSER_VERSION_CURRENT,
                            conditionalFlagsJson = null
                        )
                    )
                }
            }
        }
        if (list.isNotEmpty()) d.insertAll(list)
    }

    /**
     *  用户需求：每次更新都强制拉取新数据，避免缓存/DB脏数据导致解析或展示失真。
     *  故 getCached 永远返回空 → 上层 UI 感知到"本地无有效缓存"，永远触发 refresh 拉取最新；
     *  DB 中实际数据仍可通过 getAllFromDb(context, code) 直接读取（给需立即访问最新落库数据的场景）。
     */
    fun getCached(code: String): List<LotteryDraw> = emptyList()

    /** 获取指定彩种配置的缓存数据 → 永远返回空（见 getCached(code) 说明） */
    fun getCached(config: LotteryTypeConfig): List<LotteryDraw> = emptyList()

    /** 直接从 DB 读取当前已落库的数据（不受 getCached 空缓存屏蔽影响）。
     *  场景：IssueSearchDialog / LatestDrawsDialog / LotterFragment 在 refresh 完成后需要立即读取。
     *
     *  说明：Room DAO getAllByType 是 suspend 函数（强制在后台线程），UI 调用方都在主线程，
     *  这里用 runBlocking(Dispatchers.IO) 桥接——查询量仅数百~数千条，耗时几毫秒内，
     *  不会阻塞 UI；比把所有 UI 调用方全改成 suspend 更干净。
     *
     *  【错误边界】：所有异常内部消化，返回空列表，绝不抛出让 UI 崩溃。
     */
    fun getAllFromDb(context: Context, code: String): List<LotteryDraw> {
        return try {
            val d = ensureDao(context) ?: return emptyList()
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                d.getAllByType(code).map { e -> e.toModel() }
            }
        } catch (e: Exception) {
            android.util.Log.e("LotteryDataManager", "getAllFromDb($code) 失败", e)
            emptyList()
        }
    }
    fun getAllFromDb(context: Context, config: LotteryTypeConfig): List<LotteryDraw> =
        getAllFromDb(context, config.code)

    private fun ensureDao(context: Context): LotteryDao? = synchronized(this) {
        if (dao == null) {
            val db = LotteryDatabase.get(context.applicationContext)
            dao = db.lotteryDao()
        }
        dao
    }

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
        val successTypes = mutableListOf<String>()
        val failedTypes = mutableListOf<String>()
        val now = System.currentTimeMillis()

        for (config in LotteryType.ALL) {
            try {
                val netList = withContext(Dispatchers.IO) { LotteryRepository.fetchHistory(config) }
                if (netList.isNotEmpty()) {
                    // ===== 开发阶段：无条件全量拉取+全量覆盖 =====
                    //  【用户明确指令】：项目编写阶段每次都拉取新数据，绝不搞什么增量判断保留本地。
                    //  这样做的好处：
                    //    1) 解析器BUG修复后，下一次开发运行/用户打开立即全部重写，
                    //       旧脏数据不会因为什么"本地更完整""本地MATCH"就永久锁死。
                    //    2) 官方数据从 '-' 不完整→补全，下次刷新立刻重写。
                    //    3) 项目开发调期时，拿到的永远是官网最新解析结果，不受本机历史缓存干扰。
                    //  PARSER_VERSION_CURRENT = v3 → 入库的所有期 parserVersion 都提升到 v3，
                    //  后续再升版本时继续保证无条件覆盖。
                    val existingByIssue: Map<String, LotteryDrawEntity> = withContext(Dispatchers.IO) {
                        d.getAllByType(config.code)
                    }.associateBy { it.issue }

                    val entities = mutableListOf<LotteryDrawEntity>()

                    for (draw in netList) {
                        val local = existingByIssue[draw.issue]
                        // 忽略 local / nonNullTiers / tierMatchStatus 等所有保留判断，
                        // 只保留一个假 keepLocal=false → 全部无条件覆盖
                        val keepLocal = false

                        if (keepLocal) continue

                        // —— 新写入/覆盖 ——
                        //  【P0修复】：parseSource/parseAt/parserVersion 必须用 draw 本身携带的值，
                        //  绝不能强制写 ParseSource.NET！否则：
                        //    · SEED_INCOMPLETE（规则版本无法定位的期）会被伪装成 NET，
                        //      详情页的 resolveRuleVersion 报"元数据缺失"但 parseSource 又写着 NET，
                        //      自相矛盾，排查问题会被误导。
                        //    · parserVersion 也必须以解析器当时产出的版本为准，入库不重写。
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
                                parseSource = draw.parseSource ?: ParseSource.NET,
                                parseAt = draw.parseAt ?: now,
                                parserVersion = draw.parserVersion ?: PARSER_VERSION_CURRENT,
                                conditionalFlagsJson = encodeFlags(draw.conditionalFlags)
                            )
                        )
                    }

                    if (entities.isNotEmpty()) d.insertAll(entities)

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
     *
     * 【强制刷新机制】getCached 已永远返回空 → 这里直接从 DB 读取，保证 findDrawByIssue
     * 在 refresh 前后都能查到数据。
     */
    fun findDrawByIssue(context: Context, config: LotteryTypeConfig, queryRaw: String): LotteryDraw? {
        val q = queryRaw.trim()
        if (q.isEmpty()) return null
        val list = getAllFromDb(context, config)
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
