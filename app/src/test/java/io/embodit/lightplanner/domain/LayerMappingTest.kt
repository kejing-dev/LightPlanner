package io.embodit.lightplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** The light layer's texels land on their own cells (same bottom-up v as the label plates, [BillboardTest]). */
class LayerMappingTest {
    @Test
    fun bitmapRowZeroIsAtMinZAndColumnZeroAtMinX() {
        val uv = LayerQuad.uv()
        for (i in LayerQuad.CORNERS.indices) {
            val c = LayerQuad.CORNERS[i]
            val (u, v) = uv[i]
            // fraction of the field box from its min corner, as the field indexes cells (iz = 0 at min Z)
            val fx = c.x + 0.5f
            val fz = c.z + 0.5f
            assertEquals("column at corner $i", fx, u, 1e-6f)
            assertEquals("row at corner $i", fz, Billboard.bitmapRow(v), 1e-6f)
        }
    }

    @Test
    fun legacyMappingWasMirroredAlongZ() {
        val uv = LayerQuad.uv(legacy = true)
        for (i in LayerQuad.CORNERS.indices) {
            val fz = LayerQuad.CORNERS[i].z + 0.5f
            assertEquals(1f - fz, Billboard.bitmapRow(uv[i].second), 1e-6f)
        }
    }
}
