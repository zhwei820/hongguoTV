// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.widget.Switch
import android.widget.TextView

/** Shared visual language for browsing pages, modal panels and playback controls. */
internal object TvVisual {
    val accent get() = TvStyle.accent
    val value get() = TvStyle.muted
    val primary get() = TvStyle.text
    val focusedValue get() = TvStyle.text

    fun panel(context: Context, modal: Boolean = false) = TvStyle.shape(
        context, TvStyle.surface,
        if (modal) TvStyle.outline else Color.TRANSPARENT, radius = 10,
    )

    fun row(context: Context, focused: Boolean, selected: Boolean = false, category: Boolean = false): Drawable =
        TvStyle.shape(context,
            if (focused) TvStyle.raised else if (selected || category) TvStyle.surface else Color.TRANSPARENT,
            if (focused) TvStyle.text else if (selected) TvStyle.accent else Color.TRANSPARENT,
            radius = 7)

    fun tab(context: Context, focused: Boolean, selected: Boolean): Drawable =
        TvStyle.shape(context,
            if (focused) TvStyle.raised else if (selected) TvStyle.surface else Color.TRANSPARENT,
            if (focused) TvStyle.text else if (selected) TvStyle.accent else Color.TRANSPARENT,
            radius = 7)

    fun backdrop(context: Context): Drawable = android.graphics.drawable.ColorDrawable(TvStyle.background)

}
