package com.denusklo.hooplandhelper.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.denusklo.hooplandhelper.R
import com.denusklo.hooplandhelper.data.*

class CalibrationActivity : AppCompatActivity() {

    private enum class Step { BAR_LEFT, BAR_RIGHT, GREEN_COLOR, SHOOT_BUTTON, DONE }

    private lateinit var tvInstruction: TextView
    private lateinit var crosshair: View
    private lateinit var repo: CalibrationRepository

    private var step = Step.BAR_LEFT
    private var barLeft = 0; private var barTop = 0
    private var barRight = 0; private var barBottom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        repo = CalibrationRepository(getSharedPreferences("calibration", Context.MODE_PRIVATE))
        tvInstruction = findViewById(R.id.tv_instruction)
        crosshair = findViewById(R.id.crosshair)

        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            repo.clearAll()
            step = Step.BAR_LEFT
            updateInstruction()
            Toast.makeText(this, "Calibration cleared", Toast.LENGTH_SHORT).show()
        }

        window.decorView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) handleTap(event.rawX.toInt(), event.rawY.toInt())
            true
        }
    }

    private fun handleTap(x: Int, y: Int) {
        showCrosshair(x, y)
        when (step) {
            Step.BAR_LEFT  -> { barLeft = x; barTop = y; step = Step.BAR_RIGHT }
            Step.BAR_RIGHT -> {
                barRight = x; barBottom = y + 20
                repo.saveBarRegion(BarRegion(barLeft, barTop, barRight, barBottom))
                step = Step.GREEN_COLOR
            }
            Step.GREEN_COLOR -> {
                val sampledColor = com.denusklo.hooplandhelper.service.ScreenCaptureService.instance
                    ?.acquireBarFrame()
                    ?.let { (_, _, getPixel) -> getPixel((x - barLeft).coerceAtLeast(0), (y - barTop).coerceAtLeast(0)) }
                    ?: Color.rgb(0, 200, 0)
                val hsv = FloatArray(3)
                Color.colorToHSV(sampledColor, hsv)
                repo.saveGreenHsv(HsvRange(hue = hsv[0], saturation = hsv[1], value = hsv[2]))
                step = Step.SHOOT_BUTTON
            }
            Step.SHOOT_BUTTON -> {
                repo.saveShootPosition(ShootPosition(x, y))
                step = Step.DONE
            }
            Step.DONE -> {}
        }
        updateInstruction()
        if (step == Step.DONE) {
            Toast.makeText(this, "Calibration complete!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showCrosshair(x: Int, y: Int) {
        crosshair.visibility = View.VISIBLE
        crosshair.x = x.toFloat() - 10
        crosshair.y = y.toFloat() - 10
    }

    private fun updateInstruction() {
        tvInstruction.text = when (step) {
            Step.BAR_LEFT    -> "Tap the LEFT edge of the shooting meter bar"
            Step.BAR_RIGHT   -> "Tap the RIGHT edge of the shooting meter bar"
            Step.GREEN_COLOR -> "Tap a GREEN pixel in the meter (the perfect zone color)"
            Step.SHOOT_BUTTON-> "Tap the SHOOT button in Hoop Land"
            Step.DONE        -> "Done!"
        }
    }
}
