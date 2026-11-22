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
import android.graphics.PathMeasure
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The humpback, drawn side-on, facing +x, in body units (about 60 nose to fluke tip): a flat
 * knobbly head with the splashguard before the blowhole, a long low back, the small fin on its hump,
 * a knuckled tail stock and an edge-on fluke; the pleated throat; the long white flipper. Also the
 * app icon's fluke, for when the tail turns to face us. Shapes come from art/whale-sighting-mockup.py.
 */
internal object Humpback {
    const val BODY_D =
        "M25.2,0.5 Q25.2,-0.9 23.6,-1.4 Q22.9,-1.6 22.2,-1.8 Q21.23,-1.62 20.45,-2.23 " +
            "Q19.48,-2.05 18.7,-2.65 Q17.73,-2.47 16.95,-3.08 Q15.98,-2.9 15.2,-3.5 " +
            "Q13.6,-4.7 11.6,-4.6 C6,-5.6 -1,-6.4 -6,-6.3 Q-8.6,-6.3 -10,-7.0 " +
            "Q-11.2,-8.4 -12.4,-8.3 Q-12.7,-7.4 -13.8,-6.4 Q-14.38,-5.88 -15.14,-5.76 " +
            "Q-15.72,-5.24 -16.48,-5.12 Q-17.06,-4.6 -17.82,-4.48 Q-18.4,-3.96 -19.16,-3.84 " +
            "Q-19.74,-3.32 -20.5,-3.2 C-22.2,-2.4 -23.6,-1.5 -25,-1.0 C-28.5,-1.3 -32,-0.9 -35.2,0.9 " +
            "Q-31.5,1.3 -25,0.9 C-18,2.0 -12,3.6 -6,5.0 C0,6.7 6.5,7.0 10.5,6.0 " +
            "C15,4.9 20.5,3.2 23.6,2.2 Q25.6,1.6 25.2,0.5 Z"
    const val FLIPPER_D =
        "M13.6,2.6 C11,5.6 6.5,9.2 0.8,11.6 Q-0.42,11.58 -1.33,12.4 Q-2.56,12.38 -3.47,13.2 " +
            "Q-4.69,13.18 -5.6,14 Q-6.6,14.3 -5.6,13.0 C-2,10.2 2.5,7.6 8.2,4.2 Z"
    const val MOUTH_D = "M25.1,0.9 Q19,1.4 12.8,2.6"
    const val PLEATS_D = "M22.4,2.2 Q11,6.4 -4,5.0 M20.6,3.0 Q10,6.8 -1.5,5.7 M18.6,3.8 Q9,7.1 1.5,6.3"
    /** The app icon's fluke seen from behind, in icon units: stock root at y 62, notch at 54,44.2. */
    const val FLUKE_D =
        "M48.9,84 C49.94,73 51.22,62.5 51.56,56.2 C47.5,53.6 40.31,50.9 35.67,46.6 " +
            "C31.73,42.9 29.64,37.6 29.52,30.2 Q31.96,33.6 34.98,35 Q36.54,35.02 37.57,36.03 " +
            "Q39.13,36.05 40.16,37.07 Q41.72,37.08 42.75,38.1 Q44.31,38.12 45.34,39.13 " +
            "Q46.9,39.15 47.93,40.17 Q49.49,40.18 50.52,41.2 C52.14,41.9 53.3,43 54,44.2 " +
            "C54.7,43 55.86,41.9 57.48,41.2 Q58.51,40.18 60.07,40.17 Q61.1,39.15 62.66,39.13 " +
            "Q63.69,38.12 65.25,38.1 Q66.28,37.08 67.84,37.07 Q68.87,36.05 70.43,36.03 " +
            "Q71.46,35.02 73.02,35 Q76.04,33.6 78.48,30.2 C78.36,37.6 76.27,42.9 72.33,46.6 " +
            "C67.69,50.9 60.5,53.6 56.44,56.2 C56.78,62.5 58.06,73 59.1,84 Z"
    /** The fluke's serrated trailing edge, both blades, icon units: where water drips from. */
    val EDGE: List<FloatArray> =
        listOf(
                34.98f to 35f, 37.57f to 36.03f, 40.16f to 37.07f, 42.75f to 38.1f,
                45.34f to 39.13f, 47.93f to 40.17f, 50.52f to 41.2f,
            )
            .flatMap { (x, y) -> listOf(floatArrayOf(x, y), floatArrayOf(108f - x, y)) }

