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
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** What a routine can ask of the bar it is drawn on, this frame. */
internal interface Stage {
    /** The water's surface (its y) at [x], in the routine's own units. */
    fun surface(x: Float): Float
}

/**
 * One sighting's routine, ported from its mockup in art/ (whale-surface-mockup.html and the rest)
 * with the values tuned there. Each works in its mockup's own coordinates: dp, the sea running from
 * x = 8 to x = 142, the calm water at y = [water]. The stage scales and places that on the bar.
 * Every position is a function of time, so nothing accumulates frame to frame.
 */
internal abstract class Routine {
    abstract val water: Float
    abstract val duration: Float
    val sea = Sea()

    /** Called once a frame before anything else, with the routine's clock. */
    open fun prepare(t: Float) {}

    /** The slow mound of water the whales push up, at [x]. */
    open fun mound(x: Float): Float = 0f

    /** 0 to 1: a smooth, calm patch at [x] that damps the ripples (a fluke print). */
    open fun calm(x: Float): Float = 0f

    /** Drawn behind the whales (unclipped by the water). */
    open fun behind(c: Canvas, t: Float, stage: Stage, ink: Ink, paint: Paint) {}

    /** The whales; the stage has clipped the canvas to above the water. */
    abstract fun whales(c: Canvas, t: Float, stage: Stage, ink: Ink, paint: Paint)

    /** Drawn over the sea: spray, spouts, foam. */
    open fun front(c: Canvas, t: Float, stage: Stage, ink: Ink, paint: Paint) {}

    companion object {
        const val LEFT = 8f
        const val SEA_END = 142f
        /** The whales' scale: 1.5× a real humpback's proportions against the bar, as tuned, then
         * a quarter bigger again (WhaleSong 1.5). */
        const val SIZE = 1.875f
        /** "Out of the water" 1.65×: how high a resting whale floats. */
        const val TOP = 1.6f - (1.65f - 1f) * 3.2f
        const val DEEP = 20f
        const val TAIL_PIVOT = -35f
        const val HEAD_PIVOT = 20f
    }
}

// --- Surface and blow ----------------------------------------------------------------------------

/**
 * The whale comes up head first, nose raised along its climb, and blows a single bushy spout; it
 * rests and bobs, swimming slowly on, then sinks away nose down, tipping about its fluke so the tail
 * never lifts out, with a long gentle swell.
 */
internal class SurfaceRoutine : Routine() {
    override val water = 28f
    override val duration = 13f

    private class State {
        var x = 0f
        var depth = 0f
        var angle = 0f
        var dive = 0f
        var out = 0f
        var stir = 0f
    }

    private fun swimX(t: Float) = 54f + 3.2f * t

    private fun depthAt(t: Float, withBob: Boolean = true): Float {
        val up = smoother((t - 0.5f) / 2.0f)
        val down = smoother((t - 6.3f) / 5.6f).pow(2.4f)
        val env = smoother((t - 2.2f) / 1.2f) * (1f - smoother((t - 5.3f) / 0.8f))
        val bob = 0.45f * Sea.cycle(t - 2.2f, 1.6f) * env
        return lerp(DEEP, TOP, up * (1f - down)) + if (withBob) bob else 0f
    }

    private fun stateAt(t: Float, s: State): State {
        s.x = swimX(t)
        s.depth = depthAt(t)
        val vy = (depthAt(t + 0.02f, false) - depthAt(t - 0.02f, false)) / 0.04f
        val bob = { u: Float -> depthAt(u) - depthAt(u, false) }
        val vb = (bob(t + 0.02f) - bob(t - 0.02f)) / 0.04f
        val climb = if (t < 5f) 0.42f * atan2(vy, 3.2f + 12f) * DEG else 0f
        s.dive = 10f * smoother((t - 6.4f) / 4.0f)
        s.angle = -3f + climb + 0.15f * atan2(vb, 12f) * DEG
        s.out = 1f - smoother((s.depth - 2f) / (DEEP - 2f))
        s.stir = smoother((t - 0.3f) / 1.2f) * (1f - smoother((t - 7.5f) / 3.0f))
        return s
    }

    private fun moundOf(x: Float, s: State): Float {
        val d = x - (s.x + 6f * SIZE)
        return (s.stir * 0.9f + s.out * 1.4f) * 0.5f * exp(-(d * d) / 160f)
    }

    private fun matrixOf(s: State, chain: Chain): Chain =
        chain.reset()
            .translate(s.x, water - moundOf(s.x, s) + s.depth)
            .rotateAbout(s.dive, TAIL_PIVOT * SIZE, 0f)
            .rotate(s.angle)
            .rotateAbout(-3f, HEAD_PIVOT * SIZE, 0f)
            .scale(SIZE)

    private val now = State()
    private val chain = Chain()
    private val blewAt: FloatArray
    private val pt = FloatArray(2)

    init {
        for ((t, dx, a, sigma) in
            listOf(
                floatArrayOf(0.7f, 6f, 2.2f, 6f), // the water stirs
                floatArrayOf(1.45f, 16f, 5.0f, 5f), // head breaks the surface
                floatArrayOf(2.2f, 10f, 1.0f, 3f), // the blow
                floatArrayOf(3.6f, 2f, 1.0f, 5f), floatArrayOf(5.2f, 2f, 1.0f, 5f), // bobbing
                floatArrayOf(7.0f, 0f, 1.8f, 8f), // sinks back: a long, gentle swell
            )) {
            sea.add(t, swimX(t) + dx * SIZE, a, sigma)
        }
        blewAt = matrixOf(stateAt(BLOW_AT, State()), Chain()).map(Humpback.BLOWHOLE[0], Humpback.BLOWHOLE[1])
    }

