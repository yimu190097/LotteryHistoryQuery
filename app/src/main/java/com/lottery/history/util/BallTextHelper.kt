package com.lottery.history.util

import android.content.res.Resources
import android.util.TypedValue

/**
 * 球内文字字号计算工具。
 *
 * 根据球的实际像素尺寸按比例计算文字字号，使两位数字（如"33"）始终
 * 在球内清晰可见且不溢出。
 *
 * 经验比例：两位数字宽度约为字号的 1.1 倍（单位 px），为保证留白，
 * 文字宽度取球内径的 0.62 倍，反推字号。
 */
object BallTextHelper {

    /** 文字宽度占球内径的比例（留出约 38% 边距） */
    private const val TEXT_WIDTH_RATIO = 0.62f

    /** 两位数字宽度相对字号的系数（粗略：每字符约 0.55 倍字号宽） */
    private const val DIGIT_WIDTH_FACTOR = 1.1f

    /** 字号下限（sp），避免过小不可读 */
    private const val MIN_TEXT_SP = 7f

    /** 字号上限（sp），避免过大溢出 */
    private const val MAX_TEXT_SP = 14f

    /**
     * 根据球的像素尺寸计算合适的文字字号（px）。
     *
     * @param ballSizePx 球的边长（像素）
     * @param density 屏幕密度
     * @return 文字字号（像素）
     */
    fun textSizePx(ballSizePx: Int, density: Float): Float {
        // 球内可用宽度 = 球尺寸 × 比例
        val availableWidth = ballSizePx * TEXT_WIDTH_RATIO
        // 反推字号 px：可用宽度 / 数字宽度系数
        val textSizePx = availableWidth / DIGIT_WIDTH_FACTOR
        // 转换为 sp 并夹取到合理区间
        val textSizeSp = (textSizePx / density).coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
        return textSizeSp * density
    }

    /**
     * 便捷方法：直接设置到 TextView。
     *
     * @param tv 目标 TextView
     * @param ballSizePx 球的边长（像素）
     */
    fun apply(tv: android.widget.TextView, ballSizePx: Int) {
        val density = tv.resources.displayMetrics.density
        val sizePx = textSizePx(ballSizePx, density)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
    }

    /**
     * 获取固定区头部小球（18dp 左右）的字号 px。
     * 使用与资源一致的 density。
     */
    fun headerBallTextPx(res: Resources, ballSizePx: Int): Float {
        val density = res.displayMetrics.density
        return textSizePx(ballSizePx, density)
    }
}
