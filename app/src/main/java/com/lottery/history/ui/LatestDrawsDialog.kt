package com.lottery.history.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.lottery.history.R
import com.lottery.history.data.LotteryDataManager
import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryType
import com.lottery.history.util.BallTextHelper
import com.lottery.history.widget.FlowLayout

/**
 * 最新开奖汇总弹窗：一屏查看全部 8 个彩种的最新一期开奖号码。
 * 每个彩种一行：彩种名(加粗着色) + 期号/日期 + 号码球(FlowLayout 自适应换行)。
 * 点击某行可查看该彩种最近 N 期开奖详情。
 */
class LatestDrawsDialog(context: Context) : Dialog(context) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_latest_draws)
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

        findViewById<TextView>(R.id.ivLatestClose).setOnClickListener { dismiss() }
        renderAll()
    }

    private fun renderAll() {
        val container = findViewById<LinearLayout>(R.id.llLatestList)
        container.removeAllViews()
        val res = context.resources
        val density = res.displayMetrics.density
        val rowVerticalPadding = (8 * density).toInt()

        LotteryType.ALL.forEachIndexed { index, config ->
            val draw: LotteryDraw? = LotteryDataManager.getAllFromDb(context, config.code).firstOrNull()

            // 球大小：快乐8（20个号码）单独缩小，一屏内多行显示不挤
            val ballSize = if (config.parsePrimaryCount >= 15) (18 * density).toInt() else (22 * density).toInt()
            val ballMargin = if (config.parsePrimaryCount >= 15) (2 * density).toInt() else (3 * density).toInt()

            // 单个彩种卡片
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, rowVerticalPadding, 0, rowVerticalPadding)
                isClickable = true
                isFocusable = true
            }
            // 斑马纹背景，区分彩种
            if (index % 2 == 1) {
                row.setBackgroundColor(Color.parseColor("#FAFAFA"))
            }
            // 点击查看该彩种最近开奖详情
            row.setOnClickListener { showDetail(config) }

            // 第一行：彩种名 + 期号 + 日期
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val tvName = TextView(context).apply {
                text = config.displayName
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(if (config.hasSecondary) Color.parseColor("#C62828") else Color.parseColor("#1565C0"))
                typeface = Typeface.DEFAULT_BOLD
            }
            headerRow.addView(tvName)

            // ======= 最新一期适用政策标签徽章：和彩种名同排，防止用户误以为所有历史期都用当前规则 =======
            // 严格模式：绝不用 config.ruleVersions.first() 最新版保底。
            //   resolveRuleVersion 返回 null 时，显示红色"版本不明"警告徽章。
            val currentRule = draw?.resolveRuleVersion(config)
            val (badgeText, badgeTextColor, isMissing) = when {
                draw == null -> Triple("暂无数据", Color.parseColor("#9E9E9E"), false)
                currentRule != null -> {
                    val issueLabel = draw.issue.let { i -> "｜期号$i" }
                    Triple("${currentRule.policyLabel}$issueLabel", Color.parseColor("#1B5E20"), false)
                }
                else -> {
                    // 元数据缺失：红色警告徽章
                    val issueLabel = draw.issue.let { i -> "｜期号$i" }
                    Triple("【⚠版本不明】$issueLabel", Color.parseColor("#B71C1C"), true)
                }
            }
            val tvPolicyBadge = TextView(context).apply {
                text = badgeText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(badgeTextColor)
                setTypeface(null, Typeface.BOLD)
                setBackgroundResource(
                    if (isMissing) R.drawable.bg_policy_badge else R.drawable.bg_policy_badge
                )
                val padH = (5 * density).toInt()
                val padV = (2 * density).toInt()
                setPadding(padH, padV, padH, padV)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginStart = (6 * density).toInt()
                layoutParams = lp
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            headerRow.addView(tvPolicyBadge)

            val issueText = draw?.issue ?: "暂无"
            val tvIssue = TextView(context).apply {
                text = issueText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.parseColor("#212121"))
                typeface = Typeface.MONOSPACE
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = (8 * density).toInt()
                layoutParams = lp
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            headerRow.addView(tvIssue)

            val dateText = draw?.date.orEmpty()
            if (dateText.isNotEmpty()) {
                val tvDate = TextView(context).apply {
                    text = dateText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(Color.parseColor("#616161"))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                headerRow.addView(tvDate)
            }
            row.addView(headerRow)

            // 第二行：号码球（FlowLayout 自适应换行）
            val ballsContainer = FlowLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                val topMargin = (4 * density).toInt()
                (layoutParams as LinearLayout.LayoutParams).topMargin = topMargin
                clipChildren = false
                clipToPadding = false
            }

            if (draw == null) {
                val tvEmpty = TextView(context).apply {
                    text = "暂无数据"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(Color.parseColor("#9E9E9E"))
                    gravity = Gravity.CENTER
                }
                ballsContainer.addView(tvEmpty)
            } else {
                draw.primaryNumbers.sorted().forEach { num ->
                    ballsContainer.addView(createBall(num, true, ballSize, ballMargin))
                }
                if (config.hasSecondary && draw.secondaryNumbers.isNotEmpty()) {
                    // 分隔符
                    val sep = TextView(context).apply {
                        text = "+"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(Color.parseColor("#616161"))
                        gravity = Gravity.CENTER
                        val lp = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        lp.setMargins(ballMargin * 2, 0, ballMargin * 2, 0)
                        layoutParams = lp
                    }
                    ballsContainer.addView(sep)
                    draw.secondaryNumbers.sorted().forEach { num ->
                        ballsContainer.addView(createBall(num, false, ballSize, ballMargin))
                    }
                }
            }
            row.addView(ballsContainer)

            // 第三行：按钮「查看本期奖项详情」→ 4态自适应（暂无数据/暂未公布/数据不完整/正常）
            val hasValidTierData = run {
                if (draw == null) false
                else {
                    val hasAnyNonZero = draw.allPrizeTiers.any { t ->
                        t != null && (t.count > 0 || t.amount > 0L)
                    }
                    val hasFirstSecond = (draw.firstPrizeCount != null && draw.firstPrizeCount > 0) ||
                        (draw.firstPrizeAmount != null && draw.firstPrizeAmount > 0) ||
                        (draw.secondPrizeCount != null && draw.secondPrizeCount > 0) ||
                        (draw.secondPrizeAmount != null && draw.secondPrizeAmount > 0)
                    hasAnyNonZero || hasFirstSecond
                }
            }
            val isTierMismatch = draw != null && draw.tierMatchStatus != null &&
                draw.tierMatchStatus != "MATCH"

            val btnViewIssueDetail = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (40 * density).toInt()
                )
                lp.topMargin = (6 * density).toInt()
                lp.marginStart = (12 * density).toInt()
                lp.marginEnd = (12 * density).toInt()
                layoutParams = lp

                when {
                    draw == null -> {
                        text = "暂无开奖数据，请下拉刷新"
                        setTextColor(Color.parseColor("#616161"))
                        setBackgroundColor(Color.parseColor("#EEEEEE"))
                        isClickable = false
                    }
                    !hasValidTierData -> {
                        text = "本期奖级暂未公布，请稍后再试"
                        setTextColor(Color.parseColor("#E65100"))
                        setBackgroundColor(Color.parseColor("#FFF3E0"))
                        isClickable = false
                    }
                    isTierMismatch -> {
                        text = "数据不完整(${draw.tierMatchStatus})，点击查看"
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#F57C00"))
                        setOnClickListener { DrawDetailDialog(context, config, draw).show() }
                    }
                    else -> {
                        text = "查看本期奖项详情 ›"
                        setTextColor(Color.WHITE)
                        setBackgroundResource(R.drawable.bg_button_red)
                        setOnClickListener { DrawDetailDialog(context, config, draw).show() }
                    }
                }
            }
            row.addView(btnViewIssueDetail)

            // 第四行：头奖/二奖/奖池简略预览（不点击即可看关键信息）
            if (draw != null && hasValidTierData) {
                val sb = StringBuilder()
                draw.allPrizeTiers.getOrNull(0)?.let { t ->
                    if (t != null && (t.count > 0 || t.amount > 0L)) {
                        sb.append("头奖 ").append(t.count).append("注/").append(formatAmountShort(t.amount))
                    }
                }
                draw.allPrizeTiers.getOrNull(1)?.let { t ->
                    if (t != null && (t.count > 0 || t.amount > 0L)) {
                        if (sb.isNotEmpty()) sb.append("  ")
                        sb.append("二奖 ").append(t.count).append("注/").append(formatAmountShort(t.amount))
                    }
                }
                draw.jackpotAmount?.let { jp ->
                    if (jp > 0L) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append("奖池滚存：").append(formatAmountShort(jp)).append("元")
                    }
                }
                if (sb.isNotEmpty()) {
                    val preview = TextView(context).apply {
                        text = sb.toString()
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(Color.parseColor("#37474F"))
                        val lp2 = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        lp2.topMargin = (4 * density).toInt()
                        lp2.marginStart = (14 * density).toInt()
                        lp2.marginEnd = (14 * density).toInt()
                        layoutParams = lp2
                    }
                    row.addView(preview)
                }
            }

            container.addView(row)

            // 分隔线（最后一个不加）
            if (index < LotteryType.ALL.size - 1) {
                val divider = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (1 * density).toInt()
                    )
                    setBackgroundColor(Color.parseColor("#E0E0E0"))
                }
                container.addView(divider)
            }
        }
    }

    /** 点击某彩种行：弹出最近10期开奖详情 */
    private fun showDetail(config: com.lottery.history.model.LotteryTypeConfig) {
        val draws = LotteryDataManager.getAllFromDb(context, config.code).take(10)
        if (draws.isEmpty()) return
        dismiss()
        HistoryDialog(context, draws, config).show()
    }

    private fun createBall(number: Int, isPrimary: Boolean, ballSize: Int, margin: Int): TextView {
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

    /** 金额简略格式化：>=1亿→X亿；>=1万→X万；<1万→X元 */
    private fun formatAmountShort(amount: Long): String = when {
        amount >= 100_000_000L -> {
            val yi = amount / 100_000_000.0
            if (yi % 1.0 == 0.0) "${yi.toInt()}亿" else String.format("%.1f亿", yi)
        }
        amount >= 10_000L -> {
            val wan = amount / 10_000.0
            if (wan % 1.0 == 0.0) "${wan.toInt()}万" else String.format("%.1f万", wan)
        }
        else -> "${amount}元"
    }
}