    override fun prepare(t: Float) {
        stateAt(t, now)
    }

    override fun mound(x: Float) = moundOf(x, now)

    override fun whales(c: Canvas, t: Float, stage: Stage, ink: Ink, paint: Paint) {
        if (now.out > 0.001f) Draw.sideWhale(c, matrixOf(now, chain).m, SIZE, ink.track, ink, paint)
    }

    override fun front(c: Canvas, t: Float, stage: Stage, ink: Ink, paint: Paint) {
        val a = t - BLOW_AT
        if (a < 0f || a > 3.2f) return
        matrixOf(now, chain).map(Humpback.BLOWHOLE[0], Humpback.BLOWHOLE[1], pt)
        Draw.spout(c, a, pt[0], min(pt[1], stage.surface(pt[0])), blewAt[0], blewAt[1], 1f, SIZE, ink, paint)
    }

    private companion object {
        const val BLOW_AT = 2.2f
    }
}

// --- Cruise with calf ----------------------------------------------------------------------------

/**
 * A mother and her calf come up together and swim along. The calf, paler and riding high, keeps
 * station at her head; it bobs faster, breathes more often, and dips under and back up while she
 * keeps a steady course. Both leave a gentle wake, then sink away together, the calf a beat behind.
 */
internal class CruiseRoutine : Routine() {
    override val water = 28f
    override val duration = 17f

    private class Whale(
        val scale: Float,
        val offset: Float,
        val trim: Float,
        val ride: Float,
        val calf: Boolean,
        val up: Float,
        val upDur: Float,
        val sink: Float,
        val sinkDur: Float,
        val bobAmp: Float,
        val bobPeriod: Float,
        val bobPhase: Float,
        val dips: List<FloatArray>,
        val blows: FloatArray,
        val blowScale: Float,
    )

    private class State {
        var x = 0f
        var depth = 0f
        var angle = 0f
        var dive = 0f
        var out = 0f
        var stir = 0f
    }

    private val mother =
        Whale(SIZE * BIG, 0f, -3f, 0f, false, 0.5f, 2.0f, 10.4f, 5.6f, 0.45f, 1.8f, 0f, emptyList(),
            floatArrayOf(2.9f), 1f)
    private val calf =
        Whale(SIZE * BIG * 0.55f, 0.6f * 22f * SIZE * BIG, -5.5f, 0.8f, true, 1.0f, 1.8f, 11.0f, 5.0f, 0.4f,
            1.25f, 0.7f, listOf(floatArrayOf(5.4f, 2.8f)), floatArrayOf(2.2f, 8.3f), 0.55f)
    private val whales = listOf(mother, calf)

    private fun swimX(t: Float) = 36f + 3.2f * t

    private companion object {
        /** The mother and calf are drawn this much larger than the other sightings' whale. */
        const val BIG = 1.28f
        /** And float this much higher (dp at the mother's size), so more of them shows. */
        const val LIFT = 1.5f
        /** The size their wakes, dips, lift and blows are measured against: the sightings' size
         * before WhaleSong 1.5 made the others bigger, so the pair stays as approved. */
        const val REF = 1.5f
    }

    /** part: 0 all, 1 the path alone, 2 the bob alone. */
    private fun depthOf(w: Whale, t: Float, part: Int = 0): Float {
        val k = w.scale / REF
        val top = TOP * k - w.ride - LIFT * k
        val up = smoother((t - w.up) / w.upDur)
        val down = smoother((t - w.sink) / w.sinkDur).pow(2.4f)
        val d = lerp(DEEP, top, up * (1f - down))
        var dip = 0f
        var quiet = 1f
        for (dp in w.dips) {
            dip += 7f * k * bump((t - dp[0]) / dp[1])
            quiet *= 1f - bump((t - dp[0] + 0.3f) / (dp[1] + 0.6f))
        }
        val env = smoother((t - w.up - w.upDur) / 1.2f) * (1f - smoother((t - w.sink + 1.0f) / 0.8f))
        val bob = w.bobAmp * k * Sea.cycle(t - w.bobPhase, w.bobPeriod) * env * quiet
        return when (part) {
            1 -> d + dip
            2 -> bob
            else -> d + dip + bob
        }
    }

    private fun stateOf(w: Whale, t: Float, s: State): State {
        s.x = swimX(t) + w.offset
        s.depth = depthOf(w, t)
        val vy = (depthOf(w, t + 0.02f, 1) - depthOf(w, t - 0.02f, 1)) / 0.04f
        val vb = (depthOf(w, t + 0.02f, 2) - depthOf(w, t - 0.02f, 2)) / 0.04f
        // Pitch follows the path; nose-down pitch turns about the fluke tip, so no tail flips up.
        val climb = if (t < w.sink - 0.2f) 0.42f * atan2(vy, 3.2f + 12f) * DEG else 0f
        val noseDown = climb * smoother(climb / 9f)
        s.dive = 10f * smoother((t - w.sink - 0.1f) / 4.0f) + noseDown
        s.angle = -3f + (climb - noseDown) + 0.15f * atan2(vb, 12f) * DEG
        val k = w.scale / REF
        s.out = 1f - smoother((s.depth - 2f * k) / (DEEP - 2f * k))
        s.stir = smoother((t - w.up + 0.2f) / 1.2f) * (1f - smoother((t - w.sink - 1.2f) / 3.0f))
        return s
    }

