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
import com.lottery.history.model.ConditionalKey
import com.lottery.history.model.ConditionalValue
import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryType
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.model.MatchMode
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

    /** 非 FC3D/P3 通用选号集合（保持原代码不变） */
    private val selectedPrimary = LinkedHashSet<Int>()
    private val selectedSecondary = LinkedHashSet<Int>()

    /** FC3D/P3 专用：按位置保存百位/十位/个位；未填位置用 -1 占位。大小恒为 3。 */
    private val positionalPrimary: MutableList<Int> = mutableListOf(-1, -1, -1)
    /** FC3D/P3 匹配模式：直选/组选3/组选6；其他彩种忽略 */
    private var currentMatchMode: MatchMode = MatchMode.DIRECT
    private var chipModeContainer: LinearLayout? = null
    private var positionalBallsContainer: LinearLayout? = null

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
    // 按期号查询相关
    private lateinit var etIssueQuery: android.widget.EditText
    private lateinit var btnQueryByIssue: TextView
    private lateinit var tvIssueHint: TextView
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
        etIssueQuery = view.findViewById(R.id.etIssueQuery)
        btnQueryByIssue = view.findViewById(R.id.btnQueryByIssue)
        tvIssueHint = view.findViewById(R.id.tvIssueHint)
        tvSelectedNumbers = view.findViewById(R.id.tvSelectedNumbers)
        llResultRedBalls = view.findViewById(R.id.llResultRedBalls)
        llResultBlueBalls = view.findViewById(R.id.llResultBlueBalls)
        resultContainer = view.findViewById(R.id.resultContainer)
        secondarySection = view.findViewById(R.id.secondarySection)
        tvSecondaryTitle = view.findViewById(R.id.tvSecondaryTitle)
        llResultSecondaryRow = view.findViewById(R.id.llResultSecondaryRow)
    }

    /** 根据彩种配置设置标签文字和后区可见性（含结果表头动态重命名 + 单区隐藏蓝球列） */
    private fun setupLabels() {
        // 卡片标题
        view?.findViewById<TextView>(R.id.tvPrimaryLabel)?.text = "选号对比"
        // 前区标题
        view?.findViewById<TextView>(R.id.tvPrimaryTitle)?.text = config.primaryLabel
        // 结果区前区标签
        view?.findViewById<TextView>(R.id.tvResultPrimaryLabel)?.text = config.primaryLabel

        // ========== FC3D/P3 模式 Chip 条 + 位置球 ==========
        injectPositionalUi()

        // ============ 查询结果 5 列表头：文字描述动态匹配彩种术语 ============
        // 列：奖项 / 命中primary / 命中secondary / 次数 / 操作
        view?.findViewById<TextView>(R.id.tvHeaderPrizeName)?.text = "奖项"
        view?.findViewById<TextView>(R.id.tvHeaderCount)?.text = "次数"
        view?.findViewById<TextView>(R.id.tvHeaderAction)?.text = "操作"
        view?.findViewById<TextView>(R.id.tvHeaderHitPrimary)?.text = "命中${config.primaryLabel}"
        // primary label 颜色跟随实际号码球色（红球/前区红/号码 统一红顶）
        view?.findViewById<TextView>(R.id.tvHeaderHitPrimary)?.setTextColor(
            android.graphics.Color.parseColor("#C62828")
        )

        val headerHitSec = view?.findViewById<TextView>(R.id.tvHeaderHitSecondary)
        if (config.hasSecondary) {
            secondarySection.visibility = View.VISIBLE
            llResultSecondaryRow.visibility = View.VISIBLE
            tvSecondaryTitle.text = config.secondaryLabel
            view?.findViewById<TextView>(R.id.tvResultSecondaryLabel)?.text = config.secondaryLabel

            // 次号列：按球色显示蓝顶（大乐透后区=蓝、七乐彩特别号=蓝球统一风格；福彩3D等无次号直接隐藏）
            headerHitSec?.visibility = View.VISIBLE
            headerHitSec?.text = "命中${config.secondaryLabel}"
            headerHitSec?.setTextColor(android.graphics.Color.parseColor("#1565C0"))
        } else {
            secondarySection.visibility = View.GONE
            llResultSecondaryRow.visibility = View.GONE
            // 单区彩种：没有蓝球/后区/特别号 → 直接隐藏命中次号表头列
            headerHitSec?.visibility = View.GONE
        }

        // ===== 按期号查询：按彩种动态设置 hint / 最近一期期号示例 =====
        val sampleLatest = LotteryDataManager.getAllFromDb(requireContext(), config).firstOrNull()?.issue
        tvIssueHint.text = buildString {
            append("格式示例：")
            append(config.issueHint)
            if (sampleLatest != null) append(" ｜ 最新：$sampleLatest")
            append(" ｜ 支持输后几位模糊匹配")
        }
        etIssueQuery.hint = "如 ${config.issuePattern}"
    }

    /** FC3D/P3：注入"模式 Chip 条（直选/组选3/组选6）"和"位置球容器（百/十/个）" */
    private fun injectPositionalUi() {
        val isPositional = config.code == "3d" || config.code == "p3"
        if (!isPositional) return
        val ctx = requireContext()
        val v = view ?: return

        // ===== 1) 在前区标题父容器下追加：Chip 条 =====
        val primaryTitle = v.findViewById<TextView>(R.id.tvPrimaryTitle)
        val titleParent = primaryTitle.parent as? ViewGroup ?: return
        val titleIndex = titleParent.indexOfChild(primaryTitle)
        val density = resources.displayMetrics.density

        val chipsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
            gravity = android.view.Gravity.START
        }
        MatchMode.values().forEach { mode ->
            val chip = TextView(ctx).apply {
                text = mode.label
                gravity = android.view.Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
                layoutParams = lp
                val padVH = (8 * density).toInt()
                val padHH = (14 * density).toInt()
                setPadding(padHH, padVH, padHH, padVH)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, Typeface.BOLD)
                isClickable = true
                isFocusable = true
                tag = mode
                setOnClickListener { onModeChipClicked(it as TextView) }
            }
            chipsRow.addView(chip)
        }
        chipModeContainer = chipsRow
        titleParent.addView(chipsRow, titleIndex + 1)

        // ===== 2) 在前区 Label 卡片下追加：位置球（百/十/个三格） =====
        val tvPrimaryLabel = v.findViewById<TextView>(R.id.tvPrimaryLabel)
        val labelParent = tvPrimaryLabel.parent as? ViewGroup ?: return
        val labelIndex = labelParent.indexOfChild(tvPrimaryLabel)
        val posRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        listOf("百位", "十位", "个位").forEachIndexed { idx, name ->
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    marginStart = if (idx == 0) 0 else (10 * density).toInt()
                }
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            val titleTv = TextView(ctx).apply {
                text = name
                setTextColor(Color.parseColor("#546E7A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, (4 * density).toInt())
            }
            val ballSize = computeCompactBallSize() + (4 * density).toInt()
            val ball = TextView(ctx).apply {
                layoutParams = ViewGroup.MarginLayoutParams(ballSize, ballSize)
                gravity = android.view.Gravity.CENTER
                text = "-"
                typeface = Typeface.MONOSPACE
                BallTextHelper.apply(this, ballSize)
                setBackgroundResource(R.drawable.bg_ball_normal_red)
                setTextColor(Color.parseColor("#BDBDBD"))
                tag = "pos_ball_$idx"
                isClickable = true
                isFocusable = true
                setOnClickListener { clearPositionalPosition(idx) }
            }
            val tip = TextView(ctx).apply {
                text = "点击清空"
                setTextColor(Color.parseColor("#9E9E9E"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = android.view.Gravity.CENTER
                setPadding(0, (3 * density).toInt(), 0, 0)
            }
            col.addView(titleTv)
            col.addView(ball)
            col.addView(tip)
            posRow.addView(col)
        }
        positionalBallsContainer = posRow
        labelParent.addView(positionalBallsContainer, labelIndex + 1)

        refreshModeChips()
        refreshPositionalBalls()
    }

    private fun onModeChipClicked(chip: TextView) {
        val mode = chip.tag as? MatchMode ?: return
        currentMatchMode = mode
        refreshModeChips()
    }

    private fun refreshModeChips() {
        val container = chipModeContainer ?: return
        val density = resources.displayMetrics.density
        for (i in 0 until container.childCount) {
            val chip = container.getChildAt(i) as? TextView ?: continue
            val mode = chip.tag as? MatchMode ?: continue
            val selected = mode == currentMatchMode
            val padVH = (8 * density).toInt()
            val padHH = (14 * density).toInt()
            chip.setPadding(padHH, padVH, padHH, padVH)
            if (selected) {
                chip.setBackgroundResource(R.drawable.bg_ball_red)
                chip.setTextColor(Color.WHITE)
            } else {
                chip.setBackgroundResource(R.drawable.bg_chat_input)
                chip.setTextColor(Color.parseColor("#546E7A"))
            }
        }
    }

    /** FC3D/P3 位置球 UI 重绘：从 positionalPrimary 取值 */
    private fun refreshPositionalBalls() {
        val container = positionalBallsContainer ?: return
        for (i in 0..2) {
            val ball = container.findViewWithTag<TextView>("pos_ball_$i") ?: continue
            val v = positionalPrimary[i]
            if (v < 0) {
                ball.text = "-"
                ball.setTextColor(Color.parseColor("#BDBDBD"))
                ball.setBackgroundResource(R.drawable.bg_ball_normal_red)
            } else {
                ball.text = String.format("%02d", v)
                ball.setTextColor(Color.WHITE)
                ball.setBackgroundResource(R.drawable.bg_ball_red)
            }
        }
    }

    /** 单独清空某位置（点位置球）；grid 对应球的高亮也要同步取消 */
    private fun clearPositionalPosition(idx: Int) {
        if (idx !in 0..2) return
        val oldValue = positionalPrimary[idx]
        positionalPrimary[idx] = -1
        if (oldValue >= 0) {
            selectedPrimary.remove(oldValue)
            val ballIdx = oldValue - config.primaryMin
            if (ballIdx in 0 until gridPrimary.childCount) {
                setBallNormalStyle(gridPrimary.getChildAt(ballIdx) as TextView, true)
            }
        }
        refreshPositionalBalls()
        updateSelectedTexts()
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
            // ===== FC3D/P3：按位置填充（百位→十位→个位依次）；再点"已占号码"则把该号码对应位置清空 =====
            val isPositional = config.code == "3d" || config.code == "p3"
            if (isPositional) {
                if (number in positionalPrimary) {
                    // 再点击相同号码：清除其所在槽位
                    val slot = positionalPrimary.indexOf(number)
                    if (slot in 0..2) {
                        positionalPrimary[slot] = -1
                        selectedPrimary.remove(number)
                        setBallNormalStyle(ball, true)
                    }
                } else {
                    // 找第一个空槽位填入（-1）
                    val slot = positionalPrimary.indexOf(-1)
                    if (slot < 0) {
                        // 3 个位置已满：替换最后一个位置
                        val old = positionalPrimary[2]
                        positionalPrimary[2] = number
                        // grid 里旧号码球样式同步取消高亮
                        val oldBallIdx = old - config.primaryMin
                        if (oldBallIdx in 0 until gridPrimary.childCount) {
                            setBallNormalStyle(gridPrimary.getChildAt(oldBallIdx) as TextView, true)
                        }
                        selectedPrimary.remove(old)
                        selectedPrimary.add(number)
                        setBallSelectedStyle(ball, true)
                    } else {
                        positionalPrimary[slot] = number
                        selectedPrimary.add(number)
                        setBallSelectedStyle(ball, true)
                    }
                }
                refreshPositionalBalls()
                updateSelectedTexts()
                return
            }

            // ===== 通用彩种：普通集合切换 =====
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
            positionalPrimary[0] = -1
            positionalPrimary[1] = -1
            positionalPrimary[2] = -1
            refreshPositionalBalls()
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
                    importPrimaryNumbersFromRecord(record.primaryNumbers)
                    selectedSecondary.clear()
                    selectedSecondary.addAll(record.secondaryNumbers)
                    refreshGridBallsVisualState()
                    refreshPositionalBalls()
                    updateSelectedTexts()
                },
                onPickAndQuery = { record ->
                    importPrimaryNumbersFromRecord(record.primaryNumbers)
                    selectedSecondary.clear()
                    selectedSecondary.addAll(record.secondaryNumbers)
                    refreshGridBallsVisualState()
                    refreshPositionalBalls()
                    updateSelectedTexts()
                    performQuery()
                }
            ).show()
        }

        // ===== 按期号查询按钮：直接从缓存中按 issue 定位一期并弹开奖详情 =====
        btnQueryByIssue.setOnClickListener {
            val raw = etIssueQuery.text?.toString() ?: ""
            if (raw.isBlank()) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "请先输入期号",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val c = requireContext()
            val draw = LotteryDataManager.findDrawByIssue(c, config, raw)
            if (draw == null) {
                android.widget.Toast.makeText(
                    c,
                    buildString {
                        append("未找到【${config.displayName}】期号「$raw」")
                        val sample = LotteryDataManager.getAllFromDb(c, config).firstOrNull()?.issue
                        if (sample != null) append("，最新期号示例：$sample")
                    },
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            // 隐藏输入法，弹开奖详情（显示所有奖项+金额+注数）
            (requireActivity().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as?
                android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(etIssueQuery.windowToken, 0)
            DrawDetailDialog(requireContext(), config, draw).show()
        }
    }

    /**
     * 导入历史记录的 primary 选号。
     * - 非 FC3D/P3：直接装入 selectedPrimary（集合无序，与原行为一致）
     * - FC3D/P3：按 record.primaryNumbers 顺序填充 positionalPrimary 前三位；多余值放入 selectedPrimary 作为补充
     */
    private fun importPrimaryNumbersFromRecord(numbers: List<Int>) {
        selectedPrimary.clear()
        positionalPrimary[0] = -1
        positionalPrimary[1] = -1
        positionalPrimary[2] = -1
        val isPositional = config.code == "3d" || config.code == "p3"
        if (isPositional) {
            for (i in 0..2) {
                val v = numbers.getOrNull(i)
                if (v != null) positionalPrimary[i] = v
            }
        }
        selectedPrimary.addAll(numbers)
    }

    private fun performQuery() {
        val isPositional = config.code == "3d" || config.code == "p3"
        if (isPositional) {
            if (positionalPrimary.any { it < 0 }) {
                showEmptyResultState()
                android.widget.Toast.makeText(
                    requireContext(),
                    "请先选满百位+十位+个位（3个号码）",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return
            }
        } else if (selectedPrimary.isEmpty() || (config.hasSecondary && selectedSecondary.isEmpty())) {
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
            val primaryForMatch: List<Int> = if (isPositional) {
                positionalPrimary.toList()
            } else {
                selectedPrimary.toList()
            }
            val results = LotteryMatcher.match(
                config, primaryForMatch, selectedSecondary, getHistory(), currentMatchMode
            )
            showResults(results)
            withContext(Dispatchers.IO) {
                QueryRecordManager.saveQuery(
                    context = requireContext(),
                    type = config.code,
                    primary = primaryForMatch.toSet(),
                    secondary = selectedSecondary
                )
            }
        }
    }

    private fun getHistory(): List<LotteryDraw> = LotteryDataManager.getAllFromDb(requireContext(), config)

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
        tvSelectedNumbers.text = buildString {
            append("请选择号码后点击查询")
            // —— v11 P0 修复：跨多规则版本彩种（SSQ/DLT）不再只展示最新版奖项名 ——
            //    因为空状态下"选号结果表"会变成用户对"奖级数量/名称"的视觉预期，
            //    如果只展示最新版（如 DLT 2026 = 7 级），但历史期跨度含 DLT 2019 = 9 级，
            //    用户会误以为"整个历史只有7级奖级"。这里给出多版本提示。
            if (config.ruleVersions.size > 1) {
                append("\n提示：本彩种官方规则共 ")
                append(config.ruleVersions.size)
                append(" 个版本（")
                append(config.ruleVersions.joinToString(" / ") { it.policyLabel })
                append("）\n实际命中的奖项名按各期对应规则版本展示，以「期数所在阶段」为准")
            }
        }
        tvSelectedNumbers.setTextColor(Color.parseColor("#616161"))
        llResultRedBalls.removeAllViews()
        llResultBlueBalls.removeAllViews()
        resultContainer.removeAllViews()

        // 空状态：用 config.rules（最新版）去重后的奖项做占位展示
        val seenNames = linkedSetOf<String>()
        config.rules.forEach { seenNames.add(it.prizeName) }
        val emptyResults = seenNames.map { name ->
            QueryResultItem(
                matchPrimary = -1,
                matchSecondary = -1,
                prizeName = name,
                count = 0,
                matches = emptyList()
            )
        }
        renderResultRows(emptyResults)
    }

    private fun showResults(results: List<QueryResultItem>) {
        cardResult.visibility = View.VISIBLE
        resultContainer.removeAllViews()

        // 顶部总命中统计：把 N 个奖项的命中次数相加，给出总数，40+ 用户一眼看出有没有中奖
        val totalHit = results.sumOf { r -> r.count }
        val summaryText = buildString {
            if (totalHit > 0) append("您选的号码，在历史上共命中 ${totalHit} 期：")
            else append("您选的号码，在历史上暂未命中任何奖项：")
            // 按用户明确指令：查询结果用最新政策的所有奖项结构展示，含空奖项
            if (config.ruleVersions.size > 1) {
                append("\n【展示说明】：当前列表按最新政策（")
                append(config.ruleVersions.lastOrNull()?.policyLabel ?: "")
                append("）奖级名称展示；未命中的奖项也会列出，显式标注「未中」。")
                append("\n点「查看历史」后，历史明细按每期真实规则版本分组展示真实元数据。")
            }
            // ===== v13 新增：最新一期条件性奖级状态提示 =====
            val latest = getHistory().firstOrNull()
            val flags = latest?.conditionalFlags.orEmpty()
            flags[ConditionalKey.SSQ_FUYUN]?.let { state ->
                append("\n★ 福运奖：")
                append(when (state) {
                    ConditionalValue.ON -> "≥15亿已开启（中3红=5元）"
                    ConditionalValue.OFF -> "<3亿已停止（中3红不中奖）"
                    else -> "3~15亿维持上期状态"
                })
            }
            flags[ConditionalKey.DLT_2026_FLOAT]?.let { state ->
                append("\n★ 大乐透奖池上浮：")
                append(when (state) {
                    ConditionalValue.UP -> "≥8亿已上浮（三6666/四380/五200/六18/七7）"
                    ConditionalValue.NORMAL -> "<8亿未上浮（三5000/四300/五150/六15/七5）"
                    else -> "状态未知，暂按基础金额"
                })
            }
        }
        tvSelectedNumbers.text = summaryText
        tvSelectedNumbers.setTextColor(
            if (totalHit > 0) Color.parseColor("#C62828") else Color.parseColor("#546E7A")
        )
        tvSelectedNumbers.setTypeface(null, android.graphics.Typeface.BOLD)

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

        // ===== 【用户明确指令：查询结果所有奖项都要显示，按最新政策】=====
        //   1) 最新政策 config.rules 按 prizeName 去重 → 完整奖级全集
        //   2) 对每个奖级：若 LotteryMatcher 返回的命中结果已存在 → 直接用（matches 真实 draws
        //      对象绝对不变！）；若不存在（没命中） → 生成 count=0 / matches=emptyList 的占位项
        //      【严格约束】：matches 中真实 draws 的元数据（期号、开奖日期、真实ruleVersionKey、
        //      真实奖项信息）永远不变；详情页 DrawDetailDialog 按 matches 里每期 draw 的真实
        //      ruleVersionKey 去展示真实数据。
        val prizeNameToHit: Map<String, QueryResultItem> = results.associateBy { it.prizeName }
        val seenNames = linkedSetOf<String>()
        config.rules.forEach { seenNames.add(it.prizeName) }
        val fullList = seenNames.map { name ->
            prizeNameToHit[name] ?: QueryResultItem(
                matchPrimary = -1,
                matchSecondary = -1,
                prizeName = name,
                count = 0,
                matches = emptyList(),
                sourceRuleVersionKey = config.ruleVersions.lastOrNull()?.key
            )
        }

        renderResultRows(fullList)
    }

    private fun renderResultRows(results: List<QueryResultItem>) {
        val density = resources.displayMetrics.density
        val cellPadV = (10 * density).toInt()
        val cellPadH = (4 * density).toInt()
        val btnPadV = (8 * density).toInt()
        val cornerRadius = 8 * density

        // 双区彩种 5 列，单区彩种 4 列（直接移除命中蓝球那一列），总权重按比例扩张填满整行
        val (wPrize, wPri, wSec, wCount, wAction) = if (config.hasSecondary) {
            arrayOf(1.3f, 1.1f, 1.1f, 0.9f, 1.1f)
        } else {
            // 单区彩种：把原本 wSec=1.1f 按比例均摊到其他列，让视觉间距不变
            arrayOf(1.6f, 1.4f, 0f, 1.15f, 1.35f)
        }

        results.forEachIndexed { index, item ->
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

            // 列1：奖项名称
            val col1 = createTableCell(item.prizeName, wPrize)
            (col1 as TextView).apply {
                val prizeCol = when {
                    item.prizeName.contains("一等") -> 0xFFC62828.toInt()
                    item.prizeName.contains("二等") -> 0xFFD84315.toInt()
                    item.prizeName.contains("三等") -> 0xFFEF6C00.toInt()
                    item.prizeName.contains("未中") -> 0xFF9E9E9E.toInt()
                    else -> 0xFF212121.toInt()
                }
                setTextColor(prizeCol)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, Typeface.BOLD)
                setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            row.addView(col1)

            // 列2：命中红球/前区/号码 数量（多条件合并时 matchPrimary=-1 显示 "—"）
            val hitPriTxt = if (item.matchPrimary < 0) "—" else "${item.matchPrimary}个"
            val col2 = createTableCell(hitPriTxt, wPri).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, Typeface.BOLD)
                setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
                gravity = Gravity.CENTER
                // 主号命中 ≥3 用红色加粗突出；<3 灰色正常；— 用灰色
                setTextColor(
                    if (item.matchPrimary >= 3) 0xFFC62828.toInt() else 0xFF546E7A.toInt()
                )
            }
            row.addView(col2)

            // 列3：命中蓝球/后区/特别号（仅双区彩种显示；单区彩种 GONE 直接排除不占位）
            if (config.hasSecondary) {
                val hitSecTxt = if (item.matchSecondary < 0) "—" else "${item.matchSecondary}个"
                val col3 = createTableCell(hitSecTxt, wSec).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTypeface(null, Typeface.BOLD)
                    setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
                    gravity = Gravity.CENTER
                    setTextColor(
                        if (item.matchSecondary > 0) 0xFF1565C0.toInt() else 0xFF78909C.toInt()
                    )
                }
                row.addView(col3)
            }

            // 列4：命中次数
            val countText = if (item.count > 0) "中${item.count}次" else "未中"
            val col4 = createTableCell(countText, wCount).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, Typeface.BOLD)
                setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
                gravity = Gravity.CENTER
                setTextColor(
                    if (item.count > 0) 0xFFC62828.toInt()
                    else 0xFF9E9E9E.toInt()
                )
            }
            row.addView(col4)

            // 列5：操作按钮（查看命中历史）
            val tvAction = TextView(requireContext())
            val lp5 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, wAction)
            lp5.setMargins(0, btnPadV, 0, btnPadV)
            tvAction.layoutParams = lp5
            tvAction.setPadding(cellPadH, btnPadV, cellPadH, btnPadV)
            tvAction.gravity = Gravity.CENTER
            if (item.count > 0) {
                tvAction.text = getString(R.string.view_history)
                tvAction.setTextColor(0xFFFFFFFF.toInt())
                tvAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
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
                tvAction.text = "—"
                tvAction.setTextColor(0xFFFFFFFF.toInt())
                tvAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
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

    /** 创建权重表格单元格：自动换行、居中显示，全宽自适应无横向滚动 */
    private fun createTableCell(text: String, weight: Float): android.widget.TextView {
        return android.widget.TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight
            )
            this.text = text
            setTextColor(0xFF333333.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            maxLines = 2
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
                // getCached 永远返回空 → 直接从 DB 读取（loadCache 把 DB 加载到内存 caches map，
                // 对无副作用；核心 UI 展示都走 getAllFromDb 了）
                if (LotteryDataManager.getAllFromDb(c, config).isNotEmpty()) {
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
