package com.lottery.history.network

import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryTypeConfig
import jxl.Sheet
import jxl.Workbook
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 17500.cn 开奖历史解析器。
 *
 * 重要：17500.cn 的 *.xls 文件实际是【空格分隔的纯文本】（仅扩展名为 .xls），
 * 并非真正的 Excel 二进制。本解析器先嗅探文件头：
 *   - 若以 OLE2 头(0xD0CF11E0) 开头 → 走 jxl 真二进制 Excel 解析（兼容真 xls）。
 *   - 否则 → 按空格分隔文本解析（当前 17500.cn 实际格式）。
 *
 * 文本每行字段约定：
 *   第 0 列：期号（07001）
 *   第 1 列：开奖日期（2007-05-30）
 *   第 2..M 列：主号码 + 次号码（数量由 config 控制）
 *   随后若干 "-" 占位列、销售额、奖池
 *   之后成对出现：[奖级注数 单注奖金]（一等奖、二等奖、三等奖…）
 *
 * 本解析器会从尾部成对数值中提取【一等奖/二等奖】真实注数与单注奖金，
 * 供"查看详情"展示当期中奖具体信息（用户需求：点击查看详情显示一等奖几注、二等奖几注）。
 *
 * 容错：单行解析异常不影响其余行；结果按期号倒序返回。
 */
object LotteryXlsParser {

    private val issueRegex = Regex("""^[0-9]{5,}$""")
    private val dateRegex1 = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val dateRegex2 = Regex("""^\d{4}/\d{1,2}/\d{1,2}$""")
    private val dateRegex3 = Regex("""^\d{4}\.\d{1,2}\.\d{1,2}$""")

    /** OLE2 / 复合文档魔数头 */
    private val OLE2_HEADER = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte())

    fun parse(config: LotteryTypeConfig, input: InputStream): List<LotteryDraw> {
        // 嗅探：把流读成字节，判断是否真二进制 xls
        val bytes = input.readBytes()
        return if (bytes.size >= 4 && bytes[0] == OLE2_HEADER[0] && bytes[1] == OLE2_HEADER[1] &&
            bytes[2] == OLE2_HEADER[2] && bytes[3] == OLE2_HEADER[3]
        ) {
            parseBinaryXls(config, ByteArrayInputStream(bytes))
        } else {
            parseText(config, String(bytes, Charsets.UTF_8))
        }
    }

    // ============= 真二进制 Excel 解析（兼容真 xls，17500 当前不走此分支） =============
    private fun parseBinaryXls(config: LotteryTypeConfig, input: InputStream): List<LotteryDraw> {
        val result = mutableListOf<LotteryDraw>()
        val workbook = Workbook.getWorkbook(input) ?: error("Workbook 为空")
        try {
            val sheet: Sheet = workbook.getSheet(0) ?: error("XLS 无 Sheet")
            val rows = sheet.rows
            val minColumns = 2 + config.parsePrimaryCount +
                (if (config.hasSecondary) config.parseSecondaryCount else 0)
            for (r in 0 until rows) {
                try {
                    val cells = sheet.getRow(r) ?: continue
                    if (cells.size < minColumns) continue
                    val issue = cells.getOrNull(0)?.contents?.trim().orEmpty()
                    val dateRaw = cells.getOrNull(1)?.contents?.trim().orEmpty()
                    val date = normalizeDate(dateRaw) ?: continue
                    if (!issueRegex.matches(issue)) continue
                    val primary = (0 until config.parsePrimaryCount).mapNotNull { idx ->
                        cells.getOrNull(2 + idx)?.contents?.trim()?.toIntOrNull()?.takeIf { it in 0..99 }
                    }
                    if (primary.size != config.parsePrimaryCount) continue
                    val secondary = if (config.hasSecondary && config.parseSecondaryCount > 0) {
                        val start = 2 + config.parsePrimaryCount
                        (0 until config.parseSecondaryCount).mapNotNull { idx ->
                            cells.getOrNull(start + idx)?.contents?.trim()?.let { v ->
                                v.takeIf { it != "-" && it.isNotEmpty() }?.toIntOrNull()?.takeIf { it in 0..99 }
                            }
                        }
                    } else emptyList()
                    if (config.hasSecondary && config.rules.any { it.matchSecondary > 0 }) {
                        if (secondary.size < config.parseSecondaryCount) continue
                    }
                    // 真二进制 xls 列结构不稳定，奖级信息不提取
                    result.add(LotteryDraw(issue, primary.sorted(), secondary.sorted(), date))
                } catch (_: Exception) {
                    // 单行错误忽略
                }
            }
        } finally {
            runCatching { workbook.close() }
        }
        result.sortByDescending { it.issue }
        return result
    }

    // ============= 空格分隔文本解析（17500.cn *.xls 实际格式） =============
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

                // 提取奖级信息：从号码之后的剩余字段中找成对(注数, 单注奖金)
                val extraStart = numStart + config.parsePrimaryCount +
                    (if (config.hasSecondary) config.parseSecondaryCount else 0)
                val prize = extractPrizeTiers(parts, extraStart)

                result.add(
                    LotteryDraw(
                        issue = issue,
                        primaryNumbers = primary.sorted(),
                        secondaryNumbers = secondary.sorted(),
                        date = date,
                        firstPrizeCount = prize.firstCount,
                        firstPrizeAmount = prize.firstAmount,
                        secondPrizeCount = prize.secondCount,
                        secondPrizeAmount = prize.secondAmount
                    )
                )
            } catch (_: Exception) {
                // 单行错误忽略
            }
        }
        result.sortByDescending { it.issue }
        return result
    }

    /**
     * 从 extraStart 起的剩余字段中提取一等奖/二等奖成对数据。
     * 字段结构：[占位"-"... ] [销售额] [奖池?] [一等注数 一等奖金] [二等注数 二等奖金] ...
     * 启发式：跳过 "-" 与超大销售额/奖池，找到首个"小注数+大金额"对为一等奖，次个为二等奖。
     * 注数为 0 时金额通常为 0（空开），仍视为有效奖级。
     */
    private data class PrizeTier(
        val firstCount: Int?, val firstAmount: Long?,
        val secondCount: Int?, val secondAmount: Long?
    )

    private fun extractPrizeTiers(parts: List<String>, start: Int): PrizeTier {
        // 收集剩余数值（跳过 "-" 与非数字）
        val nums = (start until parts.size).mapNotNull { idx ->
            val v = parts[idx]
            if (v == "-" || v.isEmpty()) null else v.toLongOrNull()
        }
        if (nums.size < 4) return PrizeTier(null, null, null, null)

        // 扫描成对(注数, 金额)：注数 0..50000，金额>=100 或 (注数==0 且金额==0)
        val pairs = mutableListOf<Pair<Int, Long>>()
        var i = 0
        while (i + 1 < nums.size && pairs.size < 2) {
            val count = nums[i]
            val amount = nums[i + 1]
            if (count in 0..50000 && (amount >= 100 || (count == 0L && amount == 0L))) {
                pairs.add(count.toInt() to amount)
                i += 2
            } else {
                // 销售额/奖池等非奖级大数，跳过单个继续找
                i += 1
            }
        }
        val first = pairs.getOrNull(0)
        val second = pairs.getOrNull(1)
        return PrizeTier(
            firstCount = first?.first, firstAmount = first?.second,
            secondCount = second?.first, secondAmount = second?.second
        )
    }

    // ============= 工具 =============
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
        return null
    }
}
