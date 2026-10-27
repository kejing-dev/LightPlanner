package io.embodit.lightplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The virtual room (taste profile D7 / D9): built in front of the head, and a complete room for the app's rules. */
class VirtualRoomTest {
    private fun headAt(x: Float, y: Float, z: Float, yawDeg: Float): HeadPose =
        HeadPose(Vec3(x, y, z), Quat4.axisAngle(Vec3.UP, Math.toRadians(yawDeg.toDouble()).toFloat()))

    @Test
    fun isARoomTheModelUnderstands() {
        val v = VirtualRoom.around(headAt(0f, 1.6f, 0f, 0f), floorY = null)
        val room = RoomModel(v.planes())
        assertEquals(0f, room.floorY!!, 1e-3f)
        assertEquals(2.7f, room.ceilingHeight()!!, 1e-3f)
        assertEquals(4, room.walls.size)
        assertTrue(room.hasFurniture)
        assertEquals(SurfaceKind.COFFEE, room.receivers.first { it.kind != SurfaceKind.FLOOR }.kind)
        assertEquals(2.7f, room.ceilingYAt(P2(0f, -1f))!!, 1e-3f)
    }

    @Test
    fun followsTheHeadPositionAndYaw() {
        for (yaw in listOf(0f, 90f, 180f, -135f)) {
            val h = headAt(3f, 1.5f, -2f, yaw)
            val v = VirtualRoom.around(h, floorY = 0.1f)
            val ahead = h.level()
            val toCenter = v.center - P2.of(h.position)
            assertTrue("centre ahead of the user at yaw $yaw", toCenter.dot(ahead) > 0.9f)
            assertEquals(0.1f, v.floorY, 1e-4f)
            val table = v.planes().first { it.id == VirtualRoom.ID_TABLE }.centroid
            assertTrue("table ahead at yaw $yaw", (P2.of(table) - P2.of(h.position)).dot(ahead) > 1f)
            // the user is inside the room
            val room = RoomModel(v.planes())
            assertNotNull(room.nearestWall(P2.of(h.position)))
            assertTrue(room.nearestWall(P2.of(h.position))!!.first > 0.5f)
        }
    }

    @Test
    fun resizeIsClamped() {
        val v = VirtualRoom.around(headAt(0f, 1.6f, 0f, 30f), floorY = 0f).resized(width = 0.5f, depth = 40f, height = 9f)
        assertEquals(VirtualRoom.MIN_SIDE_M, v.width, 1e-4f)
        assertEquals(VirtualRoom.MAX_SIDE_M, v.depth, 1e-4f)
        assertEquals(RoomModel.CEILING_MAX_M, v.height, 1e-4f)
    }

    @Test
    fun colouredLampsTintThePool() {
        for (c in Cct.entries) {
            assertEquals(3, c.rgb.size)
            assertEquals("brightest channel is 1 for ${c.name}", 1f, c.rgb.max(), 1e-4f)
        }
        assertEquals(Cct.K6500, Cct.byTag("6500"))
        assertEquals(Cct.BLUE, Cct.byTag("blue"))
        assertEquals(5, Cct.WHITES.size)
        assertEquals(5, Cct.COLOURS.size)
        // a cool lamp is bluer than a warm one
        assertTrue(Cct.K6500.rgb[2] / Cct.K6500.rgb[0] > Cct.K2700.rgb[2] / Cct.K2700.rgb[0])
    }
}