    private fun moundsOf(x: Float, ss: List<State>): Float {
        var h = 0f
        for (i in whales.indices) {
            val w = whales[i]
            val s = ss[i]
            val k = w.scale / REF
            val d = x - (s.x + 6f * w.scale)
            h += (s.stir * 0.9f + s.out * 1.4f) * k * 0.5f * exp(-(d * d) / (160f * k))
        }
        return h
    }

    private fun matrixOf(w: Whale, s: State, ss: List<State>, chain: Chain): Chain =
        chain.reset()
            .translate(s.x, water - moundsOf(s.x, ss) + s.depth)
            .rotateAbout(s.dive, TAIL_PIVOT * w.scale, 0f)
            .rotate(s.angle)
            .rotateAbout(w.trim, HEAD_PIVOT * w.scale, 0f)
            .scale(w.scale)

    private val now = listOf(State(), State())
    private val chain = Chain()
    private val pt = FloatArray(2)
    /** Where each blow happened: [whale][blow] → x, y. */
    private val blewAt: List<List<FloatArray>>

    private fun statesAt(t: Float, into: List<State> = listOf(State(), State())): List<State> {
        stateOf(mother, t, into[0])
        stateOf(calf, t, into[1])
        return into
    }

    init {
        fun add(w: Whale, t: Float, dx: Float, a: Float, sigma: Float) {
            val s = stateOf(w, t, State())
            sea.add(t, s.x + dx * w.scale, a * w.scale / REF, sigma)
        }
        add(mother, 0.7f, 6f, 2.2f, 6f) // the water stirs
        add(mother, 1.45f, 16f, 4.0f, 5f) // her head breaks the surface
        add(calf, 1.9f, 16f, 3.0f, 4f) // the calf's
        add(mother, 2.9f, 10f, 0.8f, 3f) // blows
        add(calf, 2.2f, 10f, 0.8f, 3f)
        add(calf, 7.7f, 16f, 3.2f, 4f) // the calf comes back up from its dip
        add(calf, 8.3f, 10f, 0.8f, 3f)
        add(mother, 11.0f, 0f, 1.8f, 8f) // sinking: long, gentle swells
        add(calf, 11.6f, 0f, 1.4f, 7f)
        // The wake: a small bow wave from each head as it swims, while it is at the surface.
        for (w in whales) {
            var t = 2.6f
            while (t < 10.6f) {
                val s = stateOf(w, t, State())
                if (s.out >= 0.8f) sea.add(t, s.x + 22f * w.scale, 0.55f * w.scale / REF, 3.5f)
                t += 0.85f
            }
        }
        blewAt =
            whales.mapIndexed { i, w ->
                w.blows.map { at ->
                    val ss = statesAt(at)
                    matrixOf(w, ss[i], ss, Chain()).map(Humpback.BLOWHOLE[0], Humpback.BLOWHOLE[1])
                }
            }
    }

    override fun prepare(t: Float) {
        statesAt(t, now)
    }

    override fun mound(x: Float) = moundsOf(x, now)

    override fun whales(c: Canvas, t: Float, stage: Stage, ink: Ink, paint: Paint) {
        for (i in whales.indices) { // the mother, then the calf on the near side
            val w = whales[i]
            if (now[i].out <= 0.001f) continue
            Draw.sideWhale(c, matrixOf(w, now[i], now, chain).m, w.scale,
                if (w.calf) ink.calf else ink.track, ink, paint)
        }
    }

    override fun front(c: Canvas, t: Float, stage: Stage, ink: Ink, paint: Paint) {
        for (i in whales.indices) {
            val w = whales[i]
            for (j in w.blows.indices) {
                val a = t - w.blows[j]
                if (a < 0f || a > 3.2f) continue
                matrixOf(w, now[i], now, chain).map(Humpback.BLOWHOLE[0], Humpback.BLOWHOLE[1], pt)
                val o = blewAt[i][j]
                Draw.spout(c, a, pt[0], min(pt[1], stage.surface(pt[0])), o[0], o[1], w.blowScale, REF, ink, paint)
            }
        }
    }
}

// --- Fluke up ------------------------------------------------------------------------------------

/**
 * The deep dive. After a last breath the whale arches its back high, rolls forward, and its tail
 * stock climbs out. Its own tail turns to face us and the app icon's fluke takes over, opening to
 * full spread with its pale underside; it hangs, water streaming and dripping off the serrated
 * trailing edge, then slides straight down, leaving a smooth calm patch: the fluke print.
 */
internal class FlukeRoutine : Routine() {
    override val water = 44f
    override val duration = 15f

    private class State {
        var x = 0f
        var depth = 0f
        var up = 0f
        var turn = 0f
        var rise = 0f
        var slide = 0f
        var archAngle = 0f
        var rollAngle = 0f
    }

    private fun swimX(t: Float) = 58f + 3.2f * t

