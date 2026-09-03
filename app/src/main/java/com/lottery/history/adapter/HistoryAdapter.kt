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
        val tvAppendInfo: TextView = view.findViewById(R.id.tvAppendInfo)
        val btnViewDrawDetail: TextView = view.findViewById(R.id.btnViewDrawDetail)
    }

    // ====== v11 预计算（P2 修复：避免 onBind O(N²) 分组统计）======
    //  ruleVersionKey → 该分组在本组 historyList 内的期数
    private var groupCountCache: Map<String, Int> = emptyMap()
    //  只在单版本彩种隐藏徽章/分组标题（避免给 ruleVersions.size==1 的彩种视觉噪音）
    private val hasMultipleRuleVersions: Boolean = config.ruleVersions.size > 1
    // ===== 规则版本不明（resolveRuleVersion 返回 null）时的统一占位常量 =====
    //   用一个特殊 key+label 保证这些 draw 也能被正确分组展示，绝不 NPE 崩溃。
    private val UNKNOWN_VERSION_KEY = "__UNKNOWN_RULE_VERSION__"
    private val UNKNOWN_VERSION_LABEL = "版本信息缺失"
    private val UNKNOWN_VERSION_NOTE = "本期版本信息缺失，奖项显示可能不准确。"

    init {
        rebuildGroupCache()
    }

    /** 安全获取 ruleVersionKey：null 时返回占位 key，保证分组、比较永不 NPE */
    private fun safeRvKey(draw: LotteryDraw): String =
        draw.resolveRuleVersion(config)?.key ?: UNKNOWN_VERSION_KEY

    /** 安全获取 RuleVersion：返回 null 时由调用方自行处理占位展示 */
    private fun safeRv(draw: LotteryDraw): LotteryTypeConfig.RuleVersion? =
        draw.resolveRuleVersion(config)

    private fun rebuildGroupCache() {
        val result = mutableMapOf<String, Int>()
        for (draw in historyList) {
            val key = safeRvKey(draw)  // 安全调用，永不 NPE
            result[key] = (result[key] ?: 0) + 1
        }
        groupCountCache = result
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val draw = historyList[position]
        val ruleVersion = safeRv(draw)
        val rvKey = safeRvKey(draw)
        val prevDraw = historyList.getOrNull(position - 1)
        val prevRvKey = prevDraw?.let { safeRvKey(it) }

        // ======= 严格模式：ruleVersion 为 null 时，所有展示用占位常量替代 =======
        val policyLabel = ruleVersion?.policyLabel ?: UNKNOWN_VERSION_LABEL
        val effectiveFrom = ruleVersion?.effectiveFromDate ?: "—"
        val changeNote = ruleVersion?.changeNote ?: UNKNOWN_VERSION_NOTE

        // ======= v11 优化：单版本彩种不显示分组标题 & 徽章，降低噪音 =======
        //   注意：即使是单版本彩种，如果出现 UNKNOWN_VERSION_KEY（版本不明期），
        //   也强制显示分组标题，提醒用户这些期的数据可信度不同。
        val forceShowBecauseUnknown = rvKey == UNKNOWN_VERSION_KEY ||
            prevRvKey == UNKNOWN_VERSION_KEY
        val isFirstOfRuleGroup = (hasMultipleRuleVersions || forceShowBecauseUnknown) &&
            (prevRvKey == null || prevRvKey != rvKey)

        if (isFirstOfRuleGroup) {
            holder.tvRuleSectionHeader.visibility = View.VISIBLE
            // 组期数统计：O(1) 读取预计算结果，不再每次 count 全表
            val groupCount = groupCountCache[rvKey] ?: 0
            holder.tvRuleSectionHeader.text = buildString {
                append("● ")
                append(policyLabel)
                append(" ｜ 生效：")
                append(effectiveFrom)
                append("起 ｜ 本组")
                append(groupCount)
                append("期")
                append("\n")
                append("   说明：")
                append(changeNote.take(80))
                if (changeNote.length > 80) append("…")
            }
            // 版本不明分组：红色背景高亮警告
            if (rvKey == UNKNOWN_VERSION_KEY) {
                holder.tvRuleSectionHeader.setTextColor(0xFFB71C1C.toInt())
                holder.tvRuleSectionHeader.setBackgroundColor(0xFFFFEBEE.toInt())
            } else {
                holder.tvRuleSectionHeader.setTextColor(0xFF1B5E20.toInt())
                holder.tvRuleSectionHeader.setBackgroundColor(0xFFE8F5E9.toInt())
            }
        } else {
            holder.tvRuleSectionHeader.visibility = View.GONE
        }

        holder.tvIndex.text = (position + 1).toString()
        holder.tvIssue.text = draw.issue
        holder.tvDate.text = draw.date.orEmpty()

        // ======= v11 优化：单版本彩种隐藏政策徽章（无信息增益只会增加视觉噪音）=======
        //   版本不明期：徽章永远显示（红色警告样式）
        val showBadge = hasMultipleRuleVersions || rvKey == UNKNOWN_VERSION_KEY
        if (showBadge) {
            holder.tvPolicyBadge.visibility = View.VISIBLE
            holder.tvPolicyBadge.text = policyLabel
            if (rvKey == UNKNOWN_VERSION_KEY) {
                // 版本不明：红色警告徽章
                holder.tvPolicyBadge.setTextColor(0xFFB71C1C.toInt())
                holder.tvPolicyBadge.setBackgroundResource(R.drawable.bg_policy_badge)
            }
        } else {
            holder.tvPolicyBadge.visibility = View.GONE
        }

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

        // ===== v13 新增：追加投注信息（仅大乐透等有追加玩法且 appendPrizeTiers 非空时显示） =====
        val appendTiers = draw.appendPrizeTiers
        if (appendTiers.isNotEmpty() && hasSelection) {
            // 查找命中的追加奖级：用【去重奖级索引】对齐 appendPrizeTiers（追加1等/2等/3等...）。
            //   ⚠️ 不能用 rules 原始索引：2014版三等奖有(5+0/4+2)两个命中方式占2个索引，
            //   若按原始索引取，中 4+2 会 getOrNull(3)=null，导致该显示追加却漏显示（同奖级显示不一致）。
            val rv = draw.resolveRuleVersion(config)
            var matchedTierIdx = -1
            if (rv != null) {
                val pCount = draw.primaryNumbers.count { it in selectedPrimary }
                val sCount = if (config.hasSecondary) draw.secondaryNumbers.count { it in selectedSecondary } else 0
                var dedup = -1
                var lastName: String? = null
                for (rule in rv.rules) {
                    if (rule.prizeName != lastName) { dedup++; lastName = rule.prizeName }
                    if (pCount == rule.matchPrimary && sCount == rule.matchSecondary) {
                        matchedTierIdx = dedup
                        break
                    }
                }
            }
            if (matchedTierIdx >= 0) {
                val append = appendTiers.getOrNull(matchedTierIdx)
                // 【用户要求（2026-09）】：追加仅在实际产生奖金分配（注数>0）时才显示；
                //   追加0注/无人中 → 不显示任何追加提示（含"本期空开"），避免不专业表现。
                if (append != null && append.count > 0) {
                    val appendCount = append.count
                    val appendAmount = append.amount
                    holder.tvAppendInfo.visibility = View.VISIBLE
                    holder.tvAppendInfo.text = buildString {
                        append("追加投注：")
                        append("中${appendCount}注")
                        if (appendAmount > 0) append(" / 单注${formatAmount(appendAmount)}")
                    }
                } else {
                    holder.tvAppendInfo.visibility = View.GONE
                }
            } else {
                holder.tvAppendInfo.visibility = View.GONE
            }
        } else {
            holder.tvAppendInfo.visibility = View.GONE
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
        rebuildGroupCache()
        notifyDataSetChanged()
    }

    private fun formatAmount(amount: Long): String =
        if (amount >= 10000) {
            val wan = amount / 10000.0
            if (wan % 1.0 == 0.0) "${wan.toInt()}万元" else String.format("%.1f万元", wan)
        } else {
            "${amount}元"
        }
}
