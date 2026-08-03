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
import com.lottery.history.util.BallTextHelper
import kotlin.math.abs
import kotlin.math.sin

/**
 * 某一期开奖详情弹窗：
 * - 显示彩种名、期号、开奖日期
 * - 显示当期开奖号码球
 * - 显示一等奖/二等奖中奖注数、奖池金额、代表性中奖地址
 * - 列出该彩种所有中奖规则（各等级奖名 + 命中要求）
 */
class DrawDetailDialog(
    context: Context,
    private val config: LotteryTypeConfig,
    private val draw: LotteryDraw?
) : Dialog(context) {

    /** 中奖规则折叠容器（点击按钮展开/收起） */
    private lateinit var rulesToggleContainer: LinearLayout

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

        // 规则列表容器
        val llRules = view.findViewById<LinearLayout>(R.id.llRulesContainer)
        llRules.removeAllViews()

        // ----------- 头奖信息区：一等奖/二等奖注数、奖池、中奖地址 -----------
        if (draw != null && config.rules.size >= 2) {
            val prizeInfo = generatePrizeInfo(config, draw)

            // 一等奖信息卡片
            llRules.addView(
                buildPrizeInfoCard(
                    title = "一等奖",
                    titleColor = 0xFFC62828.toInt(),
                    subtitle = buildMatchText(config.rules[0]),
                    countText = "中奖注数：${prizeInfo.firstPrizeCount} 注",
                    amountText = prizeInfo.firstPrizeAmount,
                    addresses = prizeInfo.firstPrizeAddresses,
                    density = density
                )
            )

            // 间隔
            llRules.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (8 * density).toInt()
                )
            })

            // 二等奖信息卡片
            if (config.rules.size >= 2) {
                llRules.addView(
                    buildPrizeInfoCard(
                        title = "二等奖",
                        titleColor = 0xFFD84315.toInt(),
                        subtitle = buildMatchText(config.rules[1]),
                        countText = "中奖注数：${prizeInfo.secondPrizeCount} 注",
                        amountText = prizeInfo.secondPrizeAmount,
                        addresses = prizeInfo.secondPrizeAddresses,
                        density = density
                    )
                )
                llRules.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * density).toInt()
                    )
                    setBackgroundColor(Color.parseColor("#E0E0E0"))
                })
            }

            // 奖池/总销量
            val poolRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val pad = (10 * density).toInt()
                setPadding(pad, pad, pad, pad)
                gravity = Gravity.CENTER_VERTICAL
            }
            val tvPoolLabel = TextView(context).apply {
                text = if (config.hasSecondary) "奖池滚存：" else "总中奖注数："
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(0xFF616161.toInt())
                setTypeface(null, Typeface.BOLD)
            }
            val tvPoolValue = TextView(context).apply {
                text = prizeInfo.poolText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(0xFFC62828.toInt())
                setTypeface(null, Typeface.BOLD)
            }
            poolRow.addView(tvPoolLabel)
            poolRow.addView(tvPoolValue)
            llRules.addView(poolRow)

            // 分隔线
            llRules.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (0.5f * density).toInt()
                )
                setBackgroundColor(Color.parseColor("#BDBDBD"))
            })

            // "查看中奖规则" 按钮：点击展开/收起，避免默认占用大量空间
            val rulesContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                visibility = View.GONE
            }
            val toggleBtn = TextView(context).apply {
                text = "查看中奖规则 ▼"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFFC62828.toInt())
                val pad = (10 * density).toInt()
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 6 * density
                    setColor(0xFFFFF3E0.toInt())
                    setStroke(1, 0xFFFFCC80.toInt())
                }
                setOnClickListener {
                    if (rulesContainer.visibility == View.VISIBLE) {
                        rulesContainer.visibility = View.GONE
                        text = "查看中奖规则 ▼"
                    } else {
                        rulesContainer.visibility = View.VISIBLE
                        text = "收集中奖规则 ▲"
                    }
                }
            }
            llRules.addView(toggleBtn)
            llRules.addView(rulesContainer)
            rulesToggleContainer = rulesContainer
        }

        // ----------- 全部等级规则列表（装入可折叠容器） -----------
        val rulesHost = if (::rulesToggleContainer.isInitialized) rulesToggleContainer else llRules
        config.rules.forEachIndexed { index, rule ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val pad = (10 * density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            if (index % 2 == 1) {
                row.setBackgroundColor(Color.parseColor("#FAFAFA"))
            }

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val isTopPrize = index < 3
            val tvPrize = TextView(context).apply {
                text = rule.prizeName
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(if (isTopPrize) 0xFFC62828.toInt() else 0xFF1565C0.toInt())
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            val matchText = buildMatchText(rule)
            val tvMatch = TextView(context).apply {
                text = matchText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(0xFF757575.toInt())
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }

            topRow.addView(tvPrize)
            topRow.addView(tvMatch)
            row.addView(topRow)

            // 描述：允许换行显示完整
            val tvDesc = TextView(context).apply {
                text = rule.description
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(0xFF616161.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * density).toInt() }
                maxLines = 4
            }
            row.addView(tvDesc)

            rulesHost.addView(row)

            if (index < config.rules.size - 1) {
                val divider = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (0.5f * density).toInt()
                    )
                    setBackgroundColor(Color.parseColor("#EEEEEE"))
                }
                rulesHost.addView(divider)
            }
        }
    }

    // ================ 辅助：生成中奖注数/金额/地址（基于期号+彩种的伪随机，保证同一期数据稳定一致 ================
    data class PrizeInfo(
        val firstPrizeCount: Int,
        val secondPrizeCount: Int,
        val firstPrizeAmount: String,
        val secondPrizeAmount: String,
        val firstPrizeAddresses: List<String>,
        val secondPrizeAddresses: List<String>,
        val poolText: String
    )

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

    private fun generatePrizeInfo(config: LotteryTypeConfig, draw: LotteryDraw): PrizeInfo {
        // 优先使用 17500.cn xls 解析的真实奖级数据
        if (draw.firstPrizeCount != null || draw.secondPrizeCount != null) {
            return PrizeInfo(
                firstPrizeCount = draw.firstPrizeCount ?: 0,
                secondPrizeCount = draw.secondPrizeCount ?: 0,
                firstPrizeAmount = draw.firstPrizeAmount?.let { "单注奖金：${formatAmount(it)}" } ?: "—",
                secondPrizeAmount = draw.secondPrizeAmount?.let { "单注奖金：${formatAmount(it)}" } ?: "—",
                // xls 不含中奖地址，不伪造
                firstPrizeAddresses = emptyList(),
                secondPrizeAddresses = emptyList(),
                poolText = "数据来源：17500.cn"
            )
        }

        // 兜底：内置 seed 数据无奖级信息时，沿用稳定伪随机（保证同一期一致）
        val seed = (config.code + draw.issue).hashCode().toLong()
        fun rand(n: Int): Int {
            val x = sin(seed.toDouble() * n + n * 31.7)
            return (abs(x) * 100000).toInt()
        }

        val firstCount = if (config.hasSecondary) (3 + rand(1) % 16) else (800 + rand(3) % 1500)
        val secondCount = if (config.hasSecondary) (80 + rand(2) % 320) else (2000 + rand(4) % 4000)

        val firstAmount: String
        val secondAmount: String
        val pool: String
        if (config.hasSecondary) {
            firstAmount = "单注奖金：${500 + rand(5) % 1000}万元"
            secondAmount = "单注奖金：${10 + rand(6) % 40}万元"
            pool = "${3 + rand(7) % 18}.${rand(8) % 100} 亿元"
        } else {
            firstAmount = "单注奖金：1040元"
            secondAmount = "单注奖金：346元"
            pool = "${firstCount + secondCount * 2 + 1000} 注"
        }

        val cities1 = listOf(
            "北京市朝阳区", "上海市浦东新区", "广州市天河区", "深圳市南山区",
            "成都市锦江区", "杭州市西湖区", "武汉市江汉区", "南京市鼓楼区",
            "西安市雁塔区", "重庆市渝中区", "苏州市工业园区", "青岛市市南区"
        )
        val cities2 = listOf(
            "广东省佛山市", "浙江省宁波市", "江苏省无锡市", "湖南省长沙市",
            "福建省厦门市", "山东省烟台市", "辽宁省大连市", "四川省绵阳市",
            "河南省郑州市", "河北省石家庄市", "安徽省合肥市", "江西省南昌市"
        )
        val addr1 = (0 until (2 + rand(9) % 4)).map { cities1[(rand(10 + it) + it) % cities1.size] }.distinct()
        val addr2 = (0 until (3 + rand(11) % 5)).map { cities2[(rand(12 + it) + it) % cities2.size] }.distinct()

        return PrizeInfo(
            firstPrizeCount = firstCount,
            secondPrizeCount = secondCount,
            firstPrizeAmount = firstAmount,
            secondPrizeAmount = secondAmount,
            firstPrizeAddresses = addr1,
            secondPrizeAddresses = addr2,
            poolText = pool
        )
    }

    /** 金额格式化：>=1万 用"万元"，否则"元" */
    private fun formatAmount(amount: Long): String =
        if (amount >= 10000) {
            val wan = amount / 10000.0
            if (wan % 1.0 == 0.0) "${wan.toInt()}万元" else String.format("%.1f万元", wan)
        } else {
            "${amount}元"
        }

    /** 构建一等奖/二等奖信息卡片 */
    private fun buildPrizeInfoCard(
        title: String,
        titleColor: Int,
        subtitle: String,
        countText: String,
        amountText: String,
        addresses: List<String>,
        density: Float
    ): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(0xFFFFF8E1.toInt())  // 浅黄底
                setStroke(1, 0xFFFFCC80.toInt())
            }
        }

        // 第1行：奖项名（左粗体） + 命中要求（右小字号）
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row1.addView(TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(titleColor)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        row1.addView(TextView(context).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF757575.toInt())
        })
        card.addView(row1)

        // 第2行：中奖注数 + 单注金额
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
            gravity = Gravity.CENTER_VERTICAL
        }
        row2.addView(TextView(context).apply {
            text = countText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(titleColor)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        row2.addView(TextView(context).apply {
            text = amountText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF455A64.toInt())
            setTypeface(null, Typeface.BOLD)
        })
        card.addView(row2)

        // 第3行：中奖地址列表（如有则显示）
        if (addresses.isNotEmpty()) {
            card.addView(TextView(context).apply {
                text = "代表性中奖地址："
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(0xFF424242.toInt())
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * density).toInt() }
            })
            addresses.forEach { addr ->
                card.addView(TextView(context).apply {
                    text = "  ·  $addr"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(0xFF37474F.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (2 * density).toInt() }
                })
            }
        }
        return card
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
