package com.lottery.history.network

import com.lottery.history.model.ConditionalKey
import com.lottery.history.model.ConditionalValue
import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.model.ParseSource
import com.lottery.history.model.PrizeTierEntry
import jxl.Sheet
import jxl.Workbook
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 官方公开开奖历史解析器。
 *
 * 数据源协议：HTTP 返回【空格分隔的纯文本（扩展名伪装 .xls）】，HTTPS 返回【真二进制 OLE2 Excel】。
 * 真机环境两种都可能出现，本解析器统一嗅探后处理：
 *   - OLE2 头(0xD0CF11E0)：真二进制 Excel → 用 jxl 读每行，把 cell 值拼成"空格分隔行字符串"，
 *     然后转交给文本解析路径。好处：号码 + 奖级提取逻辑一套实现，全彩种通用。
 *     对于有表头的彩种（如 3D 前两行是文字），会被 issueRegex 自动过滤。
 *   - 否则：文本（空格分隔）直接解析。
 *
 * 文本每行字段约定（真二进制 Excel 拼出行后也遵循此约定）：
 *   [0] 期号   [1] 开奖日期
 *   [2..2+parsePrimaryCount-1] 主号码
 *   [2+parsePrimaryCount .. 2+parsePrimaryCount+parseSecondaryCount-1] 次号码（若有）
 *   随后：销售额、奖池（大数列，启发式跳过）
 *   再随后：按等级成对出现【中奖注数 单注奖金】——一等奖、二等奖、三等奖...
 *
 * 奖级提取启发式 extractPrizeTiers：跳过销售额/奖池大数，找 (0..50000注 + >=100金额) 对。
 * 容错：单行解析异常不影响其余行；结果按期号倒序返回。
 */
object LotteryXlsParser {

    private val issueRegex = Regex("""^[0-9]{5,}$""")
    private val dateRegex1 = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val dateRegex2 = Regex("""^\d{4}/\d{1,2}/\d{1,2}$""")
    private val dateRegex3 = Regex("""^\d{4}\.\d{1,2}\.\d{1,2}$""")

