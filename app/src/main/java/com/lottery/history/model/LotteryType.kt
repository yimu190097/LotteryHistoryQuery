package com.lottery.history.model

/**
 * 彩种配置：统一描述每个彩种的号码区间、选号数量、匹配规则和解析参数。
 *
 * 【按期自动适配政策版本——核心架构】：
 *  每个彩种拥有 ruleVersions（按生效日期降序排列），每期开奖数据在**解析时**就根据当期
 *  开奖日期选择对应的 RuleVersion，并将 ruleVersionKey、actualTierCount、tierMatchStatus、
 *  jackpotAmount、salesAmount 一起写入数据库。展示时直接读取数据库里的 ruleVersionKey
 *  定位规则版本——**不依赖运行时重新按日期匹配**，避免未来规则再变更时历史数据展示错位。
 *
 * 规则数据均来自官方公开规则，且用 17500.cn 真实数据交叉验证（2026-08-06）：
 *   - 双色球2026090期：NF=31，7对奖级，T30/T31 福运奖0/5（奖池<15亿停发，占位置0/5）
 *   - 大乐透26088期：NF=38，[12-25]为基本投注7对（2/9662603→9077777/5正确），
 *     [26-38]为追加投注且仅前两等浮动（5000/300/150/15/5，奖池7.92亿<8亿未上浮正确）
 *   - 七星彩20099期：三1800/四300/五20（旧2004版）；20100期：三3000/四500/五30（新2020版）
 *   - 快乐82026208期：选十中八=720元（真实数据验证非800）
 */
