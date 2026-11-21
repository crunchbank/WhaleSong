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

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * Measures a song's integrated loudness (ITU-R BS.1770-4 with EBU R128 gating) and its sample
 * peak, by decoding it with the platform decoders.
 */
object LoudnessMeter {

    /**
     * A measurement, with the weighted mean square of each 100 ms of the song, from which the
     * loudness of several songs played as one album can be worked out exactly.
     */
    class Result(val info: LoudnessInfo, val subBlocks: FloatArray)

    /**
     * Returns the measurement, or null if the file can't be decoded or holds less than one
     * 400 ms block of audio. [isCancelled] is polled between buffers.
     *
     * Platform decoders spend most of their time handing small packets across to the media
     * process, so a long song is cut into up to [MAX_PARTS] parts decoded side by side. Each
     * part starts decoding [PREROLL_US] early to settle the decoder and filters, and counts only
     * its own whole 100 ms sub-blocks, so the joined result equals a single pass. A part that
     * fails is retried from further back, then from the beginning of the file; if it still fails,
     * the song is measured again in one pass.
     */
    fun measure(context: Context, uri: Uri, isCancelled: () -> Boolean = { false }): Result? {
        val durationUs = try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val track = audioTrack(extractor) ?: return null
                val format = extractor.getTrackFormat(track)
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            return null
        }
        val parts = (durationUs / MIN_PART_US).toInt().coerceIn(1, MAX_PARTS)
        if (parts > 1) {
            val results = arrayOfNulls<Analyser>(parts)
            val partUs = durationUs / parts
            val threads = (0 until parts).map { k ->
                thread(name = "LoudnessPart$k", priority = Thread.currentThread().priority) {
                    val startUs = if (k == 0) null else partUs * k
                    val endUs = if (k == parts - 1) null else partUs * (k + 1)
                    // A decoder can reject the first packet after a seek: try further back, then
                    // from the beginning.
                    results[k] = decode(context, uri, startUs, endUs, PREROLL_US, isCancelled)
                        ?: startUs?.takeUnless { isCancelled() }?.let {
                            decode(context, uri, it, endUs, RETRY_PREROLL_US, isCancelled)
                                ?: decode(context, uri, it, endUs, null, isCancelled)
                        }
                }
            }
            threads.forEach { it.join() }
            if (isCancelled()) return null
            if (results.all { it != null }) {
                val subBlocks = results.flatMap { it!!.subBlocks }
                val info = Analyser.result(subBlocks, results.maxOf { it!!.peak }) ?: return null
                return Result(info, FloatArray(subBlocks.size) { subBlocks[it].toFloat() })
            }
        }
        val analyser = decode(context, uri, null, null, null, isCancelled) ?: return null
        val info = analyser.result() ?: return null
        return Result(info, FloatArray(analyser.subBlocks.size) { analyser.subBlocks[it].toFloat() })
    }

    /**
     * The loudness of several songs played one after another, as BS.1770 measures an album: all
     * their 100 ms sub-blocks gated together, with the highest peak.
     */
    fun albumLoudness(subBlocks: List<FloatArray>, peak: Double): LoudnessInfo? {
        val all = ArrayList<Double>(subBlocks.sumOf { it.size })
        subBlocks.forEach { song -> song.forEach { all += it.toDouble() } }
        return Analyser.result(all, peak)
    }

    private fun audioTrack(extractor: MediaExtractor): Int? = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    }

    /**
     * Decodes the audio from [startUs] (or the beginning) to [endUs] (or the end) and returns the
     * analysis of the 100 ms sub-blocks that fall inside, on a grid of sub-blocks counted from the
     * start of the song. Decoding starts [prerollUs] before [startUs], or at the beginning of the
     * file if that is null. Returns null on failure or cancellation.
     */
    private fun decode(
        context: Context, uri: Uri, startUs: Long?, endUs: Long?, prerollUs: Long?,
        isCancelled: () -> Boolean,
    ): Analyser? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = audioTrack(extractor) ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (startUs != null && prerollUs != null) {
                extractor.seekTo(max(0L, startUs - prerollUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                // After a seek into an Ogg file, the platform extractor can return the tail of a
                // packet continued from the previous page as if it were a whole packet, which the
                // Opus decoder rejects. The first packet is only preroll, so skip it.
                extractor.advance()
            }
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var analyser: Analyser? = null
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            // Frame positions, in output frames from the start of the song, set on the first output.
            var next = -1L // frame index of the next decoded frame
            var countFrom = 0L
            var countTo = Long.MAX_VALUE
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var shorts = ShortArray(0)
            var floats = FloatArray(0)
            var idlePolls = 0 // polls in a row that produced nothing: a stalled decoder gives up
            while (true) {
                if (isCancelled() || idlePolls > MAX_IDLE_POLLS) return null
                // Give the decoder every input buffer it has free.
                var fed = false
                while (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    fed = true
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                // Take every output buffer that's ready; wait only when there was nothing to do.
                var waitUs = if (fed) 0L else TIMEOUT_US
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, waitUs)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!fed) idlePolls++
                        break
                    }
                    idlePolls = 0
                    waitUs = 0
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (analyser != null) return null // the format changed mid-stream
                        val out = codec.outputFormat
                        encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                        if (encoding != AudioFormat.ENCODING_PCM_16BIT &&
                            encoding != AudioFormat.ENCODING_PCM_FLOAT
                        ) return null
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        continue
                    }
                    if (outIndex < 0) continue
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        val a = analyser ?: Analyser(sampleRate, channels).also {
                            analyser = it
                            // Place the part on the song's grid of 100 ms sub-blocks.
                            val sub = it.subBlockFrames.toLong()
                            if (startUs != null) {
                                countFrom = (startUs * sampleRate / 1_000_000 + sub / 2) / sub * sub
                                next = info.presentationTimeUs * sampleRate / 1_000_000
                                // A seek that lands well past the part's start would leave a gap.
                                if (next > countFrom + sampleRate / 10) return null
                            } else {
                                next = 0
                            }
                            if (endUs != null) countTo = (endUs * sampleRate / 1_000_000 + sub / 2) / sub * sub
                        }
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        val n: Int
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buffer.asFloatBuffer()
                            n = fb.remaining()
                            if (floats.size < n) floats = FloatArray(n)
                            fb.get(floats, 0, n)
                        } else {
                            val sb = buffer.asShortBuffer()
                            n = sb.remaining()
                            if (shorts.size < n) shorts = ShortArray(n)
                            sb.get(shorts, 0, n)
                        }
                        val frames = n / channels
                        // Frames before the part only warm the filters; frames after it end the part.
                        val warm = (countFrom - next).coerceIn(0, frames.toLong()).toInt()
                        val counted = (min(countTo, next + frames) - next).coerceIn(0, frames.toLong()).toInt()
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            a.addFloats(floats, 0, warm * channels, count = false)
                            if (counted > warm) a.addFloats(floats, warm * channels, counted * channels, count = true)
                        } else {
                            a.addShorts(shorts, 0, warm * channels, count = false)
                            if (counted > warm) a.addShorts(shorts, warm * channels, counted * channels, count = true)
                        }
                        next += frames
                        if (next >= countTo) {
                            codec.releaseOutputBuffer(outIndex, false)
                            return a
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (eos) return analyser
                }
            }
        } catch (e: Exception) {
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_POLLS = 500 // 5 s
    private const val MAX_PARTS = 4
    private const val MIN_PART_US = 30_000_000L
    private const val PREROLL_US = 1_000_000L
    private const val RETRY_PREROLL_US = 3_000_000L

    /** Two-stage K-weighting filter per channel, 100 ms sub-blocks, gated integration. */
    internal class Analyser(sampleRate: Int, private val channels: Int) {
        private val shelf = Biquad.highShelf(sampleRate.toDouble())
        private val highPass = Biquad.highPass(sampleRate.toDouble())
        private val state = Array(channels) { DoubleArray(8) }
        private val weights = DoubleArray(channels) { channelWeight(it, channels) }
        val subBlockFrames = sampleRate / 10
        private val subBlockSum = DoubleArray(channels)
        private var subBlockCount = 0
        val subBlocks = ArrayList<Double>() // weighted mean square of each 100 ms
        private var channel = 0
        var peak = 0.0
            private set

        /**
         * Adds samples [from] until [to] (interleaved, whole frames). With [count] false they
         * only run through the filters, to settle them before the audio that counts.
         */
        fun addShorts(samples: ShortArray, from: Int, to: Int, count: Boolean = true) {
            for (i in from until to) add(samples[i] * (1.0 / 32768), count)
        }

        fun addFloats(samples: FloatArray, from: Int, to: Int, count: Boolean = true) {
            for (i in from until to) add(samples[i].toDouble(), count)
        }

        @Suppress("NOTHING_TO_INLINE")
        private inline fun add(sample: Double, count: Boolean) {
            val s = state[channel]
            val y1 = shelf.process(sample, s, 0)
            val y2 = highPass.process(y1, s, 4)
            if (!count) {
                channel++
                if (channel == channels) channel = 0
                return
            }
            val a = abs(sample)
            if (a > peak) peak = a
            subBlockSum[channel] += y2 * y2
            channel++
            if (channel == channels) {
                channel = 0
                subBlockCount++
                if (subBlockCount == subBlockFrames) {
                    var power = 0.0
                    for (c in 0 until channels) {
                        power += weights[c] * subBlockSum[c] / subBlockFrames
                        subBlockSum[c] = 0.0
                    }
                    subBlocks += power
                    subBlockCount = 0
                }
            }
        }

        fun result(): LoudnessInfo? = result(subBlocks, peak)

        companion object {
            private const val ABSOLUTE_GATE = -70.0
            private const val RELATIVE_GATE = 10.0

            private fun loudness(power: Double) = -0.691 + 10 * log10(max(power, 1e-20))

            /** Gated integrated loudness of consecutive 100 ms sub-blocks. */
            fun result(subBlocks: List<Double>, peak: Double): LoudnessInfo? {
                // 400 ms blocks with 75% overlap: every run of four consecutive 100 ms sub-blocks.
                if (subBlocks.size < 4) return null
                val blocks = DoubleArray(subBlocks.size - 3) { i ->
                    (subBlocks[i] + subBlocks[i + 1] + subBlocks[i + 2] + subBlocks[i + 3]) / 4
                }
                val absoluteGated = blocks.filter { loudness(it) > ABSOLUTE_GATE }
                if (absoluteGated.isEmpty()) return null
                val relativeGate = loudness(absoluteGated.average()) - RELATIVE_GATE
                val gated = absoluteGated.filter { loudness(it) > relativeGate }
                if (gated.isEmpty()) return null
                return LoudnessInfo(loudness(gated.average()), peak, LoudnessInfo.Source.MEASURED)
            }

            /**
             * BS.1770 weights: 1.0 for front channels, 1.41 for surrounds, 0 for LFE (5.1 layout).
             * A mono file counts as if played on two speakers, as the listener hears it.
             */
            fun channelWeight(index: Int, channels: Int): Double = when {
                channels == 1 -> 2.0
                channels == 6 && index == 3 -> 0.0
                channels == 6 && index >= 4 -> 1.41
                else -> 1.0
            }
        }
    }

    /** Direct form I biquad; the per-channel state holds x1, x2, y1, y2 at [offset]. */
    internal class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        fun process(x: Double, s: DoubleArray, offset: Int): Double {
            val y = b0 * x + b1 * s[offset] + b2 * s[offset + 1] - a1 * s[offset + 2] - a2 * s[offset + 3]
            s[offset + 1] = s[offset]
            s[offset] = x
            s[offset + 3] = s[offset + 2]
            s[offset + 2] = y
            return y
        }

        companion object {
            // K-weighting parameters valid at any sample rate (as in libebur128).
            fun highShelf(rate: Double): Biquad {
                val f0 = 1681.974450955533
                val g = 3.999843853973347
                val q = 0.7071752369554196
                val k = tan(PI * f0 / rate)
                val vh = 10.0.pow(g / 20)
                val vb = vh.pow(0.4996667741545416)
                val a0 = 1 + k / q + k * k
                return Biquad(
                    (vh + vb * k / q + k * k) / a0,
                    2 * (k * k - vh) / a0,
                    (vh - vb * k / q + k * k) / a0,
                    2 * (k * k - 1) / a0,
                    (1 - k / q + k * k) / a0,
                )
            }

            fun highPass(rate: Double): Biquad {
                val f0 = 38.13547087602444
                val q = 0.5003270373238773
                val k = tan(PI * f0 / rate)
                val a0 = 1 + k / q + k * k
                return Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
            }
        }
    }
}
