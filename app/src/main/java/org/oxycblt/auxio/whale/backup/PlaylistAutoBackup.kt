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

package org.oxycblt.auxio.whale.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
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
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.tag.Name
import timber.log.Timber as L

/**
 * Keeps a copy of every playlist as an .m3u file in the Music folder, rewritten a moment after
 * any playlist changes, so playlists survive reinstalling WhaleSong and can be used by other
 * players. Song paths are relative to the Music folder, so the files keep working if the music
 * is copied elsewhere; songs on other storage get absolute paths.
 *
 * Files are written through MediaStore (Android 10 and later), which needs no permission. Only
 * files carrying [MARKER] are ever rewritten or deleted: an .m3u the user or another app put in
 * the Music folder is left alone, even one with a playlist's name.
 */
@Singleton
class PlaylistAutoBackup
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
) : MusicRepository.UpdateListener, SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val key = context.getString(R.string.ws_set_key_backup_playlists)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null

    private val enabled
        get() = prefs.getBoolean(key, true)

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        musicRepository.addUpdateListener(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (changes.userLibrary) schedule()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, changed: String?) {
        if (changed == key && enabled) schedule()
    }

    private fun schedule() {
        if (!enabled) return
        val playlists = musicRepository.library?.playlists ?: return
        pending?.cancel()
        pending =
            scope.launch {
                delay(DEBOUNCE_MS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        sync(playlists)
                    } catch (e: Exception) {
                        L.e("Playlist backup failed: $e")
                    }
                }
            }
    }

    private class Backup(val uri: Uri, val name: String, val playlistId: String?, val text: String)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun sync(playlists: Collection<Playlist>) {
        val resolver = context.contentResolver
        val ours = findBackups()
        val byId = ours.filter { it.playlistId != null }.associateBy { it.playlistId }
        val kept = HashSet<Uri>()

        for (playlist in playlists) {
            val id = playlist.uid.toString()
            val text = render(playlist)
            val wanted = fileName(playlist.name.raw)
            val existing = byId[id]
            if (existing != null && isNameFor(existing.name, wanted)) {
                kept += existing.uri
                if (existing.text != text) {
                    write(existing.uri, text)
                    L.d("Updated backup of ${playlist.name.raw}")
                }
                continue
            }
            // New, or renamed: write a fresh file. MediaStore picks a free name if another
            // app's file already has this one.
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, wanted)
                    put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val uri = resolver.insert(collection(), values) ?: continue
            write(uri, text)
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            kept += uri
            L.d("Backed up ${playlist.name.raw}")
        }

        // Playlists that were deleted or renamed: remove our old copies.
        for (backup in ours) {
            if (backup.uri !in kept) {
                resolver.delete(backup.uri, null, null)
                L.d("Removed old backup ${backup.name}")
            }
        }
    }

    /** Our own backups in the Music folder, recognised by [MARKER]. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findBackups(): List<Backup> {
        val resolver = context.contentResolver
        val found = mutableListOf<Backup>()
        resolver
            .query(
                collection(),
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf(RELATIVE_PATH, "%.m3u"),
                null,
            )
            ?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection(), c.getLong(0))
                    val text =
                        try {
                            resolver.openInputStream(uri)?.use {
                                it.bufferedReader().readText()
                            }
                        } catch (e: Exception) {
                            null
                        } ?: continue
                    if (text.lineSequence().take(3).none { it == MARKER }) continue
                    val id =
                        text.lineSequence().firstOrNull { it.startsWith(ID_PREFIX) }
                            ?.removePrefix(ID_PREFIX)
                            ?.trim()
                    found += Backup(uri, c.getString(1), id, text)
                }
            }
        return found
    }

    private fun write(uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "rwt")?.use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun render(playlist: Playlist): String = buildString {
        append("#EXTM3U\n")
        append(MARKER).append('\n')
        append("#EXTENC:UTF-8\n")
        append("#PLAYLIST:").append(playlist.name.raw).append('\n')
        append(ID_PREFIX).append(playlist.uid).append('\n')
        for (song in playlist.songs) {
            append("#EXTINF:").append(song.durationMs / 1000).append(',')
            (song.artists.firstOrNull()?.name as? Name.Known)?.raw?.let { append(it).append(" - ") }
            append(song.name.raw).append('\n')
            append(pathOf(song)).append('\n')
        }
    }

    /** [song]'s path relative to the Music folder, or absolute if it's on other storage. */
    private fun pathOf(song: Song): String {
        val path = song.path
        val parts = path.components.components
        if (path.volume is Volume.Internal) {
            return relativePath(listOf(MUSIC_DIR), parts)
        }
        val volume = path.volume.components?.components ?: emptyList()
        return "/" + (volume + parts).joinToString("/")
    }

    private fun collection() = MediaStore.Files.getContentUri("external_primary")

    companion object {
        const val MARKER = "# Backed up by WhaleSong. Edits to this file will be overwritten."
        private const val ID_PREFIX = "# WhaleSong playlist: "
        private const val MUSIC_DIR = "Music"
        private const val RELATIVE_PATH = "$MUSIC_DIR/"
        private const val MIME_TYPE = "audio/x-mpegurl"
        private const val DEBOUNCE_MS = 2000L

        /** The path from directory [base] to [target], both from the same root. */
        internal fun relativePath(base: List<String>, target: List<String>): String {
            var common = 0
            while (common < base.size && common < target.size - 1 &&
                base[common] == target[common]) {
                common++
            }
            return (List(base.size - common) { ".." } + target.drop(common)).joinToString("/")
        }

        /** A file name for a playlist: characters not allowed in file names become "_". */
        /**
         * Whether a backup's file name belongs to a playlist whose name gives [wanted]. When
         * another app's file already has the name, MediaStore saves ours as "Name (1).m3u", and
         * that must still count; otherwise every sync would see a rename, write a new file and
         * delete the old one, without end.
         */
        internal fun isNameFor(existing: String, wanted: String): Boolean {
            if (existing.equals(wanted, ignoreCase = true)) return true
            val base = wanted.removeSuffix(".m3u")
            return Regex("${Regex.escape(base)} \\(\\d+\\)\\.m3u", RegexOption.IGNORE_CASE)
                .matches(existing)
        }

        internal fun fileName(name: String): String {
            val clean = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.')
            return (clean.ifEmpty { "Playlist" }).take(120) + ".m3u"
        }
    }
}
