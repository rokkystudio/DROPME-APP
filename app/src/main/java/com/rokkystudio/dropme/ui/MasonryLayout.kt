package com.rokkystudio.dropme.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * Размещает карточки в одной колонке на узкой ширине и в двух колонках на широкой.
 * В двухколоночном режиме каждый следующий блок помещается в колонку с меньшей
 * текущей высотой, поэтому вертикальное пространство заполняется без построчного выравнивания.
 */
class MasonryLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {
    private val minimumColumnWidth = dp(320)
    private val itemGap = dp(12)

    /**
     * Измеряет дочерние карточки по ширине активной колонки и вычисляет высоту самой высокой колонки.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = max(0, measuredWidth - paddingLeft - paddingRight)
        val columnCount = columnCount(contentWidth)
        val columnWidth = if (columnCount == 2) {
            max(0, (contentWidth - itemGap) / 2)
        } else {
            contentWidth
        }
        val heights = IntArray(columnCount)

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) {
                continue
            }

            val layoutParams = child.layoutParams as MarginLayoutParams
            val childWidthSpec = getChildMeasureSpec(
                MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.EXACTLY),
                layoutParams.leftMargin + layoutParams.rightMargin,
                layoutParams.width,
            )
            val childHeightSpec = getChildMeasureSpec(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                layoutParams.topMargin + layoutParams.bottomMargin,
                layoutParams.height,
            )
            child.measure(childWidthSpec, childHeightSpec)

            val column = shortestColumn(heights)
            if (heights[column] > 0) {
                heights[column] += itemGap
            }
            heights[column] +=
                layoutParams.topMargin +
                    child.measuredHeight +
                    layoutParams.bottomMargin
        }

        val desiredHeight = paddingTop + heights.maxOrNull().orEmptyHeight() + paddingBottom
        setMeasuredDimension(
            resolveSize(measuredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    /**
     * Раскладывает карточки сверху вниз и для каждой новой карточки выбирает менее занятую колонку.
     */
    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val contentWidth = max(0, right - left - paddingLeft - paddingRight)
        val columnCount = columnCount(contentWidth)
        val columnWidth = if (columnCount == 2) {
            max(0, (contentWidth - itemGap) / 2)
        } else {
            contentWidth
        }
        val heights = IntArray(columnCount) { paddingTop }

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) {
                continue
            }

            val layoutParams = child.layoutParams as MarginLayoutParams
            val column = shortestColumn(heights)
            var childTop = heights[column]
            if (childTop > paddingTop) {
                childTop += itemGap
            }
            childTop += layoutParams.topMargin

            val columnLeft = paddingLeft + column * (columnWidth + itemGap)
            val childLeft = columnLeft + layoutParams.leftMargin
            child.layout(
                childLeft,
                childTop,
                childLeft + child.measuredWidth,
                childTop + child.measuredHeight,
            )
            heights[column] =
                childTop +
                    child.measuredHeight +
                    layoutParams.bottomMargin
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        )
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateLayoutParams(layoutParams: LayoutParams?): LayoutParams {
        return MarginLayoutParams(layoutParams)
    }

    override fun checkLayoutParams(layoutParams: LayoutParams?): Boolean {
        return layoutParams is MarginLayoutParams
    }

    private fun columnCount(contentWidth: Int): Int {
        return if (contentWidth >= minimumColumnWidth * 2 + itemGap) 2 else 1
    }

    private fun shortestColumn(heights: IntArray): Int {
        if (heights.size == 1) {
            return 0
        }
        return if (heights[0] <= heights[1]) 0 else 1
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun Int?.orEmptyHeight(): Int {
        return this ?: 0
    }
}