    private fun stateAt(t: Float, s: State): State {
        val up = smoother((t - 0.5f) / 2.0f)
        val env = smoother((t - 2.5f) / 1.2f) * (1f - smoother((t - DIVE + 0.6f) / 0.8f))
        val bob = 0.45f * Sea.cycle(t - 2.2f, 1.6f) * env
        val arch = smoother((t - 4.4f) / 1.6f)
        val roll = smoother((t - 5.6f) / 1.8f)
        s.turn = smoother((t - 6.4f) / 1.3f)
        s.rise = smoother((t - 6.8f) / 1.2f)
        s.slide = smoother((t - 8.7f) / 1.5f).pow(1.4f)
        s.up = up
        s.depth = lerp(DEEP, TOP, up) + bob - 1.4f * arch * (1f - roll) - 13f * s.rise + (13f + 30f) * s.slide
        s.x = swimX(t) - 6f * roll
        s.archAngle = 20f * arch
        s.rollAngle = 66f * roll + 8f * s.slide
        return s
    }

    private fun moundOf(x: Float, s: State): Float {
        val d = x - (s.x + 6f * SIZE)
        return s.up * (1f - s.slide) * 1.4f * 0.5f * exp(-(d * d) / 160f)
    }

    private fun bodyMatrix(s: State, chain: Chain): Chain =
        chain.reset()
            .translate(s.x, water - moundOf(s.x, s) + s.depth)
            .rotateAbout(s.rollAngle, TAIL * SIZE, 0f)
            .rotateAbout(s.archAngle, HUMP * SIZE, 0f)
            .rotateAbout(-3f, HEAD_PIVOT * SIZE, 0f)
            .scale(SIZE)

    // The fluke: until it turns, the tail is the body's own; as it turns, the icon's fluke takes
    // over, riding on the tail stock. Only the blades turn; the stock always matches the body's.
    private val flukePaths = HashMap<Int, Path>()

    private fun spreadOf(turn: Float) = lerp(0.32f, 1f, smoother((turn - SWAP0) / (1f - SWAP0)))

    private fun widthAt(iy: Float, sx: Float) = lerp(sx, STOCK, smoother((iy - 48f) / 9f))

    private fun flukePath(sx: Float): Path {
        val key = (sx * 100f + 0.5f).toInt()
        return flukePaths.getOrPut(key) {
            val q = key / 100f
            Humpback.svgPath(Humpback.FLUKE_D) { p -> p[0] = 54f + (p[0] - 54f) * widthAt(p[1], q) }
        }
    }

    /** The fluke's own matrix: icon units, riding on the tail stock, pointing the way it points. */
    private fun flukeMatrix(s: State, body: Chain, out: Chain): Chain {
        val a = body.map(PEDUNCLE, 0f)
        val b = body.map(PEDUNCLE - 5f, 0f)
        val ang = atan2(b[1] - a[1], b[0] - a[0]) * DEG + 90f
        return out.reset().translate(a[0], a[1]).rotate(ang).scale(FLUKE_SCALE).translate(-54f, -FLUKE_ROOT)
    }

    private fun flukePoint(s: State, f: Chain, ix: Float, iy: Float, out: FloatArray): FloatArray {
        val sx = spreadOf(s.turn)
        return f.map(54f + (ix - 54f) * widthAt(iy, sx), iy, out)
    }

    private fun tailCut(s: State) = lerp(-37f, PEDUNCLE - 0.3f, smoother((s.turn - SWAP0) / (SWAP1 - SWAP0)))

    // Drips: each leaves the trailing edge at its own moment and falls; each landing ripples.
    private class Drip(val at: Float, val x: Float, val y: Float, val land: Float, val size: Float)

    private val drips = ArrayList<Drip>()
    private var printX = 0f

    private val now = State()
    private val body = Chain()
    private val fluke = Chain()
    private val pt = FloatArray(2)
    private val blewAt: FloatArray

    init {
        fun at(t: Float, dx: Float, a: Float, sigma: Float) {
            sea.add(t, stateAt(t, State()).x + dx * SIZE, a, sigma)
        }
        at(0.7f, 6f, 2.2f, 6f) // stirs
        at(1.45f, 16f, 4.0f, 5f) // head breaks the surface
        at(2.9f, 10f, 0.8f, 3f) // the breath
        at(5.0f, 2f, 2.4f, 7f) // the arch pushes the water up
        at(6.6f, -22f, 2.6f, 5f) // the stock climbs out
        at(9.6f, -24f, 1.6f, 6f) // the fluke slips under
        val r = Random(31)
        repeat(34) {
            val dAt = 7.4f + r.nextFloat() * 2.0f
            val e = Humpback.EDGE[(r.nextFloat() * Humpback.EDGE.size).toInt().coerceAtMost(Humpback.EDGE.size - 1)]
            val size = 0.35f + r.nextFloat() * 0.45f
            val s = stateAt(dAt, State())
            if (s.rise < 0.35f || s.slide > 0.6f) return@repeat // only while the fluke is up
            val bm = bodyMatrix(s, Chain())
            val p = flukePoint(s, flukeMatrix(s, bm, Chain()), e[0], e[1], FloatArray(2))
            val fall = max(0f, water - p[1])
            val land = dAt + sqrt(2f * fall / G)
            drips.add(Drip(dAt, p[0], p[1], land, size))
            sea.add(land, p[0], 0.35f * size, 1.6f)
        }
        val ps = stateAt(9.6f, State())
        printX = flukeMatrix(ps, bodyMatrix(ps, Chain()), Chain()).map(54f, FLUKE_ROOT)[0]
        blewAt = bodyMatrix(stateAt(BLOW_AT, State()), Chain()).map(Humpback.BLOWHOLE[0], Humpback.BLOWHOLE[1])
    }

