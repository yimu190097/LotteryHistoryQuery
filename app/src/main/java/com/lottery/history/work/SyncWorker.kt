package com.lottery.history.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lottery.history.data.QuotaRepository
import com.lottery.history.db.LotteryDatabase
import com.lottery.history.db.SyncStatus

/**
 * 配额同步 Worker：联网后扫描 pending_sync 队列，推送到服务器。
 *
 * 当前阶段（服务器未接入）：直接标记为 SYNCED（本地已扣减生效），保留日志备查。
 * 后期阶段：在此处调用服务器 API 推送操作日志，成功后用服务器返回的权威快照覆盖本地配额。
 *
 * 约束：仅联网执行；指数退避由 WorkManager 自动处理。
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val dao = LotteryDatabase.get(applicationContext).pendingSyncDao()
            val pending = dao.getByStatus(SyncStatus.PENDING)

            // TODO(服务器接入): 此处循环调用服务器 API 推送每条 pending，
            //   成功后用服务器返回的权威 Quota 快照调用 QuotaRepository.applyServerSnapshot()。
            //   服务器用 clientOpId 幂等去重，防止重试导致重复扣减。
            val now = System.currentTimeMillis()
            pending.forEach { item ->
                // 当前阶段：服务器未接入，直接标记已同步（本地扣减已生效）
                dao.updateStatus(
                    id = item.id,
                    status = SyncStatus.SYNCED,
                    syncedAt = now,
                    retryCount = item.retryCount,
                    error = null
                )
            }
            Result.success()
        } catch (e: Exception) {
            // 网络/服务器异常时重试，WorkManager 指数退避
            Result.retry()
        }
    }
}
