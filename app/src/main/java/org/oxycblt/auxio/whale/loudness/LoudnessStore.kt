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

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Remembers each song's loudness, keyed by file path and modification time, so a file that is
 * re-tagged or replaced is looked at again. A row with no loudness records a file that could not
 * be measured, so it isn't retried on every play. Measured songs also keep their 100 ms sub-block
 * powers (as little-endian floats), for working out the loudness of an album.
 */
class LoudnessStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "loudness.db", null, 2) {

    sealed class Entry {
        class Known(val info: LoudnessInfo) : Entry()
        object Unmeasurable : Entry()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE loudness (path TEXT PRIMARY KEY, modified INTEGER NOT NULL, " +
                    "loudness REAL, peak REAL, source INTEGER NOT NULL, " +
                    "album_loudness REAL, album_peak REAL, blocks BLOB)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE loudness ADD COLUMN album_loudness REAL")
            db.execSQL("ALTER TABLE loudness ADD COLUMN album_peak REAL")
            db.execSQL("ALTER TABLE loudness ADD COLUMN blocks BLOB")
            // Version 1 kept no sub-blocks: measure those songs again, and read tags again for
            // their album gain.
            db.execSQL("DELETE FROM loudness WHERE loudness IS NOT NULL")
        }
    }

    fun get(path: String, modified: Long): Entry? = readableDatabase.query(
        "loudness", arrayOf("loudness", "peak", "source", "album_loudness", "album_peak"),
        "path = ? AND modified = ?", arrayOf(path, modified.toString()), null, null, null
    ).use { c ->
        if (!c.moveToFirst()) return null
        if (c.isNull(0)) return Entry.Unmeasurable
        Entry.Known(
            LoudnessInfo(
                c.getDouble(0),
                if (c.isNull(1)) null else c.getDouble(1),
                LoudnessInfo.Source.entries.getOrElse(c.getInt(2)) { LoudnessInfo.Source.MEASURED },
                if (c.isNull(3)) null else c.getDouble(3),
                if (c.isNull(4)) null else c.getDouble(4),
            )
        )
    }

    /** The 100 ms sub-block powers of a measured song, if they were kept. */
    fun subBlocks(path: String, modified: Long): FloatArray? = readableDatabase.query(
        "loudness", arrayOf("blocks"),
        "path = ? AND modified = ?", arrayOf(path, modified.toString()), null, null, null
    ).use { c ->
        if (!c.moveToFirst() || c.isNull(0)) return null
        val bytes = ByteBuffer.wrap(c.getBlob(0)).order(ByteOrder.LITTLE_ENDIAN)
        FloatArray(bytes.remaining() / 4) { bytes.getFloat() }
    }

    fun put(path: String, modified: Long, info: LoudnessInfo?, subBlocks: FloatArray? = null) {
        val values = ContentValues().apply {
            put("path", path)
            put("modified", modified)
            put("loudness", info?.loudness)
            put("peak", info?.peak)
            put("source", (info?.source ?: LoudnessInfo.Source.MEASURED).ordinal)
            put("album_loudness", info?.albumLoudness)
            put("album_peak", info?.albumPeak)
            put("blocks", subBlocks?.let {
                ByteBuffer.allocate(it.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    .apply { it.forEach { v -> putFloat(v) } }.array()
            })
        }
        writableDatabase.insertWithOnConflict("loudness", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
