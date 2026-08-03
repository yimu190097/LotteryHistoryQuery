package com.lottery.history.network

import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.model.PrizeTierEntry
import jxl.Sheet
import jxl.Workbook
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 17500.cn 开奖历史解析器。
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
                    // 整串数字去 .0
                    val normalized = v.toLongOrNull()?.toString()
                        ?: v.toDoubleOrNull()?.let { d ->
                            if (d == d.toLong().toDouble()) d.toLong().toString() else v
                        }
                        ?: v.trim()
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
                if (config.hasSecondary && config.rules.any { it.matchSecondary > 0 }) {
                    if (secondary.size < config.parseSecondaryCount) continue
                }

                // 号码之后的剩余部分：销售额、奖池、成对奖级数据
                val extraStart = numStart + config.parsePrimaryCount +
                    (if (config.hasSecondary) config.parseSecondaryCount else 0)
                val allTiers = extractAllPrizeTiers(parts, extraStart)

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
                        allPrizeTiers = allTiers
                    )
                )
            } catch (_: Exception) {
                // 单行错误忽略
            }
        }
        result.sortByDescending { it.issue }
        return result
    }

    // 从 parts 尾部的数值字段中提取【全部奖级】(注数,金额) 对列表（一等奖、二等奖、三等奖…按顺序）
    // 五步（100% 真实奖级，绝不回退 / 伪造）：
    //   0) '-' 号当作 null（七乐彩最新一期延迟公开：尾部16个全'-'，跳过整段不解析）
    //   1) 跳过紧随号码之后的重复/排序号码段（值 0..35 连续数字；ssq/dlt/7lc 常见）
    //   2) **预扫描：跳过销售额/奖池大数**：单个值 > 2_000_000（200万）的数字一律视为
    //        销售额 / 奖池 / 累计金额，直接跳过。—— 全国任何彩种的「中奖注数」从未超过 200 万：
    //        双色球六等奖（末等奖）最高一期约 170 万注，七乐彩六等奖最高 6 万注。
    //        （注：一等奖单注奖金 ≥ 500万，会被 "count≤500 && amount≥100万" 的白名单捞回来，
    //          因为「count 是注数，肯定在跳过之前已经先被校验了」，不会漏头奖。）
    //   3) 然后找 (count, amount) 成对
    //   4) 白名单/普通过滤：高奖金 count≤500 且 amount≥100万 直接通过；普通 amount 0..200万；
    //        count==0 && amount==0 空开保留。
    // 返回：按顺序的奖级列表。最多提取 15 级（覆盖全彩种）。
    private fun extractAllPrizeTiers(parts: List<String>, start: Int): List<PrizeTierEntry> {
        val nums = (start until parts.size).mapNotNull { idx ->
            val v = parts[idx]
            // 七乐彩最新一期尾部全 '-'，全部跳过后 nums.size<2 → 直接返回空
            if (v == "-" || v.isEmpty()) null else v.toLongOrNull()
        }
        val all = mutableListOf<PrizeTierEntry>()
        if (nums.size < 2) return all
        var i = 0
        // 1) 跳过紧随号码之后的重复/排序号码段（值 0..35）
        while (i < nums.size && nums[i] in 0..35) { i++ }
        // 2) 预扫描：单个值 > 2_000_000（200万）一律视为销售额/奖池/累计额 → 跳过
        //    七乐彩典型：销售额 5,264,472；奖池 2,583,654；都是 > 200 万，这一步全部吃掉。
        while (i < nums.size && nums[i] > 2_000_000L) { i++ }
        // 最多 15 级（大乐透规则最多到九等奖，加冗余到 15 足够）
        while (i + 1 < nums.size && all.size < 15) {
            val count = nums[i]
            val amount = nums[i + 1]
            // 注数上限严格 200 万（同 step2），避免奖池金额被误当 count，造成 pair 错位
            if (count !in 0..2_000_000L) {
                i += 1
                continue
            }
            if (amount < 0) {
                i += 1
                continue
            }
            // 高奖金白名单：count 很少（≤500注）且 amount ≥ 100万 → 典型浮动头奖/二等奖，直接通过
            val isBigPrize = count <= 500L && amount >= 1_000_000L
            // 普通奖金：0 ≤ amount ≤ 2_000_000（200万封顶，如双色球三等奖 3000、大乐透三等奖 10000 都在范围内）
            val isNormalPrize = amount in 0..2_000_000L
            // count==0 且 amount==0 也允许（空开）
            val isZeroOpen = count == 0L && amount == 0L
            if (isBigPrize || isNormalPrize || isZeroOpen) {
                all.add(PrizeTierEntry(count = count.toInt(), amount = amount))
                i += 2
            } else {
                i += 1
            }
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
