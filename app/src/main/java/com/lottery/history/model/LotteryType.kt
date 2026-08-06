package com.lottery.history.model

/**
 * 彩种配置：统一描述每个彩种的号码区间、选号数量、匹配规则和解析参数。
 *
 * 【按期自动适配政策版本】：
 *   每个彩种拥有 [ruleVersions]（按生效日期降序排列），展示开奖详情时根据当期开奖日期
 *   选择对应的 [RuleVersion]——因为不同阶段奖项设立存在差异（如大乐透2026-01-31由9级改7级、
 *   双色球2026-02-01增设福运奖）。解析器也按期日期选用对应的 extraFieldCount / prizeTierPairCount
 *   做结构化解析，确保注数/金额与当期数据完全对齐。
 *
 * 规则数据均来自官方公开规则（联网核查2026-08-06）：
 *   - 双色球/七乐彩/福彩3D：中国福利彩票 cwl.gov.cn / gdfc.org.cn
 *   - 大乐透/七星彩/排列三/排列五：国家体彩 lottery.gov.cn
 *   - 快乐8：全国联网福彩规则
 *   - 数据源格式：data.17500.cn（真实开奖数据交叉验证）
 */
data class LotteryTypeConfig(
    val code: String,           // "ssq", "dlt", "3d" ...
    val displayName: String,    // "双色球"
    val url: String,            // 主数据源 URL
    val txtFallbackUrl: String, // 兜底数据源 URL

    // 前区/主号码配置
    val primaryMin: Int,
    val primaryMax: Int,
    val primaryPickCount: Int,
    val primaryLabel: String,

    // 后区/次号码配置（hasSecondary=false 时忽略）
    val secondaryMin: Int = 0,
    val secondaryMax: Int = 0,
    val secondaryPickCount: Int = 0,
    val secondaryLabel: String = "",

    val hasSecondary: Boolean = true,

    /** 规则版本列表（按生效日期降序，最新的在前） */
    val ruleVersions: List<RuleVersion>,

    // 解析参数：日期后取多少个主号码、多少个次号码
    val parsePrimaryCount: Int,
    val parseSecondaryCount: Int = 0,

    // 结果描述用词
    val primaryUnit: String,
    val secondaryUnit: String,

    val issuePattern: String,
    val issueHint: String
) {

    /**
     * 规则版本：某个生效日期起适用的奖项规则。
     *
     * @param effectiveFromDate 生效日期（"2026-01-31"），用 ISO 日期字符串比较即可
     * @param policyLabel       政策标签（展示用，如"2026年新规（7级）"）
     * @param changeNote        变更说明（简述与其他阶段的差异原因）
     * @param rules             匹配规则（含描述、奖项名、固定奖金）；同名连续奖项共享一个真实奖级对
     * @param realTiersToUse    从真实 allPrizeTiers 取前 N 个奖级对参与展示（=去重后奖级数）
     * @param prizeTierPairCount 结构化解析：号码后跳过 extraFieldCount 个额外字段，提取 N 对 (注数,金额)
     * @param extraFieldCount   号码之后、奖级数据之前的额外字段数（销售额/奖池/出球顺序等）
     */
    data class RuleVersion(
        val effectiveFromDate: String,
        val policyLabel: String,
        val changeNote: String,
        val rules: List<MatchRuleDef>,
        val realTiersToUse: Int = rules.size,
        val prizeTierPairCount: Int = realTiersToUse,
        val extraFieldCount: Int = 0
    )

    /** 匹配规则定义：命中数 + 奖项名 + 官方单注固定奖金（浮动奖填 null） */
    data class MatchRuleDef(
        val matchPrimary: Int,
        val matchSecondary: Int,
        val description: String,
        val prizeName: String,
        val fixedAmountYuan: Long? = null
    )

    // ============ 向后兼容：默认取最新版本的属性（供 Matcher 等不区分日期的逻辑使用）============
    val rules: List<MatchRuleDef> get() = ruleVersions.first().rules
    val realTiersToUse: Int get() = ruleVersions.first().realTiersToUse
    val extraFieldCount: Int get() = ruleVersions.first().extraFieldCount
    val prizeTierPairCount: Int get() = ruleVersions.first().prizeTierPairCount

    /**
     * 按开奖日期选择适用的规则版本（核心：按期自动适配政策）。
     * date 为 "yyyy-MM-dd" 格式；null 时返回最新版本。
     */
    fun rulesForDate(date: String?): RuleVersion {
        if (date.isNullOrEmpty()) return ruleVersions.first()
        return ruleVersions.firstOrNull { date >= it.effectiveFromDate }
            ?: ruleVersions.last()
    }
}

