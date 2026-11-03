/*
 * SPDX-FileCopyrightText: 2026 Saalim Quadri <danascape@gmail.com>
 * SPDX-License-Identifier: Apache-2.0
 */

package org.prauga.recorder.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import org.prauga.recorder.audio.WaveformSink

/** Static waveform of a recording with progress, seeking, an optional selection and tag ranges. */
class PlaybackWaveformView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr) {
    interface Listener {
        fun onScrub(positionMs: Long) {}

        fun onSeek(positionMs: Long)

        fun onSelectionChanged(startMs: Long, endMs: Long) {}
    }

    data class Range(val startMs: Long, val endMs: Long, val color: Int)

    var listener: Listener? = null

    private var amplitudes = ByteArray(0)
    var durationMs = 0L
        private set

    var positionMs = 0L
        set(value) {
            if (!dragging) {
                field = value.coerceIn(0, durationMs)
                invalidate()
            }
        }

    var ranges: List<Range> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var selectionEnabled = false
        set(value) {
            field = value
            if (value && selectionEndMs <= selectionStartMs) {
                selectionStartMs = 0
                selectionEndMs = durationMs
            }
            invalidate()
        }

    var selectionStartMs = 0L
        private set

    var selectionEndMs = 0L
        private set

    private val density = resources.displayMetrics.density
    private val barWidth = 2 * density
    private val barGap = 1.5f * density
    private val handleWidth = 4 * density

    private val playedPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MaterialColors.getColor(this@PlaybackWaveformView, android.R.attr.colorPrimary)
            strokeCap = Paint.Cap.ROUND
            strokeWidth = barWidth
        }
    private val restPaint =
        Paint(playedPaint).apply {
            color =
                MaterialColors.getColor(
                    this@PlaybackWaveformView,
                    com.google.android.material.R.attr.colorOutline,
                )
        }
    private val cursorPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color =
                MaterialColors.getColor(
                    this@PlaybackWaveformView,
                    com.google.android.material.R.attr.colorOnSurface,
                )
            strokeWidth = 2 * density
        }
    private val selectionPaint =
        Paint().apply {
            color =
                MaterialColors.getColor(
                    this@PlaybackWaveformView,
                    com.google.android.material.R.attr.colorPrimaryContainer,
                )
            alpha = 140
        }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = playedPaint.color }
    private val rangePaint = Paint()

    private var dragging = false
    private var dragTarget = DRAG_POSITION

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setWaveform(amplitudes: ByteArray, durationMs: Long) {
        this.amplitudes = amplitudes
        this.durationMs = max(durationMs, amplitudes.size.toLong() * WaveformSink.WINDOW_MS)
        if (selectionEndMs == 0L) selectionEndMs = this.durationMs
        invalidate()
    }

    private fun xFor(ms: Long) =
        if (durationMs == 0L) paddingLeft.toFloat()
        else paddingLeft + contentWidth() * ms / durationMs.toFloat()

    private fun msFor(x: Float) =
        if (contentWidth() <= 0f) 0L
        else ((x - paddingLeft) / contentWidth() * durationMs).toLong().coerceIn(0, durationMs)

    private fun contentWidth() = (width - paddingLeft - paddingRight).toFloat()

    override fun onDraw(canvas: Canvas) {
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val mid = (top + bottom) / 2
        val half = (bottom - top) / 2

        for (range in ranges) {
            rangePaint.color = range.color
            canvas.drawRect(xFor(range.startMs), top, xFor(range.endMs), bottom, rangePaint)
        }

        if (selectionEnabled) {
            val l = xFor(selectionStartMs)
            val r = xFor(selectionEndMs)
            canvas.drawRect(l, top, r, bottom, selectionPaint)
            canvas.drawRect(l - handleWidth / 2, top, l + handleWidth / 2, bottom, handlePaint)
            canvas.drawRect(r - handleWidth / 2, top, r + handleWidth / 2, bottom, handlePaint)
        }

        val bars = (contentWidth() / (barWidth + barGap)).toInt()
        if (bars > 0 && amplitudes.isNotEmpty()) {
            val progressX = xFor(positionMs)
            for (b in 0 until bars) {
                val from = (b.toLong() * amplitudes.size / bars).toInt()
                val to = max(from + 1, ((b + 1).toLong() * amplitudes.size / bars).toInt())
                var peak = 0
                for (i in from until minOf(to, amplitudes.size)) {
                    peak = max(peak, amplitudes[i].toInt() and 0xFF)
                }
                val h = max(barWidth / 2, half * sqrt(peak / 255f))
                val x = paddingLeft + b * (barWidth + barGap) + barWidth / 2
                canvas.drawLine(
                    x,
                    mid - h,
                    x,
                    mid + h,
                    if (x <= progressX) playedPaint else restPaint,
                )
            }
        }

        val cx = xFor(positionMs)
        canvas.drawLine(cx, top, cx, bottom, cursorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs == 0L) return false
        val ms = msFor(event.x)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                dragTarget = DRAG_POSITION
                if (selectionEnabled) {
                    val slop = 24 * density
                    val dl = abs(event.x - xFor(selectionStartMs))
                    val dr = abs(event.x - xFor(selectionEndMs))
                    if (dl < slop || dr < slop) {
                        dragTarget = if (dl <= dr) DRAG_START else DRAG_END
                    }
                }
                update(ms)
            }
            MotionEvent.ACTION_MOVE -> update(ms)
            MotionEvent.ACTION_UP -> {
                update(ms)
                dragging = false
                if (dragTarget == DRAG_POSITION) listener?.onSeek(positionMs)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private fun update(ms: Long) {
        when (dragTarget) {
            DRAG_START -> selectionStartMs = ms.coerceAtMost(selectionEndMs - MIN_SELECTION_MS)
            DRAG_END -> selectionEndMs = ms.coerceAtLeast(selectionStartMs + MIN_SELECTION_MS)
            else -> {
                positionMsInternal(ms)
                listener?.onScrub(ms)
            }
        }
        if (dragTarget != DRAG_POSITION) {
            selectionStartMs = selectionStartMs.coerceIn(0, durationMs)
            selectionEndMs = selectionEndMs.coerceIn(0, durationMs)
            listener?.onSelectionChanged(selectionStartMs, selectionEndMs)
        }
        invalidate()
    }

    private fun positionMsInternal(ms: Long) {
        val wasDragging = dragging
        dragging = false
        positionMs = ms
        dragging = wasDragging
    }

    override fun performClick() = super.performClick()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.stateDescription =
            "${DateUtils.formatElapsedTime(positionMs / 1000)} / " +
                DateUtils.formatElapsedTime(durationMs / 1000)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val delta =
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> A11Y_STEP_MS
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -A11Y_STEP_MS
                else -> return super.performAccessibilityAction(action, arguments)
            }
        positionMs += delta
        listener?.onSeek(positionMs)
        return true
    }

    companion object {
        private const val DRAG_POSITION = 0
        private const val DRAG_START = 1
        private const val DRAG_END = 2
        private const val MIN_SELECTION_MS = 500L
        private const val A11Y_STEP_MS = 10_000L
    }
}
