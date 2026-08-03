#!/usr/bin/env kotlin
@file:DependsOn("com.squareup.okhttp3:okhttp:4.12.0")

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

// ===== 复制自项目源码的关键算法 =====
val issueRegex = Regex("""^[0-9]{5,}$""")
val dateRegex1 = Regex("""^\d{4}-\d{2}-\d{2}$""")
val dateRegex2 = Regex("""^\d{4}/\d{1,2}/\d{1,2}$""")
val dateRegex3 = Regex("""^\d{4}\.\d{1,2}\.\d{1,2}$""")

data class PrizeTierEntry(val count: Int, val amount: Long)
data class LotteryDraw(
    val issue: String,
    val primaryNumbers: List<Int>,
    val secondaryNumbers: List<Int>,
    val date: String?,
    val allPrizeTiers: List<PrizeTierEntry>
)

data class Cfg(
    val code: String,
    val displayName: String,
    val url: String,
    val parsePrimaryCount: Int,
    val parseSecondaryCount: Int,
    val hasSecondary: Boolean,
    // 官方实际奖级数（彩种规则里有「金额」的奖级数量）
    val expectedTierCount: Int,
    val rulesSize: Int  // rules 行数（可能 > tierCount 因为重复奖名比如四等奖两条）
)

val ssq = Cfg("ssq","双色球",
    "http://www.17500.cn/getData/ssq.XLS",
    parsePrimaryCount=6, parseSecondaryCount=1, hasSecondary=true,
    expectedTierCount=6, rulesSize=9) // 1-6等奖共6个真实tier，但rules有9行(4+1重复,5+0重复)
val dlt = Cfg("dlt","超级大乐透",
    "http://www.17500.cn/getData/dlt.XLS",
    parsePrimaryCount=5, parseSecondaryCount=2, hasSecondary=true,
    expectedTierCount=9, rulesSize=12)
val qlc = Cfg("qlc","七乐彩",
    "http://www.17500.cn/getData/qlc.XLS",
    parsePrimaryCount=7, parseSecondaryCount=1, hasSecondary=true,
    expectedTierCount=7, rulesSize=7)
val fc3d = Cfg("fc3d","福彩3D",
    "http://www.17500.cn/getData/3d.XLS",
    parsePrimaryCount=3, parseSecondaryCount=0, hasSecondary=false,
    expectedTierCount=3, rulesSize=3) // 单选、组三、组六
val p3 = Cfg("p3","排列三",
    "http://www.17500.cn/getData/pl3.XLS",
    parsePrimaryCount=3, parseSecondaryCount=0, hasSecondary=false,
    expectedTierCount=3, rulesSize=3)
val p5 = Cfg("p5","排列五",
    "http://www.17500.cn/getData/pl5.XLS",
    parsePrimaryCount=5, parseSecondaryCount=0, hasSecondary=false,
    expectedTierCount=1, rulesSize=1) // 只有一等奖
val qxc = Cfg("qxc","七星彩",
    "http://www.17500.cn/getData/qxc.XLS",
    parsePrimaryCount=7, parseSecondaryCount=0, hasSecondary=false,
    expectedTierCount=6, rulesSize=11)
val x22x5 = Cfg("22x5","22选5",
    "http://www.17500.cn/getData/22x5.XLS",
    parsePrimaryCount=5, parseSecondaryCount=0, hasSecondary=false,
    expectedTierCount=3, rulesSize=3)

val ALL = listOf(ssq, dlt, qlc, fc3d, p3, p5, qxc, x22x5)

