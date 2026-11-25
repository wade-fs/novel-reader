package com.novel.reader.util

import android.os.Handler
import android.os.Looper

/**
 * 快速音量變更控制器 (以半秒 500ms 為單位)
 * - 半秒內 2 次同按鈕：前進/後退一段落
 * - 半秒內 3 次連按同按鈕：前進/後退一章
 * - 只有 1 次：正常音量調節，不改變進度
 */
class FastVolumeController(
    val windowMs: Long = 500L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val scheduler: (delayMs: Long, action: () -> Unit) -> Any? = { delayMs, action ->
        val handler = Handler(Looper.getMainLooper())
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        runnable to handler
    },
    private val canceller: (token: Any?) -> Unit = { token ->
        if (token is Pair<*, *>) {
            val runnable = token.first as? Runnable
            val handler = token.second as? Handler
            if (runnable != null && handler != null) {
                handler.removeCallbacks(runnable)
            }
        }
    },
    private val onParagraphForward: () -> Unit,
    private val onParagraphBackward: () -> Unit,
    private val onChapterForward: () -> Unit,
    private val onChapterBackward: () -> Unit,
    private val onSingleVolumeClick: (direction: Int) -> Unit = {}
) {
    var currentDirection: Int = 0 // +1 (Up), -1 (Down)
        private set
    var clickCount: Int = 0
        private set
    private var firstClickTime: Long = 0L
    private var pendingToken: Any? = null

    /**
     * 收到音量變更/按鍵事件
     * @param direction +1 代表增加/前進，-1 代表減少/後退
     * @return 觸發的動作 (0: 等待/無進度動作，1: 觸發段落，2: 觸發章節)
     */
    @Synchronized
    fun onVolumeEvent(direction: Int): Int {
        if (direction != 1 && direction != -1) return 0
        val now = timeProvider()

        if (clickCount == 0) {
            // 第 1 次點擊
            currentDirection = direction
            clickCount = 1
            firstClickTime = now
            scheduleTimer(windowMs)
            return 0
        }

        val elapsed = now - firstClickTime
        if (elapsed <= windowMs && direction == currentDirection) {
            // 半秒內同方向連續點擊
            clickCount++
            if (clickCount == 2) {
                // 2 次點擊：等待 500ms 窗口結束看是否有第 3 次
                cancelTimer()
                val remaining = maxOf(50L, windowMs - elapsed)
                scheduleTimer(remaining)
                return 0
            } else if (clickCount >= 3) {
                // 3 次連按：立即觸發切換章節！
                cancelTimer()
                val dir = currentDirection
                reset()
                if (dir == 1) onChapterForward() else onChapterBackward()
                return 2
            }
        } else {
            // 超過 500ms 或不同方向按鍵：重置前次序列，啟動新序列
            cancelTimer()
            currentDirection = direction
            clickCount = 1
            firstClickTime = now
            scheduleTimer(windowMs)
        }
        return 0
    }

    private fun scheduleTimer(delayMs: Long) {
        cancelTimer()
        pendingToken = scheduler(delayMs) {
            onTimeout()
        }
    }

    private fun cancelTimer() {
        pendingToken?.let { canceller(it) }
        pendingToken = null
    }

    @Synchronized
    fun onTimeout() {
        pendingToken = null
        val count = clickCount
        val dir = currentDirection
        reset()

        when (count) {
            1 -> {
                // 單次點擊：正常音量調節
                onSingleVolumeClick(dir)
            }
            2 -> {
                // 半秒內 2 次同按鈕：前進/後退一段落！
                if (dir == 1) onParagraphForward() else onParagraphBackward()
            }
            // count >= 3 已在點擊瞬間立即觸發
        }
    }

    @Synchronized
    fun reset() {
        cancelTimer()
        currentDirection = 0
        clickCount = 0
        firstClickTime = 0L
    }
}