object LotteryType {

    // ==================== 双色球（2026-02-01起新增福运奖）====================
    //  福运奖：中3红球(蓝球未中)=5元，奖池≥15亿时启动特别规定，<3亿时停止
    //  数据源验证（ssq_desc.txt 2026090期 NF=31）：6出球顺序+销售额+奖池=8额外字段，7对奖级(含福运奖)
    val SSQ = LotteryTypeConfig(
        code = "ssq",
        displayName = "双色球",
        url = "http://data.17500.cn/ssq_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/ssq_desc.txt",
        primaryMin = 1, primaryMax = 33, primaryPickCount = 6,
        primaryLabel = "红球",
        secondaryMin = 1, secondaryMax = 16, secondaryPickCount = 1,
        secondaryLabel = "蓝球",
        hasSecondary = true,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                effectiveFromDate = "2026-02-01",
                policyLabel = "2026年新规（含福运奖）",
                changeNote = "2026年2月1日(第2026014期)起增设福运奖：中3红球=5元。" +
                    "奖池≥15亿时启动，<3亿时停止；停发期间3+0不中奖。",
                realTiersToUse = 7,
                prizeTierPairCount = 7,
                extraFieldCount = 8,  // 6出球顺序 + 销售额 + 奖池
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(6, 1, "6红球+1蓝球全中", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(6, 0, "中6红球，蓝球未中", "二等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 1, "中5红球+1蓝球", "三等奖", 3000),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "中5红球（蓝球未中）", "四等奖", 200),
                    LotteryTypeConfig.MatchRuleDef(4, 1, "中4红球+1蓝球", "四等奖", 200),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "中4红球（蓝球未中）", "五等奖", 10),
                    LotteryTypeConfig.MatchRuleDef(3, 1, "中3红球+1蓝球", "五等奖", 10),
                    LotteryTypeConfig.MatchRuleDef(2, 1, "中2红球+1蓝球", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(1, 1, "中1红球+1蓝球", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(0, 1, "仅中蓝球", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "中3红球（蓝球未中）", "福运奖", 5)
                )
            ),
            LotteryTypeConfig.RuleVersion(
                effectiveFromDate = "1900-01-01",
                policyLabel = "2003-2026年规则（6级，无福运奖）",
                changeNote = "2003年上市至2026年1月的6级规则，中3红球(蓝球未中)不中奖。",
                realTiersToUse = 6,
                prizeTierPairCount = 6,
                extraFieldCount = 8,
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(6, 1, "6红球+1蓝球全中", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(6, 0, "中6红球，蓝球未中", "二等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 1, "中5红球+1蓝球", "三等奖", 3000),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "中5红球（蓝球未中）", "四等奖", 200),
                    LotteryTypeConfig.MatchRuleDef(4, 1, "中4红球+1蓝球", "四等奖", 200),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "中4红球（蓝球未中）", "五等奖", 10),
                    LotteryTypeConfig.MatchRuleDef(3, 1, "中3红球+1蓝球", "五等奖", 10),
                    LotteryTypeConfig.MatchRuleDef(2, 1, "中2红球+1蓝球", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(1, 1, "中1红球+1蓝球", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(0, 1, "仅中蓝球", "六等奖", 5)
                )
            )
        ),
        parsePrimaryCount = 6,
        parseSecondaryCount = 1,
        primaryUnit = "个红球",
        secondaryUnit = "个蓝球",
        issuePattern = "7 位数字（例 26090）",
        issueHint = "例如：26090"
    )

    // ==================== 超级大乐透（2026-01-31起9级→7级）====================
    //  新规：13个中奖条件不变，9级合并为7级；一二等奖浮动(总额各封顶1亿)
    //  奖池<8亿：三5000/四300/五150/六15/七5；奖池≥8亿：三6666/四380/五200/六18/七7
    //  数据源验证（dlt2_desc.txt 26088期 NF=38）：销售额+奖池=2额外字段，9基本对(末2对为0占位)
    val DLT = LotteryTypeConfig(
        code = "dlt",
        displayName = "大乐透",
        url = "http://data.17500.cn/dlt2_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/dlt2_desc.txt",
        primaryMin = 1, primaryMax = 35, primaryPickCount = 5,
        primaryLabel = "前区",
        secondaryMin = 1, secondaryMax = 12, secondaryPickCount = 2,
        secondaryLabel = "后区",
        hasSecondary = true,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                effectiveFromDate = "2026-01-31",
                policyLabel = "2026年新规（7级）",
                changeNote = "2026年1月31日(第26014期)起9级合并为7级，13个中奖条件不变。" +
                    "5+0与4+2合并为三等奖，4+0与3+2合并为五等奖。" +
                    "奖池≥8亿时三~七等奖上浮(6666/380/200/18/7元)。",
                realTiersToUse = 7,
                prizeTierPairCount = 7,  // 只取7个真实奖级对(跳过末尾0占位)
                extraFieldCount = 2,     // 销售额 + 奖池
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(5, 2, "5前区+2后区全中", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 1, "中5前区+1后区", "二等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "中5前区（后区未中）", "三等奖", 5000),
                    LotteryTypeConfig.MatchRuleDef(4, 2, "中4前区+2后区", "三等奖", 5000),
                    LotteryTypeConfig.MatchRuleDef(4, 1, "中4前区+1后区", "四等奖", 300),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "中4前区（后区未中）", "五等奖", 150),
                    LotteryTypeConfig.MatchRuleDef(3, 2, "中3前区+2后区", "五等奖", 150),
                    LotteryTypeConfig.MatchRuleDef(3, 1, "中3前区+1后区", "六等奖", 15),
                    LotteryTypeConfig.MatchRuleDef(2, 2, "中2前区+2后区", "六等奖", 15),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "中3前区（后区未中）", "七等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(2, 1, "中2前区+1后区", "七等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(1, 2, "中1前区+2后区", "七等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(0, 2, "仅中2后区", "七等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(2, 0, "中2前区（后区未中）", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(1, 1, "中1前区+1后区", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(1, 0, "仅中1前区", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(0, 1, "仅中1后区", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(0, 0, "未命中", "未中奖", null)
                )
            ),
            LotteryTypeConfig.RuleVersion(
                effectiveFromDate = "1900-01-01",
                policyLabel = "2007-2026年规则（9级）",
                changeNote = "2007-2026年1月执行的9级规则，奖级更多但中小奖金额较低。",
                realTiersToUse = 9,
                prizeTierPairCount = 9,
                extraFieldCount = 2,
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(5, 2, "5前区+2后区全中", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 1, "中5前区+1后区", "二等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "中5前区（后区未中）", "三等奖", 10000),
                    LotteryTypeConfig.MatchRuleDef(4, 2, "中4前区+2后区", "四等奖", 3000),
                    LotteryTypeConfig.MatchRuleDef(4, 1, "中4前区+1后区", "五等奖", 300),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "中4前区（后区未中）", "六等奖", 200),
                    LotteryTypeConfig.MatchRuleDef(3, 2, "中3前区+2后区", "六等奖", 200),
                    LotteryTypeConfig.MatchRuleDef(3, 1, "中3前区+1后区", "七等奖", 100),
                    LotteryTypeConfig.MatchRuleDef(2, 2, "中2前区+2后区", "七等奖", 100),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "中3前区（后区未中）", "八等奖", 15),
                    LotteryTypeConfig.MatchRuleDef(2, 1, "中2前区+1后区", "八等奖", 15),
                    LotteryTypeConfig.MatchRuleDef(1, 2, "中1前区+2后区", "八等奖", 15),
                    LotteryTypeConfig.MatchRuleDef(0, 2, "仅中2后区", "八等奖", 15),
                    LotteryTypeConfig.MatchRuleDef(2, 0, "中2前区（后区未中）", "九等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(1, 1, "中1前区+1后区", "九等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(1, 0, "仅中1前区", "九等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(0, 1, "仅中1后区", "九等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(0, 0, "未命中", "未中奖", null)
                )
            )
        ),
        parsePrimaryCount = 5,
        parseSecondaryCount = 2,
        primaryUnit = "个前区",
        secondaryUnit = "个后区",
        issuePattern = "7 位数字（例 26088）",
        issueHint = "例如：26088"
    )

    // ==================== 福彩3D（直选1040/组选3=346/组选6=173）====================
    //  数据源验证（3d_desc.txt 2026208期 NF=17）：试机号等6额外字段，3对奖级
    val FC3D = LotteryTypeConfig(
        code = "3d",
        displayName = "福彩3D",
        url = "http://data.17500.cn/3d_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/3d_desc.txt",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 3,
        primaryLabel = "号码",
        hasSecondary = false,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                effectiveFromDate = "1900-01-01",
                policyLabel = "现行规则",
                changeNote = "直选/组选3/组选6三档固定奖，规则长期稳定。",
                realTiersToUse = 3,
                prizeTierPairCount = 3,
                extraFieldCount = 6,  // 试机号3 + 2未知 + 销售额
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(3, 0, "3位数字和位置全对（直选）", "直选奖", 1040),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "3位相同数字，对2位位置（组选3）", "组选3", 346),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "3位数字全不同，任意顺序（组选6）", "组选6", 173),
                    LotteryTypeConfig.MatchRuleDef(2, 0, "仅命中2个位置数字", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(1, 0, "仅命中1个位置数字", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(0, 0, "未命中任何位置", "未中奖", null)
                )
            )
        ),
        parsePrimaryCount = 3,
        parseSecondaryCount = 0,
        primaryUnit = "个号码",
        secondaryUnit = "",
        issuePattern = "7 位数字（例 2026208）",
        issueHint = "例如：2026208"
    )

    // ==================== 七乐彩（7级：一~三等浮动，四~七等固定）====================
    //  数据源验证（7lc_desc.txt 2026089期 NF=26）：销售额+奖池=2额外字段，7对奖级
    val QLC = LotteryTypeConfig(
        code = "7lc",
        displayName = "七乐彩",
        url = "http://data.17500.cn/7lc_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/7lc_desc.txt",
        primaryMin = 1, primaryMax = 30, primaryPickCount = 7,
        primaryLabel = "基本号",
        secondaryMin = 1, secondaryMax = 30, secondaryPickCount = 1,
        secondaryLabel = "特别号",
        hasSecondary = true,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                effectiveFromDate = "1900-01-01",
                policyLabel = "现行规则",
                changeNote = "一~三等奖浮动，四~七等奖固定（200/50/10/5元）。",
                realTiersToUse = 7,
                prizeTierPairCount = 7,
                extraFieldCount = 2,  // 销售额 + 奖池
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(7, 0, "7个基本号全中", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(6, 1, "中6基本号+特别号", "二等奖", null),
                    LotteryTypeConfig.MatchRuleDef(6, 0, "中6个基本号（特别号未中）", "三等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 1, "中5基本号+特别号", "四等奖", 200),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "中5个基本号（特别号未中）", "五等奖", 50),
                    LotteryTypeConfig.MatchRuleDef(4, 1, "中4基本号+特别号", "六等奖", 10),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "中4个基本号（特别号未中）", "七等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(3, 1, "中3基本号+特别号", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "仅中3个基本号", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(2, 1, "中2基本号+特别号", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(1, 1, "中1基本号+特别号", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(0, 1, "仅中特别号", "未中奖", null)
                )
            )
        ),
        parsePrimaryCount = 7,
        parseSecondaryCount = 1,
        primaryUnit = "个基本号",
        secondaryUnit = "个特别号",
        issuePattern = "7 位数字（例 2026089）",
        issueHint = "例如：2026089"
    )

