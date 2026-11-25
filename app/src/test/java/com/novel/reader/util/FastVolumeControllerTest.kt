package com.novel.reader.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FastVolumeControllerTest {

    private var currentTime = 1000L
    private val pendingTasks = mutableListOf<Pair<Long, () -> Unit>>()

    private var paragraphForwardCount = 0
    private var paragraphBackwardCount = 0
    private var chapterForwardCount = 0
    private var chapterBackwardCount = 0
    private var singleVolumeClickDirection = 0

    private lateinit var controller: FastVolumeController

    @Before
    fun setUp() {
        currentTime = 1000L
        pendingTasks.clear()
        paragraphForwardCount = 0
        paragraphBackwardCount = 0
        chapterForwardCount = 0
        chapterBackwardCount = 0
        singleVolumeClickDirection = 0

        controller = FastVolumeController(
            windowMs = 500L,
            timeProvider = { currentTime },
            scheduler = { delayMs, action ->
                val task = Pair(currentTime + delayMs, action)
                pendingTasks.add(task)
                task
            },
            canceller = { token ->
                pendingTasks.remove(token)
            },
            onParagraphForward = { paragraphForwardCount++ },
            onParagraphBackward = { paragraphBackwardCount++ },
            onChapterForward = { chapterForwardCount++ },
            onChapterBackward = { chapterBackwardCount++ },
            onSingleVolumeClick = { dir -> singleVolumeClickDirection = dir }
        )
    }

    private fun advanceTime(ms: Long) {
        currentTime += ms
        val readyTasks = pendingTasks.filter { it.first <= currentTime }.sortedBy { it.first }
        readyTasks.forEach { task ->
            pendingTasks.remove(task)
            task.second.invoke()
        }
    }

    @Test
    fun testSingleClickDoesNotChangeProgress() {
        controller.onVolumeEvent(1) // Single click Volume Up
        assertEquals(1, controller.clickCount)
        assertEquals(1, controller.currentDirection)

        // Advance 300ms (still within 500ms window)
        advanceTime(300)
        assertEquals(0, paragraphForwardCount)
        assertEquals(0, chapterForwardCount)

        // Advance past 500ms
        advanceTime(250) // Total 550ms
        assertEquals(1, singleVolumeClickDirection)
        assertEquals(0, paragraphForwardCount)
        assertEquals(0, chapterForwardCount)
        assertEquals(0, controller.clickCount)
    }

    @Test
    fun testDoubleClickWithin500msTriggersParagraphForward() {
        controller.onVolumeEvent(1) // Click 1 at 1000ms
        advanceTime(200)             // 1200ms
        controller.onVolumeEvent(1) // Click 2 at 1200ms (within 500ms)
        assertEquals(2, controller.clickCount)
        assertEquals(0, paragraphForwardCount) // Waiting for timeout

        advanceTime(350) // Now 1550ms (window of 500ms from 1000ms expired)
        assertEquals(1, paragraphForwardCount)
        assertEquals(0, chapterForwardCount)
        assertEquals(0, controller.clickCount)
    }

    @Test
    fun testDoubleClickWithin500msTriggersParagraphBackward() {
        controller.onVolumeEvent(-1) // Click 1 Volume Down
        advanceTime(200)
        controller.onVolumeEvent(-1) // Click 2 Volume Down
        assertEquals(2, controller.clickCount)
        assertEquals(0, paragraphBackwardCount)

        advanceTime(350) // Window expired
        assertEquals(1, paragraphBackwardCount)
        assertEquals(0, chapterBackwardCount)
    }

    @Test
    fun testTripleClickWithin500msTriggersChapterForwardImmediately() {
        controller.onVolumeEvent(1) // Click 1 at 1000ms
        advanceTime(150)             // 1150ms
        controller.onVolumeEvent(1) // Click 2 at 1150ms
        advanceTime(150)             // 1300ms
        val result = controller.onVolumeEvent(1) // Click 3 at 1300ms (within 500ms of 1000ms)

        assertEquals(2, result) // Returns chapter triggered
        assertEquals(1, chapterForwardCount) // Triggered IMMEDIATELY on 3rd click
        assertEquals(0, paragraphForwardCount) // Does NOT trigger paragraph
        assertEquals(0, controller.clickCount) // Reset

        // Advance further, nothing extra should fire
        advanceTime(1000)
        assertEquals(1, chapterForwardCount)
        assertEquals(0, paragraphForwardCount)
    }

    @Test
    fun testTripleClickWithin500msTriggersChapterBackwardImmediately() {
        controller.onVolumeEvent(-1)
        advanceTime(100)
        controller.onVolumeEvent(-1)
        advanceTime(100)
        controller.onVolumeEvent(-1)

        assertEquals(1, chapterBackwardCount)
        assertEquals(0, paragraphBackwardCount)
        assertEquals(0, controller.clickCount)
    }

    @Test
    fun testOppositeButtonCancelsPreviousDirection() {
        controller.onVolumeEvent(1) // Click Up
        advanceTime(100)
        controller.onVolumeEvent(-1) // Click Down: changes direction
        assertEquals(-1, controller.currentDirection)
        assertEquals(1, controller.clickCount)

        advanceTime(550) // Timeout for Down
        assertEquals(-1, singleVolumeClickDirection)
        assertEquals(0, paragraphForwardCount)
        assertEquals(0, paragraphBackwardCount)
    }

    @Test
    fun testClicksSeparatedByMoreThan500msDoNotCombine() {
        controller.onVolumeEvent(1) // Click 1 at 1000ms
        advanceTime(600)             // Window expires at 1500ms -> single click
        assertEquals(1, singleVolumeClickDirection)
        assertEquals(0, paragraphForwardCount)

        controller.onVolumeEvent(1) // Click 2 at 1600ms (starts fresh sequence)
        advanceTime(600)             // Window expires at 2100ms -> single click
        assertEquals(0, paragraphForwardCount)
        assertEquals(0, chapterForwardCount)
    }
}
