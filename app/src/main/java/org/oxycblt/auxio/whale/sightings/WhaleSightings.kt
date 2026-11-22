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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.slider.Slider
import com.google.android.material.slider.WavySlider
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Now and then, a humpback whale appears in the played part of the Now Playing seek bar, which
 * becomes the sea: its top edge is the water, and every disturbance sends real ripples along it.
 * Each sighting is one of four routines (see [Routine]), designed and tuned as mockups in art/:
 * - surfacing to blow, rest and sink away;
 * - a mother and calf cruising together;
 * - the deep dive, lifting the fluke (the app icon's) clear of the water;
 * - a breach, rolling onto its back in the air and crashing down.
 *
 * The first sighting of a song comes once a sixth of it has played, checked every second, then one
 * every 20 to 60 seconds. A new song starts the count over. Everything is drawn in the window's
 * overlay (the slider's own bounds are far too short for a breach), so no layout changes; the
 * progress thumb is left clear. Frames come from [Choreographer], not an animator, so it also runs
 * when the system animation scale is off. Between sightings nothing runs but one delayed callback.
 */
class WhaleSightings(
    private val slider: Slider,
    /** True while a sighting may start: playing, past the first-sighting point, not seeking. */
    private val canStart: () -> Boolean,
    /** True while a running sighting may carry on; when it turns false the sighting fades out. */
    private val canContinue: () -> Boolean,
    /** Identifies the current song, so each song gets its own first sighting. */
    private val songKey: () -> Any?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val drawable = SeaDrawable()
    private var routine: Routine? = null
    private var clock = 0f
    private var fadeFrom = -1f
    private var sightings = 0
    private var resumed = false
    private var lastSightingSong: Any? = NO_SONG
    private var nextSightingAt = 0L
    private var lastFrameNanos = 0L

    /** Where on the bar this sighting is drawn: its sea's offset along the line, and its scale. */
    private var offsetDp = 0f
    private var scale = 1f

    private val frame =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val r = routine ?: return
                val dt =
                    if (lastFrameNanos == 0L) 0f
                    else min((frameTimeNanos - lastFrameNanos) / 1_000_000_000f, 0.05f)
                lastFrameNanos = frameTimeNanos
                clock += dt
                if (fadeFrom < 0f && (!canContinue() || opacity() < 0.5f)) fadeFrom = clock
                if (clock >= r.duration || (fadeFrom >= 0f && clock - fadeFrom >= FADE)) {
                    stopSighting()
                    return
                }
                slider.rootView.invalidate()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

    private val scheduled = Runnable {
        if (!resumed || routine != null) return@Runnable
        // A new song is always due its first sighting; within a song, wait out the gap.
        val due = songKey() != lastSightingSong || SystemClock.elapsedRealtime() >= nextSightingAt
        if (due && canStart() && opacity() > 0.99f && seaWidthDp() >= MIN_SEA_DP) {
            startSighting()
            if (routine != null) return@Runnable // stopSighting() schedules the next one
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

    /** Ends a running sighting early, fading it out (seek, pause, song change). */
    fun dive() {
        if (routine != null && fadeFrom < 0f) fadeFrom = clock
    }

    /**
     * Checks once a second whether a sighting is due: a song's first comes as soon as a sixth of
     * it has played, later ones 20–60 seconds after the last ended. Checking often, rather than
     * sleeping through the gap, means a song change mid-gap still gets its first sighting on time.
     */
    private fun schedule() {
        handler.removeCallbacks(scheduled)
        if (!resumed) return
        handler.postDelayed(scheduled, POLL_MS)
    }

    private fun startSighting() {
        val host = slider.rootView as? ViewGroup ?: return
        sightings++
        lastSightingSong = songKey()
        routine = pickRoutine()
        // Each routine is designed in a sea of SEA_DP; a shorter played line scales it down to fit,
        // a longer one places it somewhere along the line.
        val sea = seaWidthDp()
        scale = min(1f, sea / SEA_DP)
        offsetDp = Random.nextFloat() * max(0f, sea - SEA_DP)
        clock = 0f
        fadeFrom = -1f
        drawable.setBounds(0, 0, host.width, host.height)
        host.overlay.add(drawable)
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /**
     * Surfacing and the deep dive are the common sightings, a mother and calf less so, a breach
     * rare (about one in nine), and never the first of a session.
     */
    private fun pickRoutine(): Routine {
        val forced = debugProp("debug.whalesong.routine")?.toIntOrNull()
        val roll =
            when (forced) {
                0 -> 0
                1 -> 3
                2 -> 6
                3 -> 8
                else -> Random.nextInt(if (sightings > 1) 9 else 8)
            }
        return when {
            roll < 3 -> SurfaceRoutine()
            roll < 6 -> FlukeRoutine()
            roll < 8 -> CruiseRoutine()
            else -> BreachRoutine()
        }
    }

    private fun stopSighting() {
        val wasRunning = routine != null
        Choreographer.getInstance().removeFrameCallback(frame)
        routine = null
        (slider.rootView as? ViewGroup)?.let {
            it.overlay.remove(drawable)
            it.invalidate()
        }
        if (wasRunning) {
            val gap = debugProp("debug.whalesong.gap")?.toLongOrNull()?.times(1000)
            nextSightingAt = SystemClock.elapsedRealtime() + (gap ?: Random.nextLong(MIN_GAP_MS, MAX_GAP_MS + 1))
            schedule()
        }
    }

    /**
     * Debug builds only: system properties for trying the routines out. `debug.whalesong.routine`
     * (0 surface, 1 fluke, 2 cruise, 3 breach) picks every sighting's routine and
     * `debug.whalesong.gap` sets the seconds between sightings, e.g.
     * `adb shell setprop debug.whalesong.routine 3`.
     */
    private fun debugProp(name: String): String? {
        if (!org.oxycblt.auxio.BuildConfig.DEBUG) return null
        return try {
            val value =
                Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java)
                    .invoke(null, name) as String
            value.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
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

    private fun fraction(): Float {
        val span = slider.valueTo - slider.valueFrom
        return if (span > 0f) ((slider.value - slider.valueFrom) / span).coerceIn(0f, 1f) else 0f
    }

    /** Where the slider ends its played line: this far before the thumb's centre, in px. */
    private fun thumbClearance() = slider.thumbTrackGapSize.toFloat()

    /** The played part of the line, where the whales swim, in dp. */
    private fun seaWidthDp() = (slider.trackWidth * fraction() - thumbClearance()) / density

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

    /**
     * The colour behind the seek bar, for the knock-out outlines that keep the whales clear of the
     * water and for the troughs of the ripples, which dip into the line.
     */
    private fun backgroundColour(): Int {
        var v: View? = slider
        while (v != null) {
            when (val bg = v.background) {
                is ColorDrawable -> if (bg.alpha == 255) return bg.color
                is MaterialShapeDrawable -> bg.fillColor?.let { return bg.resolvedTintColor }
            }
            v = v.parent as? View
        }
        return MaterialColors.getColor(slider, com.google.android.material.R.attr.colorSurface)
    }

    /**
     * Draws the running routine over the slider. The routine works in its mockup's own dp (sea from
     * x = 8, calm water at its own y); this maps that onto the bar: [offsetDp] along the played
     * line and [scale] for size.
     */
    private inner class SeaDrawable : Drawable(), Stage {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ink = Ink()
        private val seaPath = Path()
        private val above = Path()
        private var ys = FloatArray(0)
        private var count = 0
        // This frame's mapping, px.
        private var trackLeft = 0f
        private var sampleStart = 0f
        private var waterPx = 0f
        private var step = 1f
        private var unit = 1f
        private var r: Routine? = null

        /** The routine's x for a px x along the line. */
        private fun routineX(px: Float) = ((px - trackLeft) / density - offsetDp) / scale + Routine.LEFT

        override fun surface(x: Float): Float {
            val rt = r ?: return 0f
            val px = trackLeft + (offsetDp + (x - Routine.LEFT) * scale) * density
            val f = (px - sampleStart) / step
            if (count == 0 || f < 0f || f > count - 1) return rt.water
            val i = f.toInt().coerceAtMost(count - 2)
            val y = lerp(ys[i], ys[i + 1], f - i)
            return rt.water + (y - waterPx) / unit
        }

        override fun draw(canvas: Canvas) {
            val rt = routine ?: return
            r = rt
            val d = density
            locateSlider()
            val sliderX = (sliderLoc[0] - hostLoc[0]).toFloat()
            val sliderY = (sliderLoc[1] - hostLoc[1]).toFloat()
            trackLeft = sliderX + slider.trackSidePadding
            val cy = sliderY + slider.height / 2f
            val half = slider.trackHeight / 2f
            waterPx = cy - half
            val thumbX = trackLeft + slider.trackWidth * fraction()
            // Exactly the slider's own played line: from a cap's width before the track to the
            // thumb's gap.
            val seaStart = trackLeft - half
            val seaEnd = thumbX - thumbClearance()
            if (seaEnd - seaStart < 2f * half) return
            val seaDp = (seaEnd - seaStart) / d
            unit = scale * d
            step = d
            val fade = if (fadeFrom >= 0f) 1f - smoother((clock - fadeFrom) / FADE) else 1f
            ink.alpha = opacity() * fade
            if (ink.alpha <= 0.01f) return
            ink.update(
                (slider as? WavySlider)?.waveColor
                    ?: slider.trackActiveTintList.getColorForState(slider.drawableState, 0),
                backgroundColour(),
            )

            // The water, sampled every dp along the played line.
            rt.prepare(clock)
            rt.sea.prepare(clock)
            sampleStart = seaStart
            count = ((seaEnd - seaStart) / step).toInt() + 2
            if (ys.size < count) ys = FloatArray(count + 64)
            for (i in 0 until count) {
                val px = min(seaStart + i * step, seaEnd)
                val x = routineX(px)
                val calmPatch = rt.calm(x)
                var h = rt.mound(x) + rt.sea.height(x) * (1f - 0.85f * calmPatch) + 0.5f * calmPatch
                h *= scale // the routine's dp to the bar's
                h = max(-1.6f, h) // a trough can only eat into the line so far
                val xd = (px - seaStart) / d
                h *= ease(xd / 8f) * ease((seaDp - xd) / 8f) * fade // calm at the line's ends
                ys[i] = waterPx - h * d
            }

            canvas.save()
            canvas.clipRect(seaStart, 0f, seaEnd, bounds.height().toFloat())

            // The routine's own coordinates.
            val toRoutine = { c: Canvas ->
                c.translate(trackLeft + offsetDp * d, waterPx)
                c.scale(unit, unit)
                c.translate(-Routine.LEFT, -rt.water)
            }

            // Take the line's played part away: the sea is drawn in its place.
            canvas.restore()
            canvas.save()
            canvas.clipRect(seaStart - 2f, 0f, thumbX - slider.thumbWidth / 2f - 1f, bounds.height().toFloat())
            canvas.drawRect(seaStart - 2f, cy - half - 1f, seaEnd + half, cy + half + 1f, ink.fill(paint, ink.bg))
            canvas.restore()
            canvas.save()
            canvas.clipRect(seaStart, 0f, seaEnd, bounds.height().toFloat())

            canvas.save()
            toRoutine(canvas)
            rt.behind(canvas, clock, this, ink, paint)
            canvas.restore()

            // The whales, only where they are above the water.
            above.reset()
            above.moveTo(seaStart, 0f)
            for (i in 0 until count) above.lineTo(min(seaStart + i * step, seaEnd), ys[i] + 0.3f * unit)
            above.lineTo(seaEnd, 0f)
            above.close()
            canvas.save()
            canvas.clipPath(above)
            toRoutine(canvas)
            rt.whales(canvas, clock, this, ink, paint)
            canvas.restore()

            // The sea: the played line, its top edge the water.
            seaPath.reset()
            seaPath.moveTo(seaStart + half, cy + half)
            seaPath.arcTo(seaStart, cy - half, seaStart + 2f * half, cy + half, 90f, 180f, false)
            for (i in 0 until count) {
                val px = min(seaStart + i * step, seaEnd)
                if (px < seaStart + half || px > seaEnd - half) continue
                seaPath.lineTo(px, ys[i])
            }
            seaPath.lineTo(seaEnd - half, cy - half)
            seaPath.arcTo(seaEnd - 2f * half, cy - half, seaEnd, cy + half, -90f, 180f, false)
            seaPath.close()
            canvas.drawPath(seaPath, ink.fill(paint, ink.track))

            canvas.save()
            toRoutine(canvas)
            rt.front(canvas, clock, this, ink, paint)
            canvas.restore()

            canvas.restore()
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val MIN_GAP_MS = 20_000L
        const val MAX_GAP_MS = 60_000L
        /** The sea every routine was designed in, dp. */
        const val SEA_DP = Routine.SEA_END - Routine.LEFT
        /** Below this much played line the whales would be too small to read. */
        const val MIN_SEA_DP = SEA_DP * 0.55f
        /** Seconds to fade out a sighting ended early. */
        const val FADE = 0.5f
        val NO_SONG = Any()
    }
}
