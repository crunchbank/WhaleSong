/*
 * Copyright (c) 2022 Auxio Project
 * ReplayGainAudioProcessor.kt is part of Auxio.
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
 * Modifications copyright (c) 2026 crunchbank (WhaleSong): streaming mode; the gain switches at
 * the exact sample where each song's audio starts (so gapless joins stay seamless) using
 * [StreamTracker]; changes while a song plays glide instead of flushing the audio; 16-bit and
 * float PCM.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback.replaygain

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.QueueChange
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.whale.loudness.StreamingMode
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * An [AudioProcessor] that applies ReplayGain, or WhaleSong's streaming mode, to the audio.
 *
 * The gain of the playing song and of its neighbours in the queue is worked out on the main
 * thread whenever something changes. [StreamTracker] tells this processor, on the playback
 * thread, which song's audio is about to be processed, so the gain switches exactly where that
 * song begins, including across gapless joins. A change to the gain while a song plays (a
 * setting, or a loudness measurement arriving) glides over about half a second.
 *
 * @author Alexander Capehart (OxygenCobalt), crunchbank (WhaleSong)
 */
@OptIn(markerClass = [UnstableApi::class])
class ReplayGainAudioProcessor
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val playbackSettings: PlaybackSettings,
) : BaseAudioProcessor(), PlaybackStateManager.Listener, PlaybackSettings.Listener {
    /** Linear gains of the playing song and its neighbours, by song. */
    private val gains = ConcurrentHashMap<Music.UID, Double>()

    /** The song whose audio is being processed, as [StreamTracker] last reported. */
    @Volatile private var streamSong: Song? = null
    @Volatile private var target = 1.0
    private var current = 1.0
    private var step = 0.0 // per-frame smoothing factor

    private val streamingModeListener: () -> Unit = { refresh() }

    fun attach() {
        playbackManager.addListener(this)
        playbackSettings.registerListener(this)
        StreamingMode.addListener(streamingModeListener)
        refresh()
    }

    /** Remove this instance from the components required for it to function correctly. */
    fun release() {
        playbackManager.removeListener(this)
        playbackSettings.unregisterListener(this)
        StreamingMode.removeListener(streamingModeListener)
    }

    // --- OVERRIDES ---

    override fun onIndexMoved(index: Int) {
        refresh()
    }

    override fun onQueueChanged(queue: List<Song>, index: Int, change: QueueChange) {
        refresh()
    }

    override fun onQueueReordered(queue: List<Song>, index: Int, isShuffled: Boolean) {
        refresh()
    }

    override fun onNewPlayback(
        parent: MusicParent?,
        queue: List<Song>,
        index: Int,
        isShuffled: Boolean,
    ) {
        L.d("New playback started, updating playback information")
        refresh()
    }

    override fun onRepeatModeChanged(repeatMode: RepeatMode) {
        refresh()
    }

    override fun onReplayGainSettingsChanged() {
        // ReplayGain config changed, we need to set it up again.
        refresh()
    }

    /**
     * Called on the playback thread when the audio of [song] is about to be processed: its own
     * gain applies from its very first sample. Null if the song isn't known; the gain then stays.
     */
    fun onStreamStart(song: Song?) {
        if (song == null) return
        streamSong = song
        val gain = gains[song.uid] ?: return
        target = gain
        current = gain
        L.d("Stream of ${song.name.raw} starts at %.2f dB".format(20 * log10(gain)))
    }

    // --- GAIN ---

    /** Works out the gains of the playing song and its neighbours, and applies the right one. */
    private fun refresh() {
        val queue = playbackManager.queue
        val index = playbackManager.index
        StreamingMode.setQueue(queue, index)
        val playing = playbackManager.currentSong
        val neighbours = buildList {
            playing?.let { add(it) }
            queue.getOrNull(index + 1)?.let { add(it) }
            if (index + 1 >= queue.size && playbackManager.repeatMode == RepeatMode.ALL) {
                queue.firstOrNull()?.let { add(it) }
            }
            queue.getOrNull(index - 1)?.let { add(it) }
        }
        val fresh = HashMap<Music.UID, Double>()
        neighbours.forEach { song ->
            fresh[song.uid] = 10.0.pow(gainDb(song, playing = song == playing) / 20)
        }
        // Keep the song still being processed, which may be the one before the playing one.
        streamSong?.let { s -> gains[s.uid]?.let { fresh.putIfAbsent(s.uid, it) } }
        gains.keys.retainAll(fresh.keys)
        gains.putAll(fresh)
        val now = streamSong?.takeIf { gains.containsKey(it.uid) } ?: playing
        target = now?.let { gains[it.uid] } ?: 1.0
        L.d("Gain for ${now?.name?.raw}: %.2f dB".format(20 * log10(target)))
    }

    private fun gainDb(song: Song, playing: Boolean): Double {
        if (StreamingMode.enabled) {
            return StreamingMode.gainDb(song, playing)
        }

        val gain = song.replayGainAdjustment
        val preAmp = playbackSettings.replayGainPreAmp

        // ReplayGain is configurable, so determine what to do based off of the mode.
        val resolvedAdjustment =
            when (playbackSettings.replayGainMode) {
                // User wants no adjustment.
                ReplayGainMode.OFF -> null
                // User wants track gain to be preferred. Default to album gain only if
                // there is no track gain.
                ReplayGainMode.TRACK -> gain.track ?: gain.album
                // User wants album gain to be preferred. Default to track gain only if
                // here is no album gain.
                ReplayGainMode.ALBUM -> gain.album ?: gain.track
                // User wants album gain to be used when in an album, track gain otherwise.
                ReplayGainMode.DYNAMIC ->
                    gain.album?.takeIf {
                        playbackManager.parent is Album && song.album == playbackManager.parent
                    } ?: gain.track
            }

        return if (resolvedAdjustment != null) {
            // Successfully resolved an adjustment, apply the corresponding pre-amp
            resolvedAdjustment + preAmp.with
        } else {
            // No adjustment found, use the corresponding user-defined pre-amp
            preAmp.without
        }.toDouble()
    }

    // --- AUDIO PROCESSOR IMPLEMENTATION ---

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
                inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        step = 1.0 / (GLIDE_SECONDS * inputAudioFormat.sampleRate / 4)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val goal = target
        if (current == 1.0 && goal == 1.0) {
            // Nothing to adjust, just copy the audio data.
            out.put(inputBuffer)
            out.flip()
            return
        }
        val channels = inputAudioFormat.channelCount
        inputBuffer.order(ByteOrder.nativeOrder())
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4 * channels) {
                val g = advance(goal).toFloat()
                repeat(channels) { out.putFloat((inputBuffer.getFloat() * g).coerceIn(-1f, 1f)) }
            }
        } else {
            while (inputBuffer.remaining() >= 2 * channels) {
                val g = advance(goal)
                repeat(channels) {
                    // Clamp to the 16-bit range so amplified samples don't wrap around and pop.
                    val v = (inputBuffer.getShort() * g).toInt()
                    out.putShort(
                        v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                    )
                }
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    /** Moves the applied gain one frame closer to [goal] and returns it. */
    private fun advance(goal: Double): Double {
        if (current != goal) {
            current += (goal - current) * step
            if (abs(goal - current) < 1e-4) current = goal
        }
        return current
    }

    override fun onFlush() {
        // After a seek or a new song the gain applies at once.
        current = target
    }

    override fun onReset() {
        current = target
    }

    private companion object {
        const val GLIDE_SECONDS = 0.5
    }
}
