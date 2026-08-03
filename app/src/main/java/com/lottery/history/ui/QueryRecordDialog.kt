package com.lottery.history.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lottery.history.R
import com.lottery.history.adapter.QueryRecordAdapter
import com.lottery.history.data.QueryRecordManager
import com.lottery.history.model.LotteryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 查询历史弹窗：展示最近 10 天内的查询记录。
 * 点击"导入" -> 仅恢复选号到选号区，不自动查询；
 * 点击"导入最新一条" -> 导入第一条记录的选号并关闭弹窗；
 * 点击"删除" -> 立即移除该条记录。
 */
class QueryRecordDialog(
    context: Context,
    private val type: String,
    private val isPrimaryRed: Boolean,
    private val scope: LifecycleCoroutineScope,
    private val onPick: (QueryRecordManager.QueryRecord) -> Unit,
    private val onPickAndQuery: (QueryRecordManager.QueryRecord) -> Unit = onPick
) : Dialog(context) {

    private lateinit var adapter: QueryRecordAdapter
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvImportLatest: TextView

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_query_records)
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

        val title = findViewById<TextView>(R.id.tvQrTitle)
        val displayName = LotteryType.byCode(type).displayName
        title.text = "${displayName}·查询历史（10天）"
        tvEmpty = findViewById(R.id.tvQrEmpty)

        rv = findViewById(R.id.rvQueryRecords)
        rv.layoutManager = LinearLayoutManager(context)
        rv.addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        adapter = QueryRecordAdapter(
            isPrimaryRed = isPrimaryRed,
            onImport = { record ->
                onPick(record)
                dismiss()
            },
            onQuery = { record ->
                onPickAndQuery(record)
                dismiss()
            },
            onDelete = { id ->
                scope.launch {
                    withContext(Dispatchers.IO) { QueryRecordManager.deleteById(context, id) }
                    reload()
                }
            }
        )
        rv.adapter = adapter

        tvImportLatest = findViewById(R.id.tvQrImportLatest)
        tvImportLatest.setOnClickListener {
            val latest = adapter.getLatest()
            if (latest != null) {
                onPick(latest)
                dismiss()
            } else {
                Toast.makeText(context, "暂无可导入的记录", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<TextView>(R.id.tvQrClose).setOnClickListener { dismiss() }
    }

    override fun show() {
        super.show()
        reload()
    }

    fun reload() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                QueryRecordManager.getRecent(context, type, limit = 50)
            }
            adapter.submit(list)
            if (list.isEmpty()) {
                tvEmpty.visibility = TextView.VISIBLE
                rv.visibility = RecyclerView.GONE
                tvImportLatest.isEnabled = false
                tvImportLatest.setTextColor(Color.parseColor("#CCCCCC"))
            } else {
                tvEmpty.visibility = TextView.GONE
                rv.visibility = RecyclerView.VISIBLE
                tvImportLatest.isEnabled = true
                tvImportLatest.setTextColor(Color.parseColor("#C62828"))
            }
        }
    }
}
