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

package org.oxycblt.auxio.whale.plays

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.oxycblt.musikr.Song

/**
 * Counts plays in the playback service, so it works with the screen off. A song counts once
 * per time it starts (a repeat starts it again) after half of it has actually been heard:
 * time spent paused doesn't count, and neither does skipping ahead with a seek.
 */
class PlayCounter(private val player: Player) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var song: Song? = null
    private var heardMs = 0L
    private var playingSince = -1L
    private var counted = false
    private val check = Runnable { update() }

    fun attach() {
        player.addListener(this)
        startSong(player.currentMediaItem)
    }

    fun release() {
        player.removeListener(this)
        handler.removeCallbacks(check)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        startSong(mediaItem)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            if (playingSince < 0) playingSince = SystemClock.elapsedRealtime()
        } else {
            pauseClock()
        }
        update()
    }

    private fun startSong(mediaItem: MediaItem?) {
        handler.removeCallbacks(check)
        song = mediaItem?.localConfiguration?.tag as? Song
        heardMs = 0
        counted = false
        playingSince = if (player.isPlaying) SystemClock.elapsedRealtime() else -1L
        update()
    }

    private fun pauseClock() {
        if (playingSince >= 0) {
            heardMs += SystemClock.elapsedRealtime() - playingSince
            playingSince = -1L
        }
    }

    /** Counts the song if enough has been heard, otherwise checks again when it will have been. */
    private fun update() {
        handler.removeCallbacks(check)
        val song = song ?: return
        if (counted || song.durationMs <= 0) return
        val heard =
            heardMs + if (playingSince >= 0) SystemClock.elapsedRealtime() - playingSince else 0
        val needed = song.durationMs / 2
        if (heard >= needed) {
            counted = true
            PlayCounts.record(song)
        } else if (playingSince >= 0) {
            handler.postDelayed(check, needed - heard + 50)
        }
    }
}
