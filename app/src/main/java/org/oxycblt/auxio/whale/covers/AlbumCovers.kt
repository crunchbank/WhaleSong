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

import android.content.Context
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.IntegerTable
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.WsItemAlbumCoverBinding
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.util.context
import org.oxycblt.auxio.util.inflater
import org.oxycblt.musikr.Album

/**
 * Whether the Albums tab shows a list or a grid of covers. Covers are the default; the choice is
 * kept in the preferences and switched from the home toolbar.
 */
object AlbumCovers {
    private val state = MutableStateFlow<Boolean?>(null)

    /** Whether covers are shown. Read [load] first. */
    val grid: StateFlow<Boolean?> = state

    fun load(context: Context): StateFlow<Boolean?> {
        if (state.value == null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            state.value = prefs.getBoolean(context.getString(R.string.ws_key_album_covers), true)
        }
        return state
    }

    fun toggle(context: Context) {
        val next = !(load(context).value ?: true)
        state.value = next
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit {
            putBoolean(context.getString(R.string.ws_key_album_covers), next)
        }
    }

    /** Two columns on a phone, more as the screen widens, each cover about 180 dp or more. */
    fun columns(context: Context) = max(2, context.resources.configuration.screenWidthDp / 180)
}

/** An [Album] in the cover grid: its cover, with the name and artists beneath. */
class AlbumCoverViewHolder private constructor(private val binding: WsItemAlbumCoverBinding) :
    SelectionIndicatorAdapter.ViewHolder(binding.root) {

    fun bind(album: Album, listener: SelectableListListener<Album>) {
        listener.bind(album, this, menuButton = binding.coverMenu)
        binding.coverImage.bind(album)
        binding.coverName.text = album.name.resolve(binding.context)
        binding.coverInfo.text = album.artists.resolveNames(binding.context)
    }

    override fun updatePlayingIndicator(isActive: Boolean, isPlaying: Boolean) {
        binding.root.isSelected = isActive
        binding.coverImage.setPlaying(isPlaying)
    }

    override fun updateSelectionIndicator(isSelected: Boolean) {
        binding.root.isActivated = isSelected
    }

    companion object {
        const val VIEW_TYPE = IntegerTable.VIEW_TYPE_ALBUM_COVER

        fun from(parent: ViewGroup) =
            AlbumCoverViewHolder(
                WsItemAlbumCoverBinding.inflate(parent.context.inflater, parent, false),
            )
    }
}
