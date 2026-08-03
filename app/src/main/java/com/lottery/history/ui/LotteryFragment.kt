package com.lottery.history.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lottery.history.R
import com.lottery.history.data.LotteryDataManager
import com.lottery.history.data.QueryRecordManager
import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryType
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.model.QueryResultItem
import com.lottery.history.util.BallTextHelper
import com.lottery.history.util.LotteryMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通用彩种 Fragment：完全由 LotteryTypeConfig 驱动，支持任意彩种。
 * 双区彩种显示前区+后区选号网格，单区彩种自动隐藏后区。
 */
class LotteryFragment : Fragment() {

    private lateinit var config: LotteryTypeConfig

    private val selectedPrimary = LinkedHashSet<Int>()
    private val selectedSecondary = LinkedHashSet<Int>()

    private lateinit var gridPrimary: GridLayout
    private lateinit var gridSecondary: GridLayout
    private lateinit var tvPrimarySelected: TextView
    private lateinit var tvSecondarySelected: TextView
    private lateinit var llPrimarySelectedBalls: ViewGroup
    private lateinit var llSecondarySelectedBalls: ViewGroup
    private lateinit var btnReset: TextView
    private lateinit var btnQuery: TextView
    private lateinit var btnQueryHistory: TextView
    private lateinit var cardResult: CardView
    private lateinit var tvSelectedNumbers: TextView
    private lateinit var llResultRedBalls: ViewGroup
    private lateinit var llResultBlueBalls: ViewGroup
    private lateinit var resultContainer: LinearLayout
    // 后区相关容器（单区彩种时隐藏）
    private lateinit var secondarySection: View
    private lateinit var tvSecondaryTitle: TextView
    private lateinit var llResultSecondaryRow: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = arguments?.getString(ARG_CODE) ?: "ssq"
        config = LotteryType.byCode(code)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_lottery, container, false)
        initViews(view)
        setupLabels()
        setupBallGrids()
        setupButtons()
        updateSelectedTexts()
        showEmptyResultState()
        return view
    }

    private fun initViews(view: View) {
        gridPrimary = view.findViewById(R.id.gridPrimary)
        gridSecondary = view.findViewById(R.id.gridSecondary)
        tvPrimarySelected = view.findViewById(R.id.tvPrimarySelected)
        tvSecondarySelected = view.findViewById(R.id.tvSecondarySelected)
        llPrimarySelectedBalls = view.findViewById(R.id.llPrimarySelectedBalls)
        llSecondarySelectedBalls = view.findViewById(R.id.llSecondarySelectedBalls)
        btnReset = view.findViewById(R.id.btnReset)
        btnQuery = view.findViewById(R.id.btnQuery)
        btnQueryHistory = view.findViewById(R.id.btnQueryHistory)
        cardResult = view.findViewById(R.id.cardResult)
        tvSelectedNumbers = view.findViewById(R.id.tvSelectedNumbers)
        llResultRedBalls = view.findViewById(R.id.llResultRedBalls)
        llResultBlueBalls = view.findViewById(R.id.llResultBlueBalls)
        resultContainer = view.findViewById(R.id.resultContainer)
        secondarySection = view.findViewById(R.id.secondarySection)
        tvSecondaryTitle = view.findViewById(R.id.tvSecondaryTitle)
        llResultSecondaryRow = view.findViewById(R.id.llResultSecondaryRow)
    }

    /** 根据彩种配置设置标签文字和后区可见性 */
    private fun setupLabels() {
        // 卡片标题
        view?.findViewById<TextView>(R.id.tvPrimaryLabel)?.text = "选号对比"
        // 前区标题
        view?.findViewById<TextView>(R.id.tvPrimaryTitle)?.text = config.primaryLabel
        // 结果区前区标签
        view?.findViewById<TextView>(R.id.tvResultPrimaryLabel)?.text = config.primaryLabel

        if (config.hasSecondary) {
            secondarySection.visibility = View.VISIBLE
            llResultSecondaryRow.visibility = View.VISIBLE
            tvSecondaryTitle.text = config.secondaryLabel
            view?.findViewById<TextView>(R.id.tvResultSecondaryLabel)?.text = config.secondaryLabel
        } else {
            secondarySection.visibility = View.GONE
            llResultSecondaryRow.visibility = View.GONE
        }
    }

    private fun setupBallGrids() {
        val (columns, ballSize) = computeBallLayout()
        gridPrimary.columnCount = columns
        gridSecondary.columnCount = columns
        val margin = dpToPx(4f)
        val primaryRange = config.primaryMax - config.primaryMin + 1
        for (i in 0 until primaryRange) {
            val num = config.primaryMin + i
            gridPrimary.addView(createBall(num, true, ballSize, margin))
        }
        if (config.hasSecondary) {
            val secondaryRange = config.secondaryMax - config.secondaryMin + 1
            for (i in 0 until secondaryRange) {
                val num = config.secondaryMin + i
                gridSecondary.addView(createBall(num, false, ballSize, margin))
            }
        }
    }

    private fun computeBallLayout(): Pair<Int, Int> {
        val screenWidth = resources.displayMetrics.widthPixels
        // 页面padding + 卡片padding = 左右各约 20dp
        val totalPadding = dpToPx(20f) * 2
        val availableWidth = screenWidth - totalPadding
        val margin = dpToPx(3f)
        val minSize = resources.getDimensionPixelSize(R.dimen.ball_min_size)
        val maxSize = resources.getDimensionPixelSize(R.dimen.ball_max_size)

        // 根据号码范围动态选择列数：大范围(33)用更多列，小范围(10)用更少列
        val primaryRange = config.primaryMax - config.primaryMin + 1
        var columns = when {
            primaryRange <= 10 -> 5   // 3D/P3: 0-9, 5列
            primaryRange <= 12 -> 6   // DLT后区: 1-12, 6列
            primaryRange <= 22 -> 6   // 22选5: 1-22, 6列
            primaryRange <= 30 -> 7   // 七乐彩: 1-30, 7列
            else -> 7                 // 双色球: 1-33, 7列
        }
        var ballSize = (availableWidth - columns * margin) / columns
        // 如果球太小，减少列数
        while (ballSize < minSize && columns > 4) {
            columns--
            ballSize = (availableWidth - columns * margin) / columns
        }
        // 限制球的最大尺寸，避免宽屏上球过大
        ballSize = ballSize.coerceAtMost(maxSize)
        return Pair(columns, ballSize.coerceAtLeast(minSize))
    }

    private fun computeCompactBallSize(): Int {
        return resources.getDimensionPixelSize(R.dimen.ball_compact_size)
    }

    private fun createBall(number: Int, isPrimary: Boolean, ballSize: Int, margin: Int): TextView {
        val ball = TextView(requireContext())
        val params = GridLayout.LayoutParams()
        params.width = ballSize
        params.height = ballSize
        params.setMargins(margin, margin, margin, margin)
        ball.layoutParams = params
        ball.gravity = Gravity.CENTER
        ball.text = String.format("%02d", number)
        ball.typeface = Typeface.MONOSPACE
        BallTextHelper.apply(ball, ballSize)
        setBallNormalStyle(ball, isPrimary)
        ball.setOnClickListener { onBallClicked(number, isPrimary, ball) }
        return ball
    }

    private fun onBallClicked(number: Int, isPrimary: Boolean, ball: TextView) {
        if (isPrimary) {
            if (selectedPrimary.contains(number)) {
                selectedPrimary.remove(number)
                setBallNormalStyle(ball, true)
            } else {
                selectedPrimary.add(number)
                setBallSelectedStyle(ball, true)
            }
        } else {
            if (selectedSecondary.contains(number)) {
                selectedSecondary.remove(number)
                setBallNormalStyle(ball, false)
            } else {
                selectedSecondary.add(number)
                setBallSelectedStyle(ball, false)
            }
        }
        updateSelectedTexts()
    }

    private fun setBallNormalStyle(ball: TextView, isPrimary: Boolean) {
        if (isPrimary) {
            ball.setBackgroundResource(R.drawable.bg_ball_normal_red)
        } else {
            ball.setBackgroundResource(R.drawable.bg_ball_normal_blue)
        }
        ball.setTextColor(Color.parseColor("#333333"))
    }

    private fun setBallSelectedStyle(ball: TextView, isPrimary: Boolean) {
        if (isPrimary) {
            ball.setBackgroundResource(R.drawable.bg_ball_red)
        } else {
            ball.setBackgroundResource(R.drawable.bg_ball_blue)
        }
        ball.setTextColor(Color.WHITE)
    }

    private fun updateSelectedTexts() {
        tvPrimarySelected.text = "${config.primaryLabel}已选${selectedPrimary.size}个"

        // 渲染已选号码球
        val ballSize = computeCompactBallSize()
        val margin = dpToPx(4f)

        llPrimarySelectedBalls.removeAllViews()
        selectedPrimary.sorted().forEach { num ->
            llPrimarySelectedBalls.addView(createSelectedBall(num, true, ballSize, margin))
        }

        if (config.hasSecondary) {
            tvSecondarySelected.text = "${config.secondaryLabel}已选${selectedSecondary.size}个"
            llSecondarySelectedBalls.removeAllViews()
            selectedSecondary.sorted().forEach { num ->
                llSecondarySelectedBalls.addView(createSelectedBall(num, false, ballSize, margin))
            }
        }
    }

    private fun createSelectedBall(number: Int, isPrimary: Boolean, ballSize: Int, margin: Int): TextView {
        val ball = TextView(requireContext())
        val params = ViewGroup.MarginLayoutParams(ballSize, ballSize)
        params.setMargins(margin, margin, margin, margin)
        ball.layoutParams = params
        ball.gravity = Gravity.CENTER
        ball.text = String.format("%02d", number)
        ball.typeface = Typeface.MONOSPACE
        BallTextHelper.apply(ball, ballSize)
        if (isPrimary) {
            ball.setBackgroundResource(R.drawable.bg_ball_red)
        } else {
            ball.setBackgroundResource(R.drawable.bg_ball_blue)
        }
        ball.setTextColor(Color.WHITE)
        return ball
    }

    private fun setupButtons() {
        btnReset.setOnClickListener {
            selectedPrimary.clear()
            selectedSecondary.clear()
            for (i in 0 until gridPrimary.childCount) {
                setBallNormalStyle(gridPrimary.getChildAt(i) as TextView, true)
            }
            for (i in 0 until gridSecondary.childCount) {
                setBallNormalStyle(gridSecondary.getChildAt(i) as TextView, false)
            }
            updateSelectedTexts()
            showEmptyResultState()
        }

        btnQuery.setOnClickListener { performQuery() }

        btnQueryHistory.setOnClickListener {
            QueryRecordDialog(
                context = requireContext(),
                type = config.code,
                isPrimaryRed = true,
                scope = viewLifecycleOwner.lifecycleScope,
                onPick = { record ->
                    // 仅导入选号到选号区，不自动查询
                    selectedPrimary.clear()
                    selectedPrimary.addAll(record.primaryNumbers)
                    selectedSecondary.clear()
                    selectedSecondary.addAll(record.secondaryNumbers)
                    refreshGridBallsVisualState()
                    updateSelectedTexts()
                },
                onPickAndQuery = { record ->
                    // 导入选号并自动查询
                    selectedPrimary.clear()
                    selectedPrimary.addAll(record.primaryNumbers)
                    selectedSecondary.clear()
                    selectedSecondary.addAll(record.secondaryNumbers)
                    refreshGridBallsVisualState()
                    updateSelectedTexts()
                    performQuery()
                }
            ).show()
        }
    }

    private fun performQuery() {
        if (selectedPrimary.isEmpty() || (config.hasSecondary && selectedSecondary.isEmpty())) {
            showEmptyResultState()
            return
        }

        // 配额校验：未登录引导登录；有配额则扣减，无配额提示
        val authRepo = com.lottery.history.data.AuthRepository(requireContext())
        val quotaRepo = com.lottery.history.data.QuotaRepository(requireContext())
        val phone = authRepo.currentPhone()
        if (phone == null) {
            android.widget.Toast.makeText(
                requireContext(), "请先登录后查询", android.widget.Toast.LENGTH_SHORT
            ).show()
            AuthDialog(requireContext(), viewLifecycleOwner.lifecycleScope, AuthDialog.Mode.LOGIN) {
                (activity as? com.lottery.history.MainActivity)?.refreshUserInfoBarFromFragment()
            }.show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { quotaRepo.consumeOneQuery(phone) }
            if (!ok) {
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.quota_insufficient),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            // 扣减成功，执行查询
            val results = LotteryMatcher.match(config, selectedPrimary, selectedSecondary, getHistory())
            showResults(results)
            withContext(Dispatchers.IO) {
                QueryRecordManager.saveQuery(
                    context = requireContext(),
                    type = config.code,
                    primary = selectedPrimary,
                    secondary = selectedSecondary
                )
            }
        }
    }

    private fun getHistory(): List<LotteryDraw> = LotteryDataManager.getCached(config)

    private fun refreshGridBallsVisualState() {
        for (i in 0 until gridPrimary.childCount) {
            val ball = gridPrimary.getChildAt(i) as TextView
            val num = config.primaryMin + i
            if (selectedPrimary.contains(num)) {
                setBallSelectedStyle(ball, true)
            } else {
                setBallNormalStyle(ball, true)
            }
        }
        for (i in 0 until gridSecondary.childCount) {
            val ball = gridSecondary.getChildAt(i) as TextView
            val num = config.secondaryMin + i
            if (selectedSecondary.contains(num)) {
                setBallSelectedStyle(ball, false)
            } else {
                setBallNormalStyle(ball, false)
            }
        }
    }

    private fun showEmptyResultState() {
        cardResult.visibility = View.VISIBLE
        tvSelectedNumbers.text = "请选择号码后点击查询"
        tvSelectedNumbers.setTextColor(Color.parseColor("#616161"))
        llResultRedBalls.removeAllViews()
        llResultBlueBalls.removeAllViews()
        resultContainer.removeAllViews()

        val emptyResults = config.rules.map { rule ->
            QueryResultItem(
                matchPrimary = rule.matchPrimary,
                matchSecondary = rule.matchSecondary,
                prizeName = rule.prizeName,
                count = 0,
                matches = emptyList()
            )
        }
        renderResultRows(emptyResults)
    }

    private fun showResults(results: List<QueryResultItem>) {
        cardResult.visibility = View.VISIBLE
        resultContainer.removeAllViews()

        tvSelectedNumbers.text = "您选的号码，在历史上："
        tvSelectedNumbers.setTextColor(Color.parseColor("#333333"))

        val ballSize = computeCompactBallSize()
        val margin = dpToPx(4f)
        llResultRedBalls.removeAllViews()
        selectedPrimary.sorted().forEach { num ->
            llResultRedBalls.addView(createResultBall(num, true, ballSize, margin))
        }
        if (config.hasSecondary) {
            llResultBlueBalls.removeAllViews()
            selectedSecondary.sorted().forEach { num ->
                llResultBlueBalls.addView(createResultBall(num, false, ballSize, margin))
            }
        }

        renderResultRows(results)
    }

    private fun renderResultRows(results: List<QueryResultItem>) {
        val density = resources.displayMetrics.density
        val cellPadV = (10 * density).toInt()
        val cellPadH = (4 * density).toInt()
        val btnPadV = (6 * density).toInt()
        val cornerRadius = 6 * density

        results.forEachIndexed { index, item ->
            val rule = config.rules.getOrNull(index)
            val matchDesc = rule?.description ?: ""

            // 表格行：4列（奖项/规则/次数/操作），使用权重分配宽度自适应屏幕
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER_VERTICAL
                if (index % 2 == 1) {
                    setBackgroundColor(android.graphics.Color.parseColor("#FAFAFA"))
                }
            }

            // 列1：奖项名称 (权重 2.0)
            val col1 = createTableCell(item.prizeName, 2.0f)
            (col1 as TextView).apply {
                val prizeCol = when {
                    item.prizeName.contains("一等") -> 0xFFC62828.toInt()
                    item.prizeName.contains("二等") -> 0xFFD84315.toInt()
                    item.prizeName.contains("三等") -> 0xFFEF6C00.toInt()
                    else -> 0xFF212121.toInt()
                }
                setTextColor(prizeCol)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, Typeface.BOLD)
                setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
                gravity = Gravity.CENTER
            }
            row.addView(col1)

            // 列2：命中规则说明 (权重 2.2，多行换行显示完整内容)
            val col2 = createTableCell(matchDesc, 2.2f)
            col2.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
                maxLines = 3
                gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            }
            row.addView(col2)

            // 列3：命中次数 (权重 0.9)
            val countText = if (item.count > 0) "中${item.count}次" else "—"
            val col3 = createTableCell(countText, 0.9f)
            col3.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, Typeface.BOLD)
                setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
                gravity = Gravity.CENTER
                setTextColor(
                    if (item.count > 0) 0xFFC62828.toInt()
                    else 0xFF9E9E9E.toInt()
                )
            }
            row.addView(col3)

            // 列4：操作按钮 (权重 1.1)
            val tvAction = TextView(requireContext())
            val lp4 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f)
            lp4.setMargins(0, btnPadV, 0, btnPadV)
            tvAction.layoutParams = lp4
            tvAction.setPadding(cellPadH, btnPadV, cellPadH, btnPadV)
            tvAction.gravity = Gravity.CENTER
            if (item.count > 0) {
                tvAction.text = getString(R.string.view_history)
                tvAction.setTextColor(0xFFFFFFFF.toInt())
                tvAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                tvAction.setTypeface(null, Typeface.BOLD)
                tvAction.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    this.cornerRadius = cornerRadius
                    setColor(0xFFC62828.toInt())
                }
                tvAction.setOnClickListener { showHistoryDialog(item.matches) }
                tvAction.isClickable = true
                tvAction.isFocusable = true
            } else {
                tvAction.text = "未命中"
                tvAction.setTextColor(0xFFFFFFFF.toInt())
                tvAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                tvAction.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    this.cornerRadius = cornerRadius
                    setColor(0xFFBDBDBD.toInt())
                }
            }
            row.addView(tvAction)

            resultContainer.addView(row)

            // 分隔线
            val divider = android.view.View(requireContext())
            val dlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (0.5f * density).toInt()
            )
            divider.layoutParams = dlp
            divider.setBackgroundColor(android.graphics.Color.parseColor("#EFEFEF"))
            resultContainer.addView(divider)
        }
    }

    /** 创建权重表格单元格：自动换行、gravity居中/居中显示 */
    private fun createTableCell(text: String, weight: Float): android.widget.TextView {
        return android.widget.TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight
            )
            this.text = text
            setTextColor(0xFF333333.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            // 允许自动换行显示完整信息
            maxLines = 4
            ellipsize = null
        }
    }

    private fun createResultBall(number: Int, isPrimary: Boolean, ballSize: Int, margin: Int): TextView {
        val ball = TextView(requireContext())
        val params = ViewGroup.MarginLayoutParams(ballSize, ballSize)
        params.setMargins(margin, margin, margin, margin)
        ball.layoutParams = params
        ball.gravity = Gravity.CENTER
        ball.text = String.format("%02d", number)
        ball.typeface = Typeface.MONOSPACE
        BallTextHelper.apply(ball, ballSize)
        if (isPrimary) {
            ball.setBackgroundResource(R.drawable.bg_ball_red)
        } else {
            ball.setBackgroundResource(R.drawable.bg_ball_blue)
        }
        ball.setTextColor(Color.WHITE)
        return ball
    }

    private fun showHistoryDialog(matches: List<LotteryDraw>) {
        val dialog = HistoryDialog(
            requireContext(),
            matches.sortedByDescending { it.issue },
            config,
            selectedPrimary,
            selectedSecondary
        )
        dialog.show()
    }

    private fun dpToPx(dp: Float): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    fun refreshFromCache() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            LotteryDataManager.loadCache(ctx, config)
            updateSelectedTexts()
            if (selectedPrimary.isNotEmpty() && (!config.hasSecondary || selectedSecondary.isNotEmpty())) {
                performQuery()
            } else {
                showEmptyResultState()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            requireContext().let { c ->
                LotteryDataManager.ensureInitialized(c)
                if (LotteryDataManager.getCached(config).isEmpty()) {
                    LotteryDataManager.loadCache(c, config)
                }
            }
        }
    }

    companion object {
        private const val ARG_CODE = "lottery_code"

        fun newInstance(code: String): LotteryFragment {
            return LotteryFragment().apply {
                arguments = Bundle().apply { putString(ARG_CODE, code) }
            }
        }
    }
}
