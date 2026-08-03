package com.lottery.history.model

/**
 * 彩种配置：统一描述每个彩种的号码区间、选号数量、匹配规则和解析参数。
 * 驱动 Fragment / Matcher / Repository / DataManager 的通用逻辑。
 *
 * 规则数据均来自官方公开规则：
 *   - 双色球/七乐彩/福彩3D：中国福利彩票发行管理中心 cwl.gov.cn
 *   - 大乐透/七星彩/排列三/排列五：国家体育总局体育彩票管理中心 lottery.gov.cn
 *   - 22选5：sport.gov.cn 全国联网规则（浮动一等奖 + 二三等奖固定）
 */
data class LotteryTypeConfig(
    val code: String,           // "ssq", "dlt", "3d" ...
    val displayName: String,    // "双色球"
    val url: String,            // 主数据源 URL（官方公开开奖数据地址）
    val txtFallbackUrl: String, // 兜底数据源 URL（主源失败时回退）

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

    // 匹配规则（含描述和奖项名 + 固定奖金兜底）
    val rules: List<MatchRuleDef>,

    // 解析参数：日期后取多少个主号码、多少个次号码
    val parsePrimaryCount: Int,
    val parseSecondaryCount: Int = 0,

    // 结果描述用词
    val primaryUnit: String,    // "个红球" / "个前区" / "个号码"
    val secondaryUnit: String,  // "个蓝球" / "个后区" / "个特别号"

    // 期号格式说明：提示用户在"历史期号查询"中输入什么格式（如"26087"/"2026087"）
    val issuePattern: String,
    val issueHint: String,

    /**
     * 真实奖级对齐方式：
     *   从 allPrizeTiers 按「奖项名去重后的规则顺序」取前 N 个真实 (count, amount) 对
     *   与 rules[..].prizeName 去重后的顺序一一对应。
     *
     *   默认 rules.size：双色球/大乐透等"每个奖级对刚好对应rules"的场景。
     *   快乐8：7（选十玩法的 7 个奖级对：选十中十…选十全不中）
     */
    val realTiersToUse: Int = rules.size
) {
    /** 匹配规则定义：命中数 + 奖项名 + 官方单注固定奖金（浮动奖填 null） */
    data class MatchRuleDef(
        val matchPrimary: Int,
        val matchSecondary: Int,
        val description: String,   // 自然语言命中规则（用于 DrawDetailDialog 命中规则列）
        val prizeName: String,     // "一等奖" / "直选" / "组选3" 等
        val fixedAmountYuan: Long? = null  // 官方单注固定奖金（元）；浮动奖=null，显示真实开奖金额
    )
}

object LotteryType {