    val body: Path by lazy { svgPath(BODY_D) }
    val flipper: Path by lazy { svgPath(FLIPPER_D) }
    val mouth: Path by lazy { svgPath(MOUTH_D) }
    val pleats: Path by lazy { svgPath(PLEATS_D) }

    val BLOWHOLE = floatArrayOf(10.4f, -4.9f)
    const val EYE_X = 13.6f
    const val EYE_Y = 1.3f
    const val FLIP_ROOT_X = 12.4f
    const val FLIP_ROOT_Y = 3.2f

    private val NUMBER = Regex("[MCQLZ]|-?\\d*\\.?\\d+")

    /**
     * Parses the absolute M, L, Q, C and Z commands these shapes use, optionally moving every point
     * through [map] (which rewrites its two-element argument in place).
     */
    fun svgPath(d: String, map: ((FloatArray) -> Unit)? = null): Path {
        val path = Path()
        val tokens = NUMBER.findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = 'M'
        val p = FloatArray(6)
        val pt = FloatArray(2)
        fun read(n: Int) {
            for (j in 0 until n / 2) {
                pt[0] = tokens[i++].toFloat()
                pt[1] = tokens[i++].toFloat()
                map?.invoke(pt)
                p[j * 2] = pt[0]
                p[j * 2 + 1] = pt[1]
            }
        }
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok[0].isLetter()) {
                cmd = tok[0]
                i++
                if (cmd == 'Z') {
                    path.close()
                    continue
                }
            }
            when (cmd) {
                'M' -> { read(2); path.moveTo(p[0], p[1]); cmd = 'L' }
                'L' -> { read(2); path.lineTo(p[0], p[1]) }
                'Q' -> { read(4); path.quadTo(p[0], p[1], p[2], p[3]) }
                'C' -> { read(6); path.cubicTo(p[0], p[1], p[2], p[3], p[4], p[5]) }
                else -> i++
            }
        }
        return path
    }

    // --- The roll -------------------------------------------------------------------------------
    // A round body looks much the same whichever way up it is: what changes is which side the
    // throat bulge, the fin and the flippers are on. So the outline is sampled once as a back line
    // and a belly line, and the roll slides each towards the mirror of the other: halfway over the
    // outline is symmetrical (the whale seen square from below); on its back, the throat bulge is on
    // top and the fin underneath.

    private var rollXs = FloatArray(0)
    private var back = FloatArray(0)
    private var belly = FloatArray(0)
    private val rollPaths = arrayOfNulls<Path>(61)

    private fun sampleBody() {
        if (rollXs.isNotEmpty()) return
        val measure = PathMeasure(body, false)
        val length = measure.length
        val n = 600
        val pts = Array(n + 1) { FloatArray(2) }
        for (i in 0..n) measure.getPosTan(length * i / n, pts[i], null)
        var tip = 0
        for (i in pts.indices) if (pts[i][0] < pts[tip][0]) tip = i
        val backLine = pts.slice(0..tip)
        val bellyLine = pts.slice(tip until pts.size)
        fun at(line: List<FloatArray>, x: Float): Float? {
            for (i in 0 until line.size - 1) {
                val (x1, y1) = line[i].let { it[0] to it[1] }
                val (x2, y2) = line[i + 1].let { it[0] to it[1] }
                if ((x - x1) * (x - x2) <= 0f && x1 != x2) return y1 + (y2 - y1) * (x - x1) / (x2 - x1)
            }
            return null
        }
        val count = ((25.2f + 35.2f) / 0.25f).toInt() + 1
        rollXs = FloatArray(count) { 25.2f - it * 0.25f }
        back = FloatArray(count)
        belly = FloatArray(count)
        for (i in 0 until count) {
            val b = at(backLine, rollXs[i])
            val l = at(bellyLine, rollXs[i])
            back[i] = b ?: l ?: 0.5f
            belly[i] = l ?: back[i]
        }
    }

    /** The outline [roll] of the way over onto its back (0 upright, 1 on its back). */
    fun rolledBody(roll: Float): Path {
        val key = (roll.coerceIn(0f, 1f) * 60f + 0.5f).toInt()
        rollPaths[key]?.let { return it }
        sampleBody()
        val k = key / 60f
        val p = Path()
        p.moveTo(rollXs[0], lerp(back[0], -belly[0], k))
        for (i in 1 until rollXs.size) p.lineTo(rollXs[i], lerp(back[i], -belly[i], k))
        for (i in rollXs.size - 1 downTo 0) p.lineTo(rollXs[i], lerp(belly[i], -back[i], k))
        p.close()
        rollPaths[key] = p
        return p
    }
}

