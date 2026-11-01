package com.philipvella.maltatv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * A virtual mouse pointer for TV remotes.
 *
 * Draws an arrow on top of the web page; the D-pad moves it (accelerating while held),
 * OK/Enter clicks where it points, and pushing against the top/bottom edge scrolls.
 * It auto-hides after [HIDE_AFTER_MS] of inactivity.
 */
class PointerOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Where clicks/hover go and how scrolling is performed. Set by the activity. */
    interface Host {
        /** View that should receive synthetic touch / hover events, or null if pointer is inactive. */
        fun pointerTarget(): View?
        /** Scroll the content under (x, y) by dy pixels; callback(true) if something scrolled. */
        fun scrollAt(x: Float, y: Float, dy: Int, callback: (Boolean) -> Unit)
        /** Pointer pushed off the top of a page that can't scroll further up. */
        fun onPointerExitTop()
    }

    var host: Host? = null

    private val density = resources.displayMetrics.density
    private var px = -1f
    private var py = -1f
    private var shown = false

    private var left = false
    private var right = false
    private var up = false
    private var down = false
    private var holdStart = 0L
    private var lastHover = 0L
    private var scrollPending = false

    private val arrow = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000; style = Paint.Style.FILL }

    init {
        isClickable = false
        isFocusable = false
        val s = density * 1.6f
        // Classic arrow cursor, tip at (0,0)
        arrow.moveTo(0f, 0f)
        arrow.lineTo(0f, 17f * s)
        arrow.lineTo(4.5f * s, 13f * s)
        arrow.lineTo(7.5f * s, 20f * s)
        arrow.lineTo(10.5f * s, 18.8f * s)
        arrow.lineTo(7.5f * s, 12f * s)
        arrow.lineTo(13f * s, 12f * s)
        arrow.close()
    }

    private val hideRunnable = Runnable { hide() }

    private val tick = object : Runnable {
        override fun run() {
            if (!(left || right || up || down)) return
            step()
            postOnAnimation(this)
        }
    }

    // ------------------------------------------------------------- public API

    fun show() {
        if (px < 0) { px = width / 2f; py = height / 2f }
        shown = true
        invalidate()
        scheduleHide()
    }

    fun hide() {
        shown = false
        left = false; right = false; up = false; down = false
        removeCallbacks(hideRunnable)
        invalidate()
    }

    /** Returns true if the key was consumed by the pointer. */
    fun handleKey(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> setDir(isDown) { left = it }
            KeyEvent.KEYCODE_DPAD_RIGHT -> setDir(isDown) { right = it }
            KeyEvent.KEYCODE_DPAD_UP -> setDir(isDown) { up = it }
            KeyEvent.KEYCODE_DPAD_DOWN -> setDir(isDown) { down = it }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (isDown && event.repeatCount == 0) {
                    if (shown) click() else show()
                }
                if (!isDown) scheduleHide()
            }
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------- internals

    private inline fun setDir(isDown: Boolean, set: (Boolean) -> Unit) {
        if (isDown) {
            val wasMoving = left || right || up || down
            set(true)
            if (!shown) show()
            if (!wasMoving) {
                holdStart = SystemClock.uptimeMillis()
                step()
                postOnAnimation(tick)
            }
        } else {
            set(false)
        }
        scheduleHide()
    }

    private fun step() {
        val held = SystemClock.uptimeMillis() - holdStart
        // Start precise, accelerate the longer the key is held
        val speed = density * when {
            held < 150 -> 3f
            held < 600 -> 6f
            held < 1200 -> 12f
            else -> 20f
        }
        var dx = 0f
        var dy = 0f
        if (left) dx -= speed
        if (right) dx += speed
        if (up) dy -= speed
        if (down) dy += speed

        val maxX = width - 1f
        val maxY = height - 1f
        px = (px + dx).coerceIn(0f, maxX)
        val wantY = py + dy
        py = wantY.coerceIn(0f, maxY)

        // Pushing against top/bottom edge scrolls the page
        val edge = 24f * density
        if (!scrollPending && ((dy > 0 && py >= maxY - edge) || (dy < 0 && py <= edge))) {
            scrollPending = true
            val amount = (dy * 3).toInt()
            host?.scrollAt(px, py, amount) { scrolled ->
                scrollPending = false
                if (!scrolled && amount < 0 && py <= edge) host?.onPointerExitTop()
            }
        }

        sendHover()
        scheduleHide()
        invalidate()
    }

    private fun scheduleHide() {
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, HIDE_AFTER_MS)
    }

    private fun localPoint(target: View): FloatArray {
        val me = IntArray(2).also { getLocationOnScreen(it) }
        val t = IntArray(2).also { target.getLocationOnScreen(it) }
        return floatArrayOf(px + me[0] - t[0], py + me[1] - t[1])
    }

    /** Hover events let sites reveal player controls / menus, like a real mouse. */
    private fun sendHover() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHover < 50) return
        lastHover = now
        val target = host?.pointerTarget() ?: return
        val (x, y) = localPoint(target).let { it[0] to it[1] }
        val ev = mouseEvent(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y)
        target.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun click() {
        val target = host?.pointerTarget() ?: return
        val (x, y) = localPoint(target).let { it[0] to it[1] }
        val t = SystemClock.uptimeMillis()
        val downEv = touchEvent(t, t, MotionEvent.ACTION_DOWN, x, y)
        val upEv = touchEvent(t, t + 60, MotionEvent.ACTION_UP, x, y)
        target.dispatchTouchEvent(downEv)
        target.dispatchTouchEvent(upEv)
        downEv.recycle(); upEv.recycle()
        // Brief "press" feedback
        pressedFlash = true
        invalidate()
        postDelayed({ pressedFlash = false; invalidate() }, 120)
        scheduleHide()
    }

    private var pressedFlash = false

    private fun touchEvent(downTime: Long, time: Long, action: Int, x: Float, y: Float) =
        MotionEvent.obtain(downTime, time, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }

    private fun mouseEvent(downTime: Long, time: Long, action: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
        return MotionEvent.obtain(
            downTime, time, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        canvas.save()
        canvas.translate(px + 2 * density, py + 2 * density)
        canvas.drawPath(arrow, shadow)
        canvas.restore()
        canvas.save()
        canvas.translate(px, py)
        if (pressedFlash) canvas.scale(0.85f, 0.85f)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        canvas.restore()
    }

    companion object {
        const val HIDE_AFTER_MS = 3000L
    }
}
