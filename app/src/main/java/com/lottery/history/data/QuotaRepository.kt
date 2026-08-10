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
 * 配额 Repository：本地优先（离线可查可扣），操作入队待同步。
 *
 * - 读：直接观察本地 quotas 表 Flow，UI 秒开
 * - 写（扣减）：本地扣减 + 入队 pending_sync（同一事务），离线也能完成
 * - 同步：联网后 SyncWorker 推送，服务器权威快照覆盖本地
 *
 * 服务器接入后：仅需替换 consumeOneQuery 中的"入队"为"调服务器 + 入队兜底"，
 * UI 层（观察 Flow）零改动。
 */
class QuotaRepository(private val context: Context) {

    private val quotaDao: QuotaDao by lazy { LotteryDatabase.get(context).quotaDao() }
    private val pendingDao: PendingSyncDao by lazy { LotteryDatabase.get(context).pendingSyncDao() }

    /** 观察当前用户配额，UI 订阅 */
    fun observe(phone: String): Flow<QuotaEntity?> = quotaDao.observeByUser(phone)

    /** 新用户初始化配额：按次用户，赠送10次体验 */
    suspend fun initForNewUser(phone: String) = withContext(Dispatchers.IO) {
        if (quotaDao.getByUser(phone) == null) {
            val now = System.currentTimeMillis()
            quotaDao.upsert(
                QuotaEntity(
                    userPhone = phone,
                    planType = PlanType.PAY_PER_USE,
                    remainingQueries = 10,
                    monthlyExpireAt = null,
                    serverVersion = 0,
                    localVersion = 0,
                    updatedAt = now
                )
            )
        }
    }

    /** 管理员初始化配额：月租用户，有效期一年 */
    suspend fun initForAdmin(phone: String) = withContext(Dispatchers.IO) {
        if (quotaDao.getByUser(phone) == null) {
            val now = System.currentTimeMillis()
            val oneYear = 365L * 24 * 60 * 60 * 1000
            quotaDao.upsert(
                QuotaEntity(
                    userPhone = phone,
                    planType = PlanType.MONTHLY,
                    remainingQueries = 99999,
                    monthlyExpireAt = now + oneYear,
                    serverVersion = 0,
                    localVersion = 0,
                    updatedAt = now
                )
            )
        }
    }

    /**
     * 消耗一次查询：本地扣减 + 入队待同步。
     * 离线时只要有次数即可扣减并记录，联网后同步。
     * @return true 扣减成功（有配额），false 无配额
     */
    suspend fun consumeOneQuery(phone: String): Boolean = withContext(Dispatchers.IO) {
        val quota = quotaDao.getByUser(phone) ?: return@withContext false
        if (!quota.canQuery()) return@withContext false

        val now = System.currentTimeMillis()
        // 月租用户不扣次数（按次才扣）
        val newRemaining = if (quota.planType == PlanType.PAY_PER_USE) {
            quota.remainingQueries - 1
        } else {
            quota.remainingQueries
        }
        quotaDao.update(
            quota.copy(
                remainingQueries = newRemaining,
                localVersion = quota.localVersion + 1,
                updatedAt = now
            )
        )
        // 入队待同步（幂等键防止重试重复扣减）
        pendingDao.insert(
            PendingSyncEntity(
                userPhone = phone,
                actionType = SyncAction.QUERY_CONSUME,
                payload = """{"consumedAt":$now}""",
                clientOpId = UUID.randomUUID().toString(),
                status = SyncStatus.PENDING,
                createdAt = now
            )
        )
        // 触发后台同步（联网则立即跑，离线则排队）
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
        remainingQueries: Int,
        monthlyExpireAt: Long?,
        planType: PlanType,
        serverVersion: Long
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        quotaDao.upsert(
            QuotaEntity(
                userPhone = phone,
                planType = planType,
                remainingQueries = remainingQueries,
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
}
