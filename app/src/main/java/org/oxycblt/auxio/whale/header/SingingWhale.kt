/*
 * Copyright (c) 2026 crunchbank (WhaleSong).
 *
 * This file is part of WhaleSong, a modified version of Auxio.
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

package org.oxycblt.auxio.whale.header

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.google.android.material.R as MR
import kotlin.math.roundToInt
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.auxio.whale.sightings.Humpback
import org.oxycblt.auxio.whale.sightings.ease
import org.oxycblt.auxio.whale.sightings.lerp

/**
 * The home toolbar's title: the sightings' humpback, drawn in the title's colour, singing. Three
 * notes rise from its head in turn and fade, and it bobs gently. Design "B · Whale song" in
 * art/whale-header-mockup.html, whose timings this follows. Like the whale sightings, it keeps
 * singing with system animations off.
 */
@SuppressLint("ViewConstructor")
class SingingWhale(context: Context) : View(context) {
    private val ink =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getAttrColorCompat(MR.attr.colorOnSurface).defaultColor
        }
    // The mouth, pleats and eye are cut out of the whale, so the toolbar shows through them.
    private val cut =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            strokeCap = Paint.Cap.ROUND
        }
    private val layerBounds = RectF(LEFT, TOP, LEFT + WIDTH, TOP + HEIGHT)
    private val start = SystemClock.uptimeMillis()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (SIZE_DP * resources.displayMetrics.density).roundToInt()
        setMeasuredDimension((h * WIDTH / HEIGHT).roundToInt(), h)
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val s = height / HEIGHT

        canvas.save()
        canvas.scale(s, s)
        canvas.translate(-LEFT, -TOP)

        // The whale, bobbing a little over six seconds.
        val layer = canvas.saveLayer(layerBounds, null)
        canvas.translate(0f, BOB * wave(t / 6f))
        canvas.drawPath(Humpback.flipper, ink)
        canvas.drawPath(Humpback.body, ink)
        cut.style = Paint.Style.STROKE
        cut.strokeWidth = 0.55f
        canvas.drawPath(Humpback.mouth, cut)
        cut.strokeWidth = 0.45f
        canvas.drawPath(Humpback.pleats, cut)
        cut.style = Paint.Style.FILL
        canvas.drawCircle(Humpback.EYE_X, Humpback.EYE_Y, 0.6f, cut)
        canvas.restoreToCount(layer)

        // The notes, each rising in its turn.
        val alpha = ink.alpha
        for (n in NOTES) {
            var dx = 0f
            var dy = 0f
            val a: Float
            val u = (((t - n.delay) % PERIOD + PERIOD) % PERIOD) / PERIOD
            when {
                u < 0.55f -> { dy = 3f; a = 0f }
                u < 0.9f -> {
                    val k = ease((u - 0.55f) / 0.35f)
                    dx = lerp(0f, 3f, k)
                    dy = lerp(3f, -4f, k)
                    a = ease((u - 0.55f) / 0.1f)
                }
                else -> {
                    val k = ease((u - 0.9f) / 0.1f)
                    dx = lerp(3f, 4f, k)
                    dy = lerp(-4f, -6f, k)
                    a = 1f - k
                }
            }
            if (a <= 0f) continue
            ink.alpha = (alpha * a).roundToInt()
            canvas.save()
            canvas.translate(n.x, n.y)
            canvas.scale(n.scale, n.scale)
            canvas.translate(dx, dy)
            canvas.drawPath(note, ink)
            canvas.restore()
        }
        ink.alpha = alpha
        canvas.restore()

        postInvalidateOnAnimation()
    }

    private class Note(val x: Float, val y: Float, val scale: Float, val delay: Float)

    private companion object {
        // The drawing's frame, in the humpback's own units.
        const val LEFT = -37f
        const val TOP = -17f
        const val WIDTH = 72f
        const val HEIGHT = 32f
        /** Height on screen. */
        const val SIZE_DP = 56f
        const val BOB = 1.2f
        const val PERIOD = 7f

        val NOTES =
            listOf(
                Note(24f, -4f, 0.8f, 0f),
                Note(28.5f, -8.5f, 0.66f, 0.7f),
                Note(32f, -12.5f, 0.52f, 1.4f),
            )

        /** An eighth note, its head's centre at the origin. */
        val note: Path by lazy {
            val head = Path().apply { addOval(RectF(-1.75f, -1.25f, 1.75f, 1.25f), Path.Direction.CW) }
            head.transform(Matrix().apply { setRotate(-22f) })
            Path().apply {
                addPath(head)
                addRect(1.05f, -7.2f, 1.75f, -0.2f, Path.Direction.CW)
                addPath(
                    Humpback.svgPath(
                        "M1.05,-7.2 L1.75,-7.2 C2,-5.4 4.4,-5.2 3.6,-2.2 C3.5,-3.8 2.6,-4.6 1.75,-4.9 Z",
                    ),
                )
            }
        }

        /** 0 → 1 → 0 over one cycle, eased like the mockup's keyframes. */
        fun wave(cycles: Float): Float {
            val f = cycles - kotlin.math.floor(cycles)
            return if (f < 0.5f) ease(f * 2f) else ease(2f - f * 2f)
        }
    }
}
