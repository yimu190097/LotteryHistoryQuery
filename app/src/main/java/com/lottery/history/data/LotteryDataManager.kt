package com.lottery.history.data

import android.content.Context
import com.lottery.history.db.LotteryDao
import com.lottery.history.db.LotteryDatabase
import com.lottery.history.db.LotteryDrawEntity
import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryType
import com.lottery.history.model.LotteryTypeConfig
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

    private val mutex = Mutex()
    private var dao: LotteryDao? = null
    private var lastUpdate: Long = 0L

    /** 每个彩种的内存缓存 */
    private val caches = mutableMapOf<String, List<LotteryDraw>>()

    private fun ensureDao(context: Context): LotteryDao {
        return dao ?: LotteryDatabase.get(context).lotteryDao().also { dao = it }
    }

    /**
     * 确保内置 seed 数据已导入（仅 ssq/dlt 有 seed）。
     */
    suspend fun ensureInitialized(context: Context) {
        val d = ensureDao(context)
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
                    if (type == "ssq") {
                        if (parts.size < 8) continue
                        primary = (1..6).map { parts[it].toInt() }.sorted()
                        secondary = listOf(parts[7].toInt())
                    } else {
                        if (parts.size < 8) continue
                        primary = (1..5).map { parts[it].toInt() }.sorted()
                        secondary = listOf(parts[6].toInt(), parts[7].toInt()).sorted()
                    }
                    list.add(
                        LotteryDrawEntity(
                            issue = issue,
                            type = type,
                            primary = primary.joinToString(","),
                            secondary = secondary.joinToString(",")
                        )
                    )
                }
            }
        }
        if (list.isNotEmpty()) d.insertAll(list)
    }

    /** 获取指定彩种的缓存数据（同步，可能为空） */
    fun getCached(code: String): List<LotteryDraw> =
        caches[code] ?: emptyList()

    /** 获取指定彩种配置的缓存数据 */
    fun getCached(config: LotteryTypeConfig): List<LotteryDraw> =
        getCached(config.code)

    /** 加载指定彩种到内存缓存 */
    suspend fun loadCache(context: Context, config: LotteryTypeConfig) {
        ensureInitialized(context)
        val d = ensureDao(context)
        caches[config.code] = d.getAllByType(config.code).map { it.toModel() }
    }

    /** 加载全部彩种到内存缓存 */
    suspend fun loadCaches(context: Context) = mutex.withLock {
        ensureInitialized(context)
        val d = ensureDao(context)
        for (config in LotteryType.ALL) {
            caches[config.code] = d.getAllByType(config.code).map { it.toModel() }
        }
    }

    fun getLatestUpdateMs(): Long = lastUpdate

    /**
     * 从网络刷新全部彩种数据。
     * 每个彩种独立拉取，某个失败不影响其他。
     */
    suspend fun refresh(context: Context): RefreshResult = mutex.withLock {
        ensureInitialized(context)
        val d = ensureDao(context)
        val successTypes = mutableListOf<String>()
        val failedTypes = mutableListOf<String>()

        for (config in LotteryType.ALL) {
            try {
                val netList = withContext(Dispatchers.IO) { LotteryRepository.fetchHistory(config) }
                if (netList.isNotEmpty()) {
                    d.insertAll(netList.map {
                        LotteryDrawEntity(
                            issue = it.issue, type = config.code,
                            primary = it.primaryNumbers.joinToString(","),
                            secondary = it.secondaryNumbers.joinToString(","),
                            date = it.date,
                            firstPrizeCount = it.firstPrizeCount,
                            firstPrizeAmount = it.firstPrizeAmount,
                            secondPrizeCount = it.secondPrizeCount,
                            secondPrizeAmount = it.secondPrizeAmount
                        )
                    })
                    caches[config.code] = d.getAllByType(config.code).map { e -> e.toModel() }
                    successTypes.add(config.code)
                }
            } catch (_: Exception) {
                // 单个彩种失败不影响其他
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
            secondPrizeAmount = secondPrizeAmount
        )
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