    // ==================== 排列三（直选1040/组选3=346/组选6=173）====================
    //  数据源验证（pl3_desc.txt 2026208期 NF=12）：销售额1额外字段，3对奖级
    val P3 = LotteryTypeConfig(
        code = "p3",
        displayName = "排列三",
        url = "http://data.17500.cn/pl3_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/pl3_desc.txt",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 3,
        primaryLabel = "号码",
        hasSecondary = false,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                effectiveFromDate = "1900-01-01",
                policyLabel = "现行规则",
                changeNote = "直选/组选3/组选6三档固定奖，规则长期稳定。",
                realTiersToUse = 3,
                prizeTierPairCount = 3,
                extraFieldCount = 1,  // 销售额
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(3, 0, "3位数字和位置全对（直选）", "直选奖", 1040),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "3位相同数字，对2位位置（组选3）", "组选3", 346),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "3位数字全不同，任意顺序（组选6）", "组选6", 173),
                    LotteryTypeConfig.MatchRuleDef(2, 0, "仅命中2个位置数字", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(1, 0, "仅命中1个位置数字", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(0, 0, "未命中任何位置", "未中奖", null)
                )
            )
        ),
        parsePrimaryCount = 3,
        parseSecondaryCount = 0,
        primaryUnit = "个号码",
        secondaryUnit = "",
        issuePattern = "7 位数字（例 2026208）",
        issueHint = "例如：2026208"
    )

    // ==================== 排列五（仅一等奖固定10万）====================
    //  数据源验证（pl5_desc.txt 2026208期 NF=10）：销售额1额外字段，1对奖级
    val P5 = LotteryTypeConfig(
        code = "p5",
        displayName = "排列五",
        url = "http://data.17500.cn/pl5_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/pl5_desc.txt",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 5,
        primaryLabel = "号码",
        hasSecondary = false,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                effectiveFromDate = "1900-01-01",
                policyLabel = "现行规则",
                changeNote = "5位数字和位置全对即中10万元，规则长期稳定。",
                realTiersToUse = 1,
                prizeTierPairCount = 1,
                extraFieldCount = 1,  // 销售额
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(5, 0, "5位数字和位置全对", "一等奖", 100000),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "仅中前4位", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "仅中前3位", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(2, 0, "仅中2个位置数字", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(1, 0, "仅中1个位置数字", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(0, 0, "未命中任何位置", "未中奖", null)
                )
            )
        ),
        parsePrimaryCount = 5,
        parseSecondaryCount = 0,
        primaryUnit = "个号码",
        secondaryUnit = "",
        issuePattern = "7 位数字（例 2026208）",
        issueHint = "例如：2026208"
    )

    // ==================== 七星彩（6级：一二等浮动，三~六等固定）====================
    //  数据源验证（7xc_desc.txt 26089期 NF=23）：销售额+奖池=2额外字段，6对奖级
    val QXC = LotteryTypeConfig(
        code = "7xc",
        displayName = "七星彩",
        url = "http://data.17500.cn/7xc_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/7xc_desc.txt",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 6,
        secondaryMin = 0, secondaryMax = 14, secondaryPickCount = 1,
        primaryLabel = "前6位",
        secondaryLabel = "后1位",
        hasSecondary = true,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                effectiveFromDate = "1900-01-01",
                policyLabel = "现行规则",
                changeNote = "前6位(0-9)+后1位(0-14)，一二等浮动，三~六等固定。",
                realTiersToUse = 6,
                prizeTierPairCount = 6,
                extraFieldCount = 2,  // 销售额 + 奖池
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(6, 1, "前6位全对 + 后1位全对", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(6, 0, "前6位全对，后1位未中", "二等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 1, "前6位中5位 + 后1位全对", "三等奖", 3000),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "前6位中5位（后1位未中）", "四等奖", 500),
                    LotteryTypeConfig.MatchRuleDef(4, 1, "前6位中4位 + 后1位全对", "四等奖", 500),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "前6位中4位（后1位未中）", "五等奖", 30),
                    LotteryTypeConfig.MatchRuleDef(3, 1, "前6位中3位 + 后1位全对", "五等奖", 30),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "前6位中3位（后1位未中）", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(2, 1, "前6位中2位 + 后1位全对", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(1, 1, "前6位中1位 + 后1位全对", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(0, 1, "仅后1位全对", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(2, 0, "仅前6位中2位", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(1, 0, "仅前6位中1位", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(0, 0, "全部未中", "未中奖", null)
                )
            )
        ),
        parsePrimaryCount = 6,
        parseSecondaryCount = 1,
        primaryUnit = "位前区",
        secondaryUnit = "位后区",
        issuePattern = "6 位数字（例 26089）",
        issueHint = "例如：26089"
    )

    // ==================== 福彩快乐8（选十玩法7档奖级）====================
    //  数据源验证（kl8_desc.txt 2026208期 NF=102）：20号码+销售额+奖池=2额外字段，选十7对在前
    //  选十中八=720元（数据交叉验证，非800）
    val KL8 = LotteryTypeConfig(
        code = "kl8",
        displayName = "快乐8",
        url = "http://data.17500.cn/kl8_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/kl8_desc.txt",
        primaryMin = 1, primaryMax = 80, primaryPickCount = 10,
        primaryLabel = "号码",
        hasSecondary = false,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                effectiveFromDate = "1900-01-01",
                policyLabel = "现行规则",
                changeNote = "选十玩法7档奖级：中十/中九/中八(720)/中七/中六/中五/全不中。" +
                    "其余命中(1-4个)不中奖。",
                realTiersToUse = 7,
                prizeTierPairCount = 7,  // 只取选十玩法前7对
                extraFieldCount = 2,     // 销售额 + 奖池
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(10, 0, "选10中10全对", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(9, 0, "选10中9", "二等奖", 8000),
                    LotteryTypeConfig.MatchRuleDef(8, 0, "选10中8", "三等奖", 720),
                    LotteryTypeConfig.MatchRuleDef(7, 0, "选10中7", "四等奖", 80),
                    LotteryTypeConfig.MatchRuleDef(6, 0, "选10中6", "五等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "选10中5", "六等奖", 3),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "选10中4", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "选10中3", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(2, 0, "选10中2", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(1, 0, "选10中1", "未中奖", null),
                    LotteryTypeConfig.MatchRuleDef(0, 0, "选10中0（幸运奖）", "七等奖", 2)
                )
            )
        ),
        parsePrimaryCount = 20,
        parseSecondaryCount = 0,
        primaryUnit = "个号码",
        secondaryUnit = "",
        issuePattern = "7 位数字（例 2026208）",
        issueHint = "例如：2026208"
    )

    /** 全部彩种，按展示顺序排列 */
    val ALL: List<LotteryTypeConfig> = listOf(SSQ, DLT, FC3D, QLC, P3, P5, QXC, KL8)

    /** 按 code 查找配置（兼容老 code） */
    fun byCode(code: String): LotteryTypeConfig =
        if (code == "t22x5" || code == "22x5" || code == "sh15x5" || code == "15x5") KL8
        else ALL.firstOrNull { it.code == code } ?: SSQ
}
