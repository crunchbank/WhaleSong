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

package org.oxycblt.auxio.whale.covers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.IntegerTable
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.WsItemCoverBinding
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.music.MusicType
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.context
import org.oxycblt.auxio.util.getPlural
import org.oxycblt.auxio.util.inflater
import org.oxycblt.auxio.util.isRtl
import org.oxycblt.auxio.util.setFullWidthLookup
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Playlist

/**
 * Whether each library tab (albums, artists, genres, playlists) shows a list or a grid of covers.
 * Albums start as covers and the other tabs as lists; each tab's choice is kept in the preferences
 * and switched from the home toolbar. An artist's albums and a genre's artists follow the Albums
 * and Artists tabs.
 */
object CoverGrid {
    private val states = HashMap<MusicType, MutableStateFlow<Boolean>>()

    /** Songs stay a list: their covers would only repeat their albums'. */
    fun supports(type: MusicType) = type != MusicType.SONGS

    private fun key(context: Context, type: MusicType) =
        context.getString(
            when (type) {
                MusicType.ALBUMS -> R.string.ws_key_album_covers
                MusicType.ARTISTS -> R.string.ws_key_artist_covers
                MusicType.GENRES -> R.string.ws_key_genre_covers
                MusicType.PLAYLISTS -> R.string.ws_key_playlist_covers
                MusicType.SONGS -> error("Songs have no cover grid")
            },
        )

    fun state(context: Context, type: MusicType): StateFlow<Boolean> =
        states.getOrPut(type) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            // Albums start as covers, the other tabs as lists.
            MutableStateFlow(prefs.getBoolean(key(context, type), type == MusicType.ALBUMS))
        }

    fun toggle(context: Context, type: MusicType) {
        val state = state(context, type) as MutableStateFlow
        state.value = !state.value
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit {
            putBoolean(key(context, type), state.value)
        }
    }

    /** Two columns on a phone, more as the screen widens, each cover about 180 dp or more. */
    fun columns(context: Context) = max(2, context.resources.configuration.screenWidthDp / 180)

    /** Puts a home tab's grid [recycler] and its [adapter] under [type]'s list/covers switch. */
    fun bindHome(fragment: Fragment, recycler: RecyclerView, type: MusicType, adapter: CoverAdapter<*>) {
        val layout = recycler.layoutManager as GridLayoutManager
        // The columns the list itself uses (more than one only on a wide screen).
        val listSpan = layout.spanCount
        recycler.addItemDecoration(CoverSpacing(recycler.context))
        fragment.collectImmediately(state(fragment.requireContext(), type)) { covers ->
            layout.spanCount = if (covers) columns(recycler.context) else listSpan
            adapter.covers = covers
        }
    }

    /**
     * Puts the [tile] items of a detail list (an artist's albums, a genre's artists) under [type]'s
     * switch; every other item (headers, songs) keeps the full width.
     */
    fun bindDetail(
        fragment: Fragment,
        recycler: RecyclerView,
        type: MusicType,
        adapter: Coverable,
        tile: (Int) -> Boolean,
    ) {
        val layout = GridLayoutManager(recycler.context, 1)
        layout.setFullWidthLookup { !adapter.covers || !tile(it) }
        recycler.layoutManager = layout
        recycler.addItemDecoration(CoverSpacing(recycler.context))
        fragment.collectImmediately(state(fragment.requireContext(), type)) { covers ->
            layout.spanCount = if (covers) columns(recycler.context) else 1
            adapter.covers = covers
        }
    }
}

/** An adapter that can draw its items as a list or as cover tiles. */
interface Coverable {
    var covers: Boolean
}

/** The line under a tile's name, matching what the list rows show. */
object CoverInfo {
    fun album(context: Context, album: Album): CharSequence = album.artists.resolveNames(context)

    fun albumDate(context: Context, album: Album): CharSequence =
        album.dates?.resolve(context) ?: context.getString(R.string.def_date)

    fun artist(context: Context, artist: Artist): CharSequence =
        context.getString(
            R.string.fmt_two,
            if (artist.explicitAlbums.isNotEmpty()) {
                context.getPlural(R.plurals.fmt_album_count, artist.explicitAlbums.size)
            } else {
                context.getString(R.string.def_album_count)
            },
            if (artist.songs.isNotEmpty()) {
                context.getPlural(R.plurals.fmt_song_count, artist.songs.size)
            } else {
                context.getString(R.string.def_song_count)
            },
        )

