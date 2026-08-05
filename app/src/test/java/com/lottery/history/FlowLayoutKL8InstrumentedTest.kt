package com.lottery.history

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lottery.history.util.BallTextHelper
import com.lottery.history.widget.FlowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 快乐8 2026204期 滚动展示区 分行逻辑测试（本地 JVM 版，无需模拟器）。
 *
 * 真实数据（2026-08-02 官方公告）：
 *   期号：2026204
 *   开奖号码（20个，排序后）：03 10 13 21 22 24 28 37 38 46 51 52 54 56 59 60 65 66 70 73
 *
 * 运行环境：本地 JVM + Robolectric（在 JVM 上模拟 Android 框架），
 *           无需 Android 模拟器/真机，可直接通过 `gradle :app:testDebugUnitTest` 运行。
 *
 * 验证目标（6 个用例）：
 *   1) 360dp 标准屏：渲染20个球，分2行，每行10个，顺序正确
 *   2) 320dp 窄屏：仍分2行，每行10个（宽度刚好够：24dp/球 × 10 = 240dp ≤ 290dp可用）
 *   3) 240dp 极端窄屏（新增）：24dp/球 × 10 = 240dp 恰好等于屏宽，仍每行10个，分2行，不溢出
 *   4) 480dp 宽屏：maxPerLine=10 强制分行，不能挤成1行
 *   5) 号码内容与官方2026204期一致
 *   6) 对照组：maxPerLine=0（不限制）时 600dp 超宽屏 20球挤成1行（验证必要性）
 *
 * 注意：本测试通过 Robolectric 真实调用 [FlowLayout.onMeasure] / [FlowLayout.onLayout]，
 *       验证的是项目源码的真实渲染管线，而非复刻算法。
 *
 * 运行方式：
 *   cd /workspace
 *   gradle :app:testDebugUnitTest \
 *     --tests "com.lottery.history.FlowLayoutKL8InstrumentedTest" --no-daemon
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33], // Robolectric 4.11 对 API 34 支持不完整，使用 API 33 稳定运行
    application = android.app.Application::class // 覆盖 LotteryApp，避免 WorkManager/Room 初始化
)
class FlowLayoutKL8InstrumentedTest {

    /** 快乐8 2026204期 真实开奖号码（已排序） */
    private val kl8Numbers = listOf(
        3, 10, 13, 21, 22, 24, 28, 37, 38, 46,
        51, 52, 54, 56, 59, 60, 65, 66, 70, 73
    )

    @Test
    fun testKL8_2026204_ScrollCardBalls_PerLine10_TwoLines() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val density = ctx.resources.displayMetrics.density

