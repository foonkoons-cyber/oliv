package com.depthmaker.app.toon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateGateTest {

    private fun kept(sourceFps: Int, targetFps: Int, frames: Int): Int {
        val gate = FrameRateGate(targetFps)
        val step = 1_000_000L / sourceFps
        return (0 until frames).count { gate.accept(it * step) }
    }

    @Test
    fun `matching rates keep every frame`() {
        assertEquals(60, kept(sourceFps = 60, targetFps = 60, frames = 60))
        assertEquals(30, kept(sourceFps = 30, targetFps = 30, frames = 30))
    }

    @Test
    fun `60fps source into a 30fps target keeps about half`() {
        val keptCount = kept(sourceFps = 60, targetFps = 30, frames = 120)
        assertTrue("expected ~60, got $keptCount", keptCount in 58..62)
    }

    @Test
    fun `a slower source is never padded up`() {
        assertEquals(24, kept(sourceFps = 24, targetFps = 60, frames = 24))
    }

    @Test
    fun `the first frame is always kept`() {
        assertTrue(FrameRateGate(30).accept(123_456L))
    }
}
