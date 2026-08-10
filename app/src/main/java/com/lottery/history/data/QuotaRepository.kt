package com.lottery.history.data

import android.content.Context
import com.lottery.history.db.LotteryDatabase
import com.lottery.history.db.PlanType
import com.lottery.history.db.QuotaDao
import com.lottery.history.db.QuotaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 配额 Repository：本地实现（无后端）。
 *
 * - 读：直接观察本地 quotas 表 Flow，UI 秒开
 * - 写（扣减）：本地扣减（当前无后端，纯本地生效）
 *
 * 原设计中的「离线同步队列 pending_sync + SyncWorker」因项目当前阶段
 * 不接入任何后端服务器，属于长期闲置的未实现占位，已删除。
 * 后期接入服务器时可在 consumeOneQuery 内追加网络调用，当前保留本地扣减即可。
 */
class QuotaRepository(private val context: Context) {

    private val quotaDao: QuotaDao by lazy { LotteryDatabase.get(context).quotaDao() }

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
     * 消耗一次查询：本地扣减（无后端，纯本地生效）。
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
        true
    }

    /** 改密（本地，无服务器）：保留函数签名以便 AuthRepository 调用，
     *  原 pending_sync 入队逻辑因服务器未接入已删除。 */
    suspend fun enqueuePasswordChange(@Suppress("UNUSED_PARAMETER") phone: String) {
        // 当前阶段：本地改密立即生效，无需入队同步
    }
}
