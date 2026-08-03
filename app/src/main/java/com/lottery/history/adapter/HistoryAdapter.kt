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
    private val selectedSecondary: Set<Int> = emptySet()
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tvIndex)
        val tvIssue: TextView = view.findViewById(R.id.tvIssue)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val llNumbers: ViewGroup = view.findViewById(R.id.llNumbers)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val draw = historyList[position]
        holder.tvIndex.text = (position + 1).toString()
        holder.tvIssue.text = draw.issue
        holder.tvDate.text = draw.date.orEmpty()

        val res = holder.itemView.resources
        val density = res.displayMetrics.density
        val ballSize = res.getDimensionPixelSize(R.dimen.ball_compact_size)
        val margin = (4 * density).toInt()

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
