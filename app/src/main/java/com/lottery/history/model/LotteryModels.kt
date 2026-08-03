package com.lottery.history.model

data class LotteryDraw(
    val issue: String,
    val primaryNumbers: List<Int>,
    val secondaryNumbers: List<Int>,
    val date: String? = null
)

data class QueryResultItem(
    val matchPrimary: Int,
    val matchSecondary: Int,
    val prizeName: String,
    val count: Int,
    val matches: List<LotteryDraw>
)
