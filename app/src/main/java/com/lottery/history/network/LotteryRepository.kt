package com.lottery.history.network

import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryTypeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 通用开奖历史数据拉取。
 *
 * 主数据源：17500.cn XLS 表格（用户要求改为表格获取，更稳定规范）
 *   → URL：config.url          （.xls 结尾）
 *   → 解析：LotteryXlsParser
 *
 * 兜底数据源：原 TXT 文本，若 XLS 任一环节失败（HTTP 非 200 / 解析异常 / 结果为空）自动回退。
 *   → URL：config.txtFallbackUrl（.TXT 结尾）
 *   → 解析：parseTxt 文本行切分
 */
object LotteryRepository {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS) // xls 是二进制，略放宽
            .build()
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    suspend fun fetchHistory(config: LotteryTypeConfig): List<LotteryDraw> =
        withContext(Dispatchers.IO) {
            // 1) 优先 XLS
            runCatching { fetchAndParseXls(config) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
            // 2) 失败则回退 TXT
                ?: runCatching { fetchAndParseTxt(config) }
                    .getOrNull()
                    .orEmpty()
        }

    // =============== XLS 主路径 ===============

    private fun fetchAndParseXls(config: LotteryTypeConfig): List<LotteryDraw> {
        val bodyBytes = fetchBytes(config.url)
        return bodyBytes.inputStream().use { stream ->
            LotteryXlsParser.parse(config, stream)
        }.also { list ->
            if (list.isEmpty()) error("XLS 解析结果为空，触发兜底")
        }
    }

    // =============== TXT 兜底路径 ===============

    private fun fetchAndParseTxt(config: LotteryTypeConfig): List<LotteryDraw> {
        val body = fetchText(config.txtFallbackUrl)
        return parseTxt(config, body)
    }

    // =============== HTTP 工具 ===============

    private fun fetchBytes(url: String): ByteArray {
        val request = buildRequest(url)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun fetchText(url: String): String {
        val request = buildRequest(url)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun buildRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", "http://www.17500.cn/")
            .build()

    // =============== TXT 解析（保留原逻辑，作兜底） ===============

    private fun parseTxt(config: LotteryTypeConfig, raw: String): List<LotteryDraw> {
        val result = mutableListOf<LotteryDraw>()
        val minParts = 2 + config.parsePrimaryCount + config.parseSecondaryCount
        val dateRegex = Regex("""^\d{4}-\d{2}-\d{2}$""")
        val issueRegex = Regex("""^[0-9]{5,}$""")

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < minParts) continue

            val issue = parts[0]
            val date = parts[1]

            if (!issueRegex.matches(issue)) continue
            if (!dateRegex.matches(date)) continue

            val numStart = 2

            val primary = try {
                (0 until config.parsePrimaryCount).mapNotNull { idx ->
                    val rawNum = parts.getOrNull(numStart + idx) ?: return@mapNotNull null
                    rawNum.toIntOrNull()?.takeIf { it in 0..99 }
                }
            } catch (_: Exception) { continue }
            if (primary.size != config.parsePrimaryCount) continue

            val secondary = if (config.hasSecondary && config.parseSecondaryCount > 0) {
                try {
                    val secStart = numStart + config.parsePrimaryCount
                    (0 until config.parseSecondaryCount)
                        .mapNotNull { idx ->
                            val v = parts.getOrNull(secStart + idx)
                            v?.takeIf { it != "-" && it != "" }?.toIntOrNull()
                                ?.takeIf { it in 0..99 }
                        }
                } catch (_: Exception) { emptyList() }
            } else {
                emptyList()
            }

            if (config.hasSecondary && config.rules.any { it.matchSecondary > 0 }) {
                if (secondary.size < config.parseSecondaryCount) continue
            }

            result.add(LotteryDraw(issue, primary.sorted(), secondary.sorted(), date))
        }
        result.sortByDescending { it.issue }
        return result
    }
}
