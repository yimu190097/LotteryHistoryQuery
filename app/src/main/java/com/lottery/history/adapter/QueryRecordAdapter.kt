package com.lottery.history.adapter

import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lottery.history.R
import com.lottery.history.data.QueryRecordManager
import com.lottery.history.util.BallTextHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 查询记录列表适配器：显示选号球 + 查询时间 + 导入按钮 + 删除按钮。
 * 点击"导入"仅恢复选号到选号区；点击"查询"恢复选号并自动查询。
 */
class QueryRecordAdapter(
    private val isPrimaryRed: Boolean,
    private val onImport: (QueryRecordManager.QueryRecord) -> Unit,
    private val onQuery: (QueryRecordManager.QueryRecord) -> Unit,
    private val onDelete: (Long) -> Unit
) : RecyclerView.Adapter<QueryRecordAdapter.VH>() {

    private val items = mutableListOf<QueryRecordManager.QueryRecord>()

    fun submit(list: List<QueryRecordManager.QueryRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 获取第一条记录（最新），供"导入最新一条"按钮使用 */
    fun getLatest(): QueryRecordManager.QueryRecord? = items.firstOrNull()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime: TextView = v.findViewById(R.id.tvQrTime)
        val llNumbers: ViewGroup = v.findViewById(R.id.llQrNumbers)
        val tvImport: TextView = v.findViewById(R.id.tvQrImport)
        val tvQuery: TextView = v.findViewById(R.id.tvQrQuery)
        val tvDelete: TextView = v.findViewById(R.id.tvQrDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_query_record, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        holder.tvTime.text = fmt.format(Date(r.timestamp))
        // 渲染号码球
        holder.llNumbers.removeAllViews()
        val res = holder.itemView.resources
        val density = res.displayMetrics.density
        val ballSize = res.getDimensionPixelSize(R.dimen.ball_compact_size)
        val margin = (4 * density).toInt()
        r.primaryNumbers.forEach { n ->
            holder.llNumbers.addView(createBall(n, true, holder.tvTime, ballSize, margin))
        }
        // 分隔符 +
        if (r.secondaryNumbers.isNotEmpty()) {
            val sep = TextView(holder.itemView.context)
            val params = ViewGroup.MarginLayoutParams(
                ViewGroup.MarginLayoutParams.WRAP_CONTENT,
                ViewGroup.MarginLayoutParams.WRAP_CONTENT
            )
            params.setMargins(margin * 2, 0, margin * 2, 0)
            sep.layoutParams = params
            sep.text = "+"
            sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            sep.setTextColor(android.graphics.Color.parseColor("#616161"))
            sep.gravity = Gravity.CENTER
            holder.llNumbers.addView(sep)
        }
        r.secondaryNumbers.forEach { n ->
            holder.llNumbers.addView(createBall(n, false, holder.tvTime, ballSize, margin))
        }

        // 导入按钮：仅恢复选号，不查询
        holder.tvImport.setOnClickListener { onImport(r) }
        // 查询按钮：恢复选号并自动查询
        holder.tvQuery.setOnClickListener { onQuery(r) }
        // 删除按钮
        holder.tvDelete.setOnClickListener { onDelete(r.id) }
    }

    private fun createBall(
        number: Int, isPrimary: Boolean, tvSample: TextView, ballSize: Int, margin: Int
    ): TextView {
        val ctx = tvSample.context
        val ball = TextView(ctx)
        val params = ViewGroup.MarginLayoutParams(ballSize, ballSize)
        params.setMargins(margin, margin, margin, margin)
        ball.layoutParams = params
        ball.gravity = Gravity.CENTER
        ball.text = String.format("%02d", number)
        ball.typeface = android.graphics.Typeface.MONOSPACE
        BallTextHelper.apply(ball, ballSize)
        if (isPrimary) {
            ball.setBackgroundResource(R.drawable.bg_ball_red)
        } else {
            ball.setBackgroundResource(R.drawable.bg_ball_blue)
        }
        ball.setTextColor(android.graphics.Color.WHITE)
        return ball
    }
}
