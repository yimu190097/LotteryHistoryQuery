package com.lottery.history.model

/**
 * 彩种配置：统一描述每个彩种的号码区间、选号数量、匹配规则和解析参数。
 * 驱动 Fragment / Matcher / Repository / DataManager 的通用逻辑。
 */
data class LotteryTypeConfig(
    val code: String,           // "ssq", "dlt", "3d" ...
    val displayName: String,    // "双色球"
    val url: String,            // 主数据源 URL：17500.cn XLS 地址
    val txtFallbackUrl: String, // 兜底数据源 URL：原 TXT 地址（XLS 失败时回退）

    // 前区/主号码配置
    val primaryMin: Int,        // 号码最小值
    val primaryMax: Int,        // 号码最大值
    val primaryPickCount: Int,  // 开奖号码个数
    val primaryLabel: String,   // "红球" / "前区" / "号码"

    // 后区/次号码配置（hasSecondary=false 时忽略）
    val secondaryMin: Int = 0,
    val secondaryMax: Int = 0,
    val secondaryPickCount: Int = 0,
    val secondaryLabel: String = "",

    val hasSecondary: Boolean = true,

    // 匹配规则（含描述和奖项名）
    val rules: List<MatchRuleDef>,

    // 解析参数：日期后取多少个主号码、多少个次号码
    val parsePrimaryCount: Int,
    val parseSecondaryCount: Int = 0,

    // 结果描述用词
    val primaryUnit: String,    // "个红球" / "个前区" / "个号码"
    val secondaryUnit: String   // "个蓝球" / "个后区" / "个特别号"
) {
    /** 匹配规则定义：命中数 + 奖项名 */
    data class MatchRuleDef(
        val matchPrimary: Int,
        val matchSecondary: Int,
        val description: String,  // "开出6个红球和1个蓝球"
        val prizeName: String     // "一等奖"
    )
}

object LotteryType {

    // ==================== 双色球 ====================
    val SSQ = LotteryTypeConfig(
        code = "ssq",
        displayName = "双色球",
        url = "http://www.17500.cn/getData/ssq.xls",
        txtFallbackUrl = "http://www.17500.cn/getData/ssq.TXT",
        primaryMin = 1, primaryMax = 33, primaryPickCount = 6,
        primaryLabel = "红球",
        secondaryMin = 1, secondaryMax = 16, secondaryPickCount = 1,
        secondaryLabel = "蓝球",
        hasSecondary = true,
        rules = listOf(
            LotteryTypeConfig.MatchRuleDef(6, 1, "开出6个红球和1个蓝球", "一等奖"),
            LotteryTypeConfig.MatchRuleDef(6, 0, "开出6个红球和0个蓝球", "二等奖"),
            LotteryTypeConfig.MatchRuleDef(5, 1, "开出5个红球和1个蓝球", "三等奖"),
            LotteryTypeConfig.MatchRuleDef(5, 0, "开出5个红球和0个蓝球", "四等奖"),
            LotteryTypeConfig.MatchRuleDef(4, 1, "开出4个红球和1个蓝球", "四等奖"),
            LotteryTypeConfig.MatchRuleDef(4, 0, "开出4个红球和0个蓝球", "五等奖"),
            LotteryTypeConfig.MatchRuleDef(3, 1, "开出3个红球和1个蓝球", "五等奖"),
            LotteryTypeConfig.MatchRuleDef(2, 1, "开出2个红球和1个蓝球", "六等奖"),
            LotteryTypeConfig.MatchRuleDef(1, 1, "开出1个红球和1个蓝球", "六等奖"),
            LotteryTypeConfig.MatchRuleDef(0, 1, "开出0个红球和1个蓝球", "六等奖")
        ),
        parsePrimaryCount = 6,
        parseSecondaryCount = 1,
        primaryUnit = "个红球",
        secondaryUnit = "个蓝球"
    )

