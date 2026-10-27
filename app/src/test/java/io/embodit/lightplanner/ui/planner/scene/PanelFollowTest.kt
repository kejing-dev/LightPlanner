package io.embodit.lightplanner.ui.planner.scene

import io.embodit.lightplanner.domain.HeadPose
import io.embodit.lightplanner.domain.P2
import io.embodit.lightplanner.domain.PanelRest
import io.embodit.lightplanner.domain.Quat4
import io.embodit.lightplanner.domain.Vec3
import kotlin.math.asin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The main panel's placement (taste profile D9 / D10; 0.1.1 user feedback "要抬着头看"): below the eyes whatever the
 * gaze pitch, in front of the head's current yaw, and re-centred after turning round. The emulator could not turn its
 * camera while this was written (all pool emulators busy, < 50 fps), so this is the turn-90 / turn-180 check.
 */
class PanelFollowTest {
    private fun head(yawDeg: Float, pitchDeg: Float = 0f, at: Vec3 = Vec3(0.3f, 1.6f, -0.2f)): HeadPose {
        val yaw = Quat4.axisAngle(Vec3.UP, Math.toRadians(-yawDeg.toDouble()).toFloat()) // + = turn right
        val pitch = Quat4.axisAngle(Vec3.RIGHT, Math.toRadians(pitchDeg.toDouble()).toFloat())
        return HeadPose(at, yaw * pitch)
    }

    /** Same rule as `PlannerScene.restMain`. */
    private fun rest(h: HeadPose): Vec3 {
        val gaze = Math.toDegrees(asin(h.forward.y.toDouble())).toFloat()
        val left = if (gaze < -PlannerScene.LOOK_DOWN_DEG) PlannerScene.SIDE_LEFT_DEG else PlannerScene.MAIN_LEFT_DEG
        return PanelRest.at(h, left, -PlannerScene.MAIN_DOWN_DEG, PlannerScene.MAIN_M)
    }

    private fun degBelowEyes(h: HeadPose, p: Vec3): Float {
        val to = p - h.position
        return -Math.toDegrees(asin((to.y / to.length).toDouble())).toFloat()
    }

    private fun yawOff(h: HeadPose, p: Vec3): Float {
        val a = h.level()
        val b = P2.of(p - h.position).normalized()
        return Math.toDegrees(kotlin.math.acos(a.dot(b).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    @Test
    fun restsBelowTheEyesInFrontWhateverThePitch() {
        for (yaw in listOf(0f, 90f, 180f, -90f)) for (pitch in listOf(-20f, 0f, 30f, 60f)) {
            val h = head(yaw, pitch)
            val p = rest(h)
            val below = degBelowEyes(h, p)
            assertTrue("yaw $yaw pitch $pitch: $below deg below the eyes", below in 10f..20f)
            assertEquals(PlannerScene.MAIN_M, (p - h.position).length, 1e-3f)
            assertTrue("in front at yaw $yaw", yawOff(h, p) < 25f)
        }
    }

    @Test
    fun turningRoundBringsItBackInFront() {
        val f = PanelFollow("main") { h -> rest(h) }
        var t = 0L
        val start = head(0f)
        f.step(start, t, hold = false)
        for (turn in listOf(90f, 180f)) {
            val h = head(turn)
            var last: Vec3? = null
            repeat(40) {
                t += 50
                f.step(h, t, hold = false)?.let { last = it }
            }
            val p = last!!
            assertTrue("turned $turn: panel ${yawOff(h, p)} deg off the new front", yawOff(h, p) < 25f)
            assertTrue(degBelowEyes(h, p) in 10f..20f)
        }
    }

    @Test
    fun lookingUpAtTheCeilingDoesNotRaiseIt() {
        val f = PanelFollow("main") { h -> rest(h) }
        var t = 0L
        val first = f.step(head(0f), t, hold = false)!!
        repeat(40) {
            t += 50
            val moved = f.step(head(0f, 50f), t, hold = false)
            if (moved != null) assertTrue("never above the eyes", degBelowEyes(head(0f, 50f), moved) > 10f)
        }
        assertTrue(degBelowEyes(head(0f), first) > 10f)
    }

    @Test
    fun sideParkWhenLookingDownSettlesWithoutSlidingAgain() {
        val f = PanelFollow("main") { h -> rest(h) }
        var t = 0L
        val down = head(0f, -50f)
        f.step(head(0f), t, hold = false)
        repeat(40) {
            t += 50
            f.step(down, t, hold = false)
        }
        // parked: no more writes (0.1.1 first build re-slid to the same spot every second on the headset)
        repeat(60) {
            t += 50
            assertNull(f.step(down, t, hold = false))
        }
    }
}
