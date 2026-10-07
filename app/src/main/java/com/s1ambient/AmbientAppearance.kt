package com.s1ambient

import android.graphics.Color

// Local-time boundaries; night continues across midnight.
internal object AmbientAppearance {
    const val DAY_START_HOUR = 7
    const val NIGHT_START_HOUR = 21

    fun isNight(hour: Int) = hour < DAY_START_HOUR || hour >= NIGHT_START_HOUR

    data class Palette(val background: Int, val primary: Int, val secondary: Int, val muted: Int)

    val day = Palette(Color.rgb(12, 13, 14), Color.rgb(207, 209, 205),
        Color.rgb(173, 179, 176), Color.rgb(121, 133, 130))
    val night = Palette(Color.rgb(5, 5, 5), Color.rgb(137, 113, 89),
        Color.rgb(105, 91, 77), Color.rgb(82, 73, 64))
}