    // ==================== 超级大乐透 ====================
    val DLT = LotteryTypeConfig(
        code = "dlt",
        displayName = "大乐透",
        url = "http://www.17500.cn/getData/dlt.xls",
        txtFallbackUrl = "http://www.17500.cn/getData/dlt.TXT",
        primaryMin = 1, primaryMax = 35, primaryPickCount = 5,
        primaryLabel = "前区",
        secondaryMin = 1, secondaryMax = 12, secondaryPickCount = 2,
        secondaryLabel = "后区",
        hasSecondary = true,
        rules = listOf(
            LotteryTypeConfig.MatchRuleDef(5, 2, "开出5个前区和2个后区", "一等奖"),
            LotteryTypeConfig.MatchRuleDef(5, 1, "开出5个前区和1个后区", "二等奖"),
            LotteryTypeConfig.MatchRuleDef(5, 0, "开出5个前区和0个后区", "三等奖"),
            LotteryTypeConfig.MatchRuleDef(4, 2, "开出4个前区和2个后区", "四等奖"),
            LotteryTypeConfig.MatchRuleDef(4, 1, "开出4个前区和1个后区", "五等奖"),
            LotteryTypeConfig.MatchRuleDef(3, 2, "开出3个前区和2个后区", "六等奖"),
            LotteryTypeConfig.MatchRuleDef(4, 0, "开出4个前区和0个后区", "七等奖"),
            LotteryTypeConfig.MatchRuleDef(3, 1, "开出3个前区和1个后区", "八等奖"),
            LotteryTypeConfig.MatchRuleDef(2, 2, "开出2个前区和2个后区", "八等奖"),
            LotteryTypeConfig.MatchRuleDef(1, 2, "开出1个前区和2个后区", "八等奖"),
            LotteryTypeConfig.MatchRuleDef(0, 2, "开出0个前区和2个后区", "八等奖"),
            LotteryTypeConfig.MatchRuleDef(3, 0, "开出3个前区和0个后区", "九等奖"),
            LotteryTypeConfig.MatchRuleDef(2, 1, "开出2个前区和1个后区", "九等奖"),
            LotteryTypeConfig.MatchRuleDef(2, 0, "开出2个前区和0个后区", "九等奖"),
            LotteryTypeConfig.MatchRuleDef(1, 1, "开出1个前区和1个后区", "九等奖"),
            LotteryTypeConfig.MatchRuleDef(1, 0, "开出1个前区和0个后区", "九等奖"),
            LotteryTypeConfig.MatchRuleDef(0, 1, "开出0个前区和1个后区", "九等奖"),
            LotteryTypeConfig.MatchRuleDef(0, 0, "开出0个前区和0个后区", "无奖项")
        ),
        parsePrimaryCount = 5,
        parseSecondaryCount = 2,
        primaryUnit = "个前区",
        secondaryUnit = "个后区"
    )

    // ==================== 福彩3D ====================
    val FC3D = LotteryTypeConfig(
        code = "3d",
        displayName = "福彩3D",
        url = "http://www.17500.cn/getData/3d.xls",
        txtFallbackUrl = "http://www.17500.cn/getData/3d.TXT",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 3,
        primaryLabel = "号码",
        hasSecondary = false,
        rules = listOf(
            LotteryTypeConfig.MatchRuleDef(3, 0, "3个号码全部命中", "直选/组选"),
            LotteryTypeConfig.MatchRuleDef(2, 0, "命中2个号码", "组选3"),
            LotteryTypeConfig.MatchRuleDef(1, 0, "命中1个号码", "未中奖"),
            LotteryTypeConfig.MatchRuleDef(0, 0, "全部未命中", "未中奖")
        ),
        parsePrimaryCount = 3,
        parseSecondaryCount = 0,
        primaryUnit = "个号码",
        secondaryUnit = ""
    )

    // ==================== 七乐彩 ====================
    val QLC = LotteryTypeConfig(
        code = "7lc",
        displayName = "七乐彩",
        url = "http://www.17500.cn/getData/7lc.xls",
        txtFallbackUrl = "http://www.17500.cn/getData/7lc.TXT",
        primaryMin = 1, primaryMax = 30, primaryPickCount = 7,
        primaryLabel = "基本号",
        secondaryMin = 1, secondaryMax = 30, secondaryPickCount = 1,
        secondaryLabel = "特别号",
        hasSecondary = true,
        rules = listOf(
            LotteryTypeConfig.MatchRuleDef(7, 1, "开出7个基本号和1个特别号", "一等奖"),
            LotteryTypeConfig.MatchRuleDef(7, 0, "开出7个基本号和0个特别号", "二等奖"),
            LotteryTypeConfig.MatchRuleDef(6, 1, "开出6个基本号和1个特别号", "三等奖"),
            LotteryTypeConfig.MatchRuleDef(6, 0, "开出6个基本号和0个特别号", "四等奖"),
            LotteryTypeConfig.MatchRuleDef(5, 1, "开出5个基本号和1个特别号", "四等奖"),
            LotteryTypeConfig.MatchRuleDef(5, 0, "开出5个基本号和0个特别号", "五等奖"),
            LotteryTypeConfig.MatchRuleDef(4, 1, "开出4个基本号和1个特别号", "五等奖"),
            LotteryTypeConfig.MatchRuleDef(4, 0, "开出4个基本号和0个特别号", "六等奖"),
            LotteryTypeConfig.MatchRuleDef(3, 1, "开出3个基本号和1个特别号", "六等奖"),
            LotteryTypeConfig.MatchRuleDef(3, 0, "开出3个基本号和0个特别号", "七等奖"),
            LotteryTypeConfig.MatchRuleDef(2, 1, "开出2个基本号和1个特别号", "七等奖"),
            LotteryTypeConfig.MatchRuleDef(1, 1, "开出1个基本号和1个特别号", "七等奖"),
            LotteryTypeConfig.MatchRuleDef(0, 1, "开出0个基本号和1个特别号", "七等奖")
        ),
        parsePrimaryCount = 7,
        parseSecondaryCount = 1,
        primaryUnit = "个基本号",
        secondaryUnit = "个特别号"
    )

