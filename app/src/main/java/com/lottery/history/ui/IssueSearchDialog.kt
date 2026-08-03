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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.lottery.history.R
import com.lottery.history.data.LotteryDataManager
import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryType
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.util.BallTextHelper
import com.lottery.history.widget.FlowLayout

/**
 * 按期号查询弹窗：选择彩种 + 输入期号 → 显示该期开奖号码与中奖公布信息。
 *
 * - 彩种下拉：8 个彩种
 * - 期号输入：纯数字，支持完整期号（如 2024128）或简写（如 24128）
 * - 查询结果：开奖号码球 + 一等奖/二等奖中奖注数与单注奖金
 * - 未找到该期：友好提示
 */
class IssueSearchDialog(context: Context) : Dialog(context) {

    private val density = context.resources.displayMetrics.density

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildView())
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
    }

    private fun buildView(): View {
        val pad = (14 * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // 标题
        root.addView(TextView(context).apply {
            text = "按期号查询开奖"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(Color.parseColor("#C62828"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })

        // 彩种选择
        root.addView(TextView(context).apply {
            text = "选择彩种"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.parseColor("#424242"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (12 * density).toInt()
            layoutParams = lp
        })
        val spinner = Spinner(context).apply {
            val names = LotteryType.ALL.map { it.displayName }
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, names).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(spinner)

        // 期号输入
        root.addView(TextView(context).apply {
            text = "输入期号"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.parseColor("#424242"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (10 * density).toInt()
            layoutParams = lp
        })
        val etIssue = EditText(context).apply {
            hint = "如 2024128 或 24128"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 16f
            background = null
            setPadding((8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val etContainer = LinearLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 6 * density
                setStroke(1, Color.parseColor("#BDBDBD"))
            }
            addView(etIssue)
        }
        root.addView(etContainer)

        // 查询按钮
        val btnQuery = Button(context).apply {
            text = "查询"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#C62828"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * density).toInt()
            )
            lp.topMargin = (14 * density).toInt()
            layoutParams = lp
        }
        root.addView(btnQuery)

        // 结果容器（滚动）
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
        }
        val resultContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(resultContainer)
        root.addView(scroll)

        // 关闭按钮
        val btnClose = TextView(context).apply {
            text = "关闭"
            setTextColor(Color.parseColor("#757575"))
            textSize = 15f
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * density).toInt()
            )
            lp.topMargin = (10 * density).toInt()
            layoutParams = lp
            setOnClickListener { dismiss() }
        }
        root.addView(btnClose)

        // 查询逻辑
        btnQuery.setOnClickListener {
            val cfg = LotteryType.ALL[spinner.selectedItemPosition]
            val input = etIssue.text.toString().trim()
            if (input.isEmpty()) {
                Toast.makeText(context, "请输入期号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val draw = findDraw(cfg, input)
            resultContainer.removeAllViews()
            if (draw == null) {
                resultContainer.addView(TextView(context).apply {
                    text = "未找到 ${cfg.displayName} 期号 $input 的开奖记录"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(Color.parseColor("#9E9E9E"))
                    gravity = Gravity.CENTER
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = (20 * density).toInt()
                    layoutParams = lp
                })
            } else {
                renderResult(resultContainer, cfg, draw)
            }
        }

        return root
    }

    /** 期号匹配：支持完整期号或简写（如 24128 匹配 2024128） */
    private fun findDraw(cfg: LotteryTypeConfig, input: String): LotteryDraw? {
        val list = LotteryDataManager.getCached(cfg.code)
        if (list.isEmpty()) return null
        // 精确匹配
        list.firstOrNull { it.issue == input }?.let { return it }
        // 简写匹配：input 长度 < issue 长度，且 issue 以 "20"+input 开头 或 issue 以 input 结尾
        return list.firstOrNull { issueDraw ->
            val issue = issueDraw.issue
            issue.length > input.length && (issue.endsWith(input) || issue == "20$input")
        }
    }

    private fun renderResult(container: LinearLayout, cfg: LotteryTypeConfig, draw: LotteryDraw) {
        // 快乐8 20个号码：缩小球+间距，FlowLayout自动多行换行显示
        val ballSize = if (cfg.parsePrimaryCount >= 15) (22 * density).toInt() else (30 * density).toInt()
        val ballMargin = if (cfg.parsePrimaryCount >= 15) (3 * density).toInt() else (4 * density).toInt()

        // 彩种名 + 期号 + 日期
        container.addView(TextView(context).apply {
            text = "${cfg.displayName}  第 ${draw.issue} 期"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Color.parseColor("#212121"))
            typeface = Typeface.DEFAULT_BOLD
        })
        draw.date?.let { d ->
            container.addView(TextView(context).apply {
                text = "开奖日期：$d"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.parseColor("#616161"))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (4 * density).toInt()
                layoutParams = lp
            })
        }

        // 号码球
        val flBalls = FlowLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
            clipChildren = false
            clipToPadding = false
        }
        draw.primaryNumbers.sorted().forEach { num ->
            flBalls.addView(createBall(num, true, ballSize, ballMargin))
        }
        if (cfg.hasSecondary && draw.secondaryNumbers.isNotEmpty()) {
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
        container.addView(flBalls)

        // 中奖公布信息
        container.addView(TextView(context).apply {
            text = "中奖公布信息"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.parseColor("#212121"))
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (14 * density).toInt()
            layoutParams = lp
        })

        val hasPrize = draw.firstPrizeCount != null || draw.secondPrizeCount != null
        if (hasPrize) {
            container.addView(buildPrizeRow("一等奖", draw.firstPrizeCount, draw.firstPrizeAmount, 0xFFC62828.toInt()))
            container.addView(buildPrizeRow("二等奖", draw.secondPrizeCount, draw.secondPrizeAmount, 0xFFD84315.toInt()))
        } else {
            container.addView(TextView(context).apply {
                text = "该期暂无中奖公布数据（请刷新获取最新数据）"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.parseColor("#9E9E9E"))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (6 * density).toInt()
                layoutParams = lp
            })
        }

        // 查看本期全部奖项详情（所有奖级 + 真实注数 + 单注奖金）→ 跳转 DrawDetailDialog
        container.addView(TextView(context).apply {
            text = "查看本期全部奖项详情 ›"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (46 * density).toInt()
            )
            lp.topMargin = (14 * density).toInt()
            layoutParams = lp
            setBackgroundResource(R.drawable.bg_button_red)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                DrawDetailDialog(context, cfg, draw).show()
            }
        })
    }

    private fun buildPrizeRow(label: String, count: Int?, amount: Long?, color: Int): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(0xFFFFF8E1.toInt())
                setStroke(1, 0xFFFFCC80.toInt())
            }
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        }
        row.addView(TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val countText = if (count != null && count > 0) "中奖 ${count} 注" else "本期空开"
        val amountText = amount?.let { "单注 ${formatAmount(it)}" } ?: "—"
        row.addView(TextView(context).apply {
            text = "$countText · $amountText"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.parseColor("#212121"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        })
        return row
    }

    private fun formatAmount(amount: Long): String =
        if (amount >= 10000) {
            val wan = amount / 10000.0
            if (wan % 1.0 == 0.0) "${wan.toInt()}万元" else String.format("%.1f万元", wan)
        } else {
            "${amount}元"
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
        ball.setBackgroundResource(if (isPrimary) R.drawable.bg_ball_red else R.drawable.bg_ball_blue)
        ball.setTextColor(Color.WHITE)
        return ball
    }
}
