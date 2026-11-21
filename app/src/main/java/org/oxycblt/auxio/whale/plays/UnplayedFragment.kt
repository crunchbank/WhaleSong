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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentUnplayedBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.detail.Show
import org.oxycblt.auxio.list.ListFragment
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.adapter.SelectionIndicatorAdapter
import org.oxycblt.auxio.list.menu.Menu
import org.oxycblt.auxio.list.recycler.SongViewHolder
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.PlaylistDecision
import org.oxycblt.auxio.music.PlaylistMessage
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.playback.PlaybackDecision
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.FadingToolbarOffsetListener
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.auxio.util.showToast
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song

/**
 * WhaleSong's Unplayed list, opened from the home screen's menu. Tapping a song plays the list
 * from that song; the toolbar plays or shuffles all of it.
 */
@AndroidEntryPoint
class UnplayedFragment : ListFragment<Song, FragmentUnplayedBinding>() {
    private val unplayedModel: UnplayedViewModel by viewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    override val listModel: ListViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    private val songAdapter = SongAdapter(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentUnplayedBinding.inflate(inflater)

    override fun getSelectionToolbar(binding: FragmentUnplayedBinding) =
        binding.unplayedSelectionToolbar

    override fun onBindingCreated(binding: FragmentUnplayedBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.unplayedAppbar.addOnOffsetChangedListener(
            FadingToolbarOffsetListener(binding.unplayedToolbar, binding.unplayedContent),
        )
        binding.unplayedNormalToolbar.apply {
            setNavigationOnClickListener { findNavController().navigateUp() }
            setOnMenuItemClickListener(this@UnplayedFragment)
        }
        binding.unplayedRecycler.adapter = songAdapter

        collectImmediately(unplayedModel.songs, ::updateSongs)
        collectImmediately(listModel.selected, ::updateSelection)
        collect(listModel.menu.flow, ::handleMenu)
        collect(musicModel.playlistDecision.flow, ::handlePlaylistDecision)
        collect(musicModel.playlistMessage.flow, ::handlePlaylistMessage)
        collectImmediately(
            playbackModel.song,
            playbackModel.parent,
            playbackModel.isPlaying,
            ::updatePlayback,
        )
        collect(playbackModel.playbackDecision.flow, ::handlePlaybackDecision)
        collect(detailModel.toShow.flow, ::handleShow)
    }

    override fun onDestroyBinding(binding: FragmentUnplayedBinding) {
        super.onDestroyBinding(binding)
        binding.unplayedNormalToolbar.setOnMenuItemClickListener(null)
        binding.unplayedRecycler.adapter = null
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (super.onMenuItemClick(item)) return true
        val songs = unplayedModel.songs.value
        return when (item.itemId) {
            R.id.action_play -> {
                if (songs.isNotEmpty()) playbackModel.play(songs)
                true
            }
            R.id.action_shuffle -> {
                if (songs.isNotEmpty()) playbackModel.shuffle(songs)
                true
            }
            else -> false
        }
    }

    override fun onRealClick(item: Song) {
        playbackModel.play(item, unplayedModel.songs.value)
    }

    override fun onOpenMenu(item: Song) {
        listModel.openMenu(R.menu.song, item, PlaySong.ByItself)
    }

    private fun updateSongs(songs: List<Song>) {
        val binding = requireBinding()
        binding.unplayedRecycler.isInvisible = songs.isEmpty()
        binding.unplayedEmpty.isVisible = songs.isEmpty() && musicModel.statistics.value != null
        binding.unplayedNormalToolbar.subtitle =
            resources.getQuantityString(R.plurals.fmt_song_count, songs.size, songs.size)
        songAdapter.update(songs, null)
    }

    private fun updateSelection(selected: List<Music>) {
        songAdapter.setSelected(selected.filterIsInstanceTo(mutableSetOf()))
        val binding = requireBinding()
        if (selected.isNotEmpty()) {
            binding.unplayedSelectionToolbar.title =
                getString(R.string.fmt_selected, selected.size)
            binding.unplayedToolbar.setVisible(R.id.unplayed_selection_toolbar)
        } else {
            binding.unplayedToolbar.setVisible(R.id.unplayed_normal_toolbar)
        }
    }

    private fun updatePlayback(song: Song?, parent: MusicParent?, isPlaying: Boolean) {
        songAdapter.setPlaying(song.takeIf { parent == null }, isPlaying)
    }

    private fun handleShow(show: Show?) {
        val directions =
            when (show) {
                is Show.SongDetails -> UnplayedFragmentDirections.showSong(show.song.uid)
                is Show.SongAlbumDetails -> UnplayedFragmentDirections.showAlbum(show.song.album.uid)
                is Show.AlbumDetails -> UnplayedFragmentDirections.showAlbum(show.album.uid)
                is Show.ArtistDetails -> UnplayedFragmentDirections.showArtist(show.artist.uid)
                is Show.SongArtistDecision ->
                    UnplayedFragmentDirections.showArtistChoices(show.song.uid)
                is Show.AlbumArtistDecision ->
                    UnplayedFragmentDirections.showArtistChoices(show.album.uid)
                is Show.GenreDetails -> UnplayedFragmentDirections.showGenre(show.genre.uid)
                is Show.PlaylistDetails ->
                    UnplayedFragmentDirections.showPlaylist(show.playlist.uid)
                null -> return
            }
        findNavController().navigateSafe(directions)
    }

    private fun handleMenu(menu: Menu?) {
        if (menu == null) return
        val directions =
            when (menu) {
                is Menu.ForSong -> UnplayedFragmentDirections.openSongMenu(menu.parcel)
                is Menu.ForAlbum -> UnplayedFragmentDirections.openAlbumMenu(menu.parcel)
                is Menu.ForArtist -> UnplayedFragmentDirections.openArtistMenu(menu.parcel)
                is Menu.ForGenre -> UnplayedFragmentDirections.openGenreMenu(menu.parcel)
                is Menu.ForPlaylist -> UnplayedFragmentDirections.openPlaylistMenu(menu.parcel)
                is Menu.ForSelection -> UnplayedFragmentDirections.openSelectionMenu(menu.parcel)
            }
        findNavController().navigateSafe(directions)
    }

    private fun handlePlaylistDecision(decision: PlaylistDecision?) {
        if (decision == null) return
        val directions =
            when (decision) {
                is PlaylistDecision.Add ->
                    UnplayedFragmentDirections.addToPlaylist(
                        decision.songs.map { it.uid }.toTypedArray(),
                    )
                is PlaylistDecision.Rename ->
                    UnplayedFragmentDirections.renamePlaylist(
                        decision.playlist.uid,
                        decision.template,
                        decision.applySongs.map { it.uid }.toTypedArray(),
                        decision.reason,
                    )
                is PlaylistDecision.Delete ->
                    UnplayedFragmentDirections.deletePlaylist(decision.playlist.uid)
                is PlaylistDecision.Export ->
                    UnplayedFragmentDirections.exportPlaylist(decision.playlist.uid)
                // Importing and creating playlists start from the Playlists tab, not here.
                is PlaylistDecision.Import,
                is PlaylistDecision.New -> {
                    musicModel.playlistDecision.consume()
                    return
                }
            }
        findNavController().navigateSafe(directions)
    }

    private fun handlePlaylistMessage(message: PlaylistMessage?) {
        if (message == null) return
        requireContext().showToast(message.stringRes)
        musicModel.playlistMessage.consume()
    }

    private fun handlePlaybackDecision(decision: PlaybackDecision?) {
        if (decision == null) return
        val directions =
            when (decision) {
                is PlaybackDecision.PlayFromArtist ->
                    UnplayedFragmentDirections.playFromArtist(decision.song.uid)
                is PlaybackDecision.PlayFromGenre ->
                    UnplayedFragmentDirections.playFromGenre(decision.song.uid)
            }
        findNavController().navigateSafe(directions)
    }

    private class SongAdapter(private val listener: SelectableListListener<Song>) :
        SelectionIndicatorAdapter<Song, SongViewHolder>(SongViewHolder.DIFF_CALLBACK) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SongViewHolder.from(parent)

        override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
            holder.bind(getItem(position), listener)
        }
    }
}
