package com.lottery.history.adapter

import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lottery.history.R
import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.util.BallTextHelper

class HistoryAdapter(
    private var historyList: List<LotteryDraw>,
    private val config: LotteryTypeConfig,
    private val selectedPrimary: Set<Int> = emptySet(),
    private val selectedSecondary: Set<Int> = emptySet(),
    private val onDrawDetailClick: ((LotteryDraw) -> Unit)? = null
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRuleSectionHeader: TextView = view.findViewById(R.id.tvRuleSectionHeader)
        val tvIndex: TextView = view.findViewById(R.id.tvIndex)
        val tvIssue: TextView = view.findViewById(R.id.tvIssue)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvPolicyBadge: TextView = view.findViewById(R.id.tvPolicyBadge)
        val llNumbers: ViewGroup = view.findViewById(R.id.llNumbers)
        val llHitSummary: View = view.findViewById(R.id.llHitSummary)
        val tvHitPrimary: TextView = view.findViewById(R.id.tvHitPrimary)
        val tvHitSecondary: TextView = view.findViewById(R.id.tvHitSecondary)
        val btnViewDrawDetail: TextView = view.findViewById(R.id.btnViewDrawDetail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val draw = historyList[position]
        val ruleVersion = draw.resolveRuleVersion(config)
        val prevDraw = historyList.getOrNull(position - 1)
        val prevRuleVersion = prevDraw?.resolveRuleVersion(config)

        // ======= 规则版本分组标题（仅每组第一期显示，其余GONE） =======
        // 关键：当且仅当 当前期的ruleVersionKey 与 上一期的不同，才显示分组标题
        // 这样可以确保同一页面不同期数（规则不同的）被视觉隔开，用户不混淆
        val isFirstOfRuleGroup = (prevRuleVersion == null) || (prevRuleVersion.key != ruleVersion.key)
        if (isFirstOfRuleGroup) {
            holder.tvRuleSectionHeader.visibility = View.VISIBLE
            // 格式：● 【政策标签】 生效日期XXXX-XX-XX起 ｜ 共NN期 · 变更说明概要
            val groupCount = historyList.count { it.resolveRuleVersion(config).key == ruleVersion.key }
            holder.tvRuleSectionHeader.text = buildString {
                append("● ")
                append(ruleVersion.policyLabel)
                append(" ｜ 生效：")
                append(ruleVersion.effectiveFromDate)
                append("起 ｜ 本组")
                append(groupCount)
                append("期")
                append("\n")
                append("   说明：")
                append(ruleVersion.changeNote.take(80))
                if (ruleVersion.changeNote.length > 80) append("…")
            }
        } else {
            holder.tvRuleSectionHeader.visibility = View.GONE
        }

        holder.tvIndex.text = (position + 1).toString()
        holder.tvIssue.text = draw.issue
        holder.tvDate.text = draw.date.orEmpty()

        // ======= 政策标签徽章：每期item右上角都显示，让用户一眼知道本期规则版本 =======
        // 即便在组内也显示，防止用户滚动到中间时忘记当前属于哪个规则阶段
        holder.tvPolicyBadge.text = ruleVersion.policyLabel

        val hasSelection = selectedPrimary.isNotEmpty() || selectedSecondary.isNotEmpty()

        // 命中统计：选号与当期开奖的交集
        val hitPrimaryCount = draw.primaryNumbers.count { it in selectedPrimary }
        val hitSecondaryCount = if (config.hasSecondary) {
            draw.secondaryNumbers.count { it in selectedSecondary }
        } else 0

        if (hasSelection) {
            holder.llHitSummary.visibility = View.VISIBLE
            holder.tvHitPrimary.text = "中${hitPrimaryCount}${config.primaryUnit}"
            if (config.hasSecondary) {
                holder.tvHitSecondary.visibility = View.VISIBLE
                holder.tvHitSecondary.text = "中${hitSecondaryCount}${config.secondaryUnit}"
            } else {
                holder.tvHitSecondary.visibility = View.GONE
            }
        } else {
            holder.llHitSummary.visibility = View.GONE
        }

        // 查看当期所有奖项按钮：点击回调
        if (onDrawDetailClick != null) {
            holder.btnViewDrawDetail.visibility = View.VISIBLE
            holder.btnViewDrawDetail.setOnClickListener {
                onDrawDetailClick.invoke(draw)
            }
        } else {
            holder.btnViewDrawDetail.visibility = View.GONE
        }

        val res = holder.itemView.resources
        val density = res.displayMetrics.density
        // 快乐8 20个号码：缩小球和间距，FlowLayout多行换行显示
        val ballSize = if (config.parsePrimaryCount >= 15) {
            (20 * density).toInt()
        } else {
            res.getDimensionPixelSize(R.dimen.ball_compact_size)
        }
        val margin = if (config.parsePrimaryCount >= 15) (2 * density).toInt() else (4 * density).toInt()

        holder.llNumbers.removeAllViews()

        draw.primaryNumbers.sorted().forEach { num ->
            val ball = createBall(holder, num, true, ballSize, margin)
            holder.llNumbers.addView(ball)
        }

        if (config.hasSecondary && draw.secondaryNumbers.isNotEmpty()) {
            val separator = TextView(holder.itemView.context)
            val sepParams = ViewGroup.MarginLayoutParams(
                ViewGroup.MarginLayoutParams.WRAP_CONTENT,
                ViewGroup.MarginLayoutParams.WRAP_CONTENT
            )
            sepParams.setMargins(margin * 2, 0, margin * 2, 0)
            separator.layoutParams = sepParams
            separator.text = "+"
            separator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            separator.setTextColor(Color.parseColor("#616161"))
            separator.gravity = Gravity.CENTER
            holder.llNumbers.addView(separator)

            draw.secondaryNumbers.sorted().forEach { num ->
                val ball = createBall(holder, num, false, ballSize, margin)
                holder.llNumbers.addView(ball)
            }
        }
    }

    private fun createBall(
        holder: ViewHolder,
        number: Int,
        isPrimary: Boolean,
        ballSize: Int,
        margin: Int
    ): TextView {
        val ball = TextView(holder.itemView.context)
        val params = ViewGroup.MarginLayoutParams(ballSize, ballSize)
        params.setMargins(margin, margin, margin, margin)
        ball.layoutParams = params
        ball.gravity = Gravity.CENTER
        ball.text = String.format("%02d", number)
        ball.typeface = android.graphics.Typeface.MONOSPACE
        BallTextHelper.apply(ball, ballSize)

        val hasSelection = selectedPrimary.isNotEmpty() || selectedSecondary.isNotEmpty()
        val isMatched = if (isPrimary) {
            number in selectedPrimary
        } else {
            number in selectedSecondary
        }

        if (!hasSelection || isMatched) {
            if (isPrimary) {
                ball.setBackgroundResource(R.drawable.bg_ball_red)
            } else {
                ball.setBackgroundResource(R.drawable.bg_ball_blue)
            }
            ball.setTextColor(Color.WHITE)
        } else {
            if (isPrimary) {
                ball.setBackgroundResource(R.drawable.bg_ball_normal_red)
            } else {
                ball.setBackgroundResource(R.drawable.bg_ball_normal_blue)
            }
            ball.setTextColor(Color.parseColor("#616161"))
        }
        return ball
    }

    override fun getItemCount(): Int = historyList.size

    fun updateData(newList: List<LotteryDraw>) {
        historyList = newList
        notifyDataSetChanged()
    }
}
