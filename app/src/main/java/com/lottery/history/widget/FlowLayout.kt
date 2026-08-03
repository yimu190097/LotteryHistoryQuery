package com.lottery.history.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * 流式布局：子元素（号码球）按行从左到右排列，超出可用宽度时自动换行。
 *
 * 用于所有红球/蓝球展示区域，保证号码球自适应分行显示、不重叠、不溢出，
 * 相比 HorizontalScrollView 不会隐藏信息，相比 LinearLayout(horizontal) 不会挤压重叠。
 *
 * 子元素通过 margin 控制间距，本布局不再额外加间距。
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val contentMaxWidth =
            if (widthMode != MeasureSpec.UNSPECIFIED) (widthSize - paddingLeft - paddingRight) else Int.MAX_VALUE

        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = 0
        var maxLineWidth = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            val cw = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val ch = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (lineWidth + cw > contentMaxWidth && lineWidth > 0) {
                // 当前行放不下，换行
                totalHeight += lineHeight
                maxLineWidth = maxOf(maxLineWidth, lineWidth)
                lineWidth = cw
                lineHeight = ch
            } else {
                lineWidth += cw
                lineHeight = maxOf(lineHeight, ch)
            }
        }
        totalHeight += lineHeight
        maxLineWidth = maxOf(maxLineWidth, lineWidth)

        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(maxLineWidth + paddingLeft + paddingRight, widthSize)
            else -> maxLineWidth + paddingLeft + paddingRight
        }
        val measuredHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(totalHeight + paddingTop + paddingBottom, heightSize)
            else -> totalHeight + paddingTop + paddingBottom
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val contentWidth = (r - l) - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val cw = child.measuredWidth
            val ch = child.measuredHeight

            if (x + lp.leftMargin + cw + lp.rightMargin > paddingLeft + contentWidth && x > paddingLeft) {
                // 换行
                x = paddingLeft
                y += lineHeight
                lineHeight = 0
            }
            val childLeft = x + lp.leftMargin
            val childTop = y + lp.topMargin
            child.layout(childLeft, childTop, childLeft + cw, childTop + ch)
            x += lp.leftMargin + cw + lp.rightMargin
            lineHeight = maxOf(lineHeight, lp.topMargin + ch + lp.bottomMargin)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    override fun checkLayoutParams(p: LayoutParams): Boolean {
        return p is MarginLayoutParams
    }

    override fun generateLayoutParams(p: LayoutParams): LayoutParams {
        return MarginLayoutParams(p)
    }
}