    private val OLE2_HEADER = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte())

    // 合法千分位格式：可选符号 + 千分位整数 + 可选 .小数（如 84,337,222.00、8000.0、22）
    private val thousandsNumberRegex = Regex("""^[+-]?\d{1,3}(?:,\d{3})*(?:\.\d+)?$""")
    // 欧式小数格式：整数部分用 . 作千分位，小数部分用 ,（如 84.337.222,00）
    private val euroNumberRegex = Regex("""^[+-]?\d{1,3}(?:\.\d{3})*(?:,\d+)?$""")

    /**
     * 通用安全数字解析（纯字符串操作，不经过 Double，零精度丢失）：
     *
     * 数据本质：彩票销售额/奖池/中奖金额均为【整元】，.0/.00 只是浮点格式化产物，
     * 逗号是每3位一组的千分位分隔符（方便阅读）。
     *
     * 解析步骤：
     *  1. 确定小数分隔符（美式="."，欧式=","）
     *  2. 去除千分位分隔符（美式去","，欧式去"."）
     *  3. 截断小数部分（只取整数部分，不四舍五入）
     *  4. 纯数字字符串直接 toLongOrNull（Long 上限 9.2e18，安全覆盖所有彩票金额）
     *
     *  ① 纯整数/纯小数（无逗号）：8000.0→8000，84337222.00→84337222
     *  ② 美式千分位：84,337,222.00→84337222
     *  ③ 欧式小数：84.337.222,00→84337222
     *  ④ 畸形逗号位置一律拒绝返回 null
     */
    private fun parseNumberSafe(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // 1) 无逗号无点：纯整数，直接 toLongOrNull
        if (',' !in trimmed && '.' !in trimmed) {
            return trimmed.toLongOrNull()
        }

        // 2) 无逗号：纯整数或纯小数（如 8000.0、84337222.00）
        if (',' !in trimmed) {
            // 截断小数部分
            val intPart = trimmed.substringBefore('.')
            return intPart.toLongOrNull()
        }

        // 3) 美式千分位：逗号每3位分组、"."小数点（如 84,337,222.00）
        if (thousandsNumberRegex.matches(trimmed)) {
            val cleaned = trimmed.replace(",", "")
            val intPart = cleaned.substringBefore('.')
            return intPart.toLongOrNull()
        }

        // 4) 欧式小数："."每3位分组、","小数点（如 84.337.222,00、8000,50）
        if (euroNumberRegex.matches(trimmed)) {
            val cleaned = trimmed.replace(".", "")
            val intPart = cleaned.substringBefore(',')
            return intPart.toLongOrNull()
        }

        // 5) 畸形格式（逗号位置不合法）拒绝
        return null
    }

    fun parse(config: LotteryTypeConfig, input: InputStream): List<LotteryDraw> {
        val bytes = input.readBytes()
        val isBinary = bytes.size >= 4 &&
            bytes[0] == OLE2_HEADER[0] && bytes[1] == OLE2_HEADER[1] &&
            bytes[2] == OLE2_HEADER[2] && bytes[3] == OLE2_HEADER[3]
        val textContent: String = if (isBinary) {
            binaryXlsToSpaceText(bytes)
        } else {
            String(bytes, Charsets.UTF_8)
        }
        return parseText(config, textContent)
    }

    // ============ 真二进制 Excel → 空格分隔文本 ============
    // jxl 读出每行，数字 cell 浮点数去 .0，字符串 cell 原样，空格拼接
    private fun binaryXlsToSpaceText(bytes: ByteArray): String {
        val out = StringBuilder(estimateTextSize(bytes.size))
        val workbook = Workbook.getWorkbook(ByteArrayInputStream(bytes))
        try {
            val sheet: Sheet = workbook.getSheet(0) ?: return ""
            val rows = sheet.rows
            for (r in 0 until rows) {
                val cells = sheet.getRow(r) ?: continue
                for (c in cells.indices) {
                    val v = cells[c].contents
                    // 去掉逗号+小数转长整数，统一格式（支持 84,337,222.00、8000.0、纯整数）
                    val num = parseNumberSafe(v)
                    val normalized = num?.toString() ?: v.trim()
                    if (c > 0) out.append(' ')
                    out.append(normalized)
                }
                out.append('\n')
            }
        } finally {
            runCatching { workbook.close() }
        }
        return out.toString()
    }

    private fun estimateTextSize(binarySize: Int) = binarySize * 3

    // ============ 空格分隔文本解析（统一路径，号码 + 奖级一起） ============
    private fun parseText(config: LotteryTypeConfig, raw: String): List<LotteryDraw> {
        val result = mutableListOf<LotteryDraw>()
        val minParts = 2 + config.parsePrimaryCount + config.parseSecondaryCount
        for (line in raw.lineSequence()) {
            try {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size < minParts) continue

                val issue = parts[0]
                if (!issueRegex.matches(issue)) continue
                val date = normalizeDate(parts[1]) ?: continue

                val numStart = 2
                val primary = (0 until config.parsePrimaryCount).mapNotNull { idx ->
                    parts.getOrNull(numStart + idx)?.toIntOrNull()?.takeIf { it in 0..99 }
                }
                if (primary.size != config.parsePrimaryCount) continue

                val secondary = if (config.hasSecondary && config.parseSecondaryCount > 0) {
                    val secStart = numStart + config.parsePrimaryCount
                    (0 until config.parseSecondaryCount).mapNotNull { idx ->
                        parts.getOrNull(secStart + idx)?.let { v ->
                            v.takeIf { it != "-" && it.isNotEmpty() }?.toIntOrNull()?.takeIf { it in 0..99 }
                        }
                    }
                } else emptyList()
                @Suppress("DEPRECATION") // 此处仅用 rules 结构判断 secondary 是否参与匹配（固定不变），不用于按期定位规则
                if (config.hasSecondary && config.rules.any { it.matchSecondary > 0 }) {
                    if (secondary.size < config.parseSecondaryCount) continue
                }

                // 【零兜底·严格模式】只用真实开奖 date 定位规则版本，绝不用 issue 参与（那是猜）。
                // 解析层必须保证 date 已经从官方数据源真实解析出来；没 date 就是 SEED_INCOMPLETE。
                val ruleVersion = config.rulesForDate(date)
                // ===== 严格模式：date+issue 仍无法定位规则版本时，标记 SEED_INCOMPLETE 并跳过 =====
                //   绝不拿 ruleVersions.first() 最新版去套（否则历史期被错误归类到 2026 新规）
                if (ruleVersion == null) {
                    result.add(
                        LotteryDraw(
                            issue = issue,
                            primaryNumbers = primary.sorted(),
                            secondaryNumbers = secondary.sorted(),
                            date = date,
                            ruleVersionKey = null,
                            parseSource = ParseSource.SEED_INCOMPLETE,
                            parseAt = System.currentTimeMillis(),
                            parserVersion = 1,
                            tierMatchStatus = com.lottery.history.model.TierMatchStatus.MISMATCH
                        )
                    )
                    continue
                }

                // 号码之后的剩余部分：extraFieldCount 个额外字段（销售额/奖池/出球顺序）+ 成对奖级
                val extraStart = numStart + config.parsePrimaryCount +
                    (if (config.hasSecondary) config.parseSecondaryCount else 0)

                // —— 提取销售额和奖池（从 extra 字段中）——
                // 约定：extraFieldCount >= 2 时，最后一个 extra = 奖池，倒数第二个 extra = 销售额
                //       extraFieldCount == 1 时，唯一的 extra = 销售额（无奖池，如P3/P5）
                //       FC3D extraFieldCount=6：最后一个 extra = 销售额（无奖池概念）
                var salesAmount: Long? = null
                var jackpotAmount: Long? = null
                val ef = ruleVersion.extraFieldCount
                if (ef >= 1) {
                    val salesIdx: Int
                    val jackpotIdx: Int
                    if (config.code == "3d") {
                        salesIdx = extraStart + ef - 1
                        jackpotIdx = -1
                    } else if (ef == 1) {
                        salesIdx = extraStart
                        jackpotIdx = -1
                    } else {
                        salesIdx = extraStart + ef - 2
                        jackpotIdx = extraStart + ef - 1
                    }
                    salesAmount = parts.getOrNull(salesIdx)?.let { parseNumberSafe(it) }
                    if (jackpotIdx >= 0) {
                        jackpotAmount = parts.getOrNull(jackpotIdx)?.let { parseNumberSafe(it) }
                    }
                }

                val allTiers = extractAllPrizeTiers(
                    parts, extraStart,
                    extraFieldCount = ruleVersion.extraFieldCount,
                    prizeTierPairCount = ruleVersion.prizeTierPairCount
                )

                // ===== 追加投注段（大乐透等）：基本投注之后 appendTierPairCount 对 =====
                val tiersStart = extraStart + ruleVersion.extraFieldCount
                val expectedPairs = ruleVersion.prizeTierPairCount
                val appendPairs = ruleVersion.appendTierPairCount
                val appendTiers = mutableListOf<PrizeTierEntry?>()
                if (appendPairs > 0) {
                    val appendStart = tiersStart + expectedPairs * 2
                    for (k in 0 until appendPairs) {
                        val cStr = parts.getOrNull(appendStart + k * 2)
                        val aStr = parts.getOrNull(appendStart + k * 2 + 1)
                        if (cStr == "-" || aStr == "-" || cStr == null || aStr == null) {
                            appendTiers.add(null)
                            continue
                        }
                        val cVal = cStr.let { parseNumberSafe(it) }
                        val aVal = aStr.let { parseNumberSafe(it) }
                        if (cVal == null || aVal == null) {
                            appendTiers.add(null)
                            continue
                        }
                        // count 用 Long，低等奖级中奖注数可能超 Int(21.47 亿)
                        appendTiers.add(PrizeTierEntry(count = cVal, amount = aVal))
                    }
                }

                // ===== v11 新增：条件性奖级标志位（conditionalFlags）=====
                //  官方政策随奖池和期数变化：同一规则版本下，某些奖级仍可能本期"不适用/不开放"
                //  或"金额浮动"。把这些当期中有效的判定预计算出来，随 draw 持久化，
                //  避免展示层靠 jackkpotAmount 再猜一次（展示层猜容易和解析逻辑不一致导致错乱）。
                val conditionalFlags = buildMap<String, String> {
                    // —— SSQ 2026 新规：福运奖（3+0）双门槛迟滞机制 ——
                    //  官方政策：奖池≥15亿启动 / <3亿停止 / [3亿,15亿)维持上期状态。
                    //  数据驱动优先：若官方数据已含第7对奖级（福运奖），直接判定 ON。
                    if (config.code == "ssq" && ruleVersion.key == "ssq_20260201") {
                        val hasFuyunData = allTiers.getOrNull(6) != null
                        put(
                            ConditionalKey.SSQ_FUYUN,
                            when {
                                hasFuyunData -> ConditionalValue.ON
                                jackpotAmount == null -> ConditionalValue.HOLD
                                jackpotAmount >= 1_500_000_000L -> ConditionalValue.ON
                                jackpotAmount < 300_000_000L -> ConditionalValue.OFF
                                else -> ConditionalValue.HOLD // 3亿~15亿迟滞区间，单期无法确定
                            }
                        )
                    }
                    // —— DLT 2026 新规：三~七等奖奖池≥8亿时上浮 6666/380/200/18/7
                    if (config.code == "dlt" && ruleVersion.key == "dlt_20260131") {
                        put(
                            ConditionalKey.DLT_2026_FLOAT,
                            when {
                                jackpotAmount == null -> ConditionalValue.HOLD
                                jackpotAmount >= 800_000_000L -> ConditionalValue.UP
                                else -> ConditionalValue.NORMAL
                            }
                        )
                    }
                }

                // —— 结构一致性审计 v11：不再只算 size（全 prizeTierPairCount）
                //    而是 count { it != null }，避免 '-' 占位把 MATCH 错判成 FEWER
                val actualTierCount = allTiers.count { it != null }
                val expected = ruleVersion.realTiersToUse
                val tierMatchStatus = when {
                    actualTierCount == expected -> com.lottery.history.model.TierMatchStatus.MATCH
                    actualTierCount == 0 -> com.lottery.history.model.TierMatchStatus.MISMATCH
                    actualTierCount < expected -> com.lottery.history.model.TierMatchStatus.FEWER
                    else -> com.lottery.history.model.TierMatchStatus.MORE
                }

                result.add(
                    LotteryDraw(
                        issue = issue,
                        primaryNumbers = primary.sorted(),
                        secondaryNumbers = secondary.sorted(),
                        date = date,
                        firstPrizeCount = allTiers.getOrNull(0)?.count,
                        firstPrizeAmount = allTiers.getOrNull(0)?.amount,
                        secondPrizeCount = allTiers.getOrNull(1)?.count,
                        secondPrizeAmount = allTiers.getOrNull(1)?.amount,
                        allPrizeTiers = allTiers,
                        // —— v9 新增：按期自适应元数据（解析时一次性确定，存DB后不再重算）——
                        ruleVersionKey = ruleVersion.key,
                        actualTierCount = actualTierCount,
                        tierMatchStatus = tierMatchStatus,
                        jackpotAmount = jackpotAmount,
                        salesAmount = salesAmount,
                        appendPrizeTiers = appendTiers,
                        // —— v11 新增：条件性奖级标志 + 解析来源标记
                        conditionalFlags = conditionalFlags,
                        parseSource = ParseSource.NET,
                        parseAt = System.currentTimeMillis(),
                        parserVersion = com.lottery.history.data.LotteryDataManager.PARSER_VERSION_CURRENT
                    )
                )
            } catch (_: Exception) {
                // 单行错误忽略
            }
        }
        result.sortByDescending { it.issue }
        return result
    }

    // ============ 结构化奖级提取（按已知数据格式精确提取，不猜测）============
    //  数据格式（已用 17500.cn 各彩种真实数据交叉验证 2026-08-06）：
    //    号码之后 → extraFieldCount 个额外字段（销售额/奖池/出球顺序等）→ prizeTierPairCount 对 (注数,金额)
    //  每期的 extraFieldCount / prizeTierPairCount 由该期适用的 RuleVersion 决定（按日期自动适配）。
    //
    //  容错 v11 升级：
    //   - 之前：某一对遇到 '-' 就 break 全部后续奖级。官方更新习惯是先公布前几对，
    //           后面几对次日补，这样会让前几对连带被截断，数据少展示给客户，
    //           tierMatchStatus 判 FEWER，但其实前几对是真实有效的（P0 问题）。
    //   - 现在：某一对遇到 '-' 或解析失败时，在该位置补 null 占位，然后 continue 后续奖级。
    //           这样所有奖级位置都有索引，展示层 mergePrizeTiersWithRules 可以按索引
    //           正确对应奖项名，不会错位（一等奖永远对应 index=0，福运奖永远在 index=6）。
    private fun extractAllPrizeTiers(
        parts: List<String>,
        start: Int,
        extraFieldCount: Int,
        prizeTierPairCount: Int
    ): List<PrizeTierEntry?> {
        val all = mutableListOf<PrizeTierEntry?>()
        val prizeStart = start + extraFieldCount
        for (i in 0 until prizeTierPairCount) {
            val countIdx = prizeStart + i * 2
            val amountIdx = prizeStart + i * 2 + 1
            val countRaw = parts.getOrNull(countIdx)
            val amountRaw = parts.getOrNull(amountIdx)
            // 越界：后续奖级对都不存在，保持 null 占位并提前退出
            if (countRaw == null || amountRaw == null) {
                all.add(null)
                continue
            }
            // '-'：该级未公布，补 null，continue 不截断后续
            if (countRaw == "-" || amountRaw == "-") {
                all.add(null)
                continue
            }
            val count = parseNumberSafe(countRaw)
            val amount = parseNumberSafe(amountRaw)
            if (count == null || amount == null) {
                // 解析异常（非数字、畸形逗号位置），不整体失败、单个奖级标 null
                all.add(null)
                continue
            }
            all.add(PrizeTierEntry(count = count, amount = amount))
        }
        return all
    }

    private fun normalizeDate(raw: String): String? {
        if (raw.isEmpty()) return null
        if (dateRegex1.matches(raw)) return raw
        if (dateRegex2.matches(raw)) {
            val p = raw.split("/")
            if (p.size == 3) return "${p[0]}-${p[1].padStart(2, '0')}-${p[2].padStart(2, '0')}"
        }
        if (dateRegex3.matches(raw)) {
            val p = raw.split(".")
            if (p.size == 3) return "${p[0]}-${p[1].padStart(2, '0')}-${p[2].padStart(2, '0')}"
        }
        // 真二进制 Excel 日期是数字（jxl 转成 yyyy-mm-dd 字符串），此处兼容 jxl 原始格式
        val byJxl = raw.toDoubleOrNull()
        if (byJxl != null) {
            // jxl 默认把日期 cell 当字符串输出（上面 binaryXlsToSpaceText 用 contents），
            // 所以此分支很少命中，保底返回空。
            return null
        }
        return null
    }
}
