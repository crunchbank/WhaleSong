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

package org.oxycblt.auxio.whale.loudness

import org.oxycblt.musikr.Song

/**
 * Whether one song carries straight on from another: the next track of the same album, or the
 * first track of its next disc. Streaming mode levels a run of such songs as one album, so the
 * album's own rises and falls (and its gapless joins) stay as they are.
 */
object AlbumContinuity {
    fun follows(previous: Song?, next: Song?): Boolean {
        if (previous == null || next == null || previous.uid == next.uid) return false
        if (previous.album.uid != next.album.uid) return false
        val previousTrack = previous.track ?: return false
        val nextTrack = next.track ?: return false
        val previousDisc = previous.disc?.number ?: 1
        val nextDisc = next.disc?.number ?: 1
        return (nextDisc == previousDisc && nextTrack == previousTrack + 1) ||
            (nextDisc == previousDisc + 1 && nextTrack == 1 && previousTrack > 0)
    }
}
