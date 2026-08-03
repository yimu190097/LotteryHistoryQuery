package com.lottery.history.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 计费模式：按次付费 / 月租 */
enum class PlanType { PAY_PER_USE, MONTHLY }

/**
 * 配额表：与用户 1:1，统一建模按次与月租两种计费模式。
 *
 * - 按次用户：remainingQueries 为剩余次数，monthlyExpireAt 为 null
 * - 月租用户：monthlyExpireAt 为到期时间戳，remainingQueries 不参与判断（可冗余存"本月已用"）
 *
 * 是否可查询判断：月租未到期 OR 按次次数>0。
 * 同步元数据：serverVersion 为服务器权威版本号，localVersion 为本地乐观锁版本号。
 */
@Entity(tableName = "quotas")
data class QuotaEntity(
    @PrimaryKey val userPhone: String,
    val planType: PlanType,
    /** 按次剩余次数；月租用户此字段不参与可查询判断 */
    val remainingQueries: Int,
    /** 月租到期时间戳（毫秒）；按次用户为 null */
    val monthlyExpireAt: Long?,
    /** 服务器返回的版本号，冲突解决用 */
    val serverVersion: Long,
    /** 本地乐观锁版本号，每次本地操作 +1 */
    val localVersion: Long,
    val updatedAt: Long
) {
    /** 是否可查询：月租未到期 OR 按次次数>0 */
    fun canQuery(now: Long = System.currentTimeMillis()): Boolean = when (planType) {
        PlanType.MONTHLY -> monthlyExpireAt != null && now < monthlyExpireAt
        PlanType.PAY_PER_USE -> remainingQueries > 0
    }

    /** 月租剩余可查天数；按次返回 null */
    fun remainingDays(now: Long = System.currentTimeMillis()): Long? =
        if (planType == PlanType.MONTHLY && monthlyExpireAt != null) {
            maxOf(0L, (monthlyExpireAt - now) / (24 * 60 * 60 * 1000))
        } else null
}
