package com.lottery.history.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lottery.history.db.LotteryDatabase
import com.lottery.history.db.PendingSyncEntity
import com.lottery.history.db.PendingSyncDao
import com.lottery.history.db.QuotaDao
import com.lottery.history.db.QuotaEntity
import com.lottery.history.db.PlanType
import com.lottery.history.db.SyncAction
import com.lottery.history.db.SyncStatus
import com.lottery.history.work.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 配额 Repository：VIP 不限次数 + 免费用户每日 2 次，服务器权威。
 *
 * - 读：直接观察本地 quotas 表 Flow，UI 秒开
 * - 写（扣减）：调服务器 /consume，失败时本地兜底 + 入队待同步
 * - 同步：联网后 SyncWorker 推送，服务器权威快照覆盖本地
 */
class QuotaRepository(private val context: Context) {

    private val quotaDao: QuotaDao by lazy { LotteryDatabase.get(context).quotaDao() }
    private val pendingDao: PendingSyncDao by lazy { LotteryDatabase.get(context).pendingSyncDao() }

    /** 观察当前用户配额，UI 订阅 */
    fun observe(phone: String): Flow<QuotaEntity?> = quotaDao.observeByUser(phone)

    /** 新用户初始化配额：免费用户，每日查询次数从服务端配置获取 */
    suspend fun initForNewUser(phone: String) = withContext(Dispatchers.IO) {
        if (quotaDao.getByUser(phone) == null) {
            val now = System.currentTimeMillis()
            val today = (now / 86400000).toInt()
            val limit = fetchFreeQueryLimit()
            quotaDao.upsert(
                QuotaEntity(
                    userPhone = phone,
                    planType = PlanType.FREE,
                    freeUsed = 0,
                    freeQueryLimit = limit,
                    freeQueryDate = today,
                    monthlyExpireAt = null,
                    serverVersion = 0,
                    localVersion = 0,
                    updatedAt = now
                )
            )
        }
    }

    /** 管理员初始化配额：年VIP，有效期一年 */
    suspend fun initForAdmin(phone: String) = withContext(Dispatchers.IO) {
        if (quotaDao.getByUser(phone) == null) {
            val now = System.currentTimeMillis()
            val oneYear = 365L * 24 * 60 * 60 * 1000
            val limit = fetchFreeQueryLimit()
            quotaDao.upsert(
                QuotaEntity(
                    userPhone = phone,
                    planType = PlanType.ANNUAL_VIP,
                    freeUsed = 0,
                    freeQueryLimit = limit,
                    freeQueryDate = (now / 86400000).toInt(),
                    monthlyExpireAt = now + oneYear,
                    serverVersion = 0,
                    localVersion = 0,
                    updatedAt = now
                )
            )
        }
    }

