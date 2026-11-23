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
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import org.oxycblt.auxio.R
import org.oxycblt.musikr.Album

/**
 * A small heart shown on a favorite album's cover. It follows [Favorites] by itself, so lists
 * don't need rebinding when an album is added or removed.
 */
class FavoriteBadge
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    AppCompatImageView(context, attrs, defStyleAttr) {
    private val listener = ::refresh

    /** The album this badge belongs to, or null for anything that isn't an album. */
    var album: Album? = null
        set(value) {
            field = value
            refresh()
        }

    init {
        setBackgroundResource(R.drawable.ws_favorite_badge)
        setImageResource(R.drawable.ws_ic_favorite_on_24)
        scaleType = ScaleType.FIT_CENTER
        contentDescription = context.getString(R.string.ws_lbl_favorite)
        isVisible = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Room around the heart in proportion to the badge, whatever its size.
        val inset = (w * 0.22f).toInt()
        setPadding(inset, inset, inset, inset)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Favorites.addListener(listener)
        refresh()
    }

    override fun onDetachedFromWindow() {
        Favorites.removeListener(listener)
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        isVisible = album?.let(Favorites::isFavorite) == true
    }
}