    // ==================== 排列三 ====================
    val P3 = LotteryTypeConfig(
        code = "p3",
        displayName = "排列三",
        url = "http://www.17500.cn/getData/p3.xls",
        txtFallbackUrl = "http://www.17500.cn/getData/p3.TXT",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 3,
        primaryLabel = "号码",
        hasSecondary = false,
        rules = listOf(
            LotteryTypeConfig.MatchRuleDef(3, 0, "3个号码全部命中", "直选/组选"),
            LotteryTypeConfig.MatchRuleDef(2, 0, "命中2个号码", "组选3"),
            LotteryTypeConfig.MatchRuleDef(1, 0, "命中1个号码", "未中奖"),
            LotteryTypeConfig.MatchRuleDef(0, 0, "全部未命中", "未中奖")
        ),
        parsePrimaryCount = 3,
        parseSecondaryCount = 0,
        primaryUnit = "个号码",
        secondaryUnit = ""
    )

    // ==================== 排列五 ====================
    val P5 = LotteryTypeConfig(
        code = "p5",
        displayName = "排列五",
        url = "http://www.17500.cn/getData/p5.xls",
        txtFallbackUrl = "http://www.17500.cn/getData/p5.TXT",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 5,
        primaryLabel = "号码",
        hasSecondary = false,
        rules = listOf(
            LotteryTypeConfig.MatchRuleDef(5, 0, "5个号码全部命中", "一等奖"),
            LotteryTypeConfig.MatchRuleDef(4, 0, "命中4个号码", "二等奖"),
            LotteryTypeConfig.MatchRuleDef(3, 0, "命中3个号码", "三等奖"),
            LotteryTypeConfig.MatchRuleDef(2, 0, "命中2个号码", "未中奖"),
            LotteryTypeConfig.MatchRuleDef(1, 0, "命中1个号码", "未中奖"),
            LotteryTypeConfig.MatchRuleDef(0, 0, "全部未命中", "未中奖")
        ),
        parsePrimaryCount = 5,
        parseSecondaryCount = 0,
        primaryUnit = "个号码",
        secondaryUnit = ""
    )

    // ==================== 七星彩 ====================
    val QXC = LotteryTypeConfig(
        code = "7xc",
        displayName = "七星彩",
        url = "http://www.17500.cn/getData/7xc.xls",
        txtFallbackUrl = "http://www.17500.cn/getData/7xc.TXT",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 7,
        primaryLabel = "号码",
        hasSecondary = false,
        rules = listOf(
            LotteryTypeConfig.MatchRuleDef(7, 0, "7个号码全部命中", "一等奖"),
            LotteryTypeConfig.MatchRuleDef(6, 0, "命中6个号码", "二等奖"),
            LotteryTypeConfig.MatchRuleDef(5, 0, "命中5个号码", "三等奖"),
            LotteryTypeConfig.MatchRuleDef(4, 0, "命中4个号码", "四等奖"),
            LotteryTypeConfig.MatchRuleDef(3, 0, "命中3个号码", "五等奖"),
            LotteryTypeConfig.MatchRuleDef(2, 0, "命中2个号码", "六等奖"),
            LotteryTypeConfig.MatchRuleDef(1, 0, "命中1个号码", "未中奖"),
            LotteryTypeConfig.MatchRuleDef(0, 0, "全部未命中", "未中奖")
        ),
        parsePrimaryCount = 7,
        parseSecondaryCount = 0,
        primaryUnit = "个号码",
        secondaryUnit = ""
    )

    // ==================== 体彩22选5 ====================
    val T22X5 = LotteryTypeConfig(
        code = "t22x5",
        displayName = "22选5",
        url = "http://www.17500.cn/getData/t22x5.xls",
        txtFallbackUrl = "http://www.17500.cn/getData/t22x5.TXT",
        primaryMin = 1, primaryMax = 22, primaryPickCount = 5,
        primaryLabel = "号码",
        hasSecondary = false,
        rules = listOf(
            LotteryTypeConfig.MatchRuleDef(5, 0, "5个号码全部命中", "一等奖"),
            LotteryTypeConfig.MatchRuleDef(4, 0, "命中4个号码", "二等奖"),
            LotteryTypeConfig.MatchRuleDef(3, 0, "命中3个号码", "三等奖"),
            LotteryTypeConfig.MatchRuleDef(2, 0, "命中2个号码", "未中奖"),
            LotteryTypeConfig.MatchRuleDef(1, 0, "命中1个号码", "未中奖"),
            LotteryTypeConfig.MatchRuleDef(0, 0, "全部未命中", "未中奖")
        ),
        parsePrimaryCount = 5,
        parseSecondaryCount = 0,
        primaryUnit = "个号码",
        secondaryUnit = ""
    )

    /** 全部彩种，按展示顺序排列 */
    val ALL: List<LotteryTypeConfig> = listOf(SSQ, DLT, FC3D, QLC, P3, P5, QXC, T22X5)

    /** 按 code 查找配置 */
    fun byCode(code: String): LotteryTypeConfig =
        ALL.first { it.code == code }
}
