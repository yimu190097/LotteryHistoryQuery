package com.lottery.history.network

import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryTypeConfig
import jxl.Cell
import jxl.CellType
import jxl.DateCell
import jxl.NumberCell
import jxl.Sheet
import jxl.Workbook
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 17500.cn 开奖历史 XLS（Excel 97-2003）解析器。
 *
 * 17500 xls 每行字段约定：
 *   第 0 列：期号（Number 或 Label，形如 2024128）
 *   第 1 列：开奖日期（Date 或 Label，形如 2024-11-05 / 2024/11/05）
 *   第 2..M 列：主号码（前区/红球/基本号/百位十位个位…），顺序出现
 *   次号码（若彩种有）紧跟着主号码之后，数量由 config.parseSecondaryCount 控制
 *   剩余列：销售额/奖池/中奖注数等统计数据，直接忽略
 *
 * 容错策略：
 *   - 跳过表头行：期号非纯数字或日期无法解析即视为无效行
 *   - 号码范围校验：0-99（覆盖 0-9 位彩种、22选5、30选7、大乐透 1-35 等）
 *   - 主号码严格等于 parsePrimaryCount，否则跳过；次号码不足时按彩种要求严格校验
 *   - 所有解析异常单 try-catch，确保一行错误不影响其余
 */
object LotteryXlsParser {

    private val issueRegex = Regex("""^[0-9]{5,}$""")
    private val dateRegex1 = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val dateRegex2 = Regex("""^\d{4}/\d{1,2}/\d{1,2}$""")
    private val dateRegex3 = Regex("""^\d{4}\.\d{1,2}\.\d{1,2}$""")

    /** 从 InputStream 解析为开奖列表，按期号倒序返回。出现致命异常时抛出让上层兜底 TXT */
    fun parse(config: LotteryTypeConfig, input: InputStream): List<LotteryDraw> {
        val result = mutableListOf<LotteryDraw>()
        val workbook = Workbook.getWorkbook(input) ?: error("Workbook 为空")
        try {
            val sheet: Sheet = workbook.getSheet(0) ?: error("XLS 无 Sheet")
            val rows = sheet.rows
            val minColumns = 2 + config.parsePrimaryCount +
                (if (config.hasSecondary) config.parseSecondaryCount else 0)

            for (r in 0 until rows) {
                try {
                    val cells: Array<Cell> = sheet.getRow(r) ?: continue
                    if (cells.size < minColumns) continue

                    val issue = readIssue(cells.getOrNull(0)) ?: continue
                    val date = readDate(cells.getOrNull(1)) ?: continue

                    // 期号+日期双校验，避免误解析表头/说明行
                    if (!issueRegex.matches(issue)) continue
                    if (!dateRegex1.matches(date)) continue

                    // 主号码：列 2 .. 2+parsePrimaryCount-1
                    val startPrimary = 2
                    val primary = (0 until config.parsePrimaryCount).mapNotNull { idx ->
                        readIntCell(cells.getOrNull(startPrimary + idx))?.takeIf { it in 0..99 }
                    }
                    if (primary.size != config.parsePrimaryCount) continue

                    // 次号码：紧跟主号码后
                    val secondary = if (config.hasSecondary && config.parseSecondaryCount > 0) {
                        val startSec = startPrimary + config.parsePrimaryCount
                        (0 until config.parseSecondaryCount).mapNotNull { idx ->
                            val v = readIntCell(cells.getOrNull(startSec + idx))
                            v?.takeIf { it in 0..99 && it != -1 }
                        }
                    } else {
                        emptyList()
                    }
                    if (config.hasSecondary && config.rules.any { it.matchSecondary > 0 }) {
                        if (secondary.size < config.parseSecondaryCount) continue
                    }

                    result.add(LotteryDraw(issue, primary.sorted(), secondary.sorted(), date))
                } catch (_: Exception) {
                    // 单行解析出错，直接忽略，不中断整体解析
                }
            }
        } finally {
            runCatching { workbook.close() }
        }
        result.sortByDescending { it.issue }
        return result
    }

    // ============= 单元格读取工具 =============

    private fun readIssue(cell: Cell?): String? {
        if (cell == null) return null
        return when (cell.type) {
            CellType.NUMBER, CellType.NUMBER_FORMULA -> {
                val n = (cell as? NumberCell)?.value ?: return null
                // 期号是整数，去除 .0
                val longVal = n.toLong()
                if (longVal <= 0) null else longVal.toString()
            }
            else -> cell.contents?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun readDate(cell: Cell?): String? {
        if (cell == null) return null
        return when (cell.type) {
            CellType.DATE, CellType.DATE_FORMULA -> {
                val d: Date? = (cell as? DateCell)?.date
                d?.let { formatYmd(it) }
            }
            else -> {
                val s = cell.contents?.trim().orEmpty()
                normalizeDate(s)
            }
        }
    }

    private fun readIntCell(cell: Cell?): Int? {
        if (cell == null) return null
        return when (cell.type) {
            CellType.NUMBER, CellType.NUMBER_FORMULA -> {
                (cell as? NumberCell)?.value?.toInt()
            }
            CellType.EMPTY -> null
            else -> cell.contents?.trim()?.toIntOrNull()
        }
    }

    /** 把各种分隔符的日期统一成 YYYY-MM-DD，不符合返回 null */
    private fun normalizeDate(raw: String): String? {
        if (raw.isEmpty()) return null
        if (dateRegex1.matches(raw)) return raw
        if (dateRegex2.matches(raw)) {
            val parts = raw.split("/")
            if (parts.size == 3) {
                return "${parts[0]}-${parts[1].padStart(2, '0')}-${parts[2].padStart(2, '0')}"
            }
        }
        if (dateRegex3.matches(raw)) {
            val parts = raw.split(".")
            if (parts.size == 3) {
                return "${parts[0]}-${parts[1].padStart(2, '0')}-${parts[2].padStart(2, '0')}"
            }
        }
        return null
    }

    private val ymdFmt by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA) }
    private fun formatYmd(d: Date): String = ymdFmt.format(d)
}