    override fun prepare(t: Float) {
        stateAt(t, now)
        printNow = smoother((t - 9.4f) / 0.8f) * (1f - smoother((t - 9.4f - 3.2f) / 2.0f))
    }

    private var printNow = 0f

    override fun mound(x: Float) = moundOf(x, now)

    override fun calm(x: Float): Float {
        if (printNow <= 0f) return 0f
        val d = x - printX
        return printNow * exp(-(d * d) / 120f)
    }

    override fun whales(c: Canvas, t: Float, stage: Stage, ink: Ink, paint: Paint) {
        val s = now
        if (s.up <= 0.001f || s.slide >= 0.999f) return
        val m = bodyMatrix(s, body).m
        val cut = tailCut(s)
        val showV = s.turn >= SWAP0
        val fm = flukeMatrix(s, body, fluke).m
        val fp = flukePath(spreadOf(s.turn))
        fun inBody(block: () -> Unit) {
            c.save(); c.concat(m); c.clipRect(cut, -30f, 40f, 30f); block(); c.restore()
        }
        fun inFluke(block: () -> Unit) {
            c.save(); c.concat(fm); c.clipRect(-20f, -20f, 128f, FLUKE_ROOT + ROOT_OVERLAP); block(); c.restore()
        }
        // Two passes, so body and fluke read as one animal with one outline: every knock-out
        // outline first, then every fill on top, covering the outlines where they overlap.
        inBody { c.drawPath(Humpback.body, ink.stroke(paint, ink.bg, 1.6f / SIZE)) }
        if (showV) inFluke { c.drawPath(fp, ink.stroke(paint, ink.bg, 1.6f / FLUKE_SCALE)) }
        inBody { c.drawPath(Humpback.body, ink.fill(paint, ink.track)) }
        if (showV) inFluke { c.drawPath(fp, ink.fill(paint, ink.track)) }
        inBody {
            Draw.details(c, ink, paint, 0.38f)
            c.drawPath(Humpback.flipper, ink.fill(paint, ink.flip))
            c.drawPath(Humpback.flipper, ink.stroke(paint, ink.bg, 0.4f))
            c.drawCircle(Humpback.EYE_X, Humpback.EYE_Y, 0.5f, ink.fill(paint, ink.line))
        }
        // The pale underside, each humpback's own pattern, shows as it turns to face us.
        val pale = 0.6f * smoother((s.turn - 0.35f) / 0.45f)
        if (showV && pale > 0.01f) {
            val sx = spreadOf(s.turn)
            inFluke {
                c.clipPath(fp)
                oval(c, 54f - 13f * sx, 41f, 10f * sx, 4.2f, 0.42f, ink.fill(paint, ink.flip, pale))
                oval(c, 54f + 13f * sx, 41f, 10f * sx, 4.2f, -0.42f, paint)
                ink.fill(paint, ink.track, pale * 0.8f)
                oval(c, 54f - 16f * sx, 39.5f, 2.2f * sx, 1.2f, 0.4f, paint)
                oval(c, 54f + 17f * sx, 40.5f, 1.6f * sx, 1.0f, -0.4f, paint)
            }
        }
    }

    override fun front(c: Canvas, t: Float, stage: Stage, ink: Ink, paint: Paint) {
        val s = now
        // Water streaming off the trailing edge as it first lifts clear.
        val stream = smoother((s.rise - 0.2f) / 0.3f) * (1f - smoother((s.rise - 0.85f) / 0.15f)) * (1f - s.slide)
        if (stream > 0.01f && s.up > 0.001f) {
            bodyMatrix(s, body)
            flukeMatrix(s, body, fluke)
            ink.stroke(paint, ink.spray, 0.5f, 0.55f * stream)
            for (i in Humpback.EDGE.indices) {
                val p = flukePoint(s, fluke, Humpback.EDGE[i][0], Humpback.EDGE[i][1], pt)
                val len = (2.5f + (i * 7 % 5)) * stream
                c.drawLine(p[0], p[1] + 0.4f, p[0] + 0.2f, p[1] + 0.4f + len, paint)
            }
        }
        // Drips in flight.
        ink.fill(paint, ink.spray, 0.85f)
        for (d in drips) {
            if (t < d.at || t > d.land) continue
            val a = t - d.at
            val y = d.y + 0.5f * G * a * a
            val rx = d.size * 0.7f
            val ry = d.size * (1f + a * 1.2f)
            c.drawOval(d.x - rx, y - ry, d.x + rx, y + ry, paint)
        }
        // The last breath before the dive.
        val a = t - BLOW_AT
        if (a in 0f..3.2f) {
            bodyMatrix(s, body).map(Humpback.BLOWHOLE[0], Humpback.BLOWHOLE[1], pt)
            Draw.spout(c, a, pt[0], min(pt[1], stage.surface(pt[0])), blewAt[0], blewAt[1], 1f, SIZE, ink, paint)
        }
    }

    private companion object {
        const val DIVE = 4.4f
        const val BLOW_AT = 2.9f
        const val HUMP = -10f
        const val TAIL = -25f
        const val PEDUNCLE = -25f
        const val FLUKE_ROOT = 62f
        const val ROOT_OVERLAP = 3f
        const val FLUKE_SCALE = 0.61f
        const val SWAP0 = 0.22f
        const val SWAP1 = 0.4f
        /** The icon fluke's stock width at the root against the body's tail stock depth. */
        const val STOCK = 1.9f * SIZE / FLUKE_SCALE / 5.5f
        const val G = 70f
    }
}

