package com.lottery.history.model

/**
 * 单张单项奖级：中奖注数 + 单注奖金（元）。
 * 用于存储当期每个等级的真实数据（17500.cn 解析，按奖项顺序排序）。
 */
data class PrizeTierEntry(
    val count: Int,    // 当期该奖级中奖注数（0 表示空开）
    val amount: Long   // 单注奖金（元），0 表示空开无奖金
)

data class LotteryDraw(
    val issue: String,
    val primaryNumbers: List<Int>,
    val secondaryNumbers: List<Int>,
    val date: String? = null,
    /** 一等奖注数（便捷字段，保持向后兼容） */
    val firstPrizeCount: Int? = null,
    /** 一等奖单注金额（元）（便捷字段，保持向后兼容） */
    val firstPrizeAmount: Long? = null,
    /** 二等奖注数（便捷字段，保持向后兼容） */
    val secondPrizeCount: Int? = null,
    /** 二等奖单注金额（元）（便捷字段，保持向后兼容） */
    val secondPrizeAmount: Long? = null,
    /**
     * 当期所有奖级，从一等奖往下按顺序排列（17500.cn XLS 真实数据）。
     * 数量与规则 LotteryTypeConfig.rules 中"有金额的奖项"数量一致，可能不足时用 null 补齐。
     * 示例（双色球 5 级数据）：[一等奖(3注1000万), 二等奖(81注36.9万), 三等奖(724注3000元), 四等奖(44157注200元), 五等奖(10注643.4万), ...]
     * DrawDetailDialog 会按 rules 顺序把每个奖项名 + 该 entry 一并渲染。
     */
    val allPrizeTiers: List<PrizeTierEntry?> = emptyList()
)

data class QueryResultItem(
    val matchPrimary: Int,
    val matchSecondary: Int,
    val prizeName: String,
    val count: Int,
    val matches: List<LotteryDraw>
)
