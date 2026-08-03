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

/**
 * 某一期开奖详情弹窗（面向40+客户优化版）：
 * - 顶部：彩种名 + 期号 + 开奖日期
 * - 号码区：当期开奖号码球（红球+蓝球/前区+后区）
 * - 全部奖项列表：按 LotteryTypeConfig.rules 顺序，从一等奖到最低奖，每一项：
 *     奖项名称 | 命中规则 | 中奖注数 | 单注奖金
 *   全部展开，无需再点击"查看规则"按钮
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

        // 彩种名（强化醒目，用户一眼看到现在查的是哪个彩种）
        view.findViewById<TextView>(R.id.tvLotteryName).apply {
            text = config.displayName
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(
                Color.parseColor(if (config.hasSecondary) "#C62828" else "#1565C0")
            )
        }

        // 期号 + 日期（加大字体，显示为"第XXXX期 · yyyy-MM-dd"）
        view.findViewById<TextView>(R.id.tvIssueAndDate).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            if (draw != null) {
                val issue = draw.issue
                val date = draw.date.orEmpty()
                text = if (date.isNotEmpty()) "第 ${issue} 期  ·  ${date}" else "第 ${issue} 期"
            } else {
                text = "暂无开奖数据"
            }
        }

        // 号码球（快乐8有20个号码，自动调小球+FlowLayout多行换行显示）
        val flBalls = view.findViewById<com.lottery.history.widget.FlowLayout>(R.id.flDrawNumbers)
        flBalls.removeAllViews()
        val ballSize = if (config.parsePrimaryCount >= 15) (22 * density).toInt() else (30 * density).toInt()
        val ballMargin = if (config.parsePrimaryCount >= 15) (3 * density).toInt() else (4 * density).toInt()
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

        // ----- 按 rules 顺序逐行渲染所有奖级（100%真实数据展示，绝不兜底）-----
        val merged = mergePrizeTiersWithRules(config.rules, draw?.allPrizeTiers.orEmpty())
        // 顶部提示：若当期真实 allPrizeTiers 少于规则去重后的奖级数，显式提示客户"部分未公布"
        val haveAnyRealTiers = merged.any { it.tierEntry != null }
        val missingCount = merged.groupBy { it.prizeName }.keys.size -
            merged.count { it.tierEntry != null }.coerceAtLeast(0)
        if (!haveAnyRealTiers) {
            val warn = TextView(context).apply {
                text = "⚠️ 本期注数/金额明细未加载（\"规则固定¥X\"为游戏规则设定的基础额度，" +
                    "实际兑付以当期官方公布为准，可能含派奖、浮动调整）"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFFE65100.toInt())   // 深橙底白字，醒目不误导
                val pad = (10 * density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            llRules.addView(warn)
        } else if (missingCount > 0) {
            val warn = TextView(context).apply {
                text = "说明：本期已公布 ${merged.groupBy { it.tierEntry != null }.size} 个奖级，" +
                    "其余 $missingCount 个奖级明细未完全加载（\"规则固定¥X\" 仅为规则基础额度，" +
                    "每期具体中奖金额以官方公布的实时数据为准，含派奖、浮动调整）。"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFF5D4037.toInt())
                setBackgroundColor(0xFFFFF3E0.toInt())
                val pad = (8 * density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            llRules.addView(warn)
        }

        merged.forEachIndexed { idx, row ->
            val entry = row.tierEntry

            // ---- 中奖注数列（只能用真实数据）----
            val countText = when {
                entry == null -> "—"
                entry.count > 0 -> "${entry.count}注"
                else -> "空开"   // entry.count == 0：真实数据就是空开
            }

            // ---- 单注奖金列（只能用真实数据）----
            val amountText = when {
                entry == null -> "—"
                entry.amount > 0L -> formatAmount(entry.amount)
                entry.count == 0 -> "空开无奖金"    // count 0 + amount 0：真实空开
                else -> "—"
            }

            val rowView = buildPrizeRow(
                prizeName = row.prizeName,
                matchText = row.matchText,
                countText = countText,
                amountText = amountText,
                density = density,
                highlightTop = idx < 3
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

        // 末行：规则说明（强调派奖/浮动调整，每期以官方公布为准；不暴露数据来源）
        val footer = TextView(context).apply {
            text = buildString {
                append("空开=本期无人中；\"规则固定¥X\" 为《游戏规则》设定的基础单注奖金额度，")
                append("实际兑付会随官方派奖、奖金浮动调整而变化，")
                append("**每期具体中奖金额以当期官方公布的数据为准**。")
                append("\n重复奖项名（如双色球四等奖）两种命中规则共享同一条注数/金额。")
            }
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
        val matchText: String, // 命中规则（含规则固定奖金说明：「规则固定¥X」写在此处，只作规则说明，不作真实开奖数据）
        val tierEntry: PrizeTierEntry? // 真实 allPrizeTiers 解析到的当期奖级（=null 表示本期未公开该奖级）
    )

    /**
     * 【真实性红线】：
     *   - 注数/单注奖金两列**只能**来自真实 draw.allPrizeTiers（官方公开开奖数据）
     *   - 规则 MatchRuleDef.fixedAmountYuan 只能拼到 matchText 规则描述里，标注「规则固定¥X」，
     *     明确告知客户这是游戏规则本身设定，不代表本期实际兑付金额。
     *   - 规则中"连续同名奖级"（如 4+0 和 3+1 都叫四等奖）视为一个真实奖级，共享同一 entry
     *   - 【快乐8 多子玩法奖级隔离】：config.realTiersToUse 指定从真实 allPrizeTiers 中只取前 N 个
     *     真实奖级对参与规则对齐（避免快乐8 allPrizeTiers 中 70+ 对子玩法奖级混入选十）
     */
    private fun mergePrizeTiersWithRules(
        rules: List<LotteryTypeConfig.MatchRuleDef>,
        tiers: List<PrizeTierEntry?>
    ): List<MergedPrizeRow> {
        val merged = mutableListOf<MergedPrizeRow>()
        // 只截取真实奖级前 N 对 —— N = config.realTiersToUse（快乐8=7，其他=rules.size）
        val trimmedTiers = tiers.take(config.realTiersToUse)
        var dedupIdx = -1
        var lastName: String? = null
        rules.forEach { rule ->
            if (rule.prizeName.contains("未中奖") || rule.prizeName.contains("无奖项")) {
                return@forEach
            }
            if (rule.prizeName != lastName) {
                dedupIdx++
                lastName = rule.prizeName
            }
            val entry = trimmedTiers.getOrNull(dedupIdx)
            // 命中规则 + 规则固定奖金（如双色球三等奖「规则固定¥3,000」）→ 拼到规则列，不进金额列
            val baseMatch = rule.description.ifEmpty { buildMatchText(rule) }
            val fullMatch = if (rule.fixedAmountYuan != null) {
                baseMatch + "（规则固定¥${rule.fixedAmountYuan}）"
            } else {
                baseMatch
            }
            merged.add(
                MergedPrizeRow(
                    prizeName = rule.prizeName,
                    matchText = fullMatch,
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

    // ================ 兼容：generatePrizeInfo 旧 API（编译期保留，仅真实数据，永不输出假数据） ================
    // 严格执行用户"数据必须真实，异常就删除"红线：
    //   - 仅使用真实 draw.allPrizeTiers / firstPrizeCount 字段；缺失就显示「未公开」
    //   - 禁止任何 rand() / 伪随机 / 兜底数字；禁止伪造中奖地址/奖池
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
    private fun generatePrizeInfo(@Suppress("UNUSED_PARAMETER") config: LotteryTypeConfig, draw: LotteryDraw): PrizeInfo {
        // 只有真实来源（first/second/amount 来自 allPrizeTiers）
        //   非空 → 显示真实值；空 → 统一标「本期奖级未公开」绝不兜底
        val firstCount = draw.firstPrizeCount
        val secondCount = draw.secondPrizeCount
        val firstAmt = draw.firstPrizeAmount
        val secondAmt = draw.secondPrizeAmount
        return PrizeInfo(
            firstPrizeCount = firstCount ?: 0,
            secondPrizeCount = secondCount ?: 0,
            firstPrizeAmount = firstAmt?.let { "单注奖金：${formatAmount(it)}" } ?: "本期奖级未公开",
            secondPrizeAmount = secondAmt?.let { "单注奖金：${formatAmount(it)}" } ?: "本期奖级未公开",
            firstPrizeAddresses = emptyList(),   // 永不输出假中奖地址
            secondPrizeAddresses = emptyList(),  // 永不输出假中奖地址
            poolText = "官方公开开奖数据"  // 永不输出假奖池
        )
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
