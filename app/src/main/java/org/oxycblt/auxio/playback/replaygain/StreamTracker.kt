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

package org.oxycblt.auxio.playback.replaygain

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import java.util.concurrent.ConcurrentHashMap
import org.oxycblt.musikr.Song

/**
 * Tells [ReplayGainAudioProcessor] which song's audio is about to be processed, so each song's
 * gain applies from its first sample, including across gapless joins.
 *
 * An audio renderer is handed each song's stream ahead of time, with the offset its audio will
 * carry; [wrap] notes which song each offset belongs to. The audio sink is told when its input
 * reaches a new offset ([sink]), and that is when the processor switches.
 */
@OptIn(UnstableApi::class)
class StreamTracker(private val processor: ReplayGainAudioProcessor) {
    private val songs = ConcurrentHashMap<Long, Song>()
    @Volatile private var timeline: Timeline = Timeline.EMPTY

    /** [renderer], noting the song of every stream it is given. */
    fun wrap(renderer: Renderer): Renderer =
        object : ForwardingRenderer(renderer) {
            override fun setTimeline(timeline: Timeline) {
                this@StreamTracker.timeline = timeline
                super.setTimeline(timeline)
            }

            override fun enable(
                configuration: RendererConfiguration,
                formats: Array<out Format>,
                stream: SampleStream,
                positionUs: Long,
                joining: Boolean,
                mayRenderStartOfStream: Boolean,
                startPositionUs: Long,
                offsetUs: Long,
                mediaPeriodId: MediaSource.MediaPeriodId,
            ) {
                note(offsetUs, mediaPeriodId)
                super.enable(
                    configuration,
                    formats,
                    stream,
                    positionUs,
                    joining,
                    mayRenderStartOfStream,
                    startPositionUs,
                    offsetUs,
                    mediaPeriodId,
                )
            }

            override fun replaceStream(
                formats: Array<out Format>,
                stream: SampleStream,
                startPositionUs: Long,
                offsetUs: Long,
                mediaPeriodId: MediaSource.MediaPeriodId,
            ) {
                note(offsetUs, mediaPeriodId)
                super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
            }
        }

    /** [sink], telling the processor when its input reaches a new song. */
    fun sink(sink: AudioSink): AudioSink =
        object : ForwardingAudioSink(sink) {
            override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
                processor.onStreamStart(songs[outputStreamOffsetUs])
                super.setOutputStreamOffsetUs(outputStreamOffsetUs)
            }
        }

    private fun note(offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
        val song =
            try {
                val t = timeline
                val windowIndex = t.getPeriodByUid(mediaPeriodId.periodUid, Timeline.Period())
                    .windowIndex
                t.getWindow(windowIndex, Timeline.Window()).mediaItem.localConfiguration?.tag
                    as? Song
            } catch (e: Exception) {
                null
            } ?: return
        songs[offsetUs] = song
        if (songs.size > MAX_STREAMS) {
            songs.keys.sorted().take(songs.size - MAX_STREAMS).forEach { songs.remove(it) }
        }
    }

    private companion object {
        const val MAX_STREAMS = 8
    }
}
