package com.denusklo.hooplandhelper.data

data class BarRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class HsvRange(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val hueTolerance: Float = 15f,
    val satMin: Float = 0.5f,
    val valMin: Float = 0.3f
)

data class ShootPosition(val x: Int, val y: Int)