data class LotteryTypeConfig(
    val code: String,
    val displayName: String,
    val url: String,
    val txtFallbackUrl: String,

    val primaryMin: Int, val primaryMax: Int, val primaryPickCount: Int, val primaryLabel: String,
    val secondaryMin: Int = 0, val secondaryMax: Int = 0, val secondaryPickCount: Int = 0,
    val secondaryLabel: String = "",
    val hasSecondary: Boolean = true,

    val ruleVersions: List<RuleVersion>,

    val parsePrimaryCount: Int, val parseSecondaryCount: Int = 0,
    val primaryUnit: String, val secondaryUnit: String,
    val issuePattern: String, val issueHint: String
) {

    /**
     * 一个规则版本：某个生效日期起适用的奖项规则。
     *
     * @param key               唯一标识（= ruleVersionKey，存数据库用），格式 "{code}_{yyyymmdd}"
     * @param effectiveFromDate 生效日期（"2026-01-31"）
     * @param policyLabel       政策标签（展示用短名，如"2026新规·7级"）
     * @param changeNote        变更说明（简述与其他阶段的差异原因，让客户知道为什么不同）
     * @param rules             匹配规则（含描述、奖项名、固定奖金）；同名连续奖项共享同一奖级对
     * @param realTiersToUse    真实 allPrizeTiers 中取前 N 个奖级对参与展示（=去重后的奖级数）
     * @param prizeTierPairCount 结构化解析：号码后跳过 extraFieldCount 个额外字段，提取 N 对基本投注(注数,金额)
     * @param extraFieldCount   号码之后、基本投注奖级之前的额外字段数（销售额、奖池、出球顺序等）
     */
    data class RuleVersion(
        val key: String,
        val effectiveFromDate: String,
        val policyLabel: String,
        val changeNote: String,
        val rules: List<MatchRuleDef>,
        val realTiersToUse: Int = rules.size,
        val prizeTierPairCount: Int = realTiersToUse,
        val extraFieldCount: Int = 0,
        val appendTierPairCount: Int = 0
    )

    data class MatchRuleDef(
        val matchPrimary: Int,
        val matchSecondary: Int,
        val description: String,
        val prizeName: String,
        val fixedAmountYuan: Long? = null,
        val conditionalKey: String? = null
    )

    // ============ 向后兼容：默认取最新版本（供不区分日期的逻辑使用）============
    //  【危险警告·Deprecated】：这些属性永远返回【最新版】规则，
    //    用于 UI 排序顺序/格式化展示辅助尚可，**绝对不能用于某一期开奖数据的规则定位**，
    //    否则 2003 年老期会被错误套用 2026 新规展示。
    //  定位某一期规则版本的唯一正确入口：rulesForDate(date, issue) / draw.resolveRuleVersion(config)
    @Deprecated(
        level = DeprecationLevel.WARNING,
        message = "永远返回最新版规则，仅可用于UI排序/展示辅助，绝不能用于某一期的规则定位！" +
            "请用 rulesForDate(date,issue) 或 draw.resolveRuleVersion(config) 替代。"
    )
    val rules: List<MatchRuleDef> get() = ruleVersions.first().rules
    @Deprecated(level = DeprecationLevel.WARNING, message = "永远返回最新版，定位单期规则请用 rulesForDate()/resolveRuleVersion()")
    val realTiersToUse: Int get() = ruleVersions.first().realTiersToUse
    @Deprecated(level = DeprecationLevel.WARNING, message = "永远返回最新版，定位单期规则请用 rulesForDate()/resolveRuleVersion()")
    val extraFieldCount: Int get() = ruleVersions.first().extraFieldCount
    @Deprecated(level = DeprecationLevel.WARNING, message = "永远返回最新版，定位单期规则请用 rulesForDate()/resolveRuleVersion()")
    val prizeTierPairCount: Int get() = ruleVersions.first().prizeTierPairCount

    /**
     * 【零兜底·严格模式】只凭真实开奖 date 匹配规则版本，绝不瞎猜。
     *
     * 唯一合法输入：[date] = 官方真实开奖日期（格式 YYYY-MM-DD）。
     *   - date 非空、格式合法、且确实 ≥ 某版 effectiveFromDate → 返回该版 RuleVersion
     *   - 其他任何情况（date 空 / date 格式不合法 / issue 传入 = 非法调用 / 找不到任何一版）→ 一律返回 null。
     *
     * ❌ 已废除：
     *   · 通过 issue 前缀推断年份的 inferDateFromIssue → 废掉（期号年份不是真实开奖日期，会错）
     *   · ruleVersions.last() → 最旧版 fallback → 废掉（没有 date 就是元数据缺失，绝不拿任何版规则去硬套）
     *   · 通过 issue 定位规则版本 → 废掉（解析层必须保证每条开奖先解析出真实 date，再进 DB）
     *
     * 所有调用方必须保证只传真实 date；不传 date / 传 issue 就等同于"本期元数据缺失"。
     */
    fun rulesForDate(date: String?, issue: String? = null): RuleVersion? {
        // 有 issue 参数就直接报错级拦截：决不允许用 issue 参与规则版本定位
        if (!issue.isNullOrEmpty()) return null
        // date 必须是 "YYYY-MM-DD" 合法格式（10位字符、第5/8位为'-'）
        if (date.isNullOrEmpty() || date.length != 10 || date[4] != '-' || date[7] != '-') return null
        val y = date.take(4).toIntOrNull() ?: return null
        val m = date.substring(5, 7).toIntOrNull() ?: return null
        val d = date.substring(8).toIntOrNull() ?: return null
        if (y < 2000 || y > 2100 || m !in 1..12 || d !in 1..31) return null
        return ruleVersions.firstOrNull { date >= it.effectiveFromDate }
    }
}

object LotteryType {