private val ovalRect = RectF()

/** An ellipse centred on ([cx], [cy]), turned by [rad] radians. */
internal fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, rad: Float, paint: Paint) {
    c.save()
    c.rotate(rad * DEG, cx, cy)
    ovalRect.set(cx - rx, cy - ry, cx + rx, cy + ry)
    c.drawOval(ovalRect, paint)
    c.restore()
}

// --- Breach --------------------------------------------------------------------------------------

/**
 * The breach, modelled on a 3D breaching animation: the whale races in just below the surface,
 * tail beating hard, its bow wave humping the water; bursts out steep and nose first, still
 * travelling forward; rolls over onto its back in the air, flippers and tail rolling with it (the
 * fluke flashes fully spread for a moment); and crashes down on its back well ahead of where it came
 * out, in a crown of spray, a tower of mist and a slick of foam, sending the biggest ripples of any
 * sighting along the bar.
 */
internal class BreachRoutine : Routine() {
    override val water = 88f

    // The flight: a parabola for its middle, launched from deep so it crosses the surface at speed.
    private val g = 48f
    private val depth = 34f
    private val v0 = sqrt(2f * g * (depth + 13f * 1.65f))
    private val start = 1.6f
    private val atTop = start + v0 / g
    private val cross = start + (v0 - sqrt(v0 * v0 - 2f * g * depth)) / g
    private val land = start + (v0 + sqrt(v0 * v0 - 2f * g * depth)) / g
    private val vx = 32f
    private val x0 = 44f - vx * (cross - start)
    override val duration = land + 5.5f

    private class State {
        var cx = 0f
        var cy = 0f
        var angle = 0f
        var bend = 0f
        var roll = 0f
        var spread = 0f
        var fling = 0f
        var tuck = 0f
        var out = 0f
    }

    private fun stateAt(t: Float, s: State): State {
        val a = max(0f, t - start)
        s.cy = water + depth - v0 * a + 0.5f * g * a * a
        if (t > land) { // under again: the water slows it hard
            val u = t - land
            val vLand = v0 - g * (land - start)
            s.cy = water - vLand * 0.9f * (1f - exp(-u * 1.2f)) / 1.2f + 10f * u * u
        }
        val flown = min(a, land - start)
        val after = max(0f, t - land)
        s.cx = x0 + vx * flown + vx * (1f - exp(-after * 1.8f)) / 1.8f
        val over = smoother((t - (cross - 0.1f)) / (land - cross + 0.25f))
        s.angle = lerp(-66f, 14f, over) + 6f * smoother((t - land) / 0.8f)
        // Hard, quick tail beats on the run-up, one big kick out of the water, a curl coming down.
        val beats = 12f * Sea.cycle(t - start, 0.42f) * smoother((t - start) / 0.3f) *
            (1f - smoother((t - cross + 0.25f) / 0.3f))
        val kick = 18f * bump((t - (cross - 0.3f)) / 0.8f)
        val curl = -16f * smoother((t - atTop) / (land - atTop + 0.3f))
        s.bend = beats + kick + curl
        s.roll = smoother((t - (cross + 0.05f)) / (land - cross - 0.05f))
        s.fling = smoother((t - (cross - 0.05f)) / 0.5f)
        s.tuck = smoother((t - land + 0.15f) / 0.5f)
        // The tail rolls too: seen flat, fully spread, only for the moment it is a quarter round.
        s.spread = sin(PI.toFloat() * s.roll).pow(3)
        s.out = smoother((t - cross) / 0.4f)
        return s
    }

    private fun bodyMatrix(s: State, chain: Chain) = chain.reset().translate(s.cx, s.cy).rotate(s.angle).scale(SIZE)

    // Spray, mist and foam, all fixed when the sighting starts.
    private class Drop(val at: Float, val x: Float, val vx: Float, val vy: Float, val size: Float) {
        val land = at + 2f * vy / G_DROP
    }

    private class Mist(val at: Float, val x: Float, val h: Float, val r: Float, val drift: Float)

    private val drops = ArrayList<Drop>()
    private val mists = ArrayList<Mist>()
    private val foamX1: Float
    private val foamX2: Float
    private val exitX: Float

