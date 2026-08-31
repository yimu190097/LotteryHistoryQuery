package com.lottery.history.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 计费模式：免费 / VIP套餐 */
enum class PlanType { FREE, MONTHLY_VIP, QUARTERLY_VIP, SEMI_ANNUAL_VIP, ANNUAL_VIP;
    companion object {
        val VIP_TYPES = setOf(MONTHLY_VIP, QUARTERLY_VIP, SEMI_ANNUAL_VIP, ANNUAL_VIP)
        fun isVip(type: PlanType): Boolean = type in VIP_TYPES
        fun label(type: PlanType): String = when (type) {
            MONTHLY_VIP -> "月VIP"
            QUARTERLY_VIP -> "季VIP"
            SEMI_ANNUAL_VIP -> "半年VIP"
            ANNUAL_VIP -> "年VIP"
            FREE -> "免费"
        }
    }
}

/**
 * 配额表：与用户 1:1。
 *
 * - VIP 用户：planType 为 VIP 类型，monthlyExpireAt 为到期时间戳，不限次数
 * - 免费用户：planType = FREE，每日 freeQueryLimit 次，通过 freeQueryDate 追踪按日重置
 *
 * 同步元数据：serverVersion 为服务器权威版本号，localVersion 为本地乐观锁版本号。
 */
@Entity(tableName = "quotas")
data class QuotaEntity(
    @PrimaryKey val userPhone: String,
    val planType: PlanType,
    /** 今日已用免费次数（免费用户）；VIP 此字段为 0 */
    val freeUsed: Int,
    /** 每日免费次数上限（服务器配置） */
    val freeQueryLimit: Int,
    /** 免费次数日期（epoch day），用于判断是否跨天重置 */
    val freeQueryDate: Int,
    /** VIP 到期时间戳（毫秒）；免费用户为 null */
    val monthlyExpireAt: Long?,
    /** 服务器返回的版本号，冲突解决用 */
    val serverVersion: Long,
    /** 本地乐观锁版本号，每次本地操作 +1 */
    val localVersion: Long,
    val updatedAt: Long
) {
    /** 是否可查询：VIP 未到期 OR 免费每日次数未用完 */
    fun canQuery(now: Long = System.currentTimeMillis()): Boolean {
        if (PlanType.isVip(planType)) {
            return monthlyExpireAt != null && now < monthlyExpireAt
        }
        // 免费用户：按日重置
        val today = (now / 86400000).toInt()
        val used = if (freeQueryDate == today) freeUsed else 0
        return used < freeQueryLimit
    }

    /** VIP 剩余天数；免费用户返回 null */
    fun remainingDays(now: Long = System.currentTimeMillis()): Long? =
        if (PlanType.isVip(planType) && monthlyExpireAt != null) {
            maxOf(0L, (monthlyExpireAt - now) / (24 * 60 * 60 * 1000))
        } else null

    /** 今日已用免费次数（跨天自动重置） */
    fun todayFreeUsed(now: Long = System.currentTimeMillis()): Int {
        val today = (now / 86400000).toInt()
        return if (freeQueryDate == today) freeUsed else 0
    }
}
