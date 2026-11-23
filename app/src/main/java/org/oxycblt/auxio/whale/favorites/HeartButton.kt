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
import android.content.res.ColorStateList
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.oxycblt.auxio.R

/** Shows a toolbar button as a coral filled heart when [on], or an outlined one when not. */
object HeartButton {
    fun update(context: Context, button: MaterialButton, on: Boolean, label: CharSequence) {
        // The button tints its icon; the coral heart keeps its own colour.
        val tint =
            (button.getTag(R.id.ws_heart_tint) as? ColorStateList)
                ?: button.iconTint?.also { button.setTag(R.id.ws_heart_tint, it) }
        // Loaded with the screen's [context] and theme, as the menu's own icons are.
        button.icon =
            ContextCompat.getDrawable(
                context,
                if (on) R.drawable.ws_ic_favorite_on_24 else R.drawable.ic_favorite_border_24,
            )
        button.iconTint = if (on) null else tint
        button.contentDescription = label
        TooltipCompat.setTooltipText(button, label)
    }
}
