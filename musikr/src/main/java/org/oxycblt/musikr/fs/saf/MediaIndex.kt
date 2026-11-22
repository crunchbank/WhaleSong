/*
 * Copyright (c) 2026 crunchbank (WhaleSong)
 * MediaIndex.kt is part of WhaleSong, a fork of Auxio.
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

package org.oxycblt.musikr.fs.saf

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore as AOSPMediaStore
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.path.MediaStorePathInterpreter
import org.oxycblt.musikr.fs.path.VolumeManager

/**
 * Every audio file MediaStore knows about, looked up by path, read in one query at the start of a
 * folder (SAF) scan.
 *
 * The folder scan used to ask, for every single file, the documents provider for the file's
 * MediaStore URI and then MediaStore for its date added: two round trips per file, the largest
 * single cost of a folder scan. This answers the same question for the whole library at once. It
 * also gives each file's MediaStore URI, which opens noticeably faster than its document URI, so
 * tags are read through that.
 *
 * Needs the audio permission; without it [load] returns null and the scan falls back to the old
 * per-file lookup. Files MediaStore has not indexed (or that it put on a path we cannot match)
 * simply are not found and fall back the same way.
 */
internal class MediaIndex private constructor(private val entries: Map<String, Entry>) {
    data class Entry(val uri: Uri, val addedMs: Long)

    operator fun get(path: Path): Entry? = entries[key(path)]

    val size
        get() = entries.size

    companion object {
        private fun key(path: Path) = "${path.volume.mediaStoreName}:${path.components}"

        fun load(context: Context): MediaIndex? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            val permission =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return null
            }
            return try {
                val interpreterFactory =
                    MediaStorePathInterpreter.Factory.from(VolumeManager.from(context))
                val entries = HashMap<String, Entry>()
                context.contentResolverSafe.useQuery(
                    AOSPMediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        AOSPMediaStore.Audio.AudioColumns._ID,
                        AOSPMediaStore.Audio.AudioColumns.DATE_ADDED,
                    ) + interpreterFactory.projection,
                ) { cursor ->
                    val interpreter = interpreterFactory.wrap(cursor)
                    val idIndex = cursor.getColumnIndexOrThrow(AOSPMediaStore.Audio.AudioColumns._ID)
                    val addedIndex =
                        cursor.getColumnIndexOrThrow(AOSPMediaStore.Audio.AudioColumns.DATE_ADDED)
                    while (cursor.moveToNext()) {
                        val path = interpreter.extract() ?: continue
                        val uri =
                            Uri.withAppendedPath(
                                AOSPMediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                cursor.getLong(idIndex).toString(),
                            )
                        entries[key(path)] = Entry(uri, cursor.getLong(addedIndex) * 1000)
                    }
                }
                MediaIndex(entries)
            } catch (e: Exception) {
                null
            }
        }
    }
}
