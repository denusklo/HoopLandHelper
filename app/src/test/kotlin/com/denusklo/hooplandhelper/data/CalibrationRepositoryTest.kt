package com.denusklo.hooplandhelper.data

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class CalibrationRepositoryTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var repo: CalibrationRepository

    @Before
    fun setUp() {
        prefs = mock()
        editor = mock()
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putInt(any(), any())).thenReturn(editor)
        whenever(editor.putFloat(any(), any())).thenReturn(editor)
        repo = CalibrationRepository(prefs)
    }

    @Test
    fun `isCalibrated returns false when bar region key missing`() {
        whenever(prefs.contains("bar_left")).thenReturn(false)
        assertFalse(repo.isCalibrated())
    }

    @Test
    fun `isCalibrated returns true when all keys present`() {
        whenever(prefs.contains("bar_left")).thenReturn(true)
        whenever(prefs.contains("green_hue")).thenReturn(true)
        whenever(prefs.contains("shoot_x")).thenReturn(true)
        assertTrue(repo.isCalibrated())
    }

    @Test
    fun `loadBarRegion returns null when not saved`() {
        whenever(prefs.contains("bar_left")).thenReturn(false)
        assertNull(repo.loadBarRegion())
    }

    @Test
    fun `loadBarRegion returns saved values`() {
        whenever(prefs.contains("bar_left")).thenReturn(true)
        whenever(prefs.getInt(eq("bar_left"), any())).thenReturn(10)
        whenever(prefs.getInt(eq("bar_top"), any())).thenReturn(20)
        whenever(prefs.getInt(eq("bar_right"), any())).thenReturn(800)
        whenever(prefs.getInt(eq("bar_bottom"), any())).thenReturn(40)
        val region = repo.loadBarRegion()
        assertNotNull(region)
        assertEquals(10, region!!.left)
        assertEquals(20, region.top)
        assertEquals(800, region.right)
        assertEquals(40, region.bottom)
    }

    @Test
    fun `loadShootPosition returns null when not saved`() {
        whenever(prefs.contains("shoot_x")).thenReturn(false)
        assertNull(repo.loadShootPosition())
    }

    @Test
    fun `loadShootPosition returns saved values`() {
        whenever(prefs.contains("shoot_x")).thenReturn(true)
        whenever(prefs.getInt(eq("shoot_x"), any())).thenReturn(500)
        whenever(prefs.getInt(eq("shoot_y"), any())).thenReturn(800)
        val pos = repo.loadShootPosition()
        assertNotNull(pos)
        assertEquals(500, pos!!.x)
        assertEquals(800, pos.y)
    }
}
