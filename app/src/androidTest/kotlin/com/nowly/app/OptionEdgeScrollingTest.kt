package com.nowly.app

import android.Manifest
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.nowly.app.projects.AnswerOption
import com.nowly.app.projects.ProjectDraft
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android's real frame clock to exercise sustained edge scrolling. */
@RunWith(AndroidJUnit4::class)
class OptionEdgeScrollingTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(*if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
    private val ui = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun descend(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::descend).orEmpty() }
        return ui.rootInActiveWindow?.let(::descend).orEmpty()
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            nodes().firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        error("Node missing. Visible: ${nodes().map { "${it.text}/${it.contentDescription}" }}")
    }
    private fun click(text: String) {
        var node = awaitNode { it.text?.toString() == text }
        while (!node.isClickable) node = node.parent ?: error("No click action for $text")
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun openSettings(description: String) {
        repeat(15) {
            val target = nodes().firstOrNull { it.contentDescription?.toString() == description && it.isVisibleToUser }
            if (target != null) {
                var node: AccessibilityNodeInfo = target
                while (!node.isClickable) node = node.parent ?: error("No click action for $description")
                check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
        }
        error("Settings entry not visible: $description")
    }
    private fun lastVisibleOption(): Int = nodes().mapNotNull {
        it.contentDescription?.toString()?.takeIf { label -> label.startsWith("调整选项 ") }
            ?.substringAfter("调整选项 ")?.substringBefore(" 顺序")?.toIntOrNull()
    }.max()
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }

    @Test fun holdingAtTheEdgeScrollsBeyondInitiallyVisibleOptions() {
        val id = UUID.randomUUID().toString()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var container: AppContainer
            scenario.onActivity { container = (it.application as NowlyApplication).container }
            try {
                awaitNode { it.text?.toString() == "心情" }
                val original = runBlocking {
                    container.reminders.save(ProjectDraft(id, "边缘滚动测试", "请选择当前等级", true,
                        List(20) { AnswerOption(UUID.randomUUID().toString(), "等级 ${it + 1}", it) }, listOf(600, 1200)))
                    container.repository.projects().first { it.id == id }
                }
                openSettings("边缘滚动测试设置")
                val handle = awaitNode { it.contentDescription?.toString() == "调整选项 1 顺序" }
                val initialLast = lastVisibleOption()
                val viewport = bounds(awaitNode { it.isScrollable })
                val start = bounds(handle)
                val x = start.exactCenterX()
                val y = start.exactCenterY()
                val edge = viewport.bottom - 12f
                val downTime = SystemClock.uptimeMillis()
                fun inject(action: Int, atY: Float) {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, atY, 0).apply {
                        source = InputDevice.SOURCE_TOUCHSCREEN
                    }
                    try { check(ui.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                inject(MotionEvent.ACTION_DOWN, y)
                try {
                    for (step in 1..30) {
                        SystemClock.sleep(20)
                        inject(MotionEvent.ACTION_MOVE, y + (edge - y) * step / 30f)
                    }
                    val visibleAtEdge = lastVisibleOption()
                    SystemClock.sleep(4_000)
                    val visibleAfterHolding = lastVisibleOption()
                    println("Edge scroll: initialLast=$initialLast, atEdge=$visibleAtEdge, afterHolding=$visibleAfterHolding")
                    assertTrue("Holding still should reveal more options", visibleAfterHolding > visibleAtEdge)
                } finally { inject(MotionEvent.ACTION_UP, edge) }
                SystemClock.sleep(500)
                click("保存")
                awaitNode { it.contentDescription?.toString() == "边缘滚动测试设置" }
                val updated = runBlocking { container.repository.projects().first { it.id == id } }
                val finalPosition = updated.options.indexOfFirst { it.id == original.options.first().id } + 1
                println("Edge scroll saved: initialLast=$initialLast, finalPosition=$finalPosition")
                assertTrue("initialLast=$initialLast, finalPosition=$finalPosition", finalPosition > initialLast)
                assertEquals(original.options.map { it.id }.toSet(), updated.options.map { it.id }.toSet())
                assertEquals(original.name, updated.name)
                assertEquals(original.question, updated.question)
                assertEquals(original.times, updated.times)
            } finally { runBlocking { container.reminders.delete(id) } }
        }
    }
}
