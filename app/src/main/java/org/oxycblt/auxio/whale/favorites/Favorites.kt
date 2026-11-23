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

package org.oxycblt.auxio.whale.favorites

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.R
import org.oxycblt.musikr.Album
import timber.log.Timber as L

/**
 * The user's favorite albums, keyed by each album's [org.oxycblt.musikr.Music.UID] (the identity
 * playlists use, which survives rescans and moving the files), kept in memory and saved to a small
 * private database. Nothing is written to the music files. [FavoritesBackup] keeps a copy in the
 * Music folder so favorites survive reinstalling WhaleSong.
 *
 * Also holds the Albums tab's "favorites only" filter.
 */
object Favorites {
    private val uids: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "WhaleSongFavorites") }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var store: Store? = null

    private val _changes = MutableStateFlow(0L)

    /** Changes whenever an album is added or removed, or the saved favorites finish loading. */
    val changes: StateFlow<Long> = _changes

    @Volatile
    var loaded = false
        private set

    private val onlyState = MutableStateFlow(false)

    fun init(context: Context) {
        if (store != null) return
        val s = Store(context.applicationContext)
        store = s
        onlyState.value =
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                .getBoolean(context.getString(R.string.ws_key_favorites_only), false)
        writer.execute {
            try {
                uids += s.readAll()
                L.d("Loaded ${uids.size} favorite albums")
            } catch (e: Exception) {
                L.e("Can't load favorites: $e")
            }
            loaded = true
            changed()
        }
    }

    fun isFavorite(album: Album) = album.uid.toString() in uids

    /** Every favorite album's UID, including albums not in the library right now. */
    fun all(): Set<String> = uids.toSet()

    fun set(album: Album, favorite: Boolean) = setUids(listOf(album.uid.toString()), favorite)

    fun toggle(album: Album): Boolean {
        val now = !isFavorite(album)
        set(album, now)
        return now
    }

    /** Adds favorites restored from a backup. */
    internal fun restore(restored: Collection<String>) = setUids(restored, true)

    private fun setUids(changes: Collection<String>, favorite: Boolean) {
        val changed = changes.filter { if (favorite) uids.add(it) else uids.remove(it) }
        if (changed.isEmpty()) return
        changed()
        writer.execute {
            try {
                store?.write(changed, favorite)
            } catch (e: Exception) {
                L.e("Can't save favorites: $e")
            }
        }
    }

    /** Calls [listener] on the main thread whenever favorites change. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun changed() {
        _changes.value++
        main.post { listeners.forEach { it() } }
    }

    /** Whether the Albums tab shows only favorites. */
    val onlyFavorites: StateFlow<Boolean>
        get() = onlyState

    fun toggleOnlyFavorites(context: Context) {
        onlyState.value = !onlyState.value
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit {
            putBoolean(context.getString(R.string.ws_key_favorites_only), onlyState.value)
        }
    }

    private class Store(context: Context) :
        SQLiteOpenHelper(context, "whalesong_favorites.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE albums (uid TEXT PRIMARY KEY, added INTEGER NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

        fun readAll(): Set<String> =
            readableDatabase.query("albums", arrayOf("uid"), null, null, null, null, null).use { c
                ->
                val out = HashSet<String>(c.count)
                while (c.moveToNext()) out += c.getString(0)
                out
            }

        fun write(changed: Collection<String>, favorite: Boolean) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                for (uid in changed) {
                    if (favorite) {
                        db.execSQL(
                            "INSERT OR IGNORE INTO albums (uid, added) VALUES (?, ?)",
                            arrayOf<Any>(uid, now),
                        )
                    } else {
                        db.delete("albums", "uid = ?", arrayOf(uid))
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}