/** The colours of a sighting, all taken from the seek bar's colour and the panel's background. */
internal class Ink {
    var track = 0
    var bg = 0
    var flip = 0
    var far = 0
    var calf = 0
    var line = 0
    var spray = 0
    var foam = 0
    /** The whole sighting's opacity (the panel's fade, and the quick fade when it ends early). */
    var alpha = 1f

    fun update(track: Int, bg: Int) {
        if (track == this.track && bg == this.bg) return
        this.track = track
        this.bg = bg
        val dark = luminance(bg) < 0.5f
        flip = mix(track, Color.WHITE, 0.75f)
        far = mix(track, Color.BLACK, 0.22f)
        calf = mix(track, Color.WHITE, if (dark) 0.6f else 0.4f)
        line = if (dark) mix(track, Color.BLACK, 0.85f) else bg
        spray = mix(track, Color.WHITE, if (dark) 0.6f else 0.3f)
        foam = mix(track, Color.WHITE, if (dark) 0.85f else 0.55f)
    }

    /** Sets [paint] to fill with [colour] at [opacity] times the sighting's own. */
    fun fill(paint: Paint, colour: Int, opacity: Float = 1f): Paint {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = colour
        paint.alpha = (255 * opacity * alpha).toInt().coerceIn(0, 255)
        return paint
    }

    fun stroke(paint: Paint, colour: Int, width: Float, opacity: Float = 1f): Paint {
        fill(paint, colour, opacity)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        return paint
    }

    companion object {
        fun mix(a: Int, b: Int, k: Float): Int =
            Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
            )

        fun luminance(c: Int) =
            (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
    }
}

/** Drawing that more than one routine shares. */
internal object Draw {
    /** A side-on whale: knock-out outline, body, mouth, throat grooves, white flipper, eye. */
    fun sideWhale(c: Canvas, m: Matrix, scale: Float, colour: Int, ink: Ink, paint: Paint) {
        c.save()
        c.concat(m)
        c.drawPath(Humpback.body, ink.stroke(paint, ink.bg, 1.6f / scale))
        c.drawPath(Humpback.body, ink.fill(paint, colour))
        details(c, ink, paint, 0.38f)
        c.drawPath(Humpback.flipper, ink.fill(paint, ink.flip))
        c.drawPath(Humpback.flipper, ink.stroke(paint, ink.bg, 0.4f))
        c.drawCircle(Humpback.EYE_X, Humpback.EYE_Y, 0.5f, ink.fill(paint, ink.line))
        c.restore()
    }

    fun details(c: Canvas, ink: Ink, paint: Paint, pleats: Float, face: Float = 1f) {
        c.drawPath(Humpback.mouth, ink.stroke(paint, ink.line, 0.45f, 0.7f * face))
        c.drawPath(Humpback.pleats, ink.stroke(paint, ink.line, 0.45f, pleats * face))
    }

    // A blow: every particle a pure function of its age, so it never needs state.
    private class Drop(val delay: Float, val v: Float, val spread: Float, val size: Float, val life: Float)

    private class Mist(val delay: Float, val h: Float, val dx: Float, val r: Float)

