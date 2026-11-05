package io.github.xororz.localdream.canvas

import android.content.Context
import android.content.SharedPreferences

/**
 * Fork options shown in Settings → Local Dream Canvas Options. They live in
 * the same "canvas_prefs" file as the editor state, and the editor reads them
 * when the canvas opens.
 */
object CanvasOptions {
    const val PREFS = "canvas_prefs"

    const val KEY_EYEDROPPER_LONG_PRESS = "eyedropper_long_press"
    const val KEY_EYEDROPPER_LOUPE = "eyedropper_loupe"
    const val KEY_LOUPE_ZOOM = "eyedropper_loupe_zoom"
    const val KEY_EXPORT_TRANSPARENT = "export_transparent"

    const val DEFAULT_LONG_PRESS = true
    const val DEFAULT_LOUPE = true
    const val DEFAULT_LOUPE_ZOOM = 8
    val LOUPE_ZOOMS = listOf(4, 6, 8, 12)

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
