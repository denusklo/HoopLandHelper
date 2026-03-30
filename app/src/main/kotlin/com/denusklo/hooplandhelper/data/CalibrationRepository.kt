package com.denusklo.hooplandhelper.data

import android.content.SharedPreferences

class CalibrationRepository(private val prefs: SharedPreferences) {

    fun isCalibrated(): Boolean =
        prefs.contains("bar_left") && prefs.contains("green_hue") && prefs.contains("shoot_x")

    fun saveBarRegion(region: BarRegion) {
        prefs.edit()
            .putInt("bar_left", region.left)
            .putInt("bar_top", region.top)
            .putInt("bar_right", region.right)
            .putInt("bar_bottom", region.bottom)
            .apply()
    }

    fun loadBarRegion(): BarRegion? {
        if (!prefs.contains("bar_left")) return null
        return BarRegion(
            left = prefs.getInt("bar_left", 0),
            top = prefs.getInt("bar_top", 0),
            right = prefs.getInt("bar_right", 0),
            bottom = prefs.getInt("bar_bottom", 0)
        )
    }

    fun saveGreenHsv(hsv: HsvRange) {
        prefs.edit()
            .putFloat("green_hue", hsv.hue)
            .putFloat("green_sat", hsv.saturation)
            .putFloat("green_val", hsv.value)
            .apply()
    }

    fun loadGreenHsv(): HsvRange? {
        if (!prefs.contains("green_hue")) return null
        return HsvRange(
            hue = prefs.getFloat("green_hue", 120f),
            saturation = prefs.getFloat("green_sat", 0.7f),
            value = prefs.getFloat("green_val", 0.8f)
        )
    }

    fun saveShootPosition(pos: ShootPosition) {
        prefs.edit()
            .putInt("shoot_x", pos.x)
            .putInt("shoot_y", pos.y)
            .apply()
    }

    fun loadShootPosition(): ShootPosition? {
        if (!prefs.contains("shoot_x")) return null
        return ShootPosition(
            x = prefs.getInt("shoot_x", 0),
            y = prefs.getInt("shoot_y", 0)
        )
    }

    fun clearAll() = prefs.edit().clear().apply()
}
