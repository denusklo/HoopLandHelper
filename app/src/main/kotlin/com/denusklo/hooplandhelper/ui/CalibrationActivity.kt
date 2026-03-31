package com.denusklo.hooplandhelper.ui

import android.content.Context
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

    private enum class Step { PLACE_POINTS, PREVIEW, SHOOT_BUTTON, DONE }

    private lateinit var tvInstruction: TextView
    private lateinit var btnUndo: Button
    private lateinit var btnConfirm: Button
    private lateinit var btnRedo: Button
    private lateinit var rectPreview: View
    private lateinit var rectBorder: View
    private lateinit var repo: CalibrationRepository

    private val markers = mutableListOf<TextView>()
    private var step = Step.PLACE_POINTS
    private val points = mutableListOf<Pair<Int, Int>>()
    private var barLeft = 0; private var barTop = 0
    private var barRight = 0; private var barBottom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        repo = CalibrationRepository(getSharedPreferences("calibration", Context.MODE_PRIVATE))
        tvInstruction = findViewById(R.id.tv_instruction)
        btnUndo = findViewById(R.id.btn_undo)
        btnConfirm = findViewById(R.id.btn_confirm)
        btnRedo = findViewById(R.id.btn_redo)
        rectPreview = findViewById(R.id.rect_preview)
        rectBorder = findViewById(R.id.rect_border)

        markers.add(findViewById(R.id.marker_1))
        markers.add(findViewById(R.id.marker_2))
        markers.add(findViewById(R.id.marker_3))
        markers.add(findViewById(R.id.marker_4))

        markers.forEachIndexed { index, marker -> setupMarkerDrag(marker, index) }

        btnUndo.setOnClickListener { undoPoint() }

        btnConfirm.setOnClickListener {
            if (step == Step.PREVIEW) {
                step = Step.SHOOT_BUTTON
                rectPreview.visibility = View.GONE
                rectBorder.visibility = View.GONE
                btnConfirm.visibility = View.GONE
                btnRedo.visibility = View.GONE
                updateInstruction()
            }
        }

        btnRedo.setOnClickListener {
            if (step == Step.PREVIEW) resetState()
        }

        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            repo.clearAll()
            resetState()
            Toast.makeText(this, "Calibration cleared", Toast.LENGTH_SHORT).show()
        }

        window.decorView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) handleTap(event.rawX.toInt(), event.rawY.toInt())
            true
        }
    }

    private fun setupMarkerDrag(marker: TextView, index: Int) {
        var startRawX = 0f
        var startRawY = 0f
        var startMarkerX = 0f
        var startMarkerY = 0f
        var dragged = false

        marker.setOnTouchListener { _, event ->
            if (step != Step.PLACE_POINTS && step != Step.PREVIEW) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startMarkerX = marker.x
                    startMarkerY = marker.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) dragged = true
                    if (dragged) {
                        marker.x = startMarkerX + dx
                        marker.y = startMarkerY + dy
                        val newX = (marker.x + 22).toInt()
                        val newY = (marker.y + 22).toInt()
                        if (index < points.size) {
                            points[index] = Pair(newX, newY)
                        }
                        if (points.size == 4) {
                            recalcBBox()
                            showRectPreview()
                            if (step == Step.PLACE_POINTS) {
                                step = Step.PREVIEW
                                btnConfirm.visibility = View.VISIBLE
                                btnRedo.visibility = View.VISIBLE
                            }
                            updateInstruction()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    true
                }
                else -> false
            }
        }
    }

    private fun handleTap(x: Int, y: Int) {
        when (step) {
            Step.PLACE_POINTS -> {
                if (points.size < 4) {
                    points.add(Pair(x, y))
                    val idx = points.size - 1
                    markers[idx].visibility = View.VISIBLE
                    markers[idx].x = x.toFloat() - 22
                    markers[idx].y = y.toFloat() - 22

                    if (points.size == 4) {
                        recalcBBox()
                        showRectPreview()
                        step = Step.PREVIEW
                        btnConfirm.visibility = View.VISIBLE
                        btnRedo.visibility = View.VISIBLE
                    }
                }
                updateInstruction()
            }
            Step.SHOOT_BUTTON -> {
                val region = BarRegion(barLeft, barTop, barRight, barBottom)
                repo.saveBarRegion(region)
                repo.saveGreenHsv(HsvRange(hue = 120f, saturation = 0.7f, value = 0.7f))
                repo.saveShootPosition(ShootPosition(x, y))
                step = Step.DONE
                updateInstruction()
                Toast.makeText(this, "Calibration complete!", Toast.LENGTH_SHORT).show()
                finish()
            }
            Step.PREVIEW, Step.DONE -> {}
        }
    }

    private fun undoPoint() {
        if (points.isNotEmpty() && step == Step.PLACE_POINTS) {
            points.removeAt(points.size - 1)
            markers[points.size].visibility = View.GONE
            updateInstruction()
        }
    }

    private fun recalcBBox() {
        barLeft = points.minOf { it.first }
        barTop = points.minOf { it.second }
        barRight = points.maxOf { it.first }
        barBottom = points.maxOf { it.second }
    }

    private fun showRectPreview() {
        val width = barRight - barLeft
        val height = barBottom - barTop
        if (width > 0 && height > 0) {
            rectPreview.x = barLeft.toFloat()
            rectPreview.y = barTop.toFloat()
            val lp = rectPreview.layoutParams
            lp.width = width
            lp.height = height
            rectPreview.layoutParams = lp
            rectPreview.visibility = View.VISIBLE

            rectBorder.x = barLeft.toFloat()
            rectBorder.y = barTop.toFloat()
            val lp2 = rectBorder.layoutParams
            lp2.width = width
            lp2.height = height
            rectBorder.layoutParams = lp2
            rectBorder.visibility = View.VISIBLE
        }
    }

    private fun resetState() {
        step = Step.PLACE_POINTS
        points.clear()
        barLeft = 0; barTop = 0; barRight = 0; barBottom = 0
        markers.forEach { it.visibility = View.GONE }
        rectPreview.visibility = View.GONE
        rectBorder.visibility = View.GONE
        btnUndo.visibility = View.GONE
        btnConfirm.visibility = View.GONE
        btnRedo.visibility = View.GONE
        updateInstruction()
    }

    private fun updateInstruction() {
        tvInstruction.text = when (step) {
            Step.PLACE_POINTS -> "Tap point ${points.size + 1} of 4 on the shooting bar"
            Step.PREVIEW      -> "Drag markers to adjust. Confirm or Redo"
            Step.SHOOT_BUTTON -> "Tap the SHOOT button in Hoop Land"
            Step.DONE         -> "Done!"
        }
        btnUndo.visibility = if (step == Step.PLACE_POINTS && points.isNotEmpty()) View.VISIBLE else View.GONE
    }
}