    init {
        val r = Random(41)
        exitX = stateAt(cross, State()).cx + 8f
        repeat(46) { // the skirt of spray thrown up as it bursts out
            val side = if (r.nextFloat() < 0.5f) -1f else 1f
            drops.add(Drop(cross + r.nextFloat() * 0.45f, exitX + side * (2f + r.nextFloat() * 9f),
                side * (4f + r.nextFloat() * 14f), 30f + r.nextFloat() * 30f, 0.35f + r.nextFloat() * 0.7f))
        }
        val lb = bodyMatrix(stateAt(land, State()), Chain())
        val n = lb.map(25f, 0f)[0]
        val tl = lb.map(-33f, 0f)[0]
        val x1 = min(n, tl)
        val x2 = max(n, tl)
        val mid = (x1 + x2) / 2f
        repeat(110) { // the crown of spray along the length of the body as it slaps down
            val x = lerp(x1, x2, r.nextFloat())
            val out = if (x >= mid) 1f else -1f
            drops.add(Drop(land - 0.05f + r.nextFloat() * 0.35f, x,
                out * (6f + r.nextFloat() * 30f) + (r.nextFloat() - 0.5f) * 8f,
                25f + r.nextFloat() * 55f, 0.4f + r.nextFloat() * 1.0f))
        }
        repeat(16) { // a tower of mist that billows up and drifts
            mists.add(Mist(land + r.nextFloat() * 0.4f, lerp(x1, x2, 0.15f + 0.7f * r.nextFloat()),
                10f + r.nextFloat() * 22f, 2f + r.nextFloat() * 3.5f, (r.nextFloat() - 0.3f) * 4f))
        }
        foamX1 = x1 - 4f
        foamX2 = x2 + 4f
        // Each beat of its tail on the run-up shoves the water.
        var tb = start + 0.25f
        while (tb < cross - 0.1f) {
            sea.add(tb, stateAt(tb, State()).cx - 26f, 1.1f, 4f)
            tb += 0.42f
        }
        sea.add(cross, exitX, 4.5f, 6f) // bursts out
        sea.add(land, (foamX1 + foamX2) / 2f, 8f, 12f) // the slap down: the big one
        sea.add(land + 0.12f, foamX1 + 6f, 3f, 7f)
        sea.add(land + 0.12f, foamX2 - 6f, 3f, 7f)
        drops.forEachIndexed { i, d -> if (i % 3 == 0) sea.add(d.land, d.x + d.vx * (d.land - d.at), 0.25f * d.size, 2f) }
    }

    private val now = State()
    private val runUp = State()
    private val chain = Chain()
    private val tail = Chain()

    override fun prepare(t: Float) {
        stateAt(t, now)
        stateAt(min(t, cross), runUp)
        moundK = smoother((t - start) / (cross - start)) * (1f - smoother((t - cross) / 0.35f))
    }

    private var moundK = 0f

    // The water humps up over the whale as it races in (a travelling bow wave), then collapses.
    override fun mound(x: Float): Float {
        if (moundK <= 0f) return 0f
        val d = x - (runUp.cx + 14f)
        return 4f * 1.65f * moundK * exp(-(d * d) / 200f)
    }

    // The flat fluke at a given spread, laid along the body axis pointing back from the peduncle.
    private val flatFlukes = HashMap<Int, Path>()

    private fun flatFluke(spread: Float): Path {
        val key = (spread * 100f + 0.5f).toInt()
        return flatFlukes.getOrPut(key) {
            val sx = key / 100f
            val stock = 1.9f / (5.5f * FLUKE_K)
            Humpback.svgPath(Humpback.FLUKE_D) { p ->
                val w = lerp(sx, stock, smoother((p[1] - 48f) / 9f))
                val ix = p[0]
                p[0] = PED_X + (p[1] - FLUKE_ROOT) * FLUKE_K
                p[1] = (ix - 54f) * w * FLUKE_K
            }
        }
    }

    /**
     * Each flipper is a flat blade fixed to the side of a round body, rolling with it: seen from the
     * side, a point at angle a round the body (0 straight down) shows at height cos(a) and faces us
     * by sin(a). So its root travels round the body, its blade foreshortens and turns edge-on, and
     * it passes behind the body once its side has turned away.
     */
    private class FlipperView {
        var rootY = 0f
        var scaleY = 0f
        var depth = 0f
        var splay = 0f
    }

    private val views = arrayOf(FlipperView(), FlipperView())

    private fun flipperView(s: State, side: Float, v: FlipperView) {
        val phi = 180f * s.roll
        val hang = lerp(18f + 62f * s.fling, 90f, s.tuck)
        val rootA = (side * ROOT_A + phi) / DEG
        val bladeA = (side * hang + phi) / DEG
        v.rootY = ROOT_R * cos(rootA)
        v.scaleY = cos(bladeA)
        v.depth = ROOT_R * sin(rootA) + 9f * sin(bladeA)
        v.splay = -34f * s.fling
    }

    override fun behind(c: Canvas, t: Float, stage: Stage, ink: Ink, paint: Paint) {
        // Mist behind the whale: soft-edged puffs that billow up, spread and thin out.
        val clear = ink.spray and 0x00ffffff
        for (m in mists) {
            val a = t - m.at
            if (a <= 0f || a > 3.2f) continue
            val e = ease(a / 0.6f)
            val alpha = 0.32f * e * (1f - ease((a - 0.6f) / 2.4f))
            if (alpha < 0.005f) continue
            val x = m.x + m.drift * a
            val y = water - m.h * e + a * 2f
            val r = m.r * (0.9f + a * 1.1f)
            ink.fill(paint, Color.WHITE, alpha)
            paint.shader = RadialGradient(x, y, r, ink.spray, clear, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, paint)
            paint.shader = null
        }
    }

