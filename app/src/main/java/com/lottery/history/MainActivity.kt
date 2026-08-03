package com.lottery.history

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lottery.history.adapter.ViewPagerAdapter
import com.lottery.history.data.LotteryDataManager
import com.lottery.history.data.RefreshResult
import com.lottery.history.databinding.ActivityMainBinding
import com.lottery.history.databinding.ItemLatestDrawCardBinding
import com.lottery.history.model.LotteryDraw
import com.lottery.history.model.LotteryType
import com.lottery.history.model.LotteryTypeConfig
import com.lottery.history.ui.AuthDialog
import com.lottery.history.ui.CustomerServiceActivity
import com.lottery.history.ui.DrawDetailDialog
import com.lottery.history.ui.LatestDrawsDialog
import com.lottery.history.util.BallTextHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRefreshing = false
    private val authRepo by lazy { com.lottery.history.data.AuthRepository(this) }
    private val quotaRepo by lazy { com.lottery.history.data.QuotaRepository(this) }
    private lateinit var latestDrawsAdapter: LatestDrawsAdapter

    // ===== 最新开奖卡片自动循环轮播 =====
    private val carouselHandler = Handler(Looper.getMainLooper())
    private val carouselIntervalMs = 3000L  // 每3秒滚动一张
    private var carouselIndex = 0
    private val carouselRunnable = object : Runnable {
        override fun run() {
            if (::latestDrawsAdapter.isInitialized && latestDrawsAdapter.itemCount > 0) {
                carouselIndex = (carouselIndex + 1) % latestDrawsAdapter.itemCount
                // 平滑滚动到下一位置
                try {
                    val lm = binding.rvLatestDraws.layoutManager as? LinearLayoutManager
                    if (lm != null) {
                        val scroller = object : LinearSmoothScroller(this@MainActivity) {
                            override fun calculateSpeedPerPixel(displayMetrics: android.util.DisplayMetrics): Float {
                                // 控制滚动速度，默认值 25f（越大越慢），这里 120f 较平滑
                                return 120f / displayMetrics.densityDpi
                            }
                        }
                        scroller.targetPosition = carouselIndex
                        lm.startSmoothScroll(scroller)
                    } else {
                        binding.rvLatestDraws.smoothScrollToPosition(carouselIndex)
                    }
                } catch (_: Exception) { }
            }
            carouselHandler.postDelayed(this, carouselIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.viewPager.adapter = ViewPagerAdapter(this)

        // 最新开奖横向滚动卡片列表
        setupLatestDrawsRecycler()

        // 8个彩种两行四列展示
        setupLotteryTabs()

        // 用户信息栏：点击手机号登录或改密
        setupUserInfoBar()

        // 查看全部最新开奖
        binding.tvViewAllLatest.setOnClickListener {
            LatestDrawsDialog(this).show()
        }

        lifecycleScope.launch {
            updateStatusText("初始化数据...")
            try {
                LotteryDataManager.ensureInitialized(this@MainActivity)
                LotteryDataManager.loadCaches(this@MainActivity)
                updateLatestInfo()
                updateStatusTextFromMeta()
                autoRefreshLatest()
            } catch (e: Exception) {
                updateStatusTextFromMeta()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 页面恢复时继续轮播（从不停，保持最新开奖不间断轮播
        carouselHandler.removeCallbacks(carouselRunnable)
        carouselHandler.postDelayed(carouselRunnable, carouselIntervalMs)
    }

    override fun onPause() {
        super.onPause()
        // 后台也不停止轮播——确保始终展示
    }

    override fun onDestroy() {
        super.onDestroy()
        carouselHandler.removeCallbacks(carouselRunnable)
    }

    // ================== 最新开奖卡片列表 ==================
    data class LatestDrawItem(
        val config: LotteryTypeConfig,
        val draw: LotteryDraw?
    )

    companion object {
        /** DIFF 放在顶层伴生对象中，避免内部类 companion object 限制 */
        private val LATEST_DIFF = object : DiffUtil.ItemCallback<LatestDrawItem>() {
            override fun areItemsTheSame(a: LatestDrawItem, b: LatestDrawItem) =
                a.config.code == b.config.code
            override fun areContentsTheSame(a: LatestDrawItem, b: LatestDrawItem) =
                a.draw?.issue == b.draw?.issue
        }
    }

    private fun setupLatestDrawsRecycler() {
        latestDrawsAdapter = LatestDrawsAdapter { item ->
            // 点击某彩种卡片 -> 弹出当期中奖具体信息（号码+规则）
            DrawDetailDialog(this, item.config, item.draw).show()
        }
        // reverseLayout=true: 列表最后一项在左边，index++向左滚动 = 下一张从右边滚入（右->左轮播）
        val lm = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, true)
        binding.rvLatestDraws.layoutManager = lm
        binding.rvLatestDraws.adapter = latestDrawsAdapter

        // 当用户手动滑动时，重置轮播起始位置，避免自动滚动跳回
        binding.rvLatestDraws.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val first = lm.findFirstVisibleItemPosition()
                if (first >= 0) carouselIndex = first
            }
        })
    }

    /** 最新开奖卡片 ListAdapter，按彩种顺序展示8个彩种最新一期。
     *  改为普通类而非 inner class，避免 companion object 限制；onClick 通过构造传入 */
    inner class LatestDrawsAdapter(
        private val onClick: (LatestDrawItem) -> Unit
    ) : ListAdapter<LatestDrawItem, LatestDrawItemVH>(LATEST_DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LatestDrawItemVH {
            val bind = ItemLatestDrawCardBinding.inflate(
                layoutInflater, parent, false
            )
            return LatestDrawItemVH(bind, onClick)
        }

        override fun onBindViewHolder(holder: LatestDrawItemVH, position: Int) {
            holder.bind(getItem(position))
        }
    }

    inner class LatestDrawItemVH(
        private val card: ItemLatestDrawCardBinding,
        private val onClick: (LatestDrawItem) -> Unit
    ) : RecyclerView.ViewHolder(card.root) {

        fun bind(item: LatestDrawItem) {
            val (cfg, draw) = item
            card.tvLotteryName.text = cfg.displayName
            card.tvLotteryName.setTextColor(
                Color.parseColor(if (cfg.code == "dlt") "#1565C0" else "#C62828")
            )
            if (draw != null) {
                card.tvIssue.text = "第${draw.issue}期"
                card.tvDate.text = draw.date.orEmpty()
            } else {
                card.tvIssue.text = "暂无数据"
                card.tvDate.text = ""
            }
            // 渲染号码球
            card.llBalls.removeAllViews()
            draw?.let { renderBalls(it, cfg) }
            card.root.setOnClickListener { onClick(item) }
        }

        private fun renderBalls(draw: LotteryDraw, cfg: LotteryTypeConfig) {
            val density = itemView.resources.displayMetrics.density
            val ballSize = (18 * density).toInt()
            val margin = (2 * density).toInt()
            draw.primaryNumbers.sorted().forEach { num ->
                card.llBalls.addView(createBall(num, true, ballSize, margin, cfg))
            }
            if (cfg.hasSecondary) {
                draw.secondaryNumbers.sorted().forEach { num ->
                    card.llBalls.addView(createBall(num, false, ballSize, margin, cfg))
                }
            }
        }

        private fun createBall(
            num: Int, isPrimary: Boolean, size: Int, margin: Int, cfg: LotteryTypeConfig
        ): TextView {
            val b = TextView(this@MainActivity)
            val params = ViewGroup.MarginLayoutParams(size, size)
            params.setMargins(margin, margin, margin, margin)
            b.layoutParams = params
            b.gravity = Gravity.CENTER
            b.text = String.format("%02d", num)
            b.typeface = android.graphics.Typeface.MONOSPACE
            BallTextHelper.apply(b, size)
            b.setBackgroundResource(
                if (isPrimary) R.drawable.bg_ball_normal_red
                else R.drawable.bg_ball_normal_blue
            )
            b.setTextColor(Color.parseColor("#333333"))
            return b
        }
    }

    // ================== 用户信息栏 ==================
    private fun setupUserInfoBar() {
        refreshUserInfoBar()
        binding.tvUserPhone.setOnClickListener {
            val mode = if (authRepo.isLoggedIn()) {
                AuthDialog.Mode.CHANGE_PASSWORD
            } else {
                AuthDialog.Mode.LOGIN
            }
            AuthDialog(this, lifecycleScope, mode) { refreshUserInfoBar() }.show()
        }
    }

    private fun refreshUserInfoBar() {
        val phone = authRepo.currentPhone()
        if (phone != null) {
            val masked = if (phone.length == 11) {
                "${phone.substring(0, 3)}****${phone.substring(7)}"
            } else phone
            binding.tvUserPhone.text = masked
            observeQuota(phone)
        } else {
            binding.tvUserPhone.text = getString(R.string.tap_to_login)
            binding.tvQuotaInfo.text = ""
        }
    }

    fun refreshUserInfoBarFromFragment() = refreshUserInfoBar()

    private fun observeQuota(phone: String) {
        lifecycleScope.launch {
            quotaRepo.observe(phone).collect { quota ->
                val text = if (quota == null) {
                    ""
                } else if (!quota.canQuery()) {
                    getString(R.string.quota_expired)
                } else {
                    val days = quota.remainingDays()
                    if (days != null) {
                        getString(R.string.quota_monthly, days)
                    } else {
                        getString(R.string.quota_pay_per_use, quota.remainingQueries)
                    }
                }
                binding.tvQuotaInfo.text = text
            }
        }
    }

    // ================== 菜单 ==================
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_refresh) {
            refreshData()
            return true
        }
        if (item.itemId == R.id.action_latest) {
            LatestDrawsDialog(this).show()
            return true
        }
        if (item.itemId == R.id.action_customer_service) {
            startActivity(
                android.content.Intent(this, CustomerServiceActivity::class.java)
            )
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ================== 数据刷新 ==================
    private fun autoRefreshLatest() {
        if (isRefreshing) return
        isRefreshing = true
        updateStatusText("正在获取最新开奖数据...")
        lifecycleScope.launch {
            val result: RefreshResult = LotteryDataManager.refresh(this@MainActivity)
            isRefreshing = false
            if (result.success) {
                updateLatestInfo()
                updateStatusText(getString(R.string.refresh_success, result.successCount))
                notifyFragmentsRefresh()
            } else {
                updateStatusTextFromMeta()
            }
        }
    }

    private fun refreshData() {
        if (isRefreshing) return
        isRefreshing = true
        binding.tvDataStatus.text = getString(R.string.refreshing)
        lifecycleScope.launch {
            val result: RefreshResult = withContext(Dispatchers.IO) {
                LotteryDataManager.refresh(this@MainActivity)
            }
            isRefreshing = false
            if (result.success) {
                updateLatestInfo()
                val msg = getString(R.string.refresh_success, result.successCount)
                binding.tvDataStatus.text = msg
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                notifyFragmentsRefresh()
            } else {
                updateStatusTextFromMeta()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.refresh_fail, result.error ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun notifyFragmentsRefresh() {
        try {
            supportFragmentManager.fragments.forEach { frag ->
                if (frag is com.lottery.history.ui.LotteryFragment) {
                    frag.refreshFromCache()
                }
            }
        } catch (_: Exception) { }
    }

    private fun updateStatusText(text: String) {
        runOnUiThread { binding.tvDataStatus.text = text }
    }

    private fun updateStatusTextFromMeta() {
        val (lastMs, successN, failedN) = LotteryDataManager.readMeta(this)
        val totalCached = LotteryType.ALL.sumOf { LotteryDataManager.getCached(it.code).size }
        if (lastMs > 0 && totalCached > 0) {
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val failInfo = if (failedN > 0) " · ${failedN}个失败" else ""
            updateStatusText("已更新 ${fmt.format(Date(lastMs))} · ${successN}个彩种$failInfo · 共${totalCached}期")
        } else {
            updateStatusText("内置数据·共${totalCached}期·右上角刷新")
        }
    }

    // ================== 最新开奖 ==================
    private fun updateLatestInfo() {
        runOnUiThread {
            // 横向卡片：依次填入 8 个彩种的最新一期
            val items = LotteryType.ALL.map { cfg ->
                LatestDrawItem(cfg, LotteryDataManager.getCached(cfg.code).firstOrNull())
            }
            latestDrawsAdapter.submitList(items)
        }
    }

    // ================== 彩种Tab栏（吸顶固定） ==================
    private fun setupLotteryTabs() {
        val grid = binding.gridLotteryTabs
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        val tabTextSize = resources.getDimension(R.dimen.small_text_size)
        val tabHeight = resources.getDimensionPixelSize(R.dimen.tab_item_height)
        val horizontalPadding = (2 * density).toInt()
        val verticalPadding = (6 * density).toInt()
        val tabMinSp = (resources.getDimension(R.dimen.tiny_text_size) / density).toInt()
        val tabMaxSp = (resources.getDimension(R.dimen.body_text_size) / density).toInt()

        LotteryType.ALL.forEachIndexed { index, type ->
            val tv = TextView(this)
            val params = GridLayout.LayoutParams()
            params.width = 0
            params.height = tabHeight
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
            params.setMargins(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            tv.layoutParams = params
            tv.gravity = Gravity.CENTER
            tv.text = type.displayName
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, tabTextSize)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                tv, tabMinSp, tabMaxSp, 1, TypedValue.COMPLEX_UNIT_SP
            )
            tv.maxLines = 1
            tv.setTextColor(Color.parseColor("#E0FFFFFF"))
            tv.setBackgroundResource(R.drawable.bg_tab_item)
            tv.setPadding(0, 0, 0, 0)
            tv.setOnClickListener { binding.viewPager.currentItem = index }
            grid.addView(tv)
        }

        binding.viewPager.registerOnPageChangeCallback(object :
            androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTabSelection(position)
            }
        })
        updateTabSelection(0)
    }

    private fun updateTabSelection(selected: Int) {
        val grid = binding.gridLotteryTabs
        for (i in 0 until grid.childCount) {
            val tv = grid.getChildAt(i) as TextView
            if (i == selected) {
                tv.setTextColor(Color.WHITE)
                tv.setBackgroundResource(R.drawable.bg_tab_item_selected)
            } else {
                tv.setTextColor(Color.parseColor("#E0FFFFFF"))
                tv.setBackgroundResource(R.drawable.bg_tab_item)
            }
        }
    }
}