    // ==================== 双色球（中国福利彩票官方规则）====================
    //  一等奖(6+1) 浮动 / 二等奖(6+0) 浮动 / 三等奖(5+1)=3000 / 四等奖(5+0 或 4+1)=200
    //  五等奖(4+0 或 3+1)=10 / 六等奖(2+1/1+1/0+1)=5
    val SSQ = LotteryTypeConfig(
        code = "ssq",
        displayName = "双色球",
        // 真实数据源：data.17500.cn 官方公告 2022/11/01 迁移的新地址（倒序含全部奖级明细）
        url = "http://data.17500.cn/ssq_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/ssq_desc.txt",
        primaryMin = 1, primaryMax = 33, primaryPickCount = 6,
        primaryLabel = "红球",
        secondaryMin = 1, secondaryMax = 16, secondaryPickCount = 1,
        secondaryLabel = "蓝球",
        hasSecondary = true,
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
        ),
        parsePrimaryCount = 6,
        parseSecondaryCount = 1,
        primaryUnit = "个红球",
        secondaryUnit = "个蓝球",
        issuePattern = "7 位数字（年份后 2 位 + 期号 3 位，例 26087）",
        issueHint = "例如：26087"
    )

    // ==================== 超级大乐透（中国体彩官方规则·真实开奖数据交叉验证）====================
    //  官方真实开奖数据（17500.cn 大乐透 dlt2_desc.txt 20222124 期验证）：
    //  一(5+2)/二(5+1)/三(5+0) 浮动；四(4+2)=3000；五(4+1)=300；六(4+0/3+2)=100
    //  七(3+1/2+2)=15；八(3+0/2+1/1+2/0+2)=5；九(2+0/1+1/1+0/0+1)=5
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
        rules = listOf(
            LotteryTypeConfig.MatchRuleDef(5, 2, "5前区+2后区全中", "一等奖", null),
            LotteryTypeConfig.MatchRuleDef(5, 1, "中5前区+1后区", "二等奖", null),
            LotteryTypeConfig.MatchRuleDef(5, 0, "中5前区（后区未中）", "三等奖", null),
            LotteryTypeConfig.MatchRuleDef(4, 2, "中4前区+2后区", "四等奖", 3000),
            LotteryTypeConfig.MatchRuleDef(4, 1, "中4前区+1后区", "五等奖", 300),
            LotteryTypeConfig.MatchRuleDef(4, 0, "中4前区（后区未中）", "六等奖", 100),
            LotteryTypeConfig.MatchRuleDef(3, 2, "中3前区+2后区", "六等奖", 100),
            LotteryTypeConfig.MatchRuleDef(3, 1, "中3前区+1后区", "七等奖", 15),
            LotteryTypeConfig.MatchRuleDef(2, 2, "中2前区+2后区", "七等奖", 15),
            LotteryTypeConfig.MatchRuleDef(3, 0, "中3前区（后区未中）", "八等奖", 5),
            LotteryTypeConfig.MatchRuleDef(2, 1, "中2前区+1后区", "八等奖", 5),
            LotteryTypeConfig.MatchRuleDef(1, 2, "中1前区+2后区", "八等奖", 5),
            LotteryTypeConfig.MatchRuleDef(0, 2, "仅中2后区", "八等奖", 5),
            LotteryTypeConfig.MatchRuleDef(2, 0, "中2前区（后区未中）", "九等奖", 5),
            LotteryTypeConfig.MatchRuleDef(1, 1, "中1前区+1后区", "九等奖", 5),
            LotteryTypeConfig.MatchRuleDef(1, 0, "仅中1前区", "九等奖", 5),
            LotteryTypeConfig.MatchRuleDef(0, 1, "仅中1后区", "九等奖", 5),
            LotteryTypeConfig.MatchRuleDef(0, 0, "未命中任何奖项", "未中奖", null)
        ),
        parsePrimaryCount = 5,
        parseSecondaryCount = 2,
        primaryUnit = "个前区",
        secondaryUnit = "个后区",
        issuePattern = "7 位数字（例 26088）",
        issueHint = "例如：26088"
    )

    // ==================== 福彩3D（中国福利彩票官方规则）====================
    // 直选=1040 / 组选3=346 / 组选6=173。这里用"中3位"统一作为直选/组选命中的近似标记；
    // 实际 17500 的奖级提取顺序：直选注数/金额 → 组选3 → 组选6。
    val FC3D = LotteryTypeConfig(
        code = "3d",
        displayName = "福彩3D",
        url = "http://data.17500.cn/3d_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/3d_desc.txt",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 3,
        primaryLabel = "号码",
        hasSecondary = false,
        rules = listOf(
            LotteryTypeConfig.MatchRuleDef(3, 0, "3位数字和位置全对（直选）", "直选奖", 1040),
            LotteryTypeConfig.MatchRuleDef(3, 0, "3位相同数字，对2位位置（组选3）", "组选3", 346),
            LotteryTypeConfig.MatchRuleDef(3, 0, "3位数字全不同，任意顺序（组选6）", "组选6", 173),
            LotteryTypeConfig.MatchRuleDef(2, 0, "仅命中2个位置数字", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(1, 0, "仅命中1个位置数字", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(0, 0, "未命中任何位置", "未中奖", null)
        ),
        parsePrimaryCount = 3,
        parseSecondaryCount = 0,
        primaryUnit = "个号码",
        secondaryUnit = "",
        issuePattern = "7 位数字（例 2026203，即 yyyyNNN）",
        issueHint = "例如：2026203"
    )

    // ==================== 七乐彩（中国福彩官方规则·真实开奖数据交叉验证）====================
    // 官方真实开奖数据（17500.cn 7lc_desc.txt 2009132 期验证）：
    //  一等(7基本)、二等(6+特别)、三等(6基本) 浮动；
    //  四~七等奖按真实开奖显示（官方规则+派奖浮动，不写死固定值）
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
        rules = listOf(
            LotteryTypeConfig.MatchRuleDef(7, 0, "7个基本号全中", "一等奖", null),
            LotteryTypeConfig.MatchRuleDef(6, 1, "中6基本号+特别号", "二等奖", null),
            LotteryTypeConfig.MatchRuleDef(6, 0, "中6个基本号（特别号未中）", "三等奖", null),
            LotteryTypeConfig.MatchRuleDef(5, 1, "中5基本号+特别号", "四等奖", null),
            LotteryTypeConfig.MatchRuleDef(5, 0, "中5个基本号（特别号未中）", "五等奖", null),
            LotteryTypeConfig.MatchRuleDef(4, 1, "中4基本号+特别号", "六等奖", null),
            LotteryTypeConfig.MatchRuleDef(4, 0, "中4个基本号（特别号未中）", "七等奖", null),
            LotteryTypeConfig.MatchRuleDef(3, 1, "中3基本号+特别号", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(3, 0, "仅中3个基本号", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(2, 1, "中2基本号+特别号", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(1, 1, "中1基本号+特别号", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(0, 1, "仅中特别号", "未中奖", null)
        ),
        parsePrimaryCount = 7,
        parseSecondaryCount = 1,
        primaryUnit = "个基本号",
        secondaryUnit = "个特别号",
        issuePattern = "7 位数字（例 2026087）",
        issueHint = "例如：2026087"
    )

    // ==================== 排列三（中国体彩官方，3D同玩法）====================
    // 直选=1040 / 组选3=346 / 组选6=173
    val P3 = LotteryTypeConfig(
        code = "p3",
        displayName = "排列三",
        url = "http://data.17500.cn/pl3_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/pl3_desc.txt",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 3,
        primaryLabel = "号码",
        hasSecondary = false,
        rules = listOf(
            LotteryTypeConfig.MatchRuleDef(3, 0, "3位数字和位置全对（直选）", "直选奖", 1040),
            LotteryTypeConfig.MatchRuleDef(3, 0, "3位相同数字，对2位位置（组选3）", "组选3", 346),
            LotteryTypeConfig.MatchRuleDef(3, 0, "3位数字全不同，任意顺序（组选6）", "组选6", 173),
            LotteryTypeConfig.MatchRuleDef(2, 0, "仅命中2个位置数字", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(1, 0, "仅命中1个位置数字", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(0, 0, "未命中任何位置", "未中奖", null)
        ),
        parsePrimaryCount = 3,
        parseSecondaryCount = 0,
        primaryUnit = "个号码",
        secondaryUnit = "",
        issuePattern = "7 位数字（例 2026203）",
        issueHint = "例如：2026203"
    )

    // ==================== 排列五（中国体彩官方规则，仅一等奖固定 10 万）====================
    val P5 = LotteryTypeConfig(
        code = "p5",
        displayName = "排列五",
        url = "http://data.17500.cn/pl5_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/pl5_desc.txt",
        primaryMin = 0, primaryMax = 9, primaryPickCount = 5,
        primaryLabel = "号码",
        hasSecondary = false,
        rules = listOf(
            LotteryTypeConfig.MatchRuleDef(5, 0, "5位数字和位置全对", "一等奖", 100000),
            LotteryTypeConfig.MatchRuleDef(4, 0, "仅中前4位（不兼中兼得排列三）", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(3, 0, "仅中前3位（不兼中兼得排列三）", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(2, 0, "仅中2个位置数字", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(1, 0, "仅中1个位置数字", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(0, 0, "未命中任何位置", "未中奖", null)
        ),
        parsePrimaryCount = 5,
        parseSecondaryCount = 0,
        primaryUnit = "个号码",
        secondaryUnit = "",
        issuePattern = "7 位数字（例 2026203）",
        issueHint = "例如：2026203"
    )

    // ==================== 七星彩（中国体彩官方新规：前6位0-9 + 后1位0-14）====================
    // 一等(6+1) 浮动90% + 奖池；二等(6+0) 浮动10%；三等(5+1)=3000
    // 四等(5+0 或 4+1)=500；五等(4+0 或 3+1)=30；六等(3+0/2+1/1+1/0+1)=5
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
        ),
        parsePrimaryCount = 6,
        parseSecondaryCount = 1,
        primaryUnit = "位前区",
        secondaryUnit = "位后区",
        issuePattern = "6 位数字（例 26088）",
        issueHint = "例如：26088"
    )

    // ==================== 福彩快乐8（全国联网·官方规则+真实开奖数据交叉验证）====================
    // 玩法：从 1-80 选 10 个号；开奖开出 20 个号码。
    // 【规则基础奖级（官方公开规则设定额度）】：
    //   ①选十中十 = 浮动（≥500万，封顶）
    //   ②选十中九 = 基础 ¥8000
    //   ③选十中八 = 基础 ¥800
    //   ④选十中七 = 基础 ¥80
    //   ⑤选十中六 = 基础 ¥5
    //   ⑥选十中五 = 基础 ¥3
    //   ⑦选十全不中（0个号）= 基础 ¥2（幸运奖）
    //   其余命中(1,2,3,4) = 未中奖（无奖金）
    //  ⚠️ 每期的具体中奖金额以当期官方实际公布为准（含派奖、浮动调整，
    //     规则中"基础¥X"为《游戏规则》设定基础额度，不等于当期实际兑付金额）。
    // （真实奖级已用贵州福彩2026130期官方开奖公告交叉验证）
    val KL8 = LotteryTypeConfig(
        code = "kl8",
        displayName = "快乐8",
        url = "http://data.17500.cn/kl8_desc.txt",
        txtFallbackUrl = "http://data.17500.cn/kl8_desc.txt",
        primaryMin = 1, primaryMax = 80, primaryPickCount = 10,
        primaryLabel = "号码",
        hasSecondary = false,
        rules = listOf(
            LotteryTypeConfig.MatchRuleDef(10, 0, "选10中10全对", "一等奖", null),
            LotteryTypeConfig.MatchRuleDef(9, 0, "选10中9", "二等奖", 8000),
            LotteryTypeConfig.MatchRuleDef(8, 0, "选10中8", "三等奖", 800),
            LotteryTypeConfig.MatchRuleDef(7, 0, "选10中7", "四等奖", 80),
            LotteryTypeConfig.MatchRuleDef(6, 0, "选10中6", "五等奖", 5),
            LotteryTypeConfig.MatchRuleDef(5, 0, "选10中5", "六等奖", 3),
            LotteryTypeConfig.MatchRuleDef(4, 0, "选10中4", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(3, 0, "选10中3", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(2, 0, "选10中2", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(1, 0, "选10中1", "未中奖", null),
            LotteryTypeConfig.MatchRuleDef(0, 0, "选10中0（幸运奖）", "七等奖", 2)
        ),
        parsePrimaryCount = 20,
        parseSecondaryCount = 0,
        primaryUnit = "个号码",
        secondaryUnit = "",
        issuePattern = "7 位数字（例 2026205）",
        issueHint = "例如：2026205",
        realTiersToUse = 7   // 只渲染选十玩法 7 档奖级（1~7档），跳过选九/选八…70+对子玩法
    )

    /** 全部彩种，按展示顺序排列 */
    val ALL: List<LotteryTypeConfig> = listOf(SSQ, DLT, FC3D, QLC, P3, P5, QXC, KL8)

    /** 按 code 查找配置（兼容老 code：t22x5/sh15x5/22x5 → 重定向到快乐8，避免老缓存崩溃） */
    fun byCode(code: String): LotteryTypeConfig =
        if (code == "t22x5" || code == "22x5" || code == "sh15x5" || code == "15x5") KL8
        else ALL.firstOrNull { it.code == code } ?: SSQ
}
