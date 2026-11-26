package com.novel.reader.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 底部播放列手勢容器：
 * - Play/Pause 鈕左側：左右滑動 → 上/下一段；上下滑動 → 調整亮度
 * - Play/Pause 鈕右側：左右滑動 → 上/下一章；上下滑動 → 調整語速
 * 子 View（Play/Pause 鈕）仍可正常接收點擊與長按，滑動超過 touchSlop 後才由本容器攔截。
 */
class PlayerGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        /** forward = true 表示向右滑（下一段/下一章） */
        fun onSwipeSegment(forward: Boolean)
        fun onSwipeChapter(forward: Boolean)
        /** 垂直拖曳開始，leftSide = true 為亮度區、false 為語速區 */
        fun onVerticalDragStart(leftSide: Boolean)
        /** fraction 為自拖曳起點累積的位移比例（向上為正） */
        fun onVerticalDrag(leftSide: Boolean, fraction: Float)
        fun onVerticalDragEnd(leftSide: Boolean)
    }

    private enum class Mode { NONE, HORIZONTAL, VERTICAL }

    var listener: Listener? = null

    /** 用來判定左右兩側分界的錨點（Play/Pause 鈕），未設定時以容器中線為界 */
    var anchorView: View? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val minSwipeDistancePx = 40f * density
    /** 垂直拖曳此距離等於調整整個範圍 */
    private val verticalFullRangePx = 240f * density

    private var downX = 0f
    private var downY = 0f
    private var mode = Mode.NONE
    private var verticalLeftSide = false

    private fun dividerX(): Float {
        val anchor = anchorView ?: return width / 2f
        var x = anchor.left + anchor.width / 2f
        var parentView = anchor.parent
        while (parentView is View && parentView !== this) {
            x += parentView.left
            parentView = parentView.parent
        }
        return x
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                mode = Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (detectMode(ev)) return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                mode = Mode.NONE
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) detectMode(event)
                if (mode == Mode.VERTICAL) {
                    listener?.onVerticalDrag(verticalLeftSide, (downY - event.y) / verticalFullRangePx)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.HORIZONTAL -> handleHorizontalEnd(event.x)
                    Mode.VERTICAL -> listener?.onVerticalDragEnd(verticalLeftSide)
                    Mode.NONE -> Unit
                }
                mode = Mode.NONE
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.VERTICAL) listener?.onVerticalDragEnd(verticalLeftSide)
                mode = Mode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun detectMode(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dx) < touchSlop && abs(dy) < touchSlop) return false
        mode = if (abs(dx) >= abs(dy)) {
            Mode.HORIZONTAL
        } else {
            verticalLeftSide = downX < dividerX()
            listener?.onVerticalDragStart(verticalLeftSide)
            Mode.VERTICAL
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun handleHorizontalEnd(upX: Float) {
        val dx = upX - downX
        if (abs(dx) < minSwipeDistancePx) return
        val forward = dx > 0
        val startedLeft = downX < dividerX()
        if (startedLeft) {
            listener?.onSwipeSegment(forward)
        } else {
            listener?.onSwipeChapter(forward)
        }
    }
}
