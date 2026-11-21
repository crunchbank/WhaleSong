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

package org.oxycblt.auxio.whale.sightings

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import com.google.android.material.slider.Slider
import com.google.android.material.slider.WavySlider
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Now and then, a humpback whale surfaces in the played part of the Now Playing seek bar: the
 * wavy line that is the song so far. The water swells, the whale's back rises and cruises, it
 * blows a spout, then arches and dives, lifting its fluke clear of the water. Sometimes (about
 * one sighting in eight) it breaches first, leaping clear and landing with a splash; about one
 * in four, a calf swims behind it.
 *
 * The first sighting of a song comes once a sixth of it has played, checked every second, then
 * one every 20 to 60 seconds. A new song starts the count over. Everything is drawn in the
 * window's overlay (the slider's own bounds are too short for a leap), so no layout changes;
 * the progress thumb is left clear. Frames come from [Choreographer], not an animator, so it
 * also runs when the system animation scale is off. Between sightings nothing runs but one
 * delayed callback.
 */
class WhaleSightings(
    private val slider: Slider,
    /** True while a sighting may start: playing, past the first-sighting point, not seeking. */
    private val canStart: () -> Boolean,
    /** True while a running sighting may carry on; when it turns false the whale dives. */
    private val canContinue: () -> Boolean,
    /** Identifies the current song, so each song gets its own first sighting. */
    private val songKey: () -> Any?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val drawable = WhaleDrawable()
    private var sim: Sim? = null
    private var sightings = 0
    private var resumed = false
    private var lastSightingSong: Any? = NO_SONG
    private var lastFrameNanos = 0L

    private val frame =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val s = sim ?: return
                val dt =
                    if (lastFrameNanos == 0L) 0f
                    else min((frameTimeNanos - lastFrameNanos) / 1_000_000_000f, 0.05f)
                lastFrameNanos = frameTimeNanos
                if (!canContinue() || opacity() < 0.5f) s.dive(QUICK_DIVE)
                s.sea = seaWidthDp()
                if (!s.update(dt)) {
                    stopSighting()
                    return
                }
                slider.rootView.invalidate()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

    private val scheduled = Runnable {
        if (!resumed || sim != null) return@Runnable
        if (canStart() && opacity() > 0.99f && seaWidthDp() >= MIN_SEA_DP) {
            startSighting()
            if (sim != null) return@Runnable // stopSighting() schedules the next one
        }
        schedule()
    }

    fun resume() {
        if (resumed) return
        resumed = true
        schedule()
    }

    /** Stops everything at once (screen left, or sightings turned off). */
    fun pause() {
        resumed = false
        handler.removeCallbacks(scheduled)
        stopSighting()
    }

    /** Ends a running sighting early with a quick dive (seek, pause, song change). */
    fun dive() {
        sim?.dive(QUICK_DIVE)
    }

    /**
     * Until this song's first sighting, checks every second so the whale arrives right at the
     * first-sighting point; after that, waits 20–60 seconds.
     */
    private fun schedule() {
        handler.removeCallbacks(scheduled)
        if (!resumed) return
        val delay =
            if (songKey() == lastSightingSong) Random.nextLong(MIN_GAP_MS, MAX_GAP_MS + 1)
            else POLL_MS
        handler.postDelayed(scheduled, delay)
    }

    private fun startSighting() {
        val host = slider.rootView as? ViewGroup ?: return
        sightings++
        lastSightingSong = songKey()
        // The first sighting is always a plain one, so it shows the whole routine.
        val breach = sightings > 1 && Random.nextInt(8) == 0
        val calf = sightings > 1 && Random.nextInt(4) == 0
        sim = Sim(seaWidthDp(), breach, calf)
        drawable.setBounds(0, 0, host.width, host.height)
        host.overlay.add(drawable)
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stopSighting() {
        val wasRunning = sim != null
        Choreographer.getInstance().removeFrameCallback(frame)
        sim = null
        (slider.rootView as? ViewGroup)?.let {
            it.overlay.remove(drawable)
            it.invalidate()
        }
        if (wasRunning) schedule()
    }

    // --- Geometry, read from the slider every frame so it follows layout and theme changes ---

    private val density
        get() = slider.resources.displayMetrics.density

    private val sliderLoc = IntArray(2)
    private val hostLoc = IntArray(2)

    private fun locateSlider() {
        slider.getLocationInWindow(sliderLoc)
        slider.rootView.getLocationInWindow(hostLoc)
    }

    private fun sliderX() = (sliderLoc[0] - hostLoc[0]).toFloat()

    private fun sliderY() = (sliderLoc[1] - hostLoc[1]).toFloat()

    private fun fraction(): Float {
        val span = slider.valueTo - slider.valueFrom
        return if (span > 0f) ((slider.value - slider.valueFrom) / span).coerceIn(0f, 1f) else 0f
    }

    /** The played part of the line, where the whale swims, in dp. */
    private fun seaWidthDp() = slider.trackWidth * fraction() / density - THUMB_CLEARANCE_DP

    /** How visible the seek bar is: the Now Playing panel fades as it collapses. */
    private fun opacity(): Float {
        if (!slider.isShown) return 0f
        var a = 1f
        var v: View? = slider
        while (v != null) {
            a *= v.alpha
            v = v.parent as? View
        }
        return a
    }

    /** The whale's routine, in dp along the played line and seconds. No drawing, no Android. */
    private class Sim(var sea: Float, val breach: Boolean, val calf: Boolean) {
        var t = 0f
        var clock = 0f
        var dir = if (Random.nextBoolean()) 1f else -1f
        var face = dir
        var x = (sea * (0.3f + Random.nextFloat() * 0.4f)).coerceIn(EDGE, max(EDGE, sea - EDGE))
        var speed = 0f

        /** When the back starts to rise; a breach comes first. */
        val surfaceAt = if (breach) 3.0f else 1.0f
        val breachAt = 1.0f
        var spoutAt = surfaceAt + 1.1f
        var diveAt = spoutAt + 2.4f + Random.nextFloat() * 1.6f
        var diveDur = 2.4f
        var end = diveAt + diveDur + 1.2f

        val diving
            get() = t >= diveAt

        fun dive(quick: Float) {
            if (diving) return
            diveAt = t
            diveDur = if (t < surfaceAt) quick else max(quick, 1.6f)
            end = diveAt + diveDur + 0.8f
        }

        /** Advances by [dt] seconds; false once the sighting is over. */
        fun update(dt: Float): Boolean {
            t += dt
            clock += dt
            if (t > end) return false
            val lo = EDGE
            val hi = max(EDGE, sea - EDGE)
            val cruising = t > surfaceAt + 0.4f && !diving
            speed += ((if (cruising) CRUISE else if (diving) 3f else 0f) - speed) * min(1f, dt * 1.5f)
            if (breach && t in breachAt..breachAt + BREACH_DUR) {
                x += dir * 7f * dt
            } else {
                x += dir * speed * dt
            }
            if (x > hi && dir > 0) dir = -1f
            if (x < lo && dir < 0) dir = 1f
            x = x.coerceIn(lo - 4f, hi + 4f)
            face += (dir - face) * min(1f, dt * 3f)
            return true
        }

        /** 0 = under water, 1 = back fully surfaced. */
        fun rise(delay: Float = 0f): Float {
            val up = ease((t - surfaceAt - delay) / 0.8f)
            val down = ease((t - diveAt - delay) / 0.7f)
            return min(up, 1f - down)
        }

        /** 0..1 progress of the breach leap, or null outside it. */
        fun leap(): Float? =
            if (breach && t in breachAt..breachAt + BREACH_DUR) (t - breachAt) / BREACH_DUR
            else null

        /** Seconds since the breach landed, or null. */
        fun sinceSplash(): Float? =
            if (breach && t >= breachAt + BREACH_DUR) t - breachAt - BREACH_DUR else null

        /** 0..1 height of the raised fluke during the dive. */
        fun fluke(delay: Float = 0f): Float {
            if (!diving || t < surfaceAt + 0.8f) return 0f
            val p = (t - diveAt - 0.35f - delay) / (diveDur - 0.35f)
            return if (p <= 0f || p >= 1f) 0f else sin(PI.toFloat() * p).let { it * it }
        }

        /** Seconds since the spout began, or null if it hasn't or the whale is diving. */
        fun spout(delay: Float = 0f): Float? {
            if (diveAt < spoutAt + delay) return null // dived before it could blow
            val a = t - spoutAt - delay
            return if (a < 0f || a > SPOUT_DUR) null else a
        }

        /** How far the water stands above the line at [px] dp. */
        fun lift(px: Float): Float {
            val d = px - x
            // The sea stirs around the whale: a swell that builds, then settles.
            val a = 2.2f * min(ease(t / 1.0f), 1f - ease((t - (end - 1.2f)) / 1.2f))
            var h = a * (0.6f + 0.25f * sin(px * 0.25f - clock * 2.4f) +
                0.15f * sin(px * 0.47f + clock * 1.3f)) * exp(-(d * d) / 700f)
            h += (rise() * 2.2f + fluke() * 1.5f) * exp(-(d * d) / 260f)
            if (calf) {
                val dc = px - calfX()
                h += rise(CALF_DELAY) * 1.2f * exp(-(dc * dc) / 90f)
            }
            sinceSplash()?.let { s ->
                if (s < 1.2f) h += 5f * (1f - s / 1.2f) * exp(-(d * d) / (120f + s * 600f))
            }
            leap()?.let { p -> if (p < 0.2f) h += 3f * (1f - p / 0.2f) * exp(-(d * d) / 150f) }
            // Calm at the line's ends.
            return max(0f, h) * ease(px / 6f) * ease((sea + EDGE - px) / 6f)
        }

        fun calfX() = x - face * 17f

        companion object {
            const val EDGE = 14f
            const val CRUISE = 5f
            const val BREACH_DUR = 1.6f
            const val SPOUT_DUR = 1.6f
            const val CALF_DELAY = 0.4f

            fun ease(v: Float): Float {
                val c = v.coerceIn(0f, 1f)
                return c * c * (3f - 2f * c)
            }
        }
    }

    /** Draws the current [sim] over the slider. Reuses its paints and paths. */
    private inner class WhaleDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val clip = Path()

        override fun draw(canvas: Canvas) {
            val s = sim ?: return
            val d = density
            locateSlider()
            val left = sliderX() + slider.trackSidePadding
            val right = sliderX() + slider.width - slider.trackSidePadding
            val cy = sliderY() + slider.height / 2f
            // The wave oscillates about the line's centre; the whale's water stands on its crests.
            val waterline =
                cy - slider.trackHeight / 2f -
                    slider.resources.getDimension(org.oxycblt.auxio.R.dimen.progress_amplitude)
            val thumbX = left + (right - left) * fraction()
            val color =
                (slider as? WavySlider)?.waveColor
                    ?: slider.trackActiveTintList.getColorForState(slider.drawableState, 0)
            val alpha = (opacity() * 255).toInt().coerceIn(0, 255)
            val toPx = { dp: Float -> left + dp * d }
            val surface = { px: Float -> waterline - s.lift((px - left) / d) * d }
            paint.color = color
            paint.alpha = alpha

            canvas.save()
            // Keep the thumb clear: everything passes behind it, and nothing beyond it.
            val gap = slider.thumbWidth / 2f + slider.thumbTrackGapSize + d
            canvas.clipRect(left - 8 * d, 0f, thumbX - gap, canvas.height.toFloat())

            // Above-water clip: the whale shows only where it is above the rippling surface.
            clip.reset()
            clip.moveTo(left - 8 * d, 0f)
            var px = left - 8 * d
            while (px <= thumbX) {
                clip.lineTo(px, surface(px) + 0.5f * d)
                px += d
            }
            clip.lineTo(thumbX, 0f)
            clip.close()

            canvas.save()
            canvas.clipPath(clip)
            val leap = s.leap()
            if (leap != null) {
                drawBreach(canvas, s, leap, toPx, surface, d)
            } else {
                val r = s.rise()
                if (r > 0.01f) drawBack(canvas, toPx(s.x), surface(toPx(s.x)), r, s.face, WHALE, s, d)
                val f = s.fluke()
                if (f > 0.01f) drawFluke(canvas, toPx(s.x - s.face * 6f), surface(toPx(s.x)), f, WHALE, d)
                if (s.calf) {
                    val cx = toPx(s.calfX())
                    val cr = s.rise(Sim.CALF_DELAY)
                    if (cr > 0.01f) drawBack(canvas, cx, surface(cx), cr, s.face, WHALE * 0.55f, s, d)
                    val cf = s.fluke(Sim.CALF_DELAY)
                    if (cf > 0.01f) drawFluke(canvas, cx - s.face * 3f * d, surface(cx), cf, WHALE * 0.55f, d)
                }
            }
            canvas.restore()

            // Spouts rise from the blowhole as a puff of spray.
            s.spout()?.let { a ->
                val bx = toPx(s.x + s.face * 8.5f)
                drawSpout(canvas, bx, surface(bx) - 5.5f * d * WHALE * s.rise(), a, WHALE, alpha, d)
            }
            if (s.calf) {
                s.spout(CALF_SPOUT_DELAY)?.let { a ->
                    val bx = toPx(s.calfX() + s.face * 4f)
                    drawSpout(canvas, bx, surface(bx) - 3f * d * WHALE * s.rise(Sim.CALF_DELAY), a, WHALE * 0.55f, alpha, d)
                }
            }
            s.sinceSplash()?.let { a -> if (a < 1.0f) drawSplash(canvas, toPx(s.x), surface(toPx(s.x)), a, alpha, d) }

            // The swell: a mound of water on the line where the whale is, in the line's colour.
            // Elsewhere the slider's own wave is the sea.
            paint.alpha = alpha
            path.reset()
            var open = false
            px = left
            while (px <= thumbX) {
                val lift = s.lift((px - left) / d)
                if (lift > 0.05f) {
                    if (!open) {
                        path.moveTo(px, cy)
                        open = true
                    }
                    // Taper into the wave where the swell is low, so it has no hard edges.
                    path.lineTo(px, cy - (cy - surface(px)) * Sim.ease(lift / 1.5f))
                } else if (open) {
                    path.lineTo(px, cy)
                    path.close()
                    open = false
                }
                px += 1.5f * d
            }
            if (open) {
                path.lineTo(thumbX, cy)
                path.close()
            }
            canvas.drawPath(path, paint)
            canvas.restore()
        }

        /**
         * A humpback's back, facing [face]: long and low, the small dorsal fin on its hump, the
         * knobbly head. [rise] lifts it out of the water.
         */
        private fun drawBack(
            canvas: Canvas,
            x: Float,
            y: Float,
            rise: Float,
            face: Float,
            size: Float,
            s: Sim,
            d: Float,
        ) {
            canvas.save()
            val bob = sin(s.clock * 2.2f) * 0.5f
            // Arching before the dive: the back rolls up, then away.
            val arch = if (s.diving) 1.6f * sin(PI.toFloat() * ((s.t - s.diveAt) / 0.7f).coerceIn(0f, 1f)) else 0f
            canvas.translate(x, y + ((1f - rise) * 11f + 1f + bob - arch) * d * size)
            canvas.scale(face * d * size, d * size)
            path.reset()
            path.moveTo(-22f, 6f)
            path.quadTo(-21f, -1f, -13f, -3.2f)
            path.quadTo(-8f, -4.2f, -6f, -5f)
            // The small, hooked dorsal fin on the hump.
            path.quadTo(-4.6f, -7.6f, -2.6f, -7.2f)
            path.quadTo(-3.6f, -6.2f, -2.4f, -5.4f)
            path.quadTo(5f, -6.2f, 11f, -5f)
            path.quadTo(17f, -3.6f, 20f, -0.5f)
            path.quadTo(20.5f, 4f, 14f, 6f)
            path.close()
            canvas.drawPath(path, paint)
            // Tubercles: the knobs on a humpback's head.
            canvas.drawCircle(13.5f, -4.4f, 0.9f, paint)
            canvas.drawCircle(16.6f, -3.0f, 0.8f, paint)
            canvas.restore()
        }

        /** The tail fluke, seen from behind as the whale dives; [h] raises it, 0 to 1. */
        private fun drawFluke(canvas: Canvas, x: Float, y: Float, h: Float, size: Float, d: Float) {
            canvas.save()
            canvas.translate(x, y + (1f - h) * 13f * d * size)
            canvas.scale(d * size, d * size)
            path.reset()
            path.moveTo(-1.6f, 4f)
            path.lineTo(-1.2f, -6f)
            path.cubicTo(-4f, -6.6f, -8.5f, -8f, -11.5f, -10.8f)
            path.cubicTo(-8.2f, -11.6f, -4.2f, -11.2f, -0.8f, -9.2f)
            path.lineTo(0f, -8.4f)
            path.lineTo(0.8f, -9.2f)
            path.cubicTo(4.2f, -11.2f, 8.2f, -11.6f, 11.5f, -10.8f)
            path.cubicTo(8.5f, -8f, 4f, -6.6f, 1.2f, -6f)
            path.lineTo(1.6f, 4f)
            path.close()
            canvas.drawPath(path, paint)
            canvas.restore()
        }

        /** The whole whale in the air, arcing nose-up then nose-down, its long flippers out. */
        private fun drawBreach(
            canvas: Canvas,
            s: Sim,
            p: Float,
            toPx: (Float) -> Float,
            surface: (Float) -> Float,
            d: Float,
        ) {
            val x = toPx(s.x)
            val height = sin(PI.toFloat() * p) * 15f
            val angle = (55f - 110f * p) * s.face
            canvas.save()
            canvas.translate(x, surface(x) + (8f - height) * d)
            canvas.rotate(-angle)
            canvas.scale(s.face * d * WHALE, d * WHALE)
            path.reset()
            path.moveTo(19f, 0f)
            path.quadTo(14f, -4.2f, 6f, -4.6f)
            path.quadTo(-4f, -4.8f, -12f, -2.6f)
            path.quadTo(-16f, -1.4f, -18.5f, -0.6f)
            path.lineTo(-23f, -4f)
            path.quadTo(-21.5f, -0.4f, -21f, 0f)
            path.quadTo(-21.5f, 0.4f, -23f, 4f)
            path.lineTo(-18.5f, 0.6f)
            path.quadTo(-10f, 4.2f, 2f, 4.6f)
            path.quadTo(13f, 4.2f, 19f, 0f)
            path.close()
            canvas.drawPath(path, paint)
            // The long pectoral flipper that makes a humpback a humpback.
            path.reset()
            path.moveTo(7f, 2.5f)
            path.quadTo(3f, 9f, -6f, 12.5f)
            path.quadTo(-1.5f, 7f, 3.5f, 2.2f)
            path.close()
            canvas.drawPath(path, paint)
            canvas.drawCircle(15.5f, -2.6f, 0.9f, paint)
            canvas.restore()
        }

        /** A spout [a] seconds old: a column of spray that billows, drifts and fades. */
        private fun drawSpout(
            canvas: Canvas,
            x: Float,
            y: Float,
            a: Float,
            size: Float,
            alpha: Int,
            d: Float,
        ) {
            val k = a / Sim.SPOUT_DUR
            val height = 15f * size * Sim.ease(a / 0.45f)
            val fade = 1f - Sim.ease((k - 0.35f) / 0.65f)
            paint.alpha = (alpha * 0.75f * fade).toInt()
            for (i in 0 until SPRAY) {
                val f = i / (SPRAY - 1f)
                val spread = (f - 0.5f) * (2f + 9f * k) * size
                val up = height * (0.55f + 0.45f * sin(PI.toFloat() * (0.15f + 0.7f * f)))
                val drift = 2.5f * k * size
                canvas.drawCircle(x + (spread + drift) * d, y - up * d, (0.8f + 1.2f * k) * size * d, paint)
            }
            // The mist at the top of the blow.
            paint.alpha = (alpha * 0.35f * fade).toInt()
            canvas.drawOval(
                x + (-5f * size + 2.5f * k) * d,
                y - (height + 3f * size) * d,
                x + (5f * size + 2.5f * k + 3f * k) * d,
                y - (height - 3f * size) * d,
                paint,
            )
            paint.alpha = alpha
        }

        /** Spray thrown up where a breaching whale lands. */
        private fun drawSplash(canvas: Canvas, x: Float, y: Float, a: Float, alpha: Int, d: Float) {
            val k = a / 1.0f
            paint.alpha = (alpha * (1f - k)).toInt()
            for (i in -4..4) {
                val vx = i * 4.5f
                val vy = 16f - abs(i) * 2.2f
                val px = x + vx * k * d
                val py = y - (vy * k - 22f * k * k) * d
                canvas.drawCircle(px, py, 1.3f * d, paint)
            }
            paint.alpha = alpha
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val MIN_GAP_MS = 20_000L
        const val MAX_GAP_MS = 60_000L
        const val MIN_SEA_DP = 56f
        const val THUMB_CLEARANCE_DP = 6f
        const val QUICK_DIVE = 0.7f
        const val SPRAY = 9
        const val WHALE = 1.2f
        const val CALF_SPOUT_DELAY = 0.7f
        val NO_SONG = Any()
    }
}
