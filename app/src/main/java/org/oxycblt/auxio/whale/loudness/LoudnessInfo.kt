/*
 * Copyright (c) 2026 crunchbank (WhaleSong).
 *
 * This file is part of WhaleSong, a modified version of Auxio. It comes from Shark Music,
 * by the same author.
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

package org.oxycblt.auxio.whale.loudness

/**
 * A song's integrated loudness in LUFS and its sample peak (linear, 1.0 = full scale).
 * [peak] is null when only a tag without a peak value is known: such a song is never boosted.
 * [albumLoudness] and [albumPeak] come from album gain tags, when the file has them.
 */
data class LoudnessInfo(
    val loudness: Double,
    val peak: Double?,
    val source: Source,
    val albumLoudness: Double? = null,
    val albumPeak: Double? = null,
) {
    enum class Source { TAG, MEASURED }
}
