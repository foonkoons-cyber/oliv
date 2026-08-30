package com.depthmaker.app.toon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlannerTest {

    private val anything = EncoderLimits { _, _, _ -> true }
    private val nothing = EncoderLimits { _, _, _ -> false }

    @Test
    fun `1080p60 source targets 720p60`() {
        val plan = ExportPlanner.plan(1920, 1080, 60f, ResolutionPreset.P720, FpsPreset.UP_TO_60, anything)
        assertEquals(1280, plan.width)
        assertEquals(720, plan.height)
        assertEquals(60, plan.fps)
        assertNull(plan.downgradeReason)
    }

    @Test
    fun `portrait keeps its orientation`() {
        val plan = ExportPlanner.plan(1080, 1920, 30f, ResolutionPreset.P720, FpsPreset.UP_TO_60, anything)
        assertEquals(720, plan.width)
        assertEquals(1280, plan.height)
    }

    @Test
    fun `a 30fps source never exports at 60`() {
        val plan = ExportPlanner.plan(1280, 720, 30f, ResolutionPreset.P720, FpsPreset.UP_TO_60, anything)
        assertEquals(30, plan.fps)
    }

    @Test
    fun `source preset never upscales`() {
        val plan = ExportPlanner.plan(640, 480, 30f, ResolutionPreset.SOURCE, FpsPreset.UP_TO_60, anything)
        assertEquals(640, plan.width)
        assertEquals(480, plan.height)
    }

    @Test
    fun `odd dimensions are rounded down to even`() {
        val (w, h) = ExportPlanner.scaleTo(1081, 607, 0)
        assertEquals(1080, w)
        assertEquals(606, h)
    }

    @Test
    fun `aspect ratio survives the downscale`() {
        val (w, h) = ExportPlanner.scaleTo(1920, 1080, 1280)
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun `encoder without 60fps support falls back to 30 and says so`() {
        val only30 = EncoderLimits { _, _, fps -> fps <= 30 }
        val plan = ExportPlanner.plan(1920, 1080, 60f, ResolutionPreset.P720, FpsPreset.UP_TO_60, only30)
        assertEquals(1280, plan.width)
        assertEquals(30, plan.fps)
        assertNotNull(plan.downgradeReason)
    }

    @Test
    fun `oversized source falls back to 720p when the encoder refuses it`() {
        val only720 = EncoderLimits { w, _, _ -> w <= 1280 }
        val plan = ExportPlanner.plan(3840, 2160, 30f, ResolutionPreset.SOURCE, FpsPreset.CAP_30, only720)
        assertEquals(1280, plan.width)
        assertEquals(720, plan.height)
        assertNotNull(plan.downgradeReason)
    }

    @Test
    fun `an encoder that supports nothing still produces a usable plan`() {
        val plan = ExportPlanner.plan(1920, 1080, 60f, ResolutionPreset.P720, FpsPreset.UP_TO_60, nothing)
        assertEquals(1280, plan.width)
        assertEquals(30, plan.fps)
        assertNotNull(plan.downgradeReason)
    }

    @Test
    fun `bitrate scales with pixels and rate and stays inside the clamp`() {
        val low = ExportPlanner.bitRateFor(320, 240, 24)
        val mid = ExportPlanner.bitRateFor(1280, 720, 60)
        val high = ExportPlanner.bitRateFor(3840, 2160, 60)
        assertEquals(3_000_000, low)
        assertTrue("720p60 should land above the floor", mid > 3_000_000)
        assertEquals(20_000_000, high)
    }

    @Test
    fun `unknown source fps defaults to 30`() {
        val plan = ExportPlanner.plan(1280, 720, 0f, ResolutionPreset.P720, FpsPreset.UP_TO_60, anything)
        assertEquals(30, plan.fps)
    }

    @Test
    fun `filter ids round-trip and fall back to cartoon`() {
        assertEquals(ToonFilter.SKETCH, ToonFilter.fromId("SKETCH"))
        assertEquals(ToonFilter.CARTOON, ToonFilter.fromId(null))
        assertEquals(ToonFilter.CARTOON, ToonFilter.fromId("nonsense"))
        assertFalse(ToonFilter.entries.any { it.fragmentShader.isBlank() })
    }
}
