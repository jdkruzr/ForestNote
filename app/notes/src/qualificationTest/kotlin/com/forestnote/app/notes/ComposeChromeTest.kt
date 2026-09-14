package com.forestnote.app.notes

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue

/** Use the real Compose accessibility tree or physical touch, never a callback shortcut. */
internal object ComposeChromeTest {
    suspend fun node(tag: String, selected: Boolean? = null): AccessibilityNodeInfo = withTimeout(15000) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("compose_wait", "$tag selected=$selected")
        })
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.viewIdResourceName == tag && n.isVisibleToUser && (selected == null || n.isSelected == selected)) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let {return it}
            return null
        }
        while (true) {
            find(automation.rootInActiveWindow)?.let {return@withTimeout it}
            delay(50)
        }
        error("unreachable")
    }

    suspend fun click(tag: String) {
        assertTrue("Compose action: $tag", node(tag).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    suspend fun labels(tag: String): List<String> {
        // Android's Compose delegate exposes text/description as virtual descendants even
        // for a merged control. Inspect only this control's subtree, never unrelated text.
        val result = mutableListOf<String>()
        fun collect(n: AccessibilityNodeInfo) {
            n.text?.toString()?.takeIf {it.isNotBlank()}?.let {result.add(it)}
            n.contentDescription?.toString()?.takeIf {it.isNotBlank()}?.let {result.add(it)}
            for (i in 0 until n.childCount) n.getChild(i)?.let {collect(it)}
        }
        collect(node(tag))
        return result
    }

    suspend fun tap(tag: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bounds = Rect().also {node(tag).getBoundsInScreen(it)}
        val down = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply {source = InputDevice.SOURCE_TOUCHSCREEN}
            try {assertTrue(automation.injectInputEvent(event, true))} finally {event.recycle()}
        }
        send(MotionEvent.ACTION_DOWN)
        delay(80)
        send(MotionEvent.ACTION_UP)
    }
}
