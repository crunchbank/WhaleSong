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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

// Easing, all smooth enough that nothing starts or stops with a jolt.
internal fun ease(v: Float): Float {
    val c = v.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/** Smootherstep: flat to the second derivative at both ends. */
internal fun smoother(v: Float): Float {
    val c = v.coerceIn(0f, 1f)
    return c * c * c * (c * (c * 6f - 15f) + 10f)
}

internal fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

/** 0 → 1 → 0 over [0, 1], smooth to the second derivative. */
internal fun bump(v: Float): Float =
    when {
        v <= 0f || v >= 1f -> 0f
        v < 0.5f -> smoother(2f * v)
        else -> smoother(2f - 2f * v)
    }

internal const val DEG = (180.0 / PI).toFloat()

/**
 * The ripples on the sea. Every disturbance (a whale breaking the surface, a blow, a drop of spray
 * landing) is a hump of water released at a time and place; how it then spreads is the
 * Cauchy-Poisson problem for deep water: a sum of waves, each travelling at its own speed
 * (omega = sqrt(g k)), so the hump turns into a train with long waves running ahead and short ones
 * trailing, each crest growing and fading as it passes through the group.
 *
 * Positions and heights are in a sighting's own dp; the stage scales them to the bar.
 */
internal class Sea {
    class Ripple(val t: Float, val x: Float, val amp: Float, sigma: Float) {
        val weights = hump(sigma)
        val coef = FloatArray(N)
        var live = false
        var reach = 0f
    }

    val ripples = ArrayList<Ripple>()

    fun add(t: Float, x: Float, amp: Float, sigma: Float) {
        ripples.add(Ripple(t, x, amp, sigma))
    }

    /** Works out each ripple's wave amplitudes for time [t]; call once a frame before [height]. */
    fun prepare(t: Float) {
        for (r in ripples) {
            val age = t - r.t
            r.live = age > 0f && age < LIFE
            if (!r.live) continue
            // The hump is pushed up over a quarter second, not conjured in one frame.
            val scale = r.amp * smoother(age / 0.25f) * exp(-0.12f * age)
            for (i in 0 until N) {
                r.coef[i] =
                    scale * r.weights[i] * cos(OMEGA[i] * age) * exp(-NU * K[i] * K[i] * age)
            }
            r.reach = 30f + 60f * age
        }
    }

    /** How far the water stands above calm at [x], from the ripples alone. */
    fun height(x: Float): Float {
        var h = 0f
        for (r in ripples) {
            if (!r.live) continue
            val d = abs(x - r.x)
            if (d > r.reach) continue // not reached yet
            // cos(k r) for evenly spaced k, by rotating one step at a time: no trig per term.
            var c = cos(K0 * d)
            var s = sin(K0 * d)
            val cd = cos(DK * d)
            val sd = sin(DK * d)
            var sum = 0f
            val coef = r.coef
            for (i in 0 until N) {
                sum += coef[i] * c
                val nc = c * cd - s * sd
                s = s * cd + c * sd
                c = nc
            }
            h += sum
        }
        return h
    }

    companion object {
        const val N = 56
        const val K0 = 0.03f
        const val K1 = 2.0f
        val DK = (K1 - K0) / (N - 1)
        val K = FloatArray(N) { K0 + DK * it }
        /** Ripple speed: the tuned 0.75× of the mockups. */
        const val G = 220f * 0.75f * 0.75f
        const val NU = 0.5f
        const val LIFE = 12f
        val OMEGA = FloatArray(N) { sqrt(G * K[it]) }

        private val humps = HashMap<Float, FloatArray>()

        /** Spectral weights of a gaussian hump of width [sigma], normalised to 1 at its peak. */
        fun hump(sigma: Float): FloatArray =
            humps.getOrPut(sigma) {
                val w = FloatArray(N) { exp(-(K[it] * sigma) * (K[it] * sigma) / 4f) }
                val total = w.sum()
                for (i in w.indices) w[i] /= total
                w
            }

        /** One full cycle of [period] seconds as an angle. */
        fun cycle(t: Float, period: Float) = sin(t * 2f * PI.toFloat() / period)
    }
}
