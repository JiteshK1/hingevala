package com.hingevala

import android.graphics.Color

/** The app's colours: a dark HUD with Tinder's pink-to-orange gradient as the accent. */
object Theme {
    val bg = Color.parseColor("#0A0A12")
    val surface = Color.parseColor("#14141F")
    val surface2 = Color.parseColor("#1D1D2B")
    val line = Color.parseColor("#2A2A3C")
    val text = Color.parseColor("#F2F2F7")
    val muted = Color.parseColor("#8B8BA0")
    val pink = Color.parseColor("#FD267D")
    val orange = Color.parseColor("#FF7854")
    val cyan = Color.parseColor("#39D0FF")
    val green = Color.parseColor("#3DDC97")
    val amber = Color.parseColor("#FFB020")
    val red = Color.parseColor("#FF4D67")
    val gradient = intArrayOf(pink, orange)

    fun alpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}