    fun genre(context: Context, genre: Genre): CharSequence =
        context.getString(
            R.string.fmt_two,
            context.getPlural(R.plurals.fmt_artist_count, genre.artists.size),
            context.getPlural(R.plurals.fmt_song_count, genre.songs.size),
        )

    fun playlist(context: Context, playlist: Playlist): CharSequence =
        if (playlist.songs.isNotEmpty()) {
            context.getPlural(R.plurals.fmt_song_count, playlist.songs.size)
        } else {
            context.getString(R.string.def_song_count)
        }
}

/** One album, artist, genre or playlist as a tile: its cover, with name and a line beneath. */
class CoverViewHolder private constructor(private val binding: WsItemCoverBinding) :
    SelectionIndicatorAdapter.ViewHolder(binding.root) {

    fun <T : MusicParent> bind(item: T, listener: SelectableListListener<T>, info: CharSequence) {
        listener.bind(item, this, menuButton = binding.coverMenu)
        when (item) {
            is Album -> binding.coverImage.bind(item)
            is Artist -> binding.coverImage.bind(item)
            is Genre -> binding.coverImage.bind(item)
            is Playlist -> binding.coverImage.bind(item)
        }
        binding.coverName.text = item.name.resolve(binding.context)
        binding.coverInfo.text = info
        binding.coverFavorite.album = item as? Album
    }

    override fun updatePlayingIndicator(isActive: Boolean, isPlaying: Boolean) {
        binding.root.isSelected = isActive
        binding.coverImage.setPlaying(isPlaying)
    }

    override fun updateSelectionIndicator(isSelected: Boolean) {
        binding.root.isActivated = isSelected
    }

    companion object {
        const val VIEW_TYPE = IntegerTable.VIEW_TYPE_COVER_TILE

        fun from(parent: ViewGroup) =
            CoverViewHolder(WsItemCoverBinding.inflate(parent.context.inflater, parent, false))
    }
}

/**
 * A home tab's adapter: Auxio's list rows ([createRow], [bindRow]) or cover tiles, by [covers].
 */
class CoverAdapter<T : MusicParent>(
    private val listener: SelectableListListener<T>,
    diffCallback: DiffUtil.ItemCallback<T>,
    private val rowType: Int,
    private val createRow: (ViewGroup) -> SelectionIndicatorAdapter.ViewHolder,
    private val bindRow: (SelectionIndicatorAdapter.ViewHolder, T) -> Unit,
    private val info: (Context, T) -> CharSequence,
) : SelectionIndicatorAdapter<T, SelectionIndicatorAdapter.ViewHolder>(diffCallback), Coverable {
    override var covers = false
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int) =
        if (covers) CoverViewHolder.VIEW_TYPE else rowType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        if (viewType == CoverViewHolder.VIEW_TYPE) CoverViewHolder.from(parent) else createRow(parent)

    override fun onBindViewHolder(holder: SelectionIndicatorAdapter.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is CoverViewHolder) {
            holder.bind(item, listener, info(holder.itemView.context, item))
        } else {
            bindRow(holder, item)
        }
    }
}

/**
 * Even gaps around cover tiles: the same space at the screen's edges as between columns, with
 * every column the same width. Other items (list rows, headers, songs) are left alone.
 */
class CoverSpacing(context: Context) : RecyclerView.ItemDecoration() {
    private val space = context.resources.getDimensionPixelSize(R.dimen.spacing_mid_medium)

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        if (parent.getChildViewHolder(view) !is CoverViewHolder) return
        val params = view.layoutParams as? GridLayoutManager.LayoutParams ?: return
        val columns = (parent.layoutManager as? GridLayoutManager)?.spanCount ?: return
        val column = params.spanIndex
        val start = space * (columns - column) / columns
        val end = space * (column + 1) / columns
        val half = space / 2
        if (parent.isRtl) outRect.set(end, half, start, half) else outRect.set(start, half, end, half)
    }
}