// ====== extractAllPrizeTiers 复制自项目 ======
fun extractAllPrizeTiers(parts: List<String>, start: Int): List<PrizeTierEntry> {
    val nums = (start until parts.size).mapNotNull { idx ->
        val v = parts[idx]
        if (v == "-" || v.isEmpty()) null else v.toLongOrNull()
    }
    val all = mutableListOf<PrizeTierEntry>()
    if (nums.size < 2) return all
    var i = 0
    while (i < nums.size && nums[i] in 0..35) { i++ }
    while (i < nums.size && nums[i] > 100_0000_0000L) { i++ }
    while (i < nums.size && nums[i] > 200000) { i++ }
    while (i + 1 < nums.size && all.size < 15) {
        val count = nums[i]
        val amount = nums[i + 1]
        if (count in 0..200000 && (amount >= 100 || count == 0L)) {
            all.add(PrizeTierEntry(count = count.toInt(), amount = amount))
            i += 2
        } else {
            i += 1
        }
    }
    return all
}

fun normalizeDate(raw: String): String? {
    if (raw.isEmpty()) return null
    if (dateRegex1.matches(raw)) return raw
    if (dateRegex2.matches(raw)) {
        val p = raw.split("/")
        if (p.size == 3) return "${p[0]}-${p[1].padStart(2,'0')}-${p[2].padStart(2,'0')}"
    }
    if (dateRegex3.matches(raw)) {
        val p = raw.split(".")
        if (p.size == 3) return "${p[0]}-${p[1].padStart(2,'0')}-${p[2].padStart(2,'0')}"
    }
    return null
}

fun parseText(config: Cfg, raw: String): List<LotteryDraw> {
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
            val extraStart = numStart + config.parsePrimaryCount +
                (if (config.hasSecondary) config.parseSecondaryCount else 0)
            val allTiers = extractAllPrizeTiers(parts, extraStart)
            result.add(LotteryDraw(issue, primary.sorted(), secondary.sorted(), date, allTiers))
        } catch (_: Exception) {}
    }
    result.sortByDescending { it.issue }
    return result
}

// ===== 联网下载 + 解析 =====
val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS).build()
val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0"

fun fetchBytes(url: String): ByteArray {
    val req = Request.Builder().url(url)
        .header("User-Agent", UA)
        .header("Referer", "http://www.17500.cn/").build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        return resp.body?.bytes() ?: ByteArray(0)
    }
}

fun isOLE2(bytes: ByteArray) = bytes.size >= 4 &&
    bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
    bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()

for (cfg in ALL) {
    println("============ ${cfg.displayName}(${cfg.code}) ============")
    println("URL: ${cfg.url}")
    try {
        val bytes = fetchBytes(cfg.url)
        println("Downloaded ${bytes.size} bytes, OLE2=${isOLE2(bytes)}")
        // 文本解析：直接 UTF-8（真实 XLS 需要 jxl，但脚本里先用 String 看是否伪装 xls=文本）
        val text = String(bytes, Charsets.UTF_8)
        val draws = parseText(cfg, text)
        println("Parsed ${draws.size} draws")
        if (draws.isNotEmpty()) {
            val latest = draws.first()
            println("最新一期: issue=${latest.issue} date=${latest.date}")
            println("  号码: ${latest.primaryNumbers}+${latest.secondaryNumbers}")
            println("  提取到奖级数量=${latest.allPrizeTiers.size} (期望≈${cfg.expectedTierCount})")
            latest.allPrizeTiers.forEachIndexed { idx, t ->
                println("    Tier${idx+1}: count=${t.count}注, amount=¥${t.amount}")
            }
            // 抽样：倒数第 3 期
            if (draws.size >= 3) {
                val old = draws[2]
                println("  期号=${old.issue} 奖级数=${old.allPrizeTiers.size}: ${old.allPrizeTiers.take(5).map{ "${it.count}注¥${it.amount}" }}")
            }
            // 统计奖级分布
            val countsByTier = mutableMapOf<Int, Int>()
            draws.take(30).forEach { d -> countsByTier[d.allPrizeTiers.size] = (countsByTier[d.allPrizeTiers.size]?:0)+1 }
            println("  最近30期奖级数量分布: $countsByTier")
        }
    } catch (e: Exception) {
        println("  ❌ 失败: ${e.message}")
    }
    println()
}
