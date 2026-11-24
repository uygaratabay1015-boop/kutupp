package com.kutup.navigasyon

import org.junit.Assert.*
import org.junit.Test

class AimGuideTest {
    private fun tilt(p: Float, r: Float, s: Float = 0.1f) = CameraTilt(p, r, s, 1f, true)

    @Test fun nullTiltNotReady() { assertFalse(AimGuide().evaluate(null, 0).ready) }

    @Test fun readyOnlyAfterHold() {
        val g = AimGuide()
        assertFalse(g.evaluate(tilt(65f, 1f), 0).ready)
        assertFalse(g.evaluate(tilt(65f, 1f), 1000).ready)
        val s = g.evaluate(tilt(65f, 1f), 2100)
        assertTrue(s.ready); assertEquals(1f, s.holdProgress, 1e-6f)
    }

    @Test fun shakeResetsProgress() {
        val g = AimGuide()
        g.evaluate(tilt(65f, 0f), 0)
        g.evaluate(tilt(65f, 0f, 1.0f), 1500)
        assertFalse(g.evaluate(tilt(65f, 0f), 2100).ready)
        assertTrue(g.evaluate(tilt(65f, 0f), 4200).ready)
    }

    @Test fun messagesGuideUser() {
        val g = AimGuide()
        assertTrue(g.evaluate(tilt(10f, 0f), 0).message.contains("kaldirin"))
        assertTrue(g.evaluate(tilt(85f, 0f), 0).message.contains("asagi"))
        assertFalse(g.evaluate(tilt(65f, 10f), 0).rollOk)
        assertTrue(g.evaluate(tilt(65f, 10f), 0).message.contains("sola"))
        assertTrue(g.evaluate(tilt(65f, -10f), 0).message.contains("saga"))
    }

    @Test fun rangeFollowsFov() {
        val g = AimGuide()
        g.setFov(65f); assertEquals(54.5f, g.minPitchDeg, 1e-4f); assertEquals(74.5f, g.maxPitchDeg, 1e-4f)
        g.setFov(30f); assertEquals(50f, g.minPitchDeg, 1e-4f)
        g.setFov(120f); assertEquals(70f, g.minPitchDeg, 1e-4f); assertEquals(80f, g.maxPitchDeg, 1e-4f)
    }
}
