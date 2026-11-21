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
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.oxycblt.auxio.R
import org.oxycblt.musikr.Song
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingDeque
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import timber.log.Timber as L

/**
 * Streaming mode: plays every song at the same loudness, by default -14 LUFS.
 *
 * Loud songs are turned down to the target. Quiet songs are turned up only as far as their peaks
 * allow (keeping [HEADROOM_DB] below full scale), so nothing is limited or clipped. Loudness comes
 * from ReplayGain/R128 tags when a file has them (as read by musikr, which keeps no peaks, so a
 * tagged song is also measured for its peak before it can be boosted), otherwise it is measured
 * on the device, one song
 * at a time, while streaming mode is on: the playing song first, then the next few in the queue.
 * A song that starts playing unmeasured interrupts the measurement of one further down the queue.
 *
 * Songs that play as an album (a run of consecutive tracks in the queue, see [AlbumContinuity])
 * share one gain, from the album's loudness, so the album's own rises and falls and its gapless
 * joins stay as they are. The album loudness comes from album gain tags, or is worked out exactly
 * from the songs' measurements (all their audio gated together); until every song of the run is
 * measured, it is estimated from those that are.
 *
 * The gain stage ([org.oxycblt.auxio.playback.replaygain.ReplayGainAudioProcessor]) asks [gainDb]
 * for the song it is about to play and registers a listener to hear when the answer changes (the
 * mode is toggled, the target changes or a measurement arrives). Measurements are kept in a
 * private database; files are never written to.
 */
object StreamingMode {

    const val DEFAULT_TARGET = -14.0

    /** Peaks are kept at least this far below full scale when boosting. */
    const val HEADROOM_DB = 1.0

    /** How many songs from the playing one on are measured ahead. */
    private const val PREFETCH = 5

    /** The most songs either side of the playing one an album run reaches. */
    private const val MAX_RUN = 60

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var store: LoudnessStore
    private lateinit var prefEnabled: String
    private lateinit var prefTarget: String

    private sealed class Known {
        class Info(val info: LoudnessInfo, val final: Boolean) : Known()
        object Unmeasurable : Known()
    }

    private val known = ConcurrentHashMap<String, Known>()
    private val queue = LinkedBlockingDeque<Song>()
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var enabledCache = false

    /** The song being measured, and whether an urgent request for another song should stop it. */
    @Volatile
    private var measuring: String? = null

    @Volatile
    private var interrupted = false

    @Volatile
    private var targetCache = DEFAULT_TARGET

    /** The play queue and the playing position, for finding the album runs songs belong to. */
    @Volatile
    private var playQueue: List<Song> = emptyList()

    @Volatile
    private var playPosition = 0
    private var lastRuns: String? = null

