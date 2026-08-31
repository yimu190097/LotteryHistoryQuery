package com.lottery.history.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 同步动作类型 */
enum class SyncAction { QUERY_CONSUME, PASSWORD_CHANGE, PLAN_UPDATE }

/** 同步状态机：待同步 / 已同步 / 失败 */
enum class SyncStatus { PENDING, SYNCED, FAILED }

/**
 * 离线同步队列：记录所有待上报到服务器的本地操作。
 *
 * - 离线查询扣减、改密、套餐变更均入队，联网后由 SyncWorker 推送。
 * - clientOpId 为幂等键，防止 WorkManager 重试导致服务器重复入账。
 */
@Entity(
    tableName = "pending_sync",
    indices = [androidx.room.Index(value = ["userPhone"])]
)
data class PendingSyncEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userPhone: String,
    val actionType: SyncAction,
    /** JSON 载荷：查询参数、改密时间戳等 */
    val payload: String,
    /** 幂等键，防止重复入账 */
    val clientOpId: String,
    val status: SyncStatus,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val syncedAt: Long? = null
)
