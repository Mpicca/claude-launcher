package dev.picca.launcher.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * "Hands": an accessibility service that lets Claude look at the screen and tap/long-press/
 * swipe/type on it, so it can drive *other* launchers (One UI Home) that expose no API.
 *
 * It does nothing on its own. Every action is a request over the MCP endpoint (token-gated),
 * and the user gates the whole thing by enabling the service in Settings → Accessibility.
 * All methods are safe to call from a non-main thread (the Ktor handler thread).
 */
class HandsService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance.set(this)
        Log.i(TAG, "hands connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* polled, not event-driven */ }
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance.compareAndSet(this, null)
        return super.onUnbind(intent)
    }

    // ------------------------------------------------------------------ observing

    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /** Compact, line-per-node dump of the visible UI tree (bounded). */
    fun dump(maxNodes: Int = 400, filter: String? = null): String {
        val root = rootInActiveWindow ?: return "(no active window)"
        val sb = StringBuilder()
        var count = 0
        val needle = filter?.lowercase()
        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || count >= maxNodes) return
            if (n.isVisibleToUser) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val text = n.text?.toString()
                val desc = n.contentDescription?.toString()
                val id = n.viewIdResourceName?.substringAfter('/')
                val flags = buildString {
                    if (n.isClickable) append('C'); if (n.isLongClickable) append('L'); if (n.isCheckable) append('K')
                    if (n.isChecked) append('✓'); if (n.isScrollable) append('S'); if (n.isEditable) append('E')
                    if (n.isSelected) append('*'); if (n.isFocused) append('F')
                }
                val line = buildString {
                    append(" ".repeat(depth)); append(n.className?.toString()?.substringAfterLast('.') ?: "?")
                    if (text != null) append(" text=\"").append(text.take(60)).append('"')
                    if (desc != null) append(" desc=\"").append(desc.take(60)).append('"')
                    if (id != null) append(" id=").append(id)
                    append(" [").append(r.left).append(',').append(r.top).append(',').append(r.right).append(',').append(r.bottom).append(']')
                    if (flags.isNotEmpty()) append(' ').append(flags)
                }
                val interesting = text != null || desc != null || n.isClickable || n.isScrollable || n.isEditable || n.isCheckable
                if (interesting && (needle == null || line.lowercase().contains(needle))) {
                    sb.appendLine(line); count++
                }
            }
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
        }
        visit(root, 0)
        if (count >= maxNodes) sb.appendLine("… truncated at $maxNodes nodes")
        return sb.toString()
    }

    /** Scaled JPEG screenshot as base64 (API 30+). */
    fun screenshotBase64(targetWidth: Int = 540, quality: Int = 55): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw IllegalStateException("screenshots need Android 11+")
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var err: String? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    bitmap = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                } catch (e: Throwable) { err = e.toString() } finally { latch.countDown() }
            }
            override fun onFailure(errorCode: Int) { err = "screenshot failed: code $errorCode"; latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
        val bmp = bitmap ?: throw IllegalStateException(err ?: "screenshot timed out")
        val scale = targetWidth.toFloat() / bmp.width
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, targetWidth, (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ------------------------------------------------------------------ acting

    fun tap(x: Float, y: Float, holdMs: Long = 60): Boolean = gesture(Path().apply { moveTo(x, y) }, holdMs)
    fun longPress(x: Float, y: Float, holdMs: Long = 900): Boolean = gesture(Path().apply { moveTo(x, y) }, holdMs)
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 300): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)

    private fun gesture(path: Path, ms: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val dispatched = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { ok = false; latch.countDown() }
        }, null)
        if (!dispatched) return false
        latch.await(ms + 3000, TimeUnit.MILLISECONDS)
        return ok
    }

    /** Find visible nodes whose text or content-description matches (substring, case-insensitive; `exact` for equality). */
    fun find(text: String, exact: Boolean = false): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        val needle = text.lowercase()
        fun matches(s: CharSequence?): Boolean {
            val v = s?.toString()?.lowercase() ?: return false
            return if (exact) v == needle else v.contains(needle)
        }
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isVisibleToUser && (matches(n.text) || matches(n.contentDescription))) out += n
            for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        visit(root)
        return out
    }

    /** Click a node by text: prefer the accessibility action, fall back to a tap at its center. */
    fun clickText(text: String, exact: Boolean = false, index: Int = 0, long: Boolean = false): String {
        val nodes = find(text, exact)
        val n = nodes.getOrNull(index) ?: return "not found: \"$text\" (matches=${nodes.size})"
        val target = generateSequence(n) { it.parent }.firstOrNull { if (long) it.isLongClickable else it.isClickable }
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        if (target != null && target.performAction(action)) return "ok via action on ${target.className?.toString()?.substringAfterLast('.')}"
        val r = Rect().also { n.getBoundsInScreen(it) }
        val ok = if (long) longPress(r.exactCenterX(), r.exactCenterY()) else tap(r.exactCenterX(), r.exactCenterY())
        return if (ok) "ok via gesture at (${r.centerX()},${r.centerY()})" else "gesture failed at (${r.centerX()},${r.centerY()})"
    }

    /** Type into the focused editable field, or the first editable on screen. */
    fun setText(text: String): String {
        val root = rootInActiveWindow ?: return "no active window"
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focused?.takeIf { it.isEditable } ?: run {
            var found: AccessibilityNodeInfo? = null
            fun visit(n: AccessibilityNodeInfo?) { if (n == null || found != null) return; if (n.isEditable && n.isVisibleToUser) { found = n; return }; for (i in 0 until n.childCount) visit(n.getChild(i)) }
            visit(root); found
        } ?: return "no editable field on screen"
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "ok" else "set text failed"
    }

    fun key(name: String): Boolean = when (name.lowercase()) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }

    companion object {
        private const val TAG = "HandsService"
        private val executor = Executors.newSingleThreadExecutor()
        private val instance = AtomicReference<HandsService?>(null)
        val current: HandsService? get() = instance.get()
    }
}