    /**
     * 消耗一次查询：服务器优先（带 JWT 鉴权 + clientOpId 幂等），失败时降级到本地扣减 + 入队待同步。
     *
     * @return true 扣减成功，false 无配额
     */
    suspend fun consumeOneQuery(phone: String): Boolean = withContext(Dispatchers.IO) {
        val quota = quotaDao.getByUser(phone) ?: return@withContext false
        if (!quota.canQuery()) return@withContext false

        val clientOpId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // VIP 用户：调服务器确认未过期
        if (PlanType.isVip(quota.planType)) {
            try {
                val resp = com.lottery.history.network.ApiClient.consumeQuery(phone, 1, clientOpId)
                applyServerSnapshot(
                    phone = phone,
                    freeUsed = resp.freeUsed,
                    freeQueryLimit = resp.freeLimit,
                    planTypeStr = resp.planType,
                    monthlyExpireAt = quota.monthlyExpireAt,
                    serverVersion = quota.serverVersion + 1
                )
                return@withContext true
            } catch (e: Exception) {
                android.util.Log.w("QuotaRepository", "server consume failed, fallback local: ${e.message}")
            }
        }

        // 免费用户：调服务器扣减
        try {
            val resp = com.lottery.history.network.ApiClient.consumeQuery(phone, 1, clientOpId)
            applyServerSnapshot(
                phone = phone,
                freeUsed = resp.freeUsed,
                freeQueryLimit = resp.freeLimit,
                planTypeStr = resp.planType,
                monthlyExpireAt = quota.monthlyExpireAt,
                serverVersion = quota.serverVersion + 1
            )
            return@withContext true
        } catch (e: com.lottery.history.network.ApiClient.ApiException) {
            if (e.code == 403) {
                // 配额不足，从异常中取出 freeUsed/freeLimit 更新本地状态
                android.util.Log.w("QuotaRepository", "quota exhausted: ${e.message}")
                if (e.freeUsed != null || e.freeLimit != null) {
                    quotaDao.update(
                        quota.copy(
                            freeUsed = e.freeUsed ?: quota.freeUsed,
                            freeQueryLimit = e.freeLimit ?: quota.freeQueryLimit,
                            freeQueryDate = (now / 86400000).toInt(),
                            updatedAt = now
                        )
                    )
                }
                return@withContext false
            }
            // 其他错误走本地兜底
            android.util.Log.w("QuotaRepository", "server consume failed, fallback to local: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.w("QuotaRepository", "server consume failed, fallback to local: ${e.message}")
        }

        // 本地兜底：免费用户扣减
        val today = (now / 86400000).toInt()
        val todayUsed = if (quota.freeQueryDate == today) quota.freeUsed else 0
        if (todayUsed >= quota.freeQueryLimit) return@withContext false

        quotaDao.update(
            quota.copy(
                freeUsed = todayUsed + 1,
                freeQueryDate = today,
                localVersion = quota.localVersion + 1,
                updatedAt = now
            )
        )
        pendingDao.insert(
            PendingSyncEntity(
                userPhone = phone,
                actionType = SyncAction.QUERY_CONSUME,
                payload = """{"consumedAt":$now}""",
                clientOpId = clientOpId,
                status = SyncStatus.PENDING,
                createdAt = now
            )
        )
        triggerSync()
        true
    }

    /** 改密入队 */
    suspend fun enqueuePasswordChange(phone: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        pendingDao.insert(
            PendingSyncEntity(
                userPhone = phone,
                actionType = SyncAction.PASSWORD_CHANGE,
                payload = """{"changedAt":$now}""",
                clientOpId = UUID.randomUUID().toString(),
                status = SyncStatus.PENDING,
                createdAt = now
            )
        )
        triggerSync()
    }

    /** 服务器权威快照覆盖本地（SyncWorker 调用） */
    suspend fun applyServerSnapshot(
        phone: String,
        freeUsed: Int,
        freeQueryLimit: Int,
        planTypeStr: String,
        monthlyExpireAt: Long?,
        serverVersion: Long
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val today = (now / 86400000).toInt()
        val planType = try {
            PlanType.valueOf(planTypeStr)
        } catch (_: IllegalArgumentException) {
            PlanType.FREE
        }
        quotaDao.upsert(
            QuotaEntity(
                userPhone = phone,
                planType = planType,
                freeUsed = freeUsed,
                freeQueryLimit = freeQueryLimit,
                freeQueryDate = today,
                monthlyExpireAt = monthlyExpireAt,
                serverVersion = serverVersion,
                localVersion = serverVersion,
                updatedAt = now
            )
        )
    }

    private fun triggerSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sync_quota",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    /** 从服务端获取免费查询次数上限，失败或离线时回退默认值 2 */
    private suspend fun fetchFreeQueryLimit(): Int = withContext(Dispatchers.IO) {
        try {
            val config = com.lottery.history.network.ApiClient.getClientConfig()
            config.freeQueryLimit.coerceAtLeast(1)
        } catch (e: Exception) {
            android.util.Log.w("QuotaRepository", "fetch config failed, fallback to 2: ${e.message}")
            2
        }
    }
}
