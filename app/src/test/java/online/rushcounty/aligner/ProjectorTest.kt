package online.rushcounty.aligner

import online.rushcounty.aligner.ar.Projector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class ProjectorTest {
    private val w = 1080f
    private val h = 2160f
    private val f = 1500f

    /**
     * Device->world rotation for a phone held upright in portrait, camera level, pointing at
     * magnetic [headingDeg], rolled [rollDeg] clockwise about the camera axis, and tilted
     * [pitchDeg] (+ = camera up). Built as columns = world coords of device X, Y, Z axes.
     */
    private fun pose(headingDeg: Double, pitchDeg: Double = 0.0, rollDeg: Double = 0.0): FloatArray {
        val hd = Math.toRadians(headingDeg)
        val pt = Math.toRadians(pitchDeg)
        val rl = Math.toRadians(rollDeg)
        // Boresight (camera forward) and right/up for a level camera at heading hd.
        val fwd = doubleArrayOf(sin(hd) * cos(pt), cos(hd) * cos(pt), sin(pt))
        val right0 = doubleArrayOf(cos(hd), -sin(hd), 0.0)
        val up0 = doubleArrayOf(-sin(hd) * sin(pt), -cos(hd) * sin(pt), cos(pt))
        // Roll clockwise (as seen by the user looking at the screen) about the boresight.
        val right = DoubleArray(3) { right0[it] * cos(rl) - up0[it] * sin(rl) }
        val up = DoubleArray(3) { up0[it] * cos(rl) + right0[it] * sin(rl) }
        val z = DoubleArray(3) { -fwd[it] }
        // Row-major R with columns X=right, Y=up, Z=-forward.
        return floatArrayOf(
            right[0].toFloat(), up[0].toFloat(), z[0].toFloat(),
            right[1].toFloat(), up[1].toFloat(), z[1].toFloat(),
            right[2].toFloat(), up[2].toFloat(), z[2].toFloat(),
        )
    }

    @Test fun levelNorthPoseBasics() {
        val p = Projector(pose(0.0), 0.0, w, h, f)
        assertEquals(0.0, p.cameraHeadingTrueDeg(), 1e-4)
        assertEquals(0.0, p.cameraPitchDeg(), 1e-4)
        assertEquals(0.0, p.rollDeg(), 1e-4)
        val c = p.project(0.0, 0.0)
        assertEquals(w / 2, c.x, 0.01f)
        assertEquals(h / 2, c.y, 0.01f)
    }

    @Test fun targetToTheRightAndAbove() {
        val p = Projector(pose(0.0), 0.0, w, h, f)
        val r = p.project(10.0, 0.0)
        assertEquals(w / 2 + f * tan(Math.toRadians(10.0)).toFloat(), r.x, 0.5f)
        assertEquals(h / 2, r.y, 0.5f)
        val u = p.project(0.0, 5.0)
        assertEquals(w / 2, u.x, 0.5f)
        assertEquals(h / 2 - f * tan(Math.toRadians(5.0)).toFloat(), u.y, 0.5f)
    }

    @Test fun declinationShiftsTrueHeading() {
        // Indiana-ish: declination -4.5° (west). Camera at magnetic 284.5 => true 280.
        val p = Projector(pose(284.5), -4.5, w, h, f)
        assertEquals(280.0, p.cameraHeadingTrueDeg(), 1e-3)
        val c = p.project(280.0, 0.0)
        assertEquals(w / 2, c.x, 0.5f)
        assertEquals(h / 2, c.y, 0.5f)
    }

    @Test fun markerStaysGluedUnderPitchAndRoll() {
        // Any pose: a target exactly along the boresight must project to the center.
        for (hd in listOf(0.0, 47.0, 180.0, 281.3)) for (pt in listOf(-20.0, 0.0, 12.0)) for (rl in listOf(-30.0, 0.0, 25.0)) {
            val p = Projector(pose(hd, pt, rl), 0.0, w, h, f)
            assertEquals(hd, p.cameraHeadingTrueDeg(), 1e-3)
            assertEquals(pt, p.cameraPitchDeg(), 1e-3)
            assertEquals(rl, p.rollDeg(), 1e-3)
            val c = p.project(hd, pt)
            assertEquals(w / 2, c.x, 0.5f)
            assertEquals(h / 2, c.y, 0.5f)
            assertEquals(0.0, p.offBoresightDeg(hd, pt), 1e-3)
        }
    }

    @Test fun rollRotatesScreenOffsets() {
        // Phone rolled 90° clockwise: the top of the screen now points east, so a target to the
        // east appears straight above center, and a higher target appears to the left.
        val p = Projector(pose(0.0, 0.0, 90.0), 0.0, w, h, f)
        val r = p.project(10.0, 0.0)
        assertEquals(w / 2, r.x, 0.5f)
        assertTrue("y=${r.y}", r.y < h / 2 - 100)
        val u = p.project(0.0, 5.0)
        assertTrue("x=${u.x}", u.x < w / 2 - 100)
        assertEquals(h / 2, u.y, 0.5f)
    }

    @Test fun targetBehindIsNotInFrontAndPointsTheRightWay() {
        val p = Projector(pose(0.0), 0.0, w, h, f)
        val behindRight = p.project(150.0, 0.0)
        assertFalse(behindRight.inFront)
        assertFalse(p.isOnScreen(behindRight))
        assertTrue(behindRight.x > w) // turn right to find it
        val behindLeft = p.project(210.0, 0.0)
        assertTrue(behindLeft.x < 0)
    }

    @Test fun fillCenterFocal() {
        // 4:3 sensor 6.4 x 4.8 mm, 5 mm lens, tall 1080x2400 view: the long side fills -> 375 px/mm.
        assertEquals(1875f, Projector.focalPxForFillCenter(5.0, 6.4, 4.8, 1080f, 2400f), 0.01f)
        // Wide-ish view where the short side is limiting.
        assertEquals(5.0f * 1600f / 4.8f, Projector.focalPxForFillCenter(5.0, 6.4, 4.8, 1600f, 1800f), 0.01f)
        val p = Projector(pose(0.0), 0.0, 1000f, 1000f, Projector.focalPxForHorizontalFov(60.0, 1000f))
        assertEquals(60.0, p.horizontalFovDeg, 1e-3)
        assertEquals(p.coneRadiusPx(30.0), 500f, 0.5f)
    }

    @Test fun freeLookPoseMatchesItsHeadingAndPitch() {
        for ((h, pch) in listOf(0.0 to 0.0, 208.6 to 38.0, 95.0 to -20.0, 359.0 to 80.0)) {
            val p = Projector(Projector.rotationFor(h - 5.0, pch), 5.0, 1080f, 1920f, 1500f)
            assertEquals(h, p.cameraHeadingTrueDeg(), 1e-3)
            assertEquals(pch, p.cameraPitchDeg(), 1e-3)
            assertEquals(0.0, p.rollDeg(), 1e-3)
            // The look direction lands on the crosshair; something higher lands above it.
            val c = p.project(h, pch)
            assertEquals(540f, c.x, 0.5f)
            assertEquals(960f, c.y, 0.5f)
            if (pch < 70) assertTrue(p.project(h, pch + 5).y < c.y)
        }
    }
}
