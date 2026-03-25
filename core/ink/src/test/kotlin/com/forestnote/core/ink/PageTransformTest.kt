package com.forestnote.core.ink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageTransformTest {
    private val TOLERANCE = 1 // Allow 1 pixel tolerance due to integer rounding

    @Test
    fun initialStateHasDefaultValues() {
        val transform = PageTransform()
        assertEquals(1f, transform.scale)
        assertEquals(13_333, transform.virtualLongAxis)
        assertEquals(0, transform.screenWidth)
        assertEquals(0, transform.screenHeight)
    }

    @Test
    fun updateSetsDimensions() {
        val transform = PageTransform()
        transform.update(1440, 1920)

        assertEquals(1440, transform.screenWidth)
        assertEquals(1920, transform.screenHeight)
    }

    @Test
    fun updateCalculatesScaleForPortraitOrientation() {
        val transform = PageTransform()
        transform.update(1440, 1920)

        // Short axis is 1440, so scale should be 1440 / 10000 = 0.144
        val expectedScale = 1440f / PageTransform.VIRTUAL_SHORT_AXIS
        assertEquals(expectedScale, transform.scale, 0.001f)
    }

    @Test
    fun updateCalculatesMostSignificantDimensionAsShortAxis() {
        val transform = PageTransform()
        // Landscape orientation: width > height
        transform.update(1920, 1440)

        // Short axis is 1440, so scale should be 1440 / 10000 = 0.144
        val expectedScale = 1440f / PageTransform.VIRTUAL_SHORT_AXIS
        assertEquals(expectedScale, transform.scale, 0.001f)
    }

    @Test
    fun shortAxisIsAlwaysTenThousandVirtualUnits() {
        val transform = PageTransform()
        transform.update(1440, 1920)

        // Verify by converting back: 10000 virtual units should equal the short axis in pixels
        assertEquals(1440f, transform.toScreenSize(PageTransform.VIRTUAL_SHORT_AXIS), 1f)
    }

    @Test
    fun longAxisScalesProportionallyToShortAxis() {
        val transform = PageTransform()
        // 1440x1920 portrait: aspect ratio = 1920/1440 = 4/3
        transform.update(1440, 1920)

        // Virtual long axis should maintain the same aspect ratio
        val expectedVirtualLongAxis = (1920f / transform.scale).toInt()
        assertEquals(expectedVirtualLongAxis, transform.virtualLongAxis)
    }

    @Test
    fun toScreenXConvertsVirtualXToScreenPixels() {
        val transform = PageTransform()
        transform.update(1440, 1920)

        // Virtual x=5000 (half the short axis) should convert to half of 1440 pixels
        val screenX = transform.toScreenX(5000)
        assertEquals(720f, screenX, 1f)
    }

    @Test
    fun toScreenYConvertsVirtualYToScreenPixels() {
        val transform = PageTransform()
        transform.update(1440, 1920)

        // Virtual y=5000 (half the short axis) should convert to half of 1920 pixels
        val screenY = transform.toScreenY(5000)
        assertEquals(720f, screenY, 1f)
    }

    @Test
    fun toScreenSizeIntConvertsVirtualSizeToScreenPixels() {
        val transform = PageTransform()
        transform.update(1440, 1920)

        val screenSize = transform.toScreenSize(100)
        assertEquals(14.4f, screenSize, 0.1f)
    }

    @Test
    fun toScreenSizeFloatConvertsVirtualSizeToScreenPixels() {
        val transform = PageTransform()
        transform.update(1440, 1920)

        val screenSize = transform.toScreenSize(100.5f)
        assertEquals(14.472f, screenSize, 0.01f)
    }

    @Test
    fun toVirtualXConvertsScreenXToVirtualCoordinate() {
        val transform = PageTransform()
        transform.update(1440, 1920)

        // Screen x=720 (half of 1440) should convert to virtual x=5000
        val virtualX = transform.toVirtualX(720f)
        assertEquals(5000, virtualX)
    }

    @Test
    fun toVirtualYConvertsScreenYToVirtualCoordinate() {
        val transform = PageTransform()
        transform.update(1440, 1920)

        // Screen y=960 (half of 1920) should convert to virtual y=6666 (half of 13333)
        val virtualY = transform.toVirtualY(960f)
        assertEquals(6666, virtualY)
    }

    @Test
    fun roundTripVirtualToScreenAndBackPreservesValue() {
        val transform = PageTransform()
        transform.update(1440, 1920)

        val originalVirtualX = 3750
        val screenX = transform.toScreenX(originalVirtualX)
        val recoveredVirtualX = transform.toVirtualX(screenX)

        assertEquals(originalVirtualX, recoveredVirtualX)
    }

    @Test
    fun roundTripScreenToVirtualAndBackPreservesValue() {
        val transform = PageTransform()
        transform.update(1440, 1920)

        val originalScreenX = 720f
        val virtualX = transform.toVirtualX(originalScreenX)
        val recoveredScreenX = transform.toScreenX(virtualX)

        assertEquals(originalScreenX, recoveredScreenX, 1f)
    }

    @Test
    fun resolutionIndependencePortraitVs4KPortrait() {
        val portraitStandard = PageTransform()
        portraitStandard.update(1440, 1920)

        val portrait4K = PageTransform()
        portrait4K.update(2880, 3840)

        // Both should have same aspect ratio and virtual dimensions
        assertEquals(PageTransform.VIRTUAL_SHORT_AXIS.toDouble(), (portraitStandard.virtualLongAxis * 1440 / 1920).toDouble(), 1.0)
        assertEquals(PageTransform.VIRTUAL_SHORT_AXIS.toDouble(), (portrait4K.virtualLongAxis * 2880 / 3840).toDouble(), 1.0)

        // Converting the same virtual coordinate should give proportional screen coordinates
        val virtualX = 5000
        val screenXStandard = portraitStandard.toScreenX(virtualX)
        val screenX4K = portrait4K.toScreenX(virtualX)

        // 4K should have exactly 2x the screen pixels
        assertEquals(screenX4K / screenXStandard, 2f, 0.01f)
    }

    @Test
    fun toMillipressureConvertsFloatPressureToInt() {
        val transform = PageTransform()

        assertEquals(0, transform.toMillipressure(0f))
        assertEquals(500, transform.toMillipressure(0.5f))
        assertEquals(1000, transform.toMillipressure(1f))
        assertEquals(750, transform.toMillipressure(0.75f))
    }

    @Test
    fun toMillipressureClampsValuesOutsideRange() {
        val transform = PageTransform()

        // Values below 0 should clamp to 0
        assertEquals(0, transform.toMillipressure(-0.5f))
        // Values above 1 should clamp to 1000
        assertEquals(1000, transform.toMillipressure(1.5f))
    }

    @Test
    fun fromMillipressureConvertsIntPressureToFloat() {
        val transform = PageTransform()

        assertEquals(0f, transform.fromMillipressure(0), 0.001f)
        assertEquals(0.5f, transform.fromMillipressure(500), 0.001f)
        assertEquals(1f, transform.fromMillipressure(1000), 0.001f)
        assertEquals(0.75f, transform.fromMillipressure(750), 0.001f)
    }

    @Test
    fun roundTripPressurePreservesValue() {
        val transform = PageTransform()

        val originalPressure = 0.625f
        val millipressure = transform.toMillipressure(originalPressure)
        val recoveredPressure = transform.fromMillipressure(millipressure)

        assertEquals(originalPressure, recoveredPressure, 0.002f)
    }

    @Test
    fun differentScreenSizesProduceProportionalCoordinates() {
        val small = PageTransform()
        small.update(720, 960)

        val large = PageTransform()
        large.update(1440, 1920)

        // Same virtual coordinate should map proportionally
        val virtualX = 2500
        val smallScreenX = small.toScreenX(virtualX)
        val largeScreenX = large.toScreenX(virtualX)

        // Large should be exactly 2x small
        assertEquals(largeScreenX / smallScreenX, 2f, 0.01f)
    }
}
