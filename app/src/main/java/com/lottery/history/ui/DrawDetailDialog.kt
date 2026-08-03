package com.lottery.history.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.lottery.history.R
import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.model.PrizeTierEntry
import com.lottery.history.util.BallTextHelper
import kotlin.math.abs
import kotlin.math.sin

/**
 * 某一期开奖详情弹窗（面向40+客户优化版）：
 * - 顶部：彩种名 + 期号 + 开奖日期
 * - 号码区：当期开奖号码球（红球+蓝球/前区+后区）
 * - 全部奖项列表：按 LotteryTypeConfig.rules 顺序，从一等奖到最低奖，每一项：
 *     奖项名称 | 命中规则 | 中奖注数 | 单注奖金
 *   全部展开，无需再点击"查看规则"按钮
 * - 若该期有真实奖级数据（17500.cn 解析），显示真实注数/金额；否则显示稳定伪随机兜底
 */
class DrawDetailDialog(
    context: Context,
    private val config: LotteryTypeConfig,
    private val draw: LotteryDraw?
) : Dialog(context) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_draw_detail, null)
        setContentView(view)
        window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window?.setGravity(Gravity.CENTER)
        val lp = window?.attributes
        lp?.horizontalMargin = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 12f, context.resources.displayMetrics
        )
        window?.attributes = lp
        setCancelable(true)

        view.findViewById<TextView>(R.id.tvDetailClose).setOnClickListener { dismiss() }
        render(view)
    }

    private fun render(view: View) {
        val density = context.resources.displayMetrics.density

        // 彩种名
        view.findViewById<TextView>(R.id.tvLotteryName).apply {
            text = config.displayName
            setTextColor(
                Color.parseColor(if (config.hasSecondary) "#C62828" else "#1565C0")
            )
        }

        // 期号 + 日期
        view.findViewById<TextView>(R.id.tvIssueAndDate).apply {
            if (draw != null) {
                val issue = draw.issue
                val date = draw.date.orEmpty()
                text = if (date.isNotEmpty()) "第 ${issue} 期  ·  ${date}" else "第 ${issue} 期"
            } else {
                text = "暂无开奖数据"
            }
        }

        // 号码球
        val flBalls = view.findViewById<com.lottery.history.widget.FlowLayout>(R.id.flDrawNumbers)
        flBalls.removeAllViews()
        val ballSize = (30 * density).toInt()
        val ballMargin = (4 * density).toInt()
        if (draw != null) {
            draw.primaryNumbers.sorted().forEach { num ->
                flBalls.addView(createBall(num, true, ballSize, ballMargin))
            }
            if (config.hasSecondary && draw.secondaryNumbers.isNotEmpty()) {
                val sep = TextView(context).apply {
                    text = "+"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTextColor(Color.parseColor("#616161"))
                    gravity = Gravity.CENTER
                    val lp = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(ballMargin * 2, 0, ballMargin * 2, 0)
                    layoutParams = lp
                }
                flBalls.addView(sep)
                draw.secondaryNumbers.sorted().forEach { num ->
                    flBalls.addView(createBall(num, false, ballSize, ballMargin))
                }
            }
        } else {
            flBalls.addView(TextView(context).apply {
                text = "暂无开奖号码"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Color.parseColor("#9E9E9E"))
            })
        }

        // 全部奖项列表容器
        val llRules = view.findViewById<LinearLayout>(R.id.llRulesContainer)
        llRules.removeAllViews()

        // 标题：当期全部奖项情况（40+客户一目了然）
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val tvTitle = TextView(context).apply {
            text = "本期全部奖项开奖情况"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFC62828.toInt())
        }
        titleRow.addView(tvTitle)
        // 数据来源说明
        titleRow.addView(TextView(context).apply {
            text = if (draw?.allPrizeTiers?.isNotEmpty() == true) "（数据来源：17500.cn 真实开奖）" else "（历史数据参考）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF757575.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (2 * density).toInt() }
        })
        llRules.addView(titleRow)

        // 表头：奖项名称 / 命中规则 / 中奖注数 / 单注奖金
        val headerRow = buildPrizeRow(
            prizeName = "奖项",
            matchText = "命中规则",
            countText = "注数",
            amountText = "单注奖金",
            density = density,
            isHeader = true
        )
        llRules.addView(headerRow)
        // 表头下分隔线
        llRules.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (0.8f * density).toInt()
            )
            setBackgroundColor(0xFFC62828.toInt())
        })

        // ----- 按 rules 顺序逐行渲染所有奖级 -----
        // 真实奖级 -> 按 rules 索引对齐（rules 可能有重复奖项名（如双色球"四等奖"出现多次），
        // 按"真实有数据的奖项数"从一等奖往下分配 allPrizeTiers 里对应 entry；
        // 重复名（如双色球 4+0 和 3+1 都叫四等奖）合并共用同一 entry）。
        val merged = mergePrizeTiersWithRules(config.rules, draw?.allPrizeTiers.orEmpty())
        merged.forEachIndexed { idx, row ->
            val entry = row.tierEntry
            val countText = when {
                entry != null && entry.count > 0 -> "${entry.count}注"
                entry != null -> "空开"
                else -> "—"
            }
            val amountText = when {
                entry != null && entry.amount > 0 -> formatAmount(entry.amount)
                entry != null && entry.count == 0L.toInt() -> "—"  // 空开无金额
                else -> "—"
            }
            val rowView = buildPrizeRow(
                prizeName = row.prizeName,
                matchText = row.matchText,
                countText = countText,
                amountText = amountText,
                density = density,
                highlightTop = idx < 3    // 前3等奖(一/二/三) 高亮色
            )
            llRules.addView(rowView)
            if (idx < merged.size - 1) {
                val divider = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (0.5f * density).toInt()
                    )
                    setBackgroundColor(Color.parseColor("#EEEEEE"))
                }
                llRules.addView(divider)
            }
        }

        // 末行：数据说明 / 底部留白
        val footer = TextView(context).apply {
            text = "注：空开表示本期无人中该奖项；重复奖项名（如双色球四等奖）两种命中规则共享同一组统计。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF9E9E9E.toInt())
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        llRules.addView(footer)
    }

    // ================ 数据对齐：rules ↔ allPrizeTiers 合并 ================
    private data class MergedPrizeRow(
        val prizeName: String,
        val matchText: String,
        val tierEntry: PrizeTierEntry?
    )

    /**
     * 把 config.rules（可能含重复奖项名，如双色球四等奖出现2次）
     * 与 draw.allPrizeTiers（按一等奖→二等奖→...真实顺序）合并对齐：
     *   - 规则中"连续同名奖级"（如 4+0 和 3+1 都叫四等奖）视为一个真实奖级，共享同一 entry
     *   - 去重后的 prizeName 索引即对应 allPrizeTiers[ idx ]
     *   - 若真实 entry 不足，未命中的显示 null（显示"—"）
     */
    private fun mergePrizeTiersWithRules(
        rules: List<LotteryTypeConfig.MatchRuleDef>,
        tiers: List<PrizeTierEntry?>
    ): List<MergedPrizeRow> {
        val merged = mutableListOf<MergedPrizeRow>()
        var dedupIdx = -1
        var lastName: String? = null
        rules.forEach { rule ->
            // 遇到新奖项名（与上一个不同）→ dedupIdx++
            if (rule.prizeName != lastName) {
                dedupIdx++
                lastName = rule.prizeName
            }
            val entry = tiers.getOrNull(dedupIdx)
            merged.add(
                MergedPrizeRow(
                    prizeName = rule.prizeName,
                    matchText = buildMatchText(rule),
                    tierEntry = entry
                )
            )
        }
        return merged
    }

    private fun buildMatchText(rule: LotteryTypeConfig.MatchRuleDef): String = buildString {
        append("中")
        append(rule.matchPrimary)
        append(config.primaryUnit)
        if (config.hasSecondary) {
            append(" + ")
            append(rule.matchSecondary)
            append(config.secondaryUnit)
        }
    }

    /** 金额格式化：>=1万 用"万元"，否则"元" */
    private fun formatAmount(amount: Long): String =
        if (amount >= 10000) {
            val wan = amount / 10000.0
            if (wan % 1.0 == 0.0) "${wan.toInt()}万元" else String.format("%.1f万元", wan)
        } else {
            "${amount}元"
        }

    /** ============== 构建「奖项名 | 命中规则 | 注数 | 单注奖金」4列行 ============== */
    private fun buildPrizeRow(
        prizeName: String,
        matchText: String,
        countText: String,
        amountText: String,
        density: Float,
        isHeader: Boolean = false,
        highlightTop: Boolean = false
    ): View {
        val cellPadV = if (isHeader) (8 * density).toInt() else (12 * density).toInt()
        val cellPadH = (4 * density).toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            // 非表头：奇数行浅灰底增强可读性（40+客户不串行）
            if (!isHeader && highlightTop) {
                setBackgroundColor(0xFFFFF8E1.toInt())  // 前三等级：浅黄底高亮
            }
        }

        // 列1：奖项名称（权重 1.2，头/红或蓝大字）
        val col1 = TextView(context).apply {
            text = prizeName
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
            gravity = Gravity.CENTER
            maxLines = 2
            if (isHeader) {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFFC62828.toInt())
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            } else {
                val color = when {
                    prizeName.contains("一等") -> 0xFFC62828.toInt()
                    prizeName.contains("二等") -> 0xFFD84315.toInt()
                    prizeName.contains("三等") -> 0xFFEF6C00.toInt()
                    prizeName.contains("四等") -> 0xFFF57C00.toInt()
                    prizeName.contains("未中") -> 0xFF9E9E9E.toInt()
                    else -> 0xFF37474F.toInt()
                }
                setTextColor(color)
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }
        }
        row.addView(col1)

        // 列2：命中规则（权重 2.0）
        val col2 = TextView(context).apply {
            text = matchText
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.0f)
            setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
            gravity = Gravity.CENTER
            maxLines = 2
            if (isHeader) {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFFD84315.toInt())
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            } else {
                setTextColor(0xFF546E7A.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        }
        row.addView(col2)

        // 列3：注数（权重 1.0）
        val col3 = TextView(context).apply {
            text = countText
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
            gravity = Gravity.CENTER
            maxLines = 1
            if (isHeader) {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFFEF6C00.toInt())
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            } else {
                val isEmpty = countText == "空开" || countText == "—"
                setTextColor(if (isEmpty) 0xFF9E9E9E.toInt() else 0xFFC62828.toInt())
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }
        }
        row.addView(col3)

        // 列4：单注奖金（权重 1.2）
        val col4 = TextView(context).apply {
            text = amountText
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
            gravity = Gravity.CENTER
            maxLines = 1
            if (isHeader) {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFFF57C00.toInt())
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            } else {
                setTextColor(0xFF1565C0.toInt())
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }
        }
        row.addView(col4)

        return row
    }

    // ================ 兼容：generatePrizeInfo 旧 API（其他地方可能仍引用） ================
    // 为避免外部未更新引用编译报错，保留但不再在 UI 中使用
    @Suppress("unused")
    private data class PrizeInfo(
        val firstPrizeCount: Int,
        val secondPrizeCount: Int,
        val firstPrizeAmount: String,
        val secondPrizeAmount: String,
        val firstPrizeAddresses: List<String>,
        val secondPrizeAddresses: List<String>,
        val poolText: String
    )

    @Suppress("unused")
    private fun generatePrizeInfo(config: LotteryTypeConfig, draw: LotteryDraw): PrizeInfo {
        // 优先使用真实奖级数据
        if (draw.firstPrizeCount != null || draw.secondPrizeCount != null) {
            return PrizeInfo(
                firstPrizeCount = draw.firstPrizeCount ?: 0,
                secondPrizeCount = draw.secondPrizeCount ?: 0,
                firstPrizeAmount = draw.firstPrizeAmount?.let { "单注奖金：${formatAmount(it)}" } ?: "—",
                secondPrizeAmount = draw.secondPrizeAmount?.let { "单注奖金：${formatAmount(it)}" } ?: "—",
                firstPrizeAddresses = emptyList(),
                secondPrizeAddresses = emptyList(),
                poolText = "数据来源：17500.cn"
            )
        }
        val seed = (config.code + draw.issue).hashCode().toLong()
        fun rand(n: Int): Int {
            val x = sin(seed.toDouble() * n + n * 31.7)
            return (abs(x) * 100000).toInt()
        }
        val firstCount = if (config.hasSecondary) (3 + rand(1) % 16) else (800 + rand(3) % 1500)
        val secondCount = if (config.hasSecondary) (80 + rand(2) % 320) else (2000 + rand(4) % 4000)
        val firstAmount = if (config.hasSecondary) "单注奖金：${500 + rand(5) % 1000}万元" else "单注奖金：1040元"
        val secondAmount = if (config.hasSecondary) "单注奖金：${10 + rand(6) % 40}万元" else "单注奖金：346元"
        val pool = if (config.hasSecondary) "${3 + rand(7) % 18}.${rand(8) % 100} 亿元" else "${firstCount + secondCount * 2 + 1000} 注"
        val cities1 = listOf("北京市朝阳区", "上海市浦东新区", "广州市天河区", "深圳市南山区", "成都市锦江区", "杭州市西湖区")
        val cities2 = listOf("广东省佛山市", "浙江省宁波市", "江苏省无锡市", "湖南省长沙市", "福建省厦门市", "山东省烟台市")
        val addr1 = (0 until (2 + rand(9) % 3)).map { cities1[(rand(10 + it) + it) % cities1.size] }.distinct()
        val addr2 = (0 until (3 + rand(11) % 4)).map { cities2[(rand(12 + it) + it) % cities2.size] }.distinct()
        return PrizeInfo(firstCount, secondCount, firstAmount, secondAmount, addr1, addr2, pool)
    }

    private fun createBall(
        number: Int, isPrimary: Boolean, ballSize: Int, margin: Int
    ): TextView {
        val ball = TextView(context)
        val params = ViewGroup.MarginLayoutParams(ballSize, ballSize)
        params.setMargins(margin, margin, margin, margin)
        ball.layoutParams = params
        ball.gravity = Gravity.CENTER
        ball.text = String.format("%02d", number)
        ball.typeface = Typeface.MONOSPACE
        BallTextHelper.apply(ball, ballSize)
        ball.setBackgroundResource(
            if (isPrimary) R.drawable.bg_ball_red else R.drawable.bg_ball_blue
        )
        ball.setTextColor(Color.WHITE)
        return ball
    }
}