    override fun whales(c: Canvas, t: Float, stage: Stage, ink: Ink, paint: Paint) {
        val s = now
        val m = bodyMatrix(s, chain).m
        val tm = Matrix(m).apply { preRotate(s.bend, BEND_AT, 0f) }
        val body = Humpback.rolledBody(s.roll)
        val flipSide = 1f - 2f * s.roll
        fun inM(mm: Matrix, x0: Float, x1: Float, block: () -> Unit) {
            c.save(); c.concat(mm); c.clipRect(x0, -40f, x1, 40f); block(); c.restore()
        }
        flipperView(s, 1f, views[0])
        flipperView(s, -1f, views[1])
        fun flipper(v: FlipperView) {
            if (abs(v.scaleY) < 0.02f) return
            c.save()
            c.concat(m)
            c.translate(Humpback.FLIP_ROOT_X, v.rootY)
            c.scale(1f, v.scaleY)
            c.rotate(v.splay)
            c.translate(-Humpback.FLIP_ROOT_X, -Humpback.FLIP_ROOT_Y)
            val k = smoother((v.depth + 3f) / 6f) // white facing us, darker as it turns away
            c.drawPath(Humpback.flipper, ink.stroke(paint, ink.bg, (0.5f + 0.7f * (1f - k)) / SIZE))
            c.drawPath(Humpback.flipper, ink.fill(paint, Ink.mix(ink.far, ink.flip, k)))
            c.restore()
        }
        for (v in views) if (v.depth < 0f) flipper(v) // the side turned away: behind the body
        // As the fluke turns flat, the body's own edge-on tail is trimmed back and it takes over.
        val flat = s.spread >= FLASH_FROM
        val cut = lerp(-40f, PED_X - 0.3f, smoother((s.spread - FLASH_FROM) / (FLASH_FULL - FLASH_FROM)))
        val fluke = if (flat) flatFluke(max(0.32f, s.spread)) else null
        val flukeClip = PED_X + 3f * FLUKE_K
        // Body (in two pieces, so the tail can flex) and fluke, in two passes: outlines, then fills.
        val w = 1.6f / SIZE
        inM(m, BEND_AT - 1.5f, 30f) { c.drawPath(body, ink.stroke(paint, ink.bg, w)) }
        inM(tm, cut, BEND_AT + 1.5f) { c.drawPath(body, ink.stroke(paint, ink.bg, w)) }
        if (fluke != null) inM(tm, -60f, flukeClip) { c.drawPath(fluke, ink.stroke(paint, ink.bg, w)) }
        inM(m, BEND_AT - 1.5f, 30f) { c.drawPath(body, ink.fill(paint, ink.track)) }
        inM(tm, cut, BEND_AT + 1.5f) { c.drawPath(body, ink.fill(paint, ink.track)) }
        if (fluke != null) inM(tm, -60f, flukeClip) { c.drawPath(fluke, ink.fill(paint, ink.track)) }
        // Throat grooves, mouth and eye ride round with the roll, fading as they turn edge-on.
        val face = min(1f, abs(flipSide) * 1.6f)
        if (face > 0.02f) {
            inM(m, BEND_AT, 30f) {
                c.scale(1f, flipSide)
                Draw.details(c, ink, paint, 0.45f, face)
                c.drawCircle(Humpback.EYE_X, Humpback.EYE_Y, 0.5f, ink.fill(paint, ink.line, face))
            }
        }
        // Halfway over, the whale's pale belly is turned to us.
        val belly = 1f - abs(flipSide)
        if (belly > 0.05f) {
            inM(m, -30f, 30f) {
                c.clipPath(body)
                oval(c, 4f, 0f, 17f, 3.2f, 0f, ink.fill(paint, ink.flip, 0.45f * belly))
            }
        }
        val front = views.filter { it.depth >= 0f }.sortedBy { it.depth }
        for (v in front) flipper(v)
    }

    private val foamPath = Path()

    override fun front(c: Canvas, t: Float, stage: Stage, ink: Ink, paint: Paint) {
        // Foam: a white slick on the water where it landed, spreading and fading.
        if (t > land) {
            val a = t - land
            val k = ease(a / 0.3f) * (1f - ease((a - 1.2f) / 2.8f))
            if (k > 0.01f) {
                val spread = 3f * a
                val x1 = foamX1 - spread
                val x2 = foamX2 + spread
                foamPath.reset()
                var x = x1
                foamPath.moveTo(x, stage.surface(x) - 0.2f)
                while (x <= x2) {
                    foamPath.lineTo(x, stage.surface(x) - 0.2f)
                    x += 0.5f
                }
                x = x2
                while (x >= x1) {
                    val edge = min(1f, min((x - x1) / 6f, (x2 - x) / 6f))
                    foamPath.lineTo(x, stage.surface(x) + 1.4f * edge * (0.7f + 0.3f * sin(x * 1.7f)))
                    x -= 0.5f
                }
                foamPath.close()
                c.drawPath(foamPath, ink.fill(paint, ink.foam, 0.75f * k))
            }
        }
        // Spray in flight.
        ink.fill(paint, ink.spray, 0.9f)
        for (d in drops) {
            if (t < d.at || t > d.land) continue
            val a = t - d.at
            val x = d.x + d.vx * a
            val y = water - d.vy * a + 0.5f * G_DROP * a * a
            val rx = d.size * 0.75f
            val ry = d.size * (0.9f + abs(d.vy - G_DROP * a) / 90f)
            c.drawOval(x - rx, y - ry, x + rx, y + ry, paint)
        }
    }

    private companion object {
        const val G_DROP = 55f
        const val BEND_AT = -15f
        const val PED_X = -25f
        const val FLUKE_K = 0.36f
        const val FLUKE_ROOT = 62f
        const val FLASH_FROM = 0.32f
        const val FLASH_FULL = 0.45f
        const val ROOT_A = 35f
        val ROOT_R = Humpback.FLIP_ROOT_Y / cos(ROOT_A / DEG)
    }
}
