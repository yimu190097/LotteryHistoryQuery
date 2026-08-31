package com.lottery.history.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lottery.history.data.QuotaRepository
import com.lottery.history.data.SessionStore
import com.lottery.history.db.LotteryDatabase
import com.lottery.history.db.SyncStatus
import com.lottery.history.network.ApiClient

/**
 * 配额同步 Worker：联网后扫描 pending_sync 队列，推送到服务器并拉取权威快照对账。
 *
 * 重复扣减防护：
 * - consumeOneQuery 失败兜底时使用同一 clientOpId 入队
 * - 本 Worker 用该 clientOpId 调服务器 /consume（服务器幂等去重）
 * - 再拉 /quota 权威快照覆盖本地，纠正客户端可能的本地兜底扣减
 *
 * 约束：仅联网执行；指数退避由 WorkManager 自动处理。
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionStore = SessionStore(applicationContext)
        val currentPhone = sessionStore.getLoginPhone()
        val token = sessionStore.getToken()

        // 未登录或无 token：无法同步，标记本次为成功（避免无意义重试）
        if (currentPhone == null || token.isNullOrBlank()) {
            return Result.success()
        }

        val dao = LotteryDatabase.get(applicationContext).pendingSyncDao()
        val pending = dao.getByStatus(SyncStatus.PENDING)
        if (pending.isEmpty()) return Result.success()

        val quotaRepo = QuotaRepository(applicationContext)
        var anyFailed = false

        for (item in pending) {
            // 仅同步当前登录用户的 pending；其他用户的留待对应会话处理
            if (item.userPhone != currentPhone) continue

            try {
                // 用同 clientOpId 重试服务器 /consume（幂等：服务器已扣则直接返回首次结果）
                try {
                    ApiClient.consumeQuery(
                        phone = item.userPhone,
                        count = 1,
                        clientOpId = item.clientOpId
                    )
                } catch (e: ApiClient.ApiException) {
                    // 4xx 业务错误（如配额不足/月租过期）视为已对账完成，不再重试
                    if (e.code in 400..499) {
                        android.util.Log.i("SyncWorker", "consume ${item.clientOpId} 业务终态 ${e.code}: ${e.message}")
                    } else {
                        throw e
                    }
                }

                // 拉取权威快照并覆盖本地（纠正本地兜底可能多扣的次数）
                // serverVersion 传 0：applyServerSnapshot 会把 localVersion 也同步为 0，
                // 下次 consumeOneQuery 调用时会以 quota.serverVersion + 1 递增，无累积影响。
                val q = ApiClient.getQuota()
                val planType = try {
                    com.lottery.history.db.PlanType.valueOf(q.planType)
                } catch (_: IllegalArgumentException) {
                    // 服务器返回未知 planType 时降级为按次，避免崩溃
                    com.lottery.history.db.PlanType.PAY_PER_USE
                }
                quotaRepo.applyServerSnapshot(
                    phone = item.userPhone,
                    remainingQueries = q.remainingQueries,
                    monthlyExpireAt = q.monthlyExpireAt,
                    planType = planType,
                    serverVersion = 0L
                )

                dao.updateStatus(
                    id = item.id,
                    status = SyncStatus.SYNCED,
                    syncedAt = System.currentTimeMillis(),
                    retryCount = item.retryCount,
                    error = null
                )
            } catch (e: Exception) {
                anyFailed = true
                android.util.Log.w("SyncWorker", "sync ${item.clientOpId} failed: ${e.message}")
                dao.updateStatus(
                    id = item.id,
                    status = SyncStatus.PENDING,
                    syncedAt = null,
                    retryCount = item.retryCount + 1,
                    error = e.message?.take(200)
                )
            }
        }

        // 有失败项则让 WorkManager 指数退避重试整个 Worker
        return if (anyFailed) Result.retry() else Result.success()
    }
}
