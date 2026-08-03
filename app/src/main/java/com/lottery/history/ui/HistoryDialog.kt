package com.lottery.history.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lottery.history.R
import com.lottery.history.adapter.HistoryAdapter
import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryTypeConfig

class HistoryDialog(
    context: Context,
    private val initialMatches: List<LotteryDraw>,
    private val config: LotteryTypeConfig,
    private val selectedPrimary: Set<Int> = emptySet(),
    private val selectedSecondary: Set<Int> = emptySet()
) : Dialog(context) {

    private lateinit var adapter: HistoryAdapter

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_history, null)
        setContentView(view)

        window?.let { w ->
            val params = w.attributes
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.CENTER
            val density = context.resources.displayMetrics.density
            val horizontalMargin = (12 * density).toInt()
            params.horizontalMargin = horizontalMargin.toFloat()
            w.attributes = params
        }
        setCancelable(true)

        val ivClose: TextView = view.findViewById(R.id.ivClose)
        val rvHistory: RecyclerView = view.findViewById(R.id.rvHistory)

        adapter = HistoryAdapter(
            historyList = initialMatches,
            config = config,
            selectedPrimary = selectedPrimary,
            selectedSecondary = selectedSecondary,
            onDrawDetailClick = { draw ->
                // 打开该期所有奖项详情弹窗
                DrawDetailDialog(context, config, draw).show()
            }
        )
        rvHistory.layoutManager = LinearLayoutManager(context)
        rvHistory.adapter = adapter

        val screenHeight = context.resources.displayMetrics.heightPixels
        val density = context.resources.displayMetrics.density
        val minH = (240 * density).toInt()
        val maxH = (400 * density).toInt()
        val dynamicH = (screenHeight * 0.4).toInt().coerceIn(minH, maxH)
        rvHistory.layoutParams = rvHistory.layoutParams.apply { height = dynamicH }

        ivClose.setOnClickListener { dismiss() }
    }
}