    private val drops =
        Random(7).let { r ->
            List(46) {
                Drop(r.nextFloat() * 0.25f, 0.75f + r.nextFloat() * 0.35f, (r.nextFloat() - 0.5f) * 2f,
                    0.45f + r.nextFloat() * 0.75f, 1.3f + r.nextFloat() * 0.8f)
            }
        }
    private val mists =
        Random(19).let { r ->
            List(9) {
                Mist(0.15f + r.nextFloat() * 0.35f, 0.75f + r.nextFloat() * 0.3f,
                    (r.nextFloat() - 0.5f) * 3f, 1.4f + r.nextFloat() * 1.4f)
            }
        }
    private val column = Path()

    /**
     * A humpback's blow, [a] seconds old: one bushy column. ([bx], [by]) is the blowhole now, so the
     * column stays joined to it; ([ox], [oy]) is where it blew, where the spray hangs and drifts as
     * the whale swims on. [k] scales it (a calf's is small); [size] is the whale's scale.
     */
    fun spout(c: Canvas, a: Float, bx: Float, by: Float, ox: Float, oy: Float, k: Float, size: Float, ink: Ink, paint: Paint) {
        if (a < 0f || a > 3.2f) return
        val top = 15f * k * size / 1.25f
        val g = 34f
        val v0 = sqrt(2f * g * top)
        val wind = 3.5f
        val ta = minOf(a, v0 / g)
        val rise = minOf(top * 0.9f, v0 * ta - 0.5f * g * ta * ta)
        val colAlpha = 0.75f * (1f - ease((a - 0.3f) / 0.6f))
        if (colAlpha > 0.01f && rise > 0.5f) {
            val lean = wind * a * a
            val r = 1.6f * sqrt(k)
            column.reset()
            column.moveTo(bx - 0.5f, by)
            column.quadTo(ox - 0.9f + lean * 0.3f, oy - rise * 0.5f, ox - r + lean, oy - rise)
            column.arcTo(ox + lean - r, oy - rise - r, ox + lean + r, oy - rise + r, 180f, 180f, false)
            column.quadTo(ox + 0.9f + lean * 0.3f, oy - rise * 0.5f, bx + 0.5f, by)
            column.close()
            c.drawPath(column, ink.fill(paint, ink.spray, colAlpha))
        }
        for (m in mists) {
            val age = a - m.delay
            if (age <= 0f) continue
            val e = ease(age / 0.5f)
            val alpha = 0.32f * e * (1f - ease((age - 0.5f) / 1.8f))
            if (alpha <= 0.005f) continue
            c.drawCircle(ox + m.dx * k * (1f + age) + wind * age * age, oy - top * m.h * e + age * 1.2f,
                m.r * sqrt(k) * (0.6f + age * 1.1f), ink.fill(paint, ink.spray, alpha))
        }
        for (d in drops) {
            val age = a - d.delay
            if (age <= 0f || age > d.life) continue
            val y = v0 * d.v * age - 0.5f * g * age * age
            if (y < -1f) continue
            val x = d.spread * k * (0.6f + 3.2f * age) + wind * age * age
            c.drawCircle(ox + x, oy - y, d.size * sqrt(k) * (1f - 0.3f * age / d.life),
                ink.fill(paint, ink.spray, 0.85f * (1f - ease((age - d.life * 0.5f) / (d.life * 0.5f)))))
        }
    }
}

/** A matrix built like a CSS transform chain: each step applies to everything after it. */
internal class Chain {
    val m = Matrix()
    fun reset() = apply { m.reset() }
    fun translate(x: Float, y: Float) = apply { m.preTranslate(x, y) }
    fun rotate(deg: Float) = apply { m.preRotate(deg) }
    fun scale(sx: Float, sy: Float = sx) = apply { m.preScale(sx, sy) }
    /** Rotates by [deg] about ([x], [y]). */
    fun rotateAbout(deg: Float, x: Float, y: Float) = apply { m.preRotate(deg, x, y) }

    private val pt = FloatArray(2)
    fun map(x: Float, y: Float, out: FloatArray = FloatArray(2)): FloatArray {
        pt[0] = x
        pt[1] = y
        m.mapPoints(pt)
        out[0] = pt[0]
        out[1] = pt[1]
        return out
    }
}
