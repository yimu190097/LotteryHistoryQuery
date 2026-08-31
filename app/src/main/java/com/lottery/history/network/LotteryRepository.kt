package com.lottery.history.network

import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryTypeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object LotteryRepository {

    private val client: OkHttpClient by lazy {
        val b = OkHttpClient.Builder()
        b.connectTimeout(15L, TimeUnit.SECONDS)
        b.readTimeout(60L, TimeUnit.SECONDS)
        b.build()
    }

    private const val UA = "Mozilla/5.0"

    suspend fun fetchHistory(config: LotteryTypeConfig): List<LotteryDraw> =
        withContext(Dispatchers.IO) {
            val bodyBytes = fetchBytes(config.url)
            val list = bodyBytes.inputStream().use { stream ->
                LotteryXlsParser.parse(config, stream)
            }
            if (list.isEmpty()) error("empty parse for ${config.displayName}")
            // 真实奖级强校验（最后一道闸门：绝不入库没有真实奖级的假数据）
            //   规则：最近 3 期中，至少 1 期满足：
            //         「奖级对数量 ≥1」 且  「其中 ≥1 对的金额 > 0」
            //   解释：
            //     - ≥1 对：兼容排列5 这类只有 1 个奖级的玩法；
            //     - ≥1 金额>0：避免连续 (0,0) 空串误判（说明整段奖级都没解析到）。
            //   白名单：不用写白名单，七乐彩/福彩3D 官方虽会让最新一期尾部全 '-'（无奖级），
            //           但更早的 1~2 期一定有真实奖级数据，take(3) 自动命中通过。
            val realOk = list.take(3).any { d ->
                d.allPrizeTiers.filterNotNull().let { tiers ->
                    tiers.isNotEmpty() && tiers.any { t -> t.amount > 0 }
                }
            }
            if (!realOk) {
                error("no real prize tiers for ${config.displayName} in last 3 draws → 官方未公开奖级，按要求『异常删除』拒绝入库")
            }
            list
        }

    // P1-11: 对 5xx 与网络 IO 异常做有限指数退避重试；4xx 视为业务终态不重试
    private const val MAX_RETRIES = 2
    private const val RETRY_BASE_MS = 500L

    private fun fetchBytes(url: String): ByteArray {
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt <= MAX_RETRIES) {
            try {
                return doFetchBytes(url)
            } catch (e: Exception) {
                // 4xx（HTTPException）不重试，直接上抛
                if (e is HTTPException) throw e
                lastErr = e
                attempt++
                if (attempt <= MAX_RETRIES) {
                    Thread.sleep(RETRY_BASE_MS * attempt)
                }
            }
        }
        throw lastErr ?: error("fetch failed: $url")
    }

    private fun doFetchBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { resp ->
            if (resp.code in 400..499) throw HTTPException("HTTP ${resp.code}")
            if (!resp.isSuccessful) error("HTTP " + resp.code)
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    /** 4xx 业务终态，不参与重试 */
    class HTTPException(message: String) : java.io.IOException(message)
}
