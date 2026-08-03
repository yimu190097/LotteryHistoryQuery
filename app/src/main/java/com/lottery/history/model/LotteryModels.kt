package com.lottery.history.model

data class LotteryDraw(
    val issue: String,
    val primaryNumbers: List<Int>,
    val secondaryNumbers: List<Int>,
    val date: String? = null,
    // 当期一等奖/二等奖中奖注数与单注奖金（来自 17500.cn xls 表格真实数据，可能为空）
    val firstPrizeCount: Int? = null,
    val firstPrizeAmount: Long? = null,
    val secondPrizeCount: Int? = null,
    val secondPrizeAmount: Long? = null
)

data class QueryResultItem(
    val matchPrimary: Int,
    val matchSecondary: Int,
    val prizeName: String,
    val count: Int,
    val matches: List<LotteryDraw>
)