    /** Sub-block powers of recently used songs, and exact album loudness by run. */
    private val subBlockCache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>) = size > 64
    }
    private val albumCache = ConcurrentHashMap<String, LoudnessInfo>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            prefEnabled -> {
                enabledCache = p.getBoolean(prefEnabled, false)
                notifyListeners()
            }
            prefTarget -> {
                targetCache = readTarget(p)
                notifyListeners()
            }
        }
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        store = LoudnessStore(appContext)
        prefEnabled = appContext.getString(R.string.ws_set_key_streaming_mode)
        prefTarget = appContext.getString(R.string.ws_set_key_streaming_target)
        enabledCache = prefs.getBoolean(prefEnabled, false)
        targetCache = readTarget(prefs)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        thread(name = "StreamingModeMeter", isDaemon = true, priority = Thread.MIN_PRIORITY) { worker() }
    }

    var enabled: Boolean
        get() = enabledCache
        set(value) = prefs.edit { putBoolean(prefEnabled, value) }

    val target: Double get() = targetCache

    private fun readTarget(p: SharedPreferences): Double =
        p.getInt(prefTarget, DEFAULT_TARGET.toInt()).toDouble()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners() {
        mainHandler.post { listeners.forEach { it() } }
    }

    /**
     * The gain in dB to play [song] at: 0 when streaming mode is off or the song's loudness isn't
     * known yet (it is then measured, and listeners are told when it is). A [playing] song is
     * measured before any other; one only queued to play next waits its turn. A song playing as
     * part of an album gets the album's gain.
     */
    fun gainDb(song: Song?, playing: Boolean = true): Double {
        if (!enabledCache || song == null) return 0.0
        val run = albumRun(song)
        if (run.size >= 2) albumInfo(run, song, playing)?.let { return gainFor(it, targetCache) }
        return when (val k = lookup(song)) {
            is Known.Info -> {
                if (!k.final) request(song, urgent = playing)
                gainFor(k.info, targetCache)
            }
            Known.Unmeasurable -> 0.0
            null -> {
                request(song, urgent = playing)
                0.0
            }
        }
    }

    /**
     * Tells streaming mode the play queue and the playing position. The playing song and the next
     * few are measured ahead if needed, and so is the rest of an album the playing song is part of.
     */
    fun setQueue(queue: List<Song>, position: Int) {
        val snapshot = try {
            queue.toList()
        } catch (e: ConcurrentModificationException) {
            return
        }
        playQueue = snapshot
        playPosition = position
        if (!enabledCache) return
        val from = position.coerceIn(0, snapshot.size)
        prefetch((snapshot.drop(from).take(PREFETCH) + runAt(snapshot, from)).distinctBy { key(it) })
        // Gains change when the playing or next song joins or leaves an album run.
        val runs = runAt(snapshot, from).joinToString { key(it) } + "|" +
                runAt(snapshot, from + 1).joinToString { key(it) }
        if (runs != lastRuns) {
            lastRuns = runs
            notifyListeners()
        }
    }

    /** The album run [song] belongs to in the play queue, or an empty list if it is on its own. */
    private fun albumRun(song: Song): List<Song> {
        val queue = playQueue
        val position = playPosition
        val index = sequenceOf(position, position + 1, position - 1).firstOrNull {
            it in queue.indices && sameSong(queue[it], song)
        } ?: queue.indexOfFirst { sameSong(it, song) }
        return runAt(queue, index)
    }

    private fun runAt(queue: List<Song>, index: Int): List<Song> {
        if (index !in queue.indices) return emptyList()
        var first = index
        while (first > 0 && index - first < MAX_RUN && AlbumContinuity.follows(queue[first - 1], queue[first])) first--
        var last = index
        while (last < queue.size - 1 && last - index < MAX_RUN && AlbumContinuity.follows(queue[last], queue[last + 1])) last++
        return if (last > first) queue.subList(first, last + 1) else emptyList()
    }

    private fun sameSong(a: Song, b: Song) = a.uid == b.uid

    /**
     * The loudness of [run] played as an album, for [song] in it: from album gain tags, exactly
     * from the songs' measurements, or estimated from those known so far. Null if none is known.
     */
    private fun albumInfo(run: List<Song>, song: Song, playing: Boolean): LoudnessInfo? {
        val infos = run.map { member ->
            val k = lookup(member)
            if (k == null || (k is Known.Info && !k.final)) {
                request(member, urgent = playing && sameSong(member, song))
            }
            k
        }
        (infos[run.indexOfFirst { sameSong(it, song) }] as? Known.Info)?.info?.let { own ->
            own.albumLoudness?.let { return LoudnessInfo(it, own.albumPeak, LoudnessInfo.Source.TAG) }
        }
        val runKey = run.joinToString("\u0001") { key(it) }
        albumCache[runKey]?.let { return it }

        val known = run.indices.mapNotNull { i -> (infos[i] as? Known.Info)?.let { run[i] to it } }
        if (known.isEmpty()) return null
        val allMeasured = known.size == run.size &&
                known.all { (_, k) -> k.final && k.info.source == LoudnessInfo.Source.MEASURED }
        if (allMeasured) {
            val blocks = run.map { subBlocks(it) }
            if (blocks.all { it != null }) {
                val peak = known.maxOf { (_, k) -> k.info.peak ?: 1.0 }
                LoudnessMeter.albumLoudness(blocks.map { it!! }, peak)?.let {
                    albumCache[runKey] = it
                    return it
                }
            }
        }
        // Estimate: the songs' loudness averaged as power, weighted by length.
        var power = 0.0
        var weight = 0.0
        known.forEach { (member, k) ->
            val length = member.durationMs.coerceAtLeast(1).toDouble()
            power += length * 10.0.pow(k.info.loudness / 10)
            weight += length
        }
        val peak = if (known.all { (_, k) -> k.info.peak != null }) known.maxOf { (_, k) -> k.info.peak!! } else null
        return LoudnessInfo(10 * log10(power / weight), peak, LoudnessInfo.Source.MEASURED)
    }

    private fun subBlocks(song: Song): FloatArray? {
        val k = key(song)
        synchronized(subBlockCache) { subBlockCache[k] }?.let { return it }
        val blocks = try {
            store.subBlocks(pathOf(song), song.modifiedMs)
        } catch (e: Exception) {
            null
        } ?: return null
        synchronized(subBlockCache) { subBlockCache[k] = blocks }
        return blocks
    }

    /** Measures, in the background, the songs about to play that aren't known yet. */
    private fun prefetch(songs: List<Song>) {
        if (!enabledCache) return
        songs.forEach { song ->
            val k = lookup(song)
            if (k == null || (k is Known.Info && !k.final)) request(song, urgent = false)
        }
    }

    private const val RG_REFERENCE = -18.0

    internal fun gainFor(info: LoudnessInfo, target: Double): Double {
        val gain = target - info.loudness
        if (gain <= 0) return gain
        val peak = info.peak ?: return 0.0
        val headroom = -20 * log10(max(peak, 1e-6)) - HEADROOM_DB
        return min(gain, max(0.0, headroom))
    }

    private fun key(song: Song) = "${pathOf(song)}\u0000${song.modifiedMs}"

    /** Where [song] is, as a string: stable across rescans, unlike its URI. */
    private fun pathOf(song: Song) =
        "${song.path.volume.mediaStoreName ?: song.path.volume.components}:${song.path.components}"

    /**
     * Loudness from the song's ReplayGain or R128 tags, as musikr read them (relative to
     * -18 LUFS; musikr converts R128). There is no peak, so the song isn't boosted until its peak
     * has been measured.
     */
    private fun tagInfo(song: Song): LoudnessInfo? {
        val gain = song.replayGainAdjustment
        val track = gain.track ?: return null
        return LoudnessInfo(
            RG_REFERENCE - track,
            null,
            LoudnessInfo.Source.TAG,
            albumLoudness = gain.album?.let { RG_REFERENCE - it },
        )
    }

    private fun lookup(song: Song): Known? {
        known[key(song)]?.let { return it }
        val entry = try {
            store.get(pathOf(song), song.modifiedMs)
        } catch (e: Exception) {
            null
        }
        val k = when (entry) {
            is LoudnessStore.Entry.Known -> Known.Info(entry.info, final = true)
            LoudnessStore.Entry.Unmeasurable -> Known.Unmeasurable
            null -> return null
        }
        known[key(song)] = k
        return k
    }

    private fun request(song: Song, urgent: Boolean) {
        val k = key(song)
        if (urgent) {
            if (measuring == k) return // already under way
            // Move it to the front even if it's already waiting, and stop a measurement of a
            // song further down the queue: the playing song can't wait for it.
            queue.remove(song)
            queue.offerFirst(song)
            pending += k
            if (measuring != null) interrupted = true
        } else if (pending.add(k)) {
            queue.offerLast(song)
        }
    }

    private fun worker() {
        while (true) {
            val song = try {
                queue.takeFirst()
            } catch (e: InterruptedException) {
                return
            }
            val k = key(song)
            var requeued = false
            try {
                val current = known[k]
                if (current is Known.Unmeasurable || (current is Known.Info && current.final)) continue
                if (!enabledCache) continue

                // Tags first: a ReplayGain tag with a peak is all we need.
                val tagged = if (current == null) tagInfo(song) else null
                if (tagged != null) {
                    val final = tagged.peak != null
                    known[k] = Known.Info(tagged, final)
                    if (final) store.put(pathOf(song), song.modifiedMs, tagged)
                    notifyListeners()
                    if (final) continue
                }

                val started = System.currentTimeMillis()
                interrupted = false
                measuring = k
                val measured = try {
                    LoudnessMeter.measure(appContext, song.uri) { !enabledCache || interrupted }
                } finally {
                    measuring = null
                }
                if (measured == null && interrupted && enabledCache) {
                    // Put back behind the song that interrupted it.
                    L.d("Interrupted ${song.name.raw}")
                    queue.remove(song)
                    queue.offerLast(song)
                    requeued = true
                    continue
                }
                if (measured == null && !enabledCache) continue // cancelled; try again later
                L.d("Measured ${song.name.raw} in ${System.currentTimeMillis() - started} ms: " +
                        "${measured?.info?.loudness} LUFS, peak ${measured?.info?.peak}")
                store.put(pathOf(song), song.modifiedMs, measured?.info, measured?.subBlocks)
                if (measured != null) synchronized(subBlockCache) { subBlockCache[k] = measured.subBlocks }
                known[k] = if (measured != null) Known.Info(measured.info, final = true) else Known.Unmeasurable
                notifyListeners()
            } catch (e: Exception) {
                L.w(e, "Can't get loudness of ${song.name.raw}")
            } finally {
                if (!requeued) pending -= k
            }
        }
    }
}