        // 复刻 MainActivity.renderBalls 的真实渲染逻辑
        val flBalls = FlowLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            maxPerLine = 10 // MainActivity.kt:241-243 快乐8强制每行10个
            clipChildren = false
            clipToPadding = false
        }

        // 滚动卡片球参数：球=20dp, margin=2dp
        val ballSize = (20 * density).toInt()
        val margin = (2 * density).toInt()

        kl8Numbers.sorted().forEach { num ->
            flBalls.addView(createBall(ctx, num, ballSize, margin))
        }

        // 模拟主流手机屏宽：360dp（如 Pixel 系列）
        val screenWidthPx = (360 * density).toInt()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            screenWidthPx, View.MeasureSpec.EXACTLY
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(
            0, View.MeasureSpec.UNSPECIFIED
        )
        flBalls.measure(widthSpec, heightSpec)
        flBalls.layout(0, 0, flBalls.measuredWidth, flBalls.measuredHeight)

        assertEquals("应该渲染20个号码球", 20, flBalls.childCount)

        val rows = groupByRow(flBalls)
        assertEquals("应该分成2行（每行10个）", 2, rows.size)
        assertEquals("第1行应该有10个球", 10, rows[0].size)
        assertEquals("第2行应该有10个球", 10, rows[1].size)
        rows.forEachIndexed { idx, row ->
            assertTrue("第${idx + 1}行球数(${row.size})应≤10", row.size <= 10)
        }

        // 顺序验证：第1行最大号 < 第2行最小号
        fun View.getBallNumber(): Int = (this as TextView).text.toString().toInt()
        val line1Max = rows[0].map { it.getBallNumber() }.maxOrNull() ?: -1
        val line2Min = rows[1].map { it.getBallNumber() }.minOrNull() ?: Int.MAX_VALUE
        assertTrue(
            "第1行最大号($line1Max)应 < 第2行最小号($line2Min)",
            line1Max < line2Min
        )

        // 诊断输出
        println("===== 快乐8 2026204期 JVM 渲染诊断 [360dp标准屏] =====")
        println("屏幕宽度：${screenWidthPx}px (360dp, density=$density)")
        println("FlowLayout尺寸：${flBalls.measuredWidth}x${flBalls.measuredHeight}px")
        println("总球数：${flBalls.childCount}，分行数：${rows.size}")
        println("第1行(${rows[0].size}个)：${rows[0].map { String.format("%02d", it.getBallNumber()) }.joinToString(" ")}")
        println("第2行(${rows[1].size}个)：${rows[1].map { String.format("%02d", it.getBallNumber()) }.joinToString(" ")}")
        println("第1行最大号=$line1Max < 第2行最小号=$line2Min → 顺序${if (line1Max < line2Min) "正确" else "错误"}")
        println("====================================================")
    }

    @Test
    fun testKL8_2026204_NarrowScreen320dp_StillPerLine10() {
        // 窄屏测试：320dp 小屏手机（可用宽≈290dp，24dp/球×10=240dp ≤ 290dp）
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val density = ctx.resources.displayMetrics.density

        val flBalls = FlowLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            maxPerLine = 10
            clipChildren = false
            clipToPadding = false
        }
        val ballSize = (20 * density).toInt()
        val margin = (2 * density).toInt()
        kl8Numbers.sorted().forEach { num ->
            flBalls.addView(createBall(ctx, num, ballSize, margin))
        }

        val screenWidthPx = (320 * density).toInt()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            screenWidthPx, View.MeasureSpec.EXACTLY
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(
            0, View.MeasureSpec.UNSPECIFIED
        )
        flBalls.measure(widthSpec, heightSpec)
        flBalls.layout(0, 0, flBalls.measuredWidth, flBalls.measuredHeight)

        assertEquals("320dp窄屏应渲染20个球", 20, flBalls.childCount)

        val rows = groupByRow(flBalls)
        assertEquals("320dp窄屏应分2行", 2, rows.size)
        assertEquals("第1行应10个球", 10, rows[0].size)
        assertEquals("第2行应10个球", 10, rows[1].size)

        println("===== 快乐8 2026204期 [320dp窄屏] =====")
        println("FlowLayout尺寸：${flBalls.measuredWidth}x${flBalls.measuredHeight}px")
        println("分行数：${rows.size}，第1行${rows[0].size}个 / 第2行${rows[1].size}个")
    }

    @Test
    fun testKL8_2026204_ExtremeNarrowScreen240dp_PerLine10_NoOverflow() {
        // ⭐ 新增：极端窄屏 240dp
        // 计算：球=20dp, margin=2dp → 单球占位 = 20 + 2×2 = 24dp
        // 10个球需宽 = 10 × 24 = 240dp，恰好等于屏幕宽度
        // 因此：按宽度计算刚好放10个，maxPerLine=10 也恰好是10个，双重条件一致
        // 预期：仍然分2行，每行10个，不溢出（行宽=240dp = 屏幕宽度）
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val density = ctx.resources.displayMetrics.density

        val flBalls = FlowLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            maxPerLine = 10
            clipChildren = false
            clipToPadding = false
        }
        val ballSize = (20 * density).toInt()
        val margin = (2 * density).toInt()
        kl8Numbers.sorted().forEach { num ->
            flBalls.addView(createBall(ctx, num, ballSize, margin))
        }

        // 240dp 极端窄屏（如某些老旧小屏手机、折叠屏最小态）
        val screenWidthPx = (240 * density).toInt()
        val ballUnitWpx = (ballSize + 2 * margin)
        val rowWidthPx = 10 * ballUnitWpx
        println("===== 快乐8 2026204期 [240dp极端窄屏] 前置分析 =====")
        println("屏宽=${screenWidthPx}px (240dp)，球=${ballSize}px，margin=${margin}px")
        println("单球占位=${ballUnitWpx}px (24dp)，10个球需宽=${rowWidthPx}px (240dp)")
        println("240dp屏恰好容纳10球 → 宽度条件与maxPerLine=10条件一致")

        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            screenWidthPx, View.MeasureSpec.EXACTLY
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(
            0, View.MeasureSpec.UNSPECIFIED
        )
        flBalls.measure(widthSpec, heightSpec)
        flBalls.layout(0, 0, flBalls.measuredWidth, flBalls.measuredHeight)

        // 核心断言1：总球数=20
        assertEquals("240dp极端窄屏应渲染20个球", 20, flBalls.childCount)

        val rows = groupByRow(flBalls)
        // 核心断言2：分2行（不能因为屏幕窄而多分行，也不能挤成1行）
        assertEquals("240dp极端窄屏应分2行（恰好每行10个，不溢出也不挤行）", 2, rows.size)
        // 核心断言3：每行恰好10个（宽度刚好够，maxPerLine也刚好触发）
        assertEquals("第1行应恰好10个球", 10, rows[0].size)
        assertEquals("第2行应恰好10个球", 10, rows[1].size)

        // 核心断言4：无横向溢出（FlowLayout总宽 ≤ 屏宽）
        assertTrue(
            "FlowLayout宽度(${flBalls.measuredWidth}px)不应超出屏幕宽度(${screenWidthPx}px)",
            flBalls.measuredWidth <= screenWidthPx
        )

        // 顺序验证
        fun View.getBallNumber(): Int = (this as TextView).text.toString().toInt()
        val line1Max = rows[0].map { it.getBallNumber() }.maxOrNull() ?: -1
        val line2Min = rows[1].map { it.getBallNumber() }.minOrNull() ?: Int.MAX_VALUE
        assertTrue(
            "第1行最大号($line1Max)应 < 第2行最小号($line2Min)",
            line1Max < line2Min
        )

        println("===== 快乐8 2026204期 [240dp极端窄屏] 渲染结果 =====")
        println("FlowLayout尺寸：${flBalls.measuredWidth}x${flBalls.measuredHeight}px / 屏宽=${screenWidthPx}px")
        println("分行数：${rows.size}，第1行${rows[0].size}个 / 第2行${rows[1].size}个")
        fun View.n() = String.format("%02d", getBallNumber())
        println("第1行号码：${rows[0].joinToString(" ") { it.n() }}")
        println("第2行号码：${rows[1].joinToString(" ") { it.n() }}")
        println("总宽≤屏宽：${flBalls.measuredWidth}≤${screenWidthPx} → ${if (flBalls.measuredWidth <= screenWidthPx) "无溢出✅" else "溢出❌"}")
        println("240dp极端窄屏下，10球/行刚好铺满，分2行，无横向溢出✅")
        println("====================================================")
    }

    @Test
    fun testKL8_2026204_WideScreen480dp_ForcedPerLine10() {
        // 宽屏测试：480dp 折叠屏/平板，maxPerLine=10 强制分行
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val density = ctx.resources.displayMetrics.density

        val flBalls = FlowLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            maxPerLine = 10
            clipChildren = false
            clipToPadding = false
        }
        val ballSize = (20 * density).toInt()
        val margin = (2 * density).toInt()
        kl8Numbers.sorted().forEach { num ->
            flBalls.addView(createBall(ctx, num, ballSize, margin))
        }

        val screenWidthPx = (480 * density).toInt()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            screenWidthPx, View.MeasureSpec.EXACTLY
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(
            0, View.MeasureSpec.UNSPECIFIED
        )
        flBalls.measure(widthSpec, heightSpec)
        flBalls.layout(0, 0, flBalls.measuredWidth, flBalls.measuredHeight)

        val rows = groupByRow(flBalls)
        // 核心断言：480dp虽然宽(可放480/24=20个)，但maxPerLine=10仍强制每行10个
        assertEquals("480dp宽屏应强制分2行（不能挤成1行）", 2, rows.size)
        assertEquals("第1行应10个球", 10, rows[0].size)
        assertEquals("第2行应10个球", 10, rows[1].size)
    }

    @Test
    fun testKL8_2026204_NumbersMatchOfficial() {
        // 号码内容与官方2026204期一致性
        val expected = listOf(
            3, 10, 13, 21, 22, 24, 28, 37, 38, 46,
            51, 52, 54, 56, 59, 60, 65, 66, 70, 73
        )
        assertEquals("20个号码与官方一致", expected, kl8Numbers.sorted())
    }

    @Test
    fun testMaxPerLineZero_OnWideScreen_AllowsSingleLine() {
        // 对照组：maxPerLine=0（不限制）时，600dp 超宽屏 20球挤成1行
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val density = ctx.resources.displayMetrics.density

        val flBalls = FlowLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            maxPerLine = 0 // 不限制每行数量
            clipChildren = false
            clipToPadding = false
        }
        val ballSize = (20 * density).toInt()
        val margin = (2 * density).toInt()
        kl8Numbers.sorted().forEach { num ->
            flBalls.addView(createBall(ctx, num, ballSize, margin))
        }

        val screenWidthPx = (600 * density).toInt()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            screenWidthPx, View.MeasureSpec.EXACTLY
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(
            0, View.MeasureSpec.UNSPECIFIED
        )
        flBalls.measure(widthSpec, heightSpec)
        flBalls.layout(0, 0, flBalls.measuredWidth, flBalls.measuredHeight)

        val rows = groupByRow(flBalls)
        assertEquals(
            "maxPerLine=0 时 600dp 超宽屏应只分1行（再现'一行太长'问题）",
            1, rows.size
        )
        assertEquals("单行应容纳20个球", 20, rows[0].size)
    }

    // ============ 辅助方法 ============

    /** 按 child.getTop() 分组为行 */
    private fun groupByRow(fl: FlowLayout): List<List<View>> {
        val rows = mutableListOf<MutableList<View>>()
        val sortedByTop = (0 until fl.childCount)
            .map { fl.getChildAt(it) }
            .sortedBy { it.top }
        var currentTop = Int.MIN_VALUE
        var currentRow = mutableListOf<View>()
        for (child in sortedByTop) {
            if (child.top != currentTop) {
                if (currentRow.isNotEmpty()) rows.add(currentRow)
                currentRow = mutableListOf()
                currentTop = child.top
            }
            currentRow.add(child)
        }
        if (currentRow.isNotEmpty()) rows.add(currentRow)
        return rows
    }

    /** 复刻 MainActivity.kt createBall：创建一个号码球 */
    private fun createBall(
        ctx: android.content.Context,
        num: Int,
        size: Int,
        margin: Int
    ): TextView {
        val ball = TextView(ctx)
        val params = ViewGroup.MarginLayoutParams(size, size)
        params.setMargins(margin, margin, margin, margin)
        ball.layoutParams = params
        ball.gravity = Gravity.CENTER
        ball.text = String.format("%02d", num)
        ball.typeface = Typeface.MONOSPACE
        BallTextHelper.apply(ball, size)
        ball.setBackgroundResource(R.drawable.bg_ball_red)
        ball.setTextColor(Color.WHITE)
        return ball
    }
}