    // ==================== 双色球（2026-02-01起增设福运奖）====================
    val SSQ = LotteryTypeConfig(
        code = "ssq", displayName = "双色球",
        url = "http://data.17500.cn/ssq_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/ssq_desc.txt",
        primaryMin = 1, primaryMax = 33, primaryPickCount = 6, primaryLabel = "红球",
        secondaryMin = 1, secondaryMax = 16, secondaryPickCount = 1, secondaryLabel = "蓝球",
        hasSecondary = true,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                key = "ssq_20260201",
                effectiveFromDate = "2026-02-01",
                policyLabel = "2026年新规·含福运奖",
                changeNote = "2026-02-01第2026014期起增设福运奖：奖池≥15亿自动开启（中3红=5元），" +
                    "<3亿自动停止（占位置0/5或0/0）。每期共7对奖级。一等奖总额封顶1亿，二等奖封顶7000万。",
                realTiersToUse = 7, prizeTierPairCount = 7,
                extraFieldCount = 8,  // 6红球出球顺序 + 销售额 + 奖池
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
                    LotteryTypeConfig.MatchRuleDef(3, 0, "中3红球（蓝球未中）", "福运奖", 5, ConditionalKey.SSQ_FUYUN)
                )
            ),
            LotteryTypeConfig.RuleVersion(
                key = "ssq_20030216",
                effectiveFromDate = "2003-02-16",
                policyLabel = "2003.02-2026.01·经典6级",
                changeNote = "2003年2月16日首期上市至2026年1月的6级经典规则，中3红球（蓝球未中）不中奖。",
                realTiersToUse = 6, prizeTierPairCount = 6,
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
        parsePrimaryCount = 6, parseSecondaryCount = 1,
        primaryUnit = "个红球", secondaryUnit = "个蓝球",
        issuePattern = "5-7位数字（例26090）", issueHint = "例如：26090"
    )

    // ==================== 大乐透（6次规则调整，5个奖级版本）====================
    //  真实解析：号码 → 2额外(销售额/奖池) → N对基本投注奖级 → 追加投注奖级(忽略)
    //  26088期验证：[12-25]正好7对基本投注（一等2/9662603→七等9077777/5全对），后续是追加
    val DLT = LotteryTypeConfig(
        code = "dlt", displayName = "大乐透",
        url = "http://data.17500.cn/dlt2_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/dlt2_desc.txt",
        primaryMin = 1, primaryMax = 35, primaryPickCount = 5, primaryLabel = "前区",
        secondaryMin = 1, secondaryMax = 12, secondaryPickCount = 2, secondaryLabel = "后区",
        hasSecondary = true,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                key = "dlt_20260131",
                effectiveFromDate = "2026-01-31",
                policyLabel = "2026年新规·7级",
                changeNote = "2026-01-31第26014期起9级合并为7级：5+0与4+2合并三等奖，4+0与3+2合并五等奖。" +
                    "奖池<8亿：三5000/四300/五150/六15/七5；奖池≥8亿上浮至6666/380/200/18/7。一二等奖总额各封顶1亿。",
                realTiersToUse = 7, prizeTierPairCount = 7,
                extraFieldCount = 2,  // 销售额 + 奖池
                appendTierPairCount = 7,
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(5, 2, "5前区+2后区全中", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 1, "中5前区+1后区", "二等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "中5前区（后区未中）", "三等奖", 5000, ConditionalKey.DLT_2026_FLOAT),
                    LotteryTypeConfig.MatchRuleDef(4, 2, "中4前区+2后区", "三等奖", 5000, ConditionalKey.DLT_2026_FLOAT),
                    LotteryTypeConfig.MatchRuleDef(4, 1, "中4前区+1后区", "四等奖", 300, ConditionalKey.DLT_2026_FLOAT),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "中4前区（后区未中）", "五等奖", 150, ConditionalKey.DLT_2026_FLOAT),
                    LotteryTypeConfig.MatchRuleDef(3, 2, "中3前区+2后区", "五等奖", 150, ConditionalKey.DLT_2026_FLOAT),
                    LotteryTypeConfig.MatchRuleDef(3, 1, "中3前区+1后区", "六等奖", 15, ConditionalKey.DLT_2026_FLOAT),
                    LotteryTypeConfig.MatchRuleDef(2, 2, "中2前区+2后区", "六等奖", 15, ConditionalKey.DLT_2026_FLOAT),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "中3前区（后区未中）", "七等奖", 5, ConditionalKey.DLT_2026_FLOAT),
                    LotteryTypeConfig.MatchRuleDef(2, 1, "中2前区+1后区", "七等奖", 5, ConditionalKey.DLT_2026_FLOAT),
                    LotteryTypeConfig.MatchRuleDef(1, 2, "中1前区+2后区", "七等奖", 5, ConditionalKey.DLT_2026_FLOAT),
                    LotteryTypeConfig.MatchRuleDef(0, 2, "仅中2后区", "七等奖", 5, ConditionalKey.DLT_2026_FLOAT)
                )
            ),
            LotteryTypeConfig.RuleVersion(
                key = "dlt_20190218",
                effectiveFromDate = "2019-02-18",
                policyLabel = "2019-2026·9级经典",
                changeNote = "2019年第19019期至2026年1月30日的9级经典规则，三10000/四3000/五300/" +
                    "六200/七100/八15/九5，追加投注最高1800万。" +
                    "\n[注意：官方数据源 dlt2_desc.txt 本身是按7级合并结构化输出的，" +
                    "解析层 prizeTierPairCount=7（14格基本投注+13格追加投注=真实字段布局）；" +
                    "realTiersToUse=9（规则版本本身的匹配规则仍是9级）。" +
                    "展示层 DrawDetailDialog 用 realTiersToUse=9 的 MatchRuleDef 去匹配命中，" +
                    "不会因为数据源按7级合并存储就丢奖级。]",
                realTiersToUse = 9, prizeTierPairCount = 7,   // 7对=14格(12-25)，真实官方源字段布局
                extraFieldCount = 2,
                appendTierPairCount = 7,  // 7级追加短尾：前6级2字段+第7级1字段=13格(26-38)
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
                    LotteryTypeConfig.MatchRuleDef(0, 1, "仅中1后区", "九等奖", 5)
                )
            ),
            LotteryTypeConfig.RuleVersion(
                key = "dlt_20140505",
                effectiveFromDate = "2014-05-05",
                policyLabel = "2014-2019·6级",
                changeNote = "2014年第14052期至2019年的6级规则，奖级由8个缩为6个，一/二/三等奖浮动，四五级固定。" +
                    "\n[注意：官方数据源按7级合并结构化输出（prizeTierPairCount=7，appendTierPairCount=7），" +
                    "realTiersToUse=6 用于展示层按当期真实政策6级分组显示。]",
                realTiersToUse = 6, prizeTierPairCount = 7,  // 官方源字段布局 7对=14格，和7级合并版一致
                extraFieldCount = 2,
                appendTierPairCount = 7,  // 官方源字段布局 7级追加短尾=13格，和7级合并版一致
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(5, 2, "5前区+2后区全中", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 1, "中5前区+1后区", "二等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "中5前区（后区未中）", "三等奖", null),
                    LotteryTypeConfig.MatchRuleDef(4, 2, "中4前区+2后区", "三等奖", null),
                    LotteryTypeConfig.MatchRuleDef(4, 1, "中4前区+1后区", "四等奖", null),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "中4前区（后区未中）", "五等奖", 200),
                    LotteryTypeConfig.MatchRuleDef(3, 2, "中3前区+2后区", "五等奖", 200),
                    LotteryTypeConfig.MatchRuleDef(3, 1, "中3前区+1后区", "六等奖", 100),
                    LotteryTypeConfig.MatchRuleDef(2, 2, "中2前区+2后区", "六等奖", 100)
                )
            ),
            LotteryTypeConfig.RuleVersion(
                key = "dlt_20091017",
                effectiveFromDate = "2009-10-17",
                policyLabel = "2009-2014·8级千万头奖版",
                changeNote = "2009年第09121期至2014年的8级规则，奖池≥1亿时一等奖可达1000万。" +
                    "\n[注意：官方数据源按7级合并结构化输出（prizeTierPairCount=7，appendTierPairCount=7），" +
                    "realTiersToUse=8 用于展示层按当期真实政策8级分组显示。]",
                realTiersToUse = 8, prizeTierPairCount = 7,  // 官方源字段布局 7对=14格
                extraFieldCount = 2,
                appendTierPairCount = 7,  // 官方源字段布局 7级追加短尾=13格
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(5, 2, "5前区+2后区全中", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 1, "中5前区+1后区", "二等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "中5前区（后区未中）", "三等奖", null),
                    LotteryTypeConfig.MatchRuleDef(4, 2, "中4前区+2后区", "四等奖", 3000),
                    LotteryTypeConfig.MatchRuleDef(4, 1, "中4前区+1后区", "五等奖", 500),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "中4前区（后区未中）", "六等奖", 200),
                    LotteryTypeConfig.MatchRuleDef(3, 2, "中3前区+2后区", "六等奖", 200),
                    LotteryTypeConfig.MatchRuleDef(3, 1, "中3前区+1后区", "七等奖", 10),
                    LotteryTypeConfig.MatchRuleDef(2, 2, "中2前区+2后区", "七等奖", 10),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "中3前区（后区未中）", "八等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(2, 1, "中2前区+1后区", "八等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(1, 2, "中1前区+2后区", "八等奖", 5)
                )
            ),
            LotteryTypeConfig.RuleVersion(
                key = "dlt_20070528",
                effectiveFromDate = "2007-05-28",
                policyLabel = "2007.05-2009.10·上市首版",
                changeNote = "2007年5月28日首期上市至2009年10月，一等奖500万封顶。" +
                    "\n[注意：官方数据源按7级合并结构化输出（prizeTierPairCount=7，appendTierPairCount=7），" +
                    "realTiersToUse=8 用于展示层按当期真实政策8级分组显示。]",
                realTiersToUse = 8, prizeTierPairCount = 7,  // 官方源字段布局 7对=14格
                extraFieldCount = 2,
                appendTierPairCount = 7,  // 官方源字段布局 7级追加短尾=13格
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(5, 2, "5前区+2后区全中", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 1, "中5前区+1后区", "二等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "中5前区（后区未中）", "三等奖", null),
                    LotteryTypeConfig.MatchRuleDef(4, 2, "中4前区+2后区", "四等奖", 3000),
                    LotteryTypeConfig.MatchRuleDef(4, 1, "中4前区+1后区", "五等奖", 500),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "中4前区（后区未中）", "六等奖", 100),
                    LotteryTypeConfig.MatchRuleDef(3, 2, "中3前区+2后区", "六等奖", 100),
                    LotteryTypeConfig.MatchRuleDef(3, 1, "中3前区+1后区", "七等奖", 10),
                    LotteryTypeConfig.MatchRuleDef(2, 2, "中2前区+2后区", "七等奖", 10),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "中3前区（后区未中）", "八等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(2, 1, "中2前区+1后区", "八等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(1, 2, "中1前区+2后区", "八等奖", 5)
                )
            )
        ),
        parsePrimaryCount = 5, parseSecondaryCount = 2,
        primaryUnit = "个前区", secondaryUnit = "个后区",
        issuePattern = "5-7位数字（例26088）", issueHint = "例如：26088"
    )

    // ==================== 福彩3D（规则长期稳定）====================
    //  选号匹配：3 位全中 = 中奖（直选 1040 元为最高奖）。
    //  组选3/6 是投注方式而非不同匹配条件，详情页已展示官方真实三档奖级数据。
    val FC3D = LotteryTypeConfig(
        code = "3d", displayName = "福彩3D",
        url = "http://data.17500.cn/3d_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/3d_desc.txt",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 3, primaryLabel = "号码",
        hasSecondary = false,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                key = "3d_20041018",
                effectiveFromDate = "2004-10-18",
                policyLabel = "2004.10至今·三档固定奖",
                changeNote = "2004年10月18日全国统一上市。直选1040/组选3=346/组选6=173三档固定奖，返奖比例53%。" +
                    "组选3/6为投注方式，选号匹配统一展示为直选命中。",
                realTiersToUse = 3, prizeTierPairCount = 3,
                extraFieldCount = 6,  // 试机号3 + 其他 + 销售额
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(3, 0, "3位数字全中", "直选奖", 1040)
                )
            )
        ),
        parsePrimaryCount = 3, parseSecondaryCount = 0,
        primaryUnit = "个号码", secondaryUnit = "",
        issuePattern = "7位数字（例2026208）", issueHint = "例如：2026208"
    )

    // ==================== 七乐彩（规则长期稳定）====================
    val QLC = LotteryTypeConfig(
        code = "7lc", displayName = "七乐彩",
        url = "http://data.17500.cn/7lc_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/7lc_desc.txt",
        primaryMin = 1, primaryMax = 30, primaryPickCount = 7, primaryLabel = "基本号",
        secondaryMin = 1, secondaryMax = 30, secondaryPickCount = 1, secondaryLabel = "特别号",
        hasSecondary = true,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                key = "7lc_2000",
                effectiveFromDate = "2000-01-01",
                policyLabel = "2000至今·7级经典",
                changeNote = "2000年全国上市。一~三等奖浮动，四~七等奖固定（200/50/10/5元），规则长期稳定。",
                realTiersToUse = 7, prizeTierPairCount = 7,
                extraFieldCount = 2,  // 销售额 + 奖池
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(7, 0, "7个基本号全中", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(6, 1, "中6基本号+特别号", "二等奖", null),
                    LotteryTypeConfig.MatchRuleDef(6, 0, "中6个基本号（特别号未中）", "三等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 1, "中5基本号+特别号", "四等奖", 200),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "中5个基本号（特别号未中）", "五等奖", 50),
                    LotteryTypeConfig.MatchRuleDef(4, 1, "中4基本号+特别号", "六等奖", 10),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "中4个基本号（特别号未中）", "七等奖", 5)
                )
            )
        ),
        parsePrimaryCount = 7, parseSecondaryCount = 1,
        primaryUnit = "个基本号", secondaryUnit = "个特别号",
        issuePattern = "7位数字（例2026089）", issueHint = "例如：2026089"
    )

    // ==================== 排列三（长期稳定）====================
    //  同 FC3D：3 位全中 = 中奖，组选3/6 是投注方式，详情页展示真实三档奖级。
    val P3 = LotteryTypeConfig(
        code = "p3", displayName = "排列三",
        url = "http://data.17500.cn/pl3_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/pl3_desc.txt",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 3, primaryLabel = "号码",
        hasSecondary = false,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                key = "p3_20041218",
                effectiveFromDate = "2004-12-18",
                policyLabel = "2004.12至今·三档固定奖",
                changeNote = "2004年12月18日全国发行。直选1040/组选3=346/组选6=173三档固定奖。" +
                    "组选3/6为投注方式，选号匹配统一展示为直选命中。",
                realTiersToUse = 3, prizeTierPairCount = 3,
                extraFieldCount = 1,  // 销售额
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(3, 0, "3位数字全中", "直选奖", 1040)
                )
            )
        ),
        parsePrimaryCount = 3, parseSecondaryCount = 0,
        primaryUnit = "个号码", secondaryUnit = "",
        issuePattern = "7位数字（例2026208）", issueHint = "例如：2026208"
    )

    // ==================== 排列五（长期稳定）====================
    val P5 = LotteryTypeConfig(
        code = "p5", displayName = "排列五",
        url = "http://data.17500.cn/pl5_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/pl5_desc.txt",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 5, primaryLabel = "号码",
        hasSecondary = false,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                key = "p5_20041218",
                effectiveFromDate = "2004-12-18",
                policyLabel = "2004.12至今·固定10万",
                changeNote = "2004年12月18日与排列三同步全国发行。5位数字和位置全对即中10万元。",
                realTiersToUse = 1, prizeTierPairCount = 1,
                extraFieldCount = 1,  // 销售额
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(5, 0, "5位数字和位置全对", "一等奖", 100000)
                )
            )
        ),
        parsePrimaryCount = 5, parseSecondaryCount = 0,
        primaryUnit = "个号码", secondaryUnit = "",
        issuePattern = "7位数字（例2026208）", issueHint = "例如：2026208"
    )

    // ==================== 七星彩（2020-10-11改规）====================
    //  20099期(旧)验证：三270/1800 四3305/300 五38145/20 六415445/5
    //  20100期(新)验证：三76/3000  四2670/500 五40708/30 六1515095/5
    val QXC = LotteryTypeConfig(
        code = "7xc", displayName = "七星彩",
        url = "http://data.17500.cn/7xc_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/7xc_desc.txt",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 6, primaryLabel = "前6位",
        secondaryMin = 0, secondaryMax = 14, secondaryPickCount = 1, secondaryLabel = "后1位",
        hasSecondary = true,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                key = "qxc_20201011",
                effectiveFromDate = "2020-10-11",
                policyLabel = "2020年后·固定奖升级",
                changeNote = "2020-10-11第20100期起第二次重大调整：固定奖金额升级" +
                    "（三等奖1800→3000、四等奖300→500、五等奖20→30、六等奖保持5元）。",
                realTiersToUse = 6, prizeTierPairCount = 6,
                extraFieldCount = 2,  // 销售额 + 奖池
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(6, 1, "前6位+后1位全中", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(6, 0, "前6位全对（后1位未中）", "二等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 1, "前6位中5位 + 后1位全对", "三等奖", 3000),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "前6位中5位（后1位未中）", "四等奖", 500),
                    LotteryTypeConfig.MatchRuleDef(4, 1, "前6位中4位 + 后1位全对", "四等奖", 500),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "前6位中4位（后1位未中）", "五等奖", 30),
                    LotteryTypeConfig.MatchRuleDef(3, 1, "前6位中3位 + 后1位全对", "五等奖", 30),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "前6位中3位（后1位未中）", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(2, 1, "前6位中2位 + 后1位全对", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(1, 1, "前6位中1位 + 后1位全对", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(0, 1, "仅后1位全对", "六等奖", 5)
                )
            ),
            LotteryTypeConfig.RuleVersion(
                key = "qxc_20040518",
                effectiveFromDate = "2004-05-18",
                policyLabel = "2004.05-2020.10·经典6级",
                changeNote = "2004年5月18日首期上市至2020年10月10日的经典6级规则，固定奖金额较低（三等奖1800/四等奖300/五等奖20）。",
                realTiersToUse = 6, prizeTierPairCount = 6,
                extraFieldCount = 2,
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(6, 1, "前6位+后1位全中", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(6, 0, "前6位全对（后1位未中）", "二等奖", null),
                    LotteryTypeConfig.MatchRuleDef(5, 1, "前6位中5位 + 后1位全对", "三等奖", 1800),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "前6位中5位（后1位未中）", "四等奖", 300),
                    LotteryTypeConfig.MatchRuleDef(4, 1, "前6位中4位 + 后1位全对", "四等奖", 300),
                    LotteryTypeConfig.MatchRuleDef(4, 0, "前6位中4位（后1位未中）", "五等奖", 20),
                    LotteryTypeConfig.MatchRuleDef(3, 1, "前6位中3位 + 后1位全对", "五等奖", 20),
                    LotteryTypeConfig.MatchRuleDef(3, 0, "前6位中3位（后1位未中）", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(2, 1, "前6位中2位 + 后1位全对", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(1, 1, "前6位中1位 + 后1位全对", "六等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(0, 1, "仅后1位全对", "六等奖", 5)
                )
            )
        ),
        parsePrimaryCount = 6, parseSecondaryCount = 1,
        primaryUnit = "位前区", secondaryUnit = "位后区",
        issuePattern = "5-7位数字（例26089）", issueHint = "例如：26089"
    )

    // ==================== 快乐8（选十玩法·长期稳定，中八=800元数据源验证）====================
    val KL8 = LotteryTypeConfig(
        code = "kl8", displayName = "快乐8",
        url = "http://data.17500.cn/kl8_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/kl8_desc.txt",
        primaryMin = 1, primaryMax = 80, primaryPickCount = 10, primaryLabel = "号码",
        hasSecondary = false,
        ruleVersions = listOf(
            LotteryTypeConfig.RuleVersion(
                key = "kl8_20201028",
                effectiveFromDate = "2020-10-28",
                policyLabel = "2020.10至今·选十玩法",
                changeNote = "选十玩法7档奖级：中十浮动/中九8000/中八800/中七80/中六5/中五3/全不中2。" +
                    "数据源共输出70+对子玩法奖级（20号码+销售额+奖池=2额外后），选十前7对。" +
                    "选十中八官方固定奖800元（2023053期625注×800元=50万验证正确）。",
                realTiersToUse = 7, prizeTierPairCount = 7,
                extraFieldCount = 2,  // 销售额 + 奖池
                rules = listOf(
                    LotteryTypeConfig.MatchRuleDef(10, 0, "选10中10全对", "一等奖", null),
                    LotteryTypeConfig.MatchRuleDef(9, 0, "选10中9", "二等奖", 8000),
                    LotteryTypeConfig.MatchRuleDef(8, 0, "选10中8", "三等奖", 800),
                    LotteryTypeConfig.MatchRuleDef(7, 0, "选10中7", "四等奖", 80),
                    LotteryTypeConfig.MatchRuleDef(6, 0, "选10中6", "五等奖", 5),
                    LotteryTypeConfig.MatchRuleDef(5, 0, "选10中5", "六等奖", 3),
                    LotteryTypeConfig.MatchRuleDef(0, 0, "选10中0（幸运奖）", "七等奖", 2)
                )
            )
        ),
        parsePrimaryCount = 20, parseSecondaryCount = 0,
        primaryUnit = "个号码", secondaryUnit = "",
        issuePattern = "7位数字（例2026208）", issueHint = "例如：2026208"
    )

    val ALL: List<LotteryTypeConfig> = listOf(SSQ, DLT, FC3D, QLC, P3, P5, QXC, KL8)

    fun byCode(code: String): LotteryTypeConfig =
        if (code == "t22x5" || code == "22x5" || code == "sh15x5" || code == "15x5") KL8
        else ALL.firstOrNull { it.code == code } ?: SSQ
}
