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

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.whale.backup.PlaylistAutoBackup
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Library
import org.oxycblt.musikr.tag.Name
import timber.log.Timber as L

/**
 * Keeps a copy of the favorite albums in the Music folder, "WhaleSong favorite albums.m3u", and
 * reads it back after WhaleSong is reinstalled. The file lists each favorite album's UID, name and
 * artists, followed by the albums' songs, so other players can use it as a playlist too.
 *
 * Restoring merges: a backup never removes favorites, and nothing is written over a backup
 * until it has been read (or found not to exist), so a fresh install can't replace a backup with
 * an empty list. An album whose UID has changed (its tags were edited) is matched by name and
 * artists instead.
 *
 * Written through MediaStore (Android 10 and later). After a reinstall, the old file belongs to
 * the previous install, so it is read (and then updated) through the music folder the user picked
 * if there is one, or a new copy is written next to it.
 */
@Singleton
class FavoritesBackup
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
) : MusicRepository.UpdateListener, SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val enabledKey = context.getString(R.string.ws_set_key_backup_playlists)
    private val checkedKey = context.getString(R.string.ws_key_favorites_backup_checked)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pending: Job? = null

    private val enabled
        get() = prefs.getBoolean(enabledKey, true)

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        musicRepository.addUpdateListener(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        Favorites.addListener(::schedule)
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (changes.deviceLibrary) schedule()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, changed: String?) {
        if (changed == enabledKey && enabled) schedule()
    }

    private fun schedule() {
        val library = musicRepository.library ?: return
        if (!Favorites.loaded) return
        pending?.cancel()
        pending =
            scope.launch {
                delay(DEBOUNCE_MS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    lock.withLock {
                        try {
                            sync(library)
                        } catch (e: Exception) {
                            L.e("Favorites backup failed: $e")
                        }
                    }
                }
            }
    }

    private class Backup(val uri: Uri, val text: String, val ours: Boolean, val savedMs: Long)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun sync(library: Library) {
        val backup = find()
        if (!prefs.getBoolean(checkedKey, false)) {
            if (backup != null) {
                val restored = restore(backup.text, library)
                L.d("Restored ${restored.size} favorite albums from the backup")
                Favorites.restore(restored)
                prefs.edit { putBoolean(checkedKey, true) }
            } else if (Favorites.all().isEmpty()) {
                // Nothing to restore yet, and nothing worth writing. The backup may become
                // readable later (once a music folder is picked), so look again next time.
                return
            }
        }
        if (!enabled) return

        val text = render(library)
        if (backup != null && content(backup.text) == content(text)) return
        val resolver = context.contentResolver
        if (backup != null && (backup.ours || backup.uri.authority != MediaStore.AUTHORITY)) {
            try {
                write(backup.uri, text)
                prefs.edit { putBoolean(checkedKey, true) }
                L.d("Updated the favorites backup")
                return
            } catch (e: Exception) {
                L.w("Can't update ${backup.uri}, writing a new copy: $e")
            }
        }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection(), values) ?: return
        write(uri, text)
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        prefs.edit { putBoolean(checkedKey, true) }
        L.d("Backed up favorites")
    }

    /** The newest backup WhaleSong can read: its own through MediaStore, or one in a picked folder. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun find(): Backup? {
        val found = mutableListOf<Backup>()
        val resolver = context.contentResolver
        try {
            resolver
                .query(
                    collection(),
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf(RELATIVE_PATH, "$FILE_BASE%.m3u"),
                    null,
                )
                ?.use { c ->
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection(), c.getLong(0))
                        val ours = c.getString(1) == context.packageName
                        read(uri, ours)?.let { found += it }
                    }
                }
        } catch (e: Exception) {
            L.w("Can't look for the favorites backup in MediaStore: $e")
        }
        for (permission in resolver.persistedUriPermissions) {
            if (!permission.isReadPermission) continue
            try {
                findInTree(permission.uri)?.let { read(it, permission.isWritePermission) }
                    ?.let { found += it }
            } catch (e: Exception) {
                L.w("Can't look for the favorites backup in ${permission.uri}: $e")
            }
        }
        // The same file can turn up twice; the newest save wins, our own copy on a tie.
        return found.maxWithOrNull(compareBy<Backup>({ it.savedMs }, { it.ours }))
    }

    /** The backup in a picked folder: the folder itself if it is Music, or its Music folder. */
    private fun findInTree(tree: Uri): Uri? {
        val rootId =
            try {
                DocumentsContract.getTreeDocumentId(tree)
            } catch (e: Exception) {
                return null
            }
        val children = children(tree, rootId)
        children[FILE_NAME]?.let { return it.first }
        if (!rootId.trimEnd('/').endsWith(":$MUSIC_DIR") && !rootId.endsWith("/$MUSIC_DIR")) {
            val music = children[MUSIC_DIR]?.takeIf { it.second } ?: return null
            return children(tree, DocumentsContract.getDocumentId(music.first))[FILE_NAME]?.first
        }
        return null
    }

    /** A folder's children by name: their URIs, and whether each is a folder. */
    private fun children(tree: Uri, documentId: String): Map<String, Pair<Uri, Boolean>> {
        val out = HashMap<String, Pair<Uri, Boolean>>()
        context.contentResolver
            .query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )
            ?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (name != FILE_NAME && name != MUSIC_DIR) continue
                    out[name] =
                        DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)) to
                            (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            }
        return out
    }

    private fun read(uri: Uri, ours: Boolean): Backup? {
        val text =
            try {
                context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            } catch (e: Exception) {
                null
            } ?: return null
        if (text.lineSequence().take(3).none { it == MARKER }) return null
        val saved =
            text.lineSequence().firstOrNull { it.startsWith(SAVED_PREFIX) }
                ?.removePrefix(SAVED_PREFIX)
                ?.trim()
                ?.toLongOrNull() ?: 0L
        return Backup(uri, text, ours, saved)
    }

    private fun write(uri: Uri, text: String) {
        val stream =
            context.contentResolver.openOutputStream(uri, "rwt")
                ?: error("Can't open $uri for writing")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun render(library: Library): String = buildString {
        val favorites = Favorites.all().sorted()
        val all = library.albums.associateBy { it.uid.toString() }
        val albums = favorites.mapNotNull { all[it] }
        val byUid = albums.associateBy { it.uid.toString() }
        append("#EXTM3U\n")
        append(MARKER).append('\n')
        append("#EXTENC:UTF-8\n")
        append(SAVED_PREFIX).append(System.currentTimeMillis()).append('\n')
        append("#PLAYLIST:Favorite albums\n")
        for (uid in favorites) {
            // Albums not in the library right now are kept, in case they come back.
            val album = byUid[uid]
            append(FAVORITE_PREFIX).append(uid)
            if (album != null) append('\t').append(clean(raw(album.name))).append('\t').append(artists(album))
            append('\n')
        }
        for (album in albums) {
            // In album order: disc, then track.
            for (song in album.songs.sortedWith(compareBy({ it.disc?.number }, { it.track }))) {
                append("#EXTINF:").append(song.durationMs / 1000).append(',')
                (song.artists.firstOrNull()?.name as? Name.Known)?.raw?.let {
                    append(clean(it)).append(" - ")
                }
                append(clean(song.name.raw)).append('\n')
                append(PlaylistAutoBackup.pathOf(song)).append('\n')
            }
        }
    }

    companion object {
        private const val FILE_BASE = "WhaleSong favorite albums"
        private const val FILE_NAME = "$FILE_BASE.m3u"
        const val MARKER = "# Favorite albums, backed up by WhaleSong. Edits to this file will be overwritten."
        private const val SAVED_PREFIX = "# WhaleSong saved: "
        private const val FAVORITE_PREFIX = "# WhaleSong favorite: "
        private const val MUSIC_DIR = "Music"
        private const val RELATIVE_PATH = "$MUSIC_DIR/"
        private const val MIME_TYPE = "audio/x-mpegurl"
        private const val DEBOUNCE_MS = 2000L

        private fun collection() = MediaStore.Files.getContentUri("external_primary")

        /** A backup's text without the time it was saved. */
        private fun content(text: String) =
            text.lineSequence().filterNot { it.startsWith(SAVED_PREFIX) }.joinToString("\n")

        private fun raw(name: Name) = (name as? Name.Known)?.raw ?: ""

        private fun clean(text: String) = text.replace(Regex("[\\t\\r\\n]"), " ")

        private fun artists(album: Album) =
            album.artists.mapNotNull { (it.name as? Name.Known)?.raw }.joinToString("; ") { clean(it) }

        /**
         * The favorite UIDs in a backup's [text]. An entry whose UID isn't in [library] is kept
         * as it is, and also matched by album name and artists, in case its tags were edited.
         */
        internal fun restore(text: String, library: Library): Set<String> {
            val known = library.albums.associateBy { it.uid.toString() }
            val byName by lazy {
                library.albums.groupBy { key(raw(it.name), artists(it)) }
            }
            val out = HashSet<String>()
            for (line in text.lineSequence()) {
                if (!line.startsWith(FAVORITE_PREFIX)) continue
                val parts = line.removePrefix(FAVORITE_PREFIX).split('\t')
                val uid = parts[0].trim()
                if (uid.isEmpty()) continue
                out += uid
                if (uid !in known && parts.size >= 3) {
                    byName[key(parts[1], parts[2])]?.forEach { out += it.uid.toString() }
                }
            }
            return out
        }

        private fun key(name: String, artists: String) =
            clean(name).trim().lowercase() + "\u0000" + artists.trim().lowercase()
    }
}
