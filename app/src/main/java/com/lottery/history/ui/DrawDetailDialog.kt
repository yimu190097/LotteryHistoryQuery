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
import com.lottery.history.model.ConditionalKey
import com.lottery.history.model.ConditionalValue
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

        // ===== 优先用 draw 已解析确定的 ruleVersionKey，不依赖运行时日期（保证历史数据不错位）=====
        val ruleVersion = draw?.resolveRuleVersion(config) ?: config.ruleVersions.first()

        // ============== 顶部：销售额 + 奖池 信息栏（新增v9） ==============
        val salesJackpotBar = buildSalesJackpotBar(density)
        val contentMain = view.findViewById<LinearLayout>(R.id.llRulesContainer)
        contentMain.removeAllViews()
        contentMain.addView(salesJackpotBar)

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

        // 全部奖项列表容器（复用上面的 contentMain = llRulesContainer，奖项区域从这里开始）
        val llRules = contentMain

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

        // ===== 结构一致性审计标记（v9新增：数据异常立即告警） =====
        draw?.tierMatchStatus?.let { status ->
            val warnText: String? = when (status) {
                com.lottery.history.model.TierMatchStatus.MATCH -> null
                com.lottery.history.model.TierMatchStatus.FEWER ->
                    "⚠ 结构提示：本期实际公布 ${draw.actualTierCount ?: 0} 个奖级，少于规则配置 " +
                        "${ruleVersion.realTiersToUse} 个（部分奖级可能停发或未公布）"
                com.lottery.history.model.TierMatchStatus.MORE ->
                    "✖ 数据异常：本期实际解析到 ${draw.actualTierCount ?: 0} 个奖级，多于规则配置 " +
                        "${ruleVersion.realTiersToUse} 个（数据源结构可能已变化，请更新应用）"
                com.lottery.history.model.TierMatchStatus.MISMATCH ->
                    "✖ 数据异常：未能解析到有效奖级数据，请稍后刷新或联系客服"
                else -> null
            }
            if (warnText != null) {
                val isError = status == com.lottery.history.model.TierMatchStatus.MORE ||
                    status == com.lottery.history.model.TierMatchStatus.MISMATCH
                val warn = TextView(context).apply {
                    text = warnText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    if (isError) {
                        setTextColor(0xFFFFFFFF.toInt())
                        setBackgroundColor(0xFFB71C1C.toInt())
                    } else {
                        setTextColor(0xFF5D4037.toInt())
                        setBackgroundColor(0xFFFFF3E0.toInt())
                    }
                    val pad = (8 * density).toInt()
                    setPadding(pad, (6 * density).toInt(), pad, (6 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (4 * density).toInt() }
                }
                llRules.addView(warn)
            }
        }

        // ===== 政策标签：可展开查看本期政策 + 所有历史版本变更说明 =====
        llRules.addView(buildPolicyExpandableCard(ruleVersion, density))

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

        // ----- 按 ruleVersion 顺序逐行渲染所有奖级（按期自动适配政策版本）-----
        val merged = mergePrizeTiersWithRules(ruleVersion, draw?.allPrizeTiers.orEmpty())
        // 顶部提示：若当期真实 allPrizeTiers 少于规则去重后的奖级数，显式提示客户"部分未公布"
        val haveAnyRealTiers = merged.any { it.totalCount != null }
        // 停发行(conditionalOff)不算"未公布"，它们是有明确状态（停发）的
        val missingCount = merged.count { it.totalCount == null && !it.conditionalOff }
        if (!haveAnyRealTiers) {
            // 本期全部奖级未公布：只展示简短提示（不啰嗦）
            val warn = TextView(context).apply {
                text = "注：本期注数/金额暂未公布，请稍后刷新。"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFFE65100.toInt())
                val pad = (8 * density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            llRules.addView(warn)
        } else if (missingCount > 0) {
            // 部分奖级缺数据：一句话说明（看情况展示）
            val warn = TextView(context).apply {
                text = "注：$missingCount 个奖级明细未完全公布。"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFF5D4037.toInt())
                setBackgroundColor(0xFFFFF3E0.toInt())
                val pad = (6 * density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            llRules.addView(warn)
        }

        merged.forEachIndexed { idx, row ->
            // ---- v11：条件奖级「停发」状态专属文案（福运奖 OFF 等）----
            //    关键：绝不出现"空开"字样；OFF 语义是"本期规则不开放"≠"本期没人中（空开）"
            val countText = when {
                row.conditionalOff -> "—（奖池未达门槛，本奖项停发）"
                row.totalCount == null -> "—"
                row.totalCount > 0 -> {
                    if (row.hasAppend && row.appendCount != null && row.appendCount > 0) {
                        "${row.totalCount}注\n（追加${row.appendCount}注）"
                    } else {
                        "${row.totalCount}注"
                    }
                }
                else -> "空开"   // totalCount == 0：真实空开
            }

            // ---- 单注奖金：金额不同分两行展示（绝不合并基本/追加金额） ----
            val amountText = buildString {
                val bAmt = row.baseAmount
                val aAmt = row.appendAmount
                val countEmpty = row.totalCount == null
                val isEmpty = row.totalCount == 0

                when {
                    row.conditionalOff -> append("—（奖池未达门槛，本奖项停发）")
                    countEmpty -> append("—")
                    isEmpty -> append("空开无奖金")
                    else -> {
                        var shown = false
                        if (bAmt != null && bAmt > 0L) {
                            append("基本投注 ").append(formatAmount(bAmt))
                            shown = true
                        }
                        if (row.hasAppend && aAmt != null && aAmt > 0L) {
                            if (shown) append("\n")
                            append("追加投注 ").append(formatAmount(aAmt))
                            shown = true
                        }
                        if (!shown) append("—")
                    }
                }
            }

            val rowView = buildPrizeRow(
                prizeName = row.prizeName,
                matchText = row.matchText,
                countText = countText,
                amountText = amountText,
                density = density,
                highlightTop = idx < 3,
                conditionalOff = row.conditionalOff
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

        // 末行：精炼说明（含奖池联动提示）— 使用解析时预计算的 conditionalFlags，不重算阈值
        val extraHint = buildString {
            append("空开=本期无人中；\"规则固定¥X\"为基础额度，每期实际金额以官方公布为准。")
            if (config.code == "dlt" && ruleVersion.key.startsWith("dlt_2026")) {
                val floatState = draw?.conditionalFlags?.get(ConditionalKey.DLT_2026_FLOAT)
                val jp = draw?.jackpotAmount
                append("\n★大乐透2026新规奖池联动：")
                if (jp != null) append("当前奖池${formatAmount(jp)}，")
                append(when (floatState) {
                    ConditionalValue.UP -> "≥8亿已上浮（三6666/四380/五200/六18/七7）"
                    ConditionalValue.NORMAL -> "<8亿未上浮（三5000/四300/五150/六15/七5）"
                    else -> "奖池未知，暂按基础金额展示"
                })
            }
            if (config.code == "ssq" && ruleVersion.key.startsWith("ssq_2026")) {
                val fuyunState = draw?.conditionalFlags?.get(ConditionalKey.SSQ_FUYUN)
                val jp = draw?.jackpotAmount
                append("\n★双色球福运奖双门槛：")
                if (jp != null) append("奖池${formatAmount(jp)}，")
                append(when (fuyunState) {
                    ConditionalValue.ON -> "≥15亿福运奖已开启（中3红=5元）"
                    ConditionalValue.OFF -> "<3亿福运奖已停止（中3红不中奖）"
                    else -> "3亿~15亿之间福运奖维持上期状态"
                })
            }
        }
        val footer = TextView(context).apply {
            text = extraHint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF9E9E9E.toInt())
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        llRules.addView(footer)
    }

    // ============ v9新增：顶部销售额 + 奖池 信息栏 ============
    private fun buildSalesJackpotBar(density: Float): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val pad = (10 * density).toInt()
            setPadding(pad, (6 * density).toInt(), pad, (6 * density).toInt())
            setBackgroundColor(0xFFE3F2FD.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        // 左：销售额
        val leftInfo = TextView(context).apply {
            val sales = draw?.salesAmount
            text = "销售额：${if (sales != null) formatAmount(sales) else "未公布"}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF0D47A1.toInt())
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(leftInfo)
        // 右：奖池（仅彩种有奖池概念时显示）
        val showJackpot = config.code in listOf("ssq", "dlt", "7lc", "7xc", "kl8")
        if (showJackpot) {
            val rightInfo = TextView(context).apply {
                val jp = draw?.jackpotAmount
                text = "奖池：${if (jp != null) formatAmount(jp) else "未公布"}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xFFC62828.toInt())
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            bar.addView(rightInfo)
        }
        return bar
    }

    // ============ v9新增：政策标签 + 可展开历史变更说明卡 ============
    private fun buildPolicyExpandableCard(currentRule: LotteryTypeConfig.RuleVersion, density: Float): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
            setBackgroundColor(0xFFE8F5E9.toInt())
        }
        val pad = (10 * density).toInt()
        // 标题行：本期政策标签 + "展开历史"按钮
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(pad, (8 * density).toInt(), pad, (4 * density).toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvCurrent = TextView(context).apply {
            text = "【本期适用】${currentRule.policyLabel}\n生效日期：${currentRule.effectiveFromDate}起"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF1B5E20.toInt())
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvCurrent)
        // 展开/收起 按钮（如果有多个历史版本）
        val detailContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        if (config.ruleVersions.size > 1) {
            val btnToggle = TextView(context).apply {
                text = "查看全部变更 ▼"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFF2E7D32.toInt())
                setBackgroundColor(0xFFC8E6C9.toInt())
                val p = (6 * density).toInt()
                setPadding(p, (3 * density).toInt(), p, (3 * density).toInt())
                setOnClickListener {
                    if (detailContainer.visibility == View.VISIBLE) {
                        detailContainer.visibility = View.GONE
                        text = "查看全部变更 ▼"
                    } else {
                        detailContainer.visibility = View.VISIBLE
                        text = "收起历史变更 ▲"
                    }
                }
            }
            headerRow.addView(btnToggle)
            // 展开内容：本期说明 + 所有历史版本
            val currentNote = TextView(context).apply {
                text = "● 本期说明：${currentRule.changeNote}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFF2E7D32.toInt())
                setPadding(pad, (4 * density).toInt(), pad, (6 * density).toInt())
            }
            detailContainer.addView(currentNote)
            // 其他版本（按生效日期从新到旧，排除本期）
            config.ruleVersions.filter { it.key != currentRule.key }.forEach { rv ->
                val versionLine = TextView(context).apply {
                    text = "○ ${rv.policyLabel}（${rv.effectiveFromDate}起）：\n   ${rv.changeNote}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(0xFF558B2F.toInt())
                    setPadding(pad, (3 * density).toInt(), pad, (3 * density).toInt())
                }
                detailContainer.addView(versionLine)
            }
        } else {
            // 只有一个版本：直接显示说明，不用展开
            val currentNote = TextView(context).apply {
                text = "● 规则说明：${currentRule.changeNote}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFF2E7D32.toInt())
                setPadding(pad, (4 * density).toInt(), pad, (6 * density).toInt())
            }
            card.addView(headerRow)
            card.addView(currentNote)
            return card
        }
        card.addView(headerRow)
        card.addView(detailContainer)
        return card
    }

    // ================ 数据对齐：rules ↔ 基本投注 + 追加投注 合并 ================
    /**
     * 合并后的展示行：
     *  - 同奖项名(prizeName)的所有命中方式（如大乐透三等奖=5+0和4+2）→ 命中规则列合并成多行文本
     *  - 同奖项名的基本投注注数 + 追加投注注数 → 注数列**合并累加**（只合并相同维度：注数是总量，可加）
     *  - 基本投注金额 vs 追加投注金额 → 金额列**分两行显示**（不同信息绝不合并）
     *  - 若追加投注全0或金额=基本×80%但同奖项，仍分开展示保证不误导
     */
    private data class MergedPrizeRow(
        val prizeName: String,
        val matchText: String,              // 多个命中规则换行拼接
        val totalCount: Int?,               // 基本投注注数 + 追加投注注数（累加合并，只合并"总注数"这类同质信息）
        val baseAmount: Long?,              // 基本投注金额（=null代表当期该级别未公布）
        val appendAmount: Long?,            // 追加投注金额（=null代表无追加/追加数据为空/两者金额已合并不可区分）
        val appendCount: Int?,              // 追加投注单独注数，用于展示时提示"追加投注多少注"（如果有的话）
        val hasAppend: Boolean,             // 是否有追加投注数据展示（用于金额分行）
        val conditionalOff: Boolean = false // v11: 条件奖级本期停发 → 整行置灰 + 专属提示文字
    )

    /**
     * 【核心原则】：
     *  1) 同等奖名 → 合并注数（加法合并同质信息）；金额不同分多行展示（异质信息绝不合并）。
     *  2) 规则版本按每期的 ruleVersionKey 自动适配（大乐透2026=7级 / 2019=9级，绝不混排）。
     *  3) 缺失数据就显示"—"，绝不兜底伪数据。
     */
    private fun mergePrizeTiersWithRules(
        ruleVersion: LotteryTypeConfig.RuleVersion,
        tiers: List<PrizeTierEntry?>
    ): List<MergedPrizeRow> {
        val appendTiers = draw?.appendPrizeTiers.orEmpty()
        val effectiveCount = minOf(ruleVersion.realTiersToUse, tiers.size)
        val trimmedTiers = tiers.take(effectiveCount)

        // ===== v11 条件性奖级动态判定 =====
        //  把 conditionalFlags 读出来，按 MatchRuleDef.conditionalKey 注入行为：
        //    SSQ_FUYUN: ON(开启) / OFF(本期停发) / HOLD(奖池未知)
        //    DLT_2026_FLOAT: UP(奖池≥8亿上浮) / NORMAL(未上浮) / HOLD(奖池未知)
        //  这些判定和 XlsParser 中的 conditionalFlags 生成逻辑严格一一对应，保证展示与解析一致。
        val flags = draw?.conditionalFlags.orEmpty()
        val fuyunState = flags[ConditionalKey.SSQ_FUYUN]
        val dltFloat = flags[ConditionalKey.DLT_2026_FLOAT]
        // DLT 新规实际单注金额（只有当 conditionalKey=DLT_2026_FLOAT 的规则才替换金额）
        //   NORMAL: 5000/300/150/15/5  (官方基础额度)
        //   UP:     6666/380/200/18/7   (奖池≥8亿上浮)
        val dltFloatOverrideMap: Map<String, Long>? =
            if (dltFloat == ConditionalValue.UP) mapOf(
                "5000" to 6666L, "300" to 380L, "150" to 200L, "15" to 18L, "5" to 7L
            ) else null

        // Step1: 按奖项名分组 → 收集同奖名下的 dedup indices（规则侧）和多条命中规则文本
        data class GroupInfo(
            val matchLines: MutableList<String> = mutableListOf(),
            val dedupIndex: Int, // 取第一次出现的 index 作为基本投注 entry 的定位
            var conditionalKey: String? = null, // 该组对应的条件奖级 key（若有）
            var fixedAmountToken: String? = null // DLT_FLOAT 时取金额字符串做 override 查询
        )
        val groups = linkedMapOf<String, GroupInfo>() // LinkedHashMap 按规则顺序保留
        var dedupIdx = -1
        var lastName: String? = null
        ruleVersion.rules.forEach { rule ->
            if (rule.prizeName.contains("未中奖") || rule.prizeName.contains("无奖项")) {
                return@forEach
            }
            if (rule.prizeName != lastName) {
                dedupIdx++
                lastName = rule.prizeName
            }
            val info = groups.getOrPut(rule.prizeName) {
                GroupInfo(
                    dedupIndex = dedupIdx,
                    conditionalKey = rule.conditionalKey,
                    fixedAmountToken = rule.fixedAmountYuan?.toString()
                )
            }
            val baseMatch = rule.description.ifEmpty { buildMatchText(rule) }

            // —— v11：条件性奖级展示逻辑 ——
            val fullMatch = when {
                // DLT 2026 浮动金额：显示本期实际的上下浮金额，不永远写死规则基础金额
                rule.conditionalKey == ConditionalKey.DLT_2026_FLOAT && rule.fixedAmountYuan != null -> {
                    val token = rule.fixedAmountYuan.toString()
                    val actual = dltFloatOverrideMap?.get(token) ?: rule.fixedAmountYuan
                    val prefix = when (dltFloat) {
                        ConditionalValue.UP -> "本期奖池≥8亿已上浮"
                        ConditionalValue.NORMAL -> "规则固定"
                        else -> "奖池未知，暂按规则固定"
                    }
                    "$baseMatch（$prefix¥$actual）"
                }

                rule.fixedAmountYuan != null -> {
                    "$baseMatch（规则固定¥${rule.fixedAmountYuan}）"
                }

                else -> baseMatch
            }
            info.matchLines.add(fullMatch)
        }

        // Step2: 对每个奖项组，计算注数合并/金额分行
        val result = mutableListOf<MergedPrizeRow>()
        groups.forEach { (prizeName, info) ->
            val baseEntry = trimmedTiers.getOrNull(info.dedupIndex)
            val appendEntry = if (ruleVersion.appendTierPairCount > 0) {
                appendTiers.getOrNull(info.dedupIndex)
            } else null

            // —— v11：条件奖级停发状态注入 ——
            //    福运奖 OFF：整行变灰、奖名"(本期不开放)"、注数/金额显示说明文字，
            //    **绝对不能出现"空开 无奖金"字样**（"空开"意味着规则在但没人中，
            //    而 OFF 意味着该期此奖项根本不开放，语义完全不同，会误导）。
            val fuyunDisabled =
                info.conditionalKey == ConditionalKey.SSQ_FUYUN && fuyunState == ConditionalValue.OFF

            val displayPrizeName = when {
                fuyunDisabled -> "$prizeName（本期不开放）"
                else -> prizeName
            }

            // —— 注数：同等奖合并（基本投注注数 + 追加投注注数，同质信息加法合并）——
            val baseCount = baseEntry?.count
            val appCount = appendEntry?.count
            val mergedCount: Int? = when {
                fuyunDisabled -> null // 停发状态：用专属文字展示，不显示数字
                baseCount != null && appCount != null && appCount > 0 -> baseCount + appCount
                else -> baseCount
            }

            // —— 金额：不同信息绝不合并（基本/追加金额分两行展示）——
            val bAmount = baseEntry?.amount?.takeIf { baseEntry.count?.let { c -> c > 0 || it == 0L } ?: true }
            val aAmount = appendEntry?.amount?.takeIf {
                appendEntry.count?.let { c -> c > 0 || it == 0L } ?: true
            }
            val hasAppendData = appendEntry != null && !fuyunDisabled &&
                (appendEntry.count?.let { it > 0 } == true || appendEntry.amount?.let { it > 0L } == true)

            result.add(
                MergedPrizeRow(
                    prizeName = displayPrizeName,
                    matchText = info.matchLines.joinToString("\n"),
                    totalCount = mergedCount,
                    baseAmount = if (fuyunDisabled) null else bAmount,
                    appendAmount = if (hasAppendData) aAmount else null,
                    appendCount = if (hasAppendData) appCount else null,
                    hasAppend = hasAppendData,
                    // v11: 条件性奖级外观标志（在 buildPrizeRow 中被用于浅灰底 + 文字）
                    conditionalOff = fuyunDisabled
                )
            )
        }
        return result
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
        highlightTop: Boolean = false,
        conditionalOff: Boolean = false   // v11: 条件奖级本期停发 → 整行置灰
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
            when {
                isHeader -> Unit
                conditionalOff -> setBackgroundColor(0xFFF5F5F5.toInt())   // 停发：浅灰底（视觉上降低权重）
                highlightTop -> setBackgroundColor(0xFFFFF8E1.toInt())      // 前三等级：浅黄底高亮
            }
        }

        // 列1：奖项名称（权重 1.2，头/红或蓝大字）
        val col1 = TextView(context).apply {
            text = prizeName
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
            gravity = Gravity.CENTER
            maxLines = 5
            if (isHeader) {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFFC62828.toInt())
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            } else {
                val color = when {
                    conditionalOff -> 0xFF9E9E9E.toInt()
                    prizeName.contains("一等") -> 0xFFC62828.toInt()
                    prizeName.contains("二等") -> 0xFFD84315.toInt()
                    prizeName.contains("三等") -> 0xFFEF6C00.toInt()
                    prizeName.contains("四等") -> 0xFFF57C00.toInt()
                    prizeName.contains("未中") -> 0xFF9E9E9E.toInt()
                    else -> 0xFF37474F.toInt()
                }
                setTextColor(color)
                setTypeface(null, Typeface.BOLD)
                setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    if (conditionalOff) 14f else 16f  // 停发稍小，视觉降权
                )
            }
        }
        row.addView(col1)

        // 列2：命中规则（权重 2.0）—— 支持多行（5+0和4+2合并三等奖时有多条）
        val col2 = TextView(context).apply {
            text = matchText
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.0f)
            setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
            gravity = Gravity.CENTER
            maxLines = 10
            if (isHeader) {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFFD84315.toInt())
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            } else {
                setTextColor(if (conditionalOff) 0xFFBDBDBD.toInt() else 0xFF546E7A.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (conditionalOff) 13f else 14f)
            }
        }
        row.addView(col2)

        // 列3：注数（权重 1.0）—— 支持多行：合并后注数换行显示追加X注
        val col3 = TextView(context).apply {
            text = countText
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
            gravity = Gravity.CENTER
            maxLines = 5
            if (isHeader) {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFFEF6C00.toInt())
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            } else {
                val isOffText = conditionalOff
                val isEmpty = countText == "空开" || countText == "—"
                setTextColor(
                    when {
                        isOffText -> 0xFFBDBDBD.toInt()
                        isEmpty -> 0xFF9E9E9E.toInt()
                        else -> 0xFFC62828.toInt()
                    }
                )
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (conditionalOff) 13f else 16f)
            }
        }
        row.addView(col3)

        // 列4：单注奖金（权重 1.2）—— 支持多行：基本投注 + 追加投注分两行
        val col4 = TextView(context).apply {
            text = amountText
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
            gravity = Gravity.CENTER
            maxLines = 5
            if (isHeader) {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFFF57C00.toInt())
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            } else {
                setTextColor(if (conditionalOff) 0xFFBDBDBD.toInt() else 0xFF1565C0.toInt())
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (conditionalOff) 13f else 16f)
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
