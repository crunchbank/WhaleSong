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

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * How many times each song has been played, and when it was last played. A play is counted by
 * [PlayCounter] once at least half of the song has been heard.
 *
 * Counts are keyed by the song's [org.oxycblt.musikr.Music.UID], the same identity playlists use,
 * kept in memory for sorting and saved to a small private database.
 */
object PlayCounts {
    data class Entry(val count: Int, val lastPlayedMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "WhaleSongPlayCounts") }
    private var store: Store? = null

    private val _changes = MutableStateFlow(0L)

    /** Changes whenever a play is counted or the saved counts finish loading. */
    val changes: StateFlow<Long> = _changes

    fun init(context: Context) {
        if (store != null) return
        val s = Store(context.applicationContext)
        store = s
        writer.execute {
            try {
                s.readAll().forEach { (uid, entry) -> entries.merge(uid, entry, ::newer) }
                L.d("Loaded ${entries.size} play counts")
            } catch (e: Exception) {
                L.e("Can't load play counts: $e")
            }
            _changes.value++
        }
    }

    fun count(song: Song): Int = entries[song.uid.toString()]?.count ?: 0

    fun lastPlayedMs(song: Song): Long = entries[song.uid.toString()]?.lastPlayedMs ?: 0L

    fun count(album: Album): Int = album.songs.sumOf { count(it) }

    fun count(artist: Artist): Int = artist.songs.sumOf { count(it) }

    fun count(genre: Genre): Int = genre.songs.sumOf { count(it) }

    /** Counts one play of [song], now. */
    fun record(song: Song) {
        val uid = song.uid.toString()
        val now = System.currentTimeMillis()
        val entry = entries.compute(uid) { _, old -> Entry((old?.count ?: 0) + 1, now) }!!
        L.d("Counted a play of ${song.name}: ${entry.count}")
        _changes.value++
        writer.execute {
            try {
                store?.write(uid, entry)
            } catch (e: Exception) {
                L.e("Can't save play count: $e")
            }
        }
    }

    private fun newer(a: Entry, b: Entry) = if (a.lastPlayedMs >= b.lastPlayedMs) a else b

    private class Store(context: Context) :
        SQLiteOpenHelper(context, "whalesong_plays.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE plays (uid TEXT PRIMARY KEY, count INTEGER NOT NULL, " +
                    "last_played INTEGER NOT NULL)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

        fun readAll(): Map<String, Entry> =
            readableDatabase
                .query("plays", arrayOf("uid", "count", "last_played"), null, null, null, null, null)
                .use { c ->
                    val out = HashMap<String, Entry>(c.count)
                    while (c.moveToNext()) out[c.getString(0)] = Entry(c.getInt(1), c.getLong(2))
                    out
                }

        fun write(uid: String, entry: Entry) {
            val values =
                ContentValues().apply {
                    put("uid", uid)
                    put("count", entry.count)
                    put("last_played", entry.lastPlayedMs)
                }
            writableDatabase.insertWithOnConflict(
                "plays",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }
}
