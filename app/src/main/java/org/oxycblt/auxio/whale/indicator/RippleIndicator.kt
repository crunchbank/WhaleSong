/*
 * Copyright (c) 2026 Auxio Project
 * RippleIndicator.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.whale.indicator

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The playing indicator shown on the cover of whatever is playing: rings spreading from a drop,
 * like a whale's song through water. Replaces Auxio's dancing equaliser bars.
 *
 * Playing, three rings spread outwards one after another and fade, while the drop at the centre
 * pulses gently. [playing] false draws the paused state: the drop with two still rings.
 *
 * Drawn in a 24-unit box like a 24dp vector icon, and tinted like one (the cover view gives it its
 * colours through [setTintList]). It keeps its own frame clock rather than using an animator, so it
 * moves even when the system's animation scale is 0. Designed in art/playing-indicator-mockup.html.
 */
class RippleIndicator(context: Context, private val playing: Boolean) : Drawable(), Animatable {
    private val size = (SIZE_DP * context.resources.displayMetrics.density).roundToInt()
    private val ring =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = STROKE
        }
    private val drop = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var tint: ColorStateList? = null
    private var color = Color.WHITE
    private var baseAlpha = 255
    private var running = false
    private var startedAt = 0L

    private val tick =
        object : Runnable {
            override fun run() {
                invalidateSelf()
                if (running && isVisible) scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MS)
            }
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        if (playing) {
            val t = if (running) (SystemClock.uptimeMillis() - startedAt) / 1000f else 0f
            val cycle = t / PERIOD_S
            for (k in 0 until 3) {
                val f = (cycle + k / 3f) % 1f
                val radius = 2.6f + 7.4f * f
                // Fade in quickly as a ring leaves the drop, then out as it spreads.
                val strength = (1f - f).pow(1.4f) * min(1f, f * 6f)
                drawRing(canvas, radius, strength)
            }
            val pulse = 1.5f + 0.35f * max(0f, cos(2f * PI.toFloat() * cycle * 3f))
            drawDrop(canvas, pulse)
        } else {
            drawRing(canvas, 5.2f, 0.9f)
            drawRing(canvas, 8.8f, 0.45f)
            drawDrop(canvas, 1.5f)
        }
        canvas.restoreToCount(save)
    }

    private fun drawRing(canvas: Canvas, radius: Float, strength: Float) {
        ring.color = withAlpha(strength)
        canvas.drawCircle(12f, 12f, radius, ring)
    }

    private fun drawDrop(canvas: Canvas, radius: Float) {
        drop.color = withAlpha(1f)
        canvas.drawCircle(12f, 12f, radius, drop)
    }

    private fun withAlpha(strength: Float): Int {
        val a = Color.alpha(color) * (baseAlpha / 255f) * strength
        return (color and 0x00FFFFFF) or (a.roundToInt().coerceIn(0, 255) shl 24)
    }

    // --- Animatable ---

    override fun start() {
        if (!playing || running) return
        running = true
        startedAt = SystemClock.uptimeMillis()
        unscheduleSelf(tick)
        if (isVisible) scheduleSelf(tick, SystemClock.uptimeMillis())
    }

    override fun stop() {
        running = false
        unscheduleSelf(tick)
    }

    override fun isRunning() = running

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        unscheduleSelf(tick)
        if (visible && running) scheduleSelf(tick, SystemClock.uptimeMillis())
        return changed
    }

    // --- Tinting, like a vector icon ---

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        updateColor(state)
    }

    override fun isStateful() = tint?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean = updateColor(state)

    private fun updateColor(state: IntArray): Boolean {
        val next = tint?.getColorForState(state, tint?.defaultColor ?: Color.WHITE) ?: Color.WHITE
        if (next == color) return false
        color = next
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        baseAlpha = alpha
        invalidateSelf()
    }

    override fun getAlpha() = baseAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        ring.colorFilter = colorFilter
        drop.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth() = size

    override fun getIntrinsicHeight() = size

    private companion object {
        const val SIZE_DP = 24f
        const val STROKE = 1.7f
        /** One ring every [PERIOD_S] / 3 seconds. */
        const val PERIOD_S = 2.4f
        const val FRAME_MS = 16L
    }
}
