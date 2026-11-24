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

package org.oxycblt.auxio.whale.snapshot

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.oxycblt.musikr.cache.CacheResult
import org.oxycblt.musikr.cache.CachedFile
import org.oxycblt.musikr.cache.MutableCache
import org.oxycblt.musikr.fs.AddedMs
import org.oxycblt.musikr.fs.FS
import org.oxycblt.musikr.fs.FSUpdate
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.fs.Location
import org.oxycblt.musikr.fs.Path
import timber.log.Timber as L

/**
 * The list of music files the last complete scan of the picked folders found, so the next start
 * can show the library at once instead of first walking every folder through the file provider
 * (about 10 s for a large library). The library built from it uses the song cache as usual; a full
 * scan follows straight away and replaces it if anything changed.
 *
 * Only what a [File] needs is kept: its URI, its path below the folder it was found in, MIME type,
 * size and modification time. Tags come from the cache, as on any start.
 */
class LibrarySnapshot(context: Context) {
    private val file = context.filesDir.resolve(FILE_NAME)

    /** The files found under [locations], or null if there is no snapshot for exactly these. */
    fun load(locations: List<Location.Opened>): List<File>? {
        if (!file.exists() || locations.isEmpty()) return null
        return try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != VERSION) return null
                val saved = List(input.readInt()) { input.readUTF() }
                if (saved != locations.map { it.uri.toString() }) return null
                List(input.readInt()) {
                    val location = locations[input.readInt()]
                    val uri = Uri.parse(input.readUTF())
                    var path = location.path
                    repeat(input.readInt()) { path = path.file(input.readUTF()) }
                    File(
                        uri = uri,
                        path = path,
                        addedMs = UnknownAddedMs,
                        modifiedMs = input.readLong(),
                        mimeType = input.readUTF(),
                        size = input.readLong(),
                        parent = null,
                    )
                }
            }
        } catch (e: Exception) {
            L.w("Can't read the library snapshot: $e")
            null
        }
    }

    fun save(locations: List<Location.Opened>, files: List<File>) {
        val temp = file.resolveSibling("$FILE_NAME.tmp")
        try {
            DataOutputStream(BufferedOutputStream(temp.outputStream())).use { out ->
                out.writeInt(VERSION)
                out.writeInt(locations.size)
                locations.forEach { out.writeUTF(it.uri.toString()) }
                val kept = files.mapNotNull { f -> locate(locations, f.path)?.let { f to it } }
                out.writeInt(kept.size)
                for ((f, index) in kept) {
                    val below = f.path.components.components.drop(
                        locations[index].path.components.components.size,
                    )
                    out.writeInt(index)
                    out.writeUTF(f.uri.toString())
                    out.writeInt(below.size)
                    below.forEach(out::writeUTF)
                    out.writeLong(f.modifiedMs)
                    out.writeUTF(f.mimeType)
                    out.writeLong(f.size)
                }
            }
            if (!temp.renameTo(file)) error("Can't replace $file")
            L.d("Saved a library snapshot of ${files.size} files")
        } catch (e: Exception) {
            L.e("Can't save the library snapshot: $e")
            temp.delete()
        }
    }

    fun clear() {
        file.delete()
    }

    /** Which of [locations] holds [path]: the deepest one, if they nest. */
    private fun locate(locations: List<Location.Opened>, path: Path): Int? =
        locations.indices
            .filter {
                val root = locations[it].path
                root.volume == path.volume && root.components.contains(path.components)
            }
            .maxByOrNull { locations[it].path.components.components.size }

    private object UnknownAddedMs : AddedMs {
        // Known songs take their date added from the cache; this is only asked for a song the
        // cache doesn't have, and the full scan that follows supplies the real date.
        override suspend fun resolve(): Long? = null
    }

    private companion object {
        const val FILE_NAME = "ws_library_snapshot"
        const val VERSION = 1
    }
}

/** Hands out the files of a [LibrarySnapshot] instead of walking any folder. */
class SnapshotFS(private val files: List<File>) : FS {
    override suspend fun explore(files: Channel<File>): Deferred<Result<Unit>> {
        for (f in this.files) files.send(f)
        files.close()
        return CompletableDeferred(Result.success(Unit))
    }

    override fun track(): Flow<FSUpdate> = emptyFlow()
}

/** Passes on everything [inner] finds, and hands the complete list to [onComplete]. */
class RecordingFS(private val inner: FS, private val onComplete: (List<File>) -> Unit) : FS {
    override suspend fun explore(files: Channel<File>): Deferred<Result<Unit>> = coroutineScope {
        val found = Channel<File>(Channel.UNLIMITED)
        val recorded = ArrayList<File>()
        val forward =
            async(Dispatchers.IO) {
                // The inner walk closes its channel when done, with its error if it failed.
                try {
                    for (f in found) {
                        recorded += f
                        files.send(f)
                    }
                    null
                } catch (e: Exception) {
                    e
                }
            }
        val result = inner.explore(found).await()
        forward.await()?.let { e ->
            files.close(e)
            return@coroutineScope CompletableDeferred(Result.failure<Unit>(e))
        }
        result.onSuccess { onComplete(recorded) }
        files.close(result.exceptionOrNull())
        CompletableDeferred(result)
    }

    override fun track() = inner.track()
}

/**
 * The song cache as it is: the library built from a snapshot reads it but writes nothing, so a
 * song it couldn't find (with no date added, see [LibrarySnapshot]) is never saved that way.
 */
class ReadOnlyCache(private val inner: MutableCache) : MutableCache {
    override suspend fun read(file: File): CacheResult = inner.read(file)

    override suspend fun write(cachedFile: CachedFile) {}

    override suspend fun writeAll(cachedFiles: List<CachedFile>) {}

    override suspend fun cleanup(excluding: List<CachedFile>) {}
}
