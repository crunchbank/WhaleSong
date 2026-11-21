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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Song

/**
 * The Unplayed list: songs never listened to past the counts-as-played point (see
 * [PlayCounter]), most recently added first. It updates as songs get played.
 */
@HiltViewModel
class UnplayedViewModel @Inject constructor(private val musicRepository: MusicRepository) :
    ViewModel(), MusicRepository.UpdateListener {
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    init {
        musicRepository.addUpdateListener(this)
        viewModelScope.launch { PlayCounts.changes.collect { refresh() } }
    }

    override fun onCleared() {
        musicRepository.removeUpdateListener(this)
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (changes.deviceLibrary) refresh()
    }

    private fun refresh() {
        val library = musicRepository.library ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _songs.value =
                library.songs
                    .filter { PlayCounts.count(it) == 0 }
                    .sortedWith(compareByDescending<Song> { it.addedMs }.thenBy { it.name })
        }
    }
}
