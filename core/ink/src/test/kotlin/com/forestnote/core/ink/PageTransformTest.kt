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

        // The stable portrait notebook page fits by height on a landscape viewport.
        val expectedScale = 1440f / PageTransform.VIRTUAL_LONG_AXIS
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
    fun longAxisStaysStableAcrossViewportShapes() {
        val transform = PageTransform()
        transform.update(824, 1648)

        assertEquals(PageTransform.VIRTUAL_LONG_AXIS, transform.virtualLongAxis)
        assertEquals(824f / PageTransform.VIRTUAL_SHORT_AXIS, transform.scale, 0.001f)
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
    fun templatePitchVirtualIsFixedVirtualUnitsPerMm() {
        // Pitch is a fixed page-space quantity (mm × VIRTUAL_UNITS_PER_MM), independent of any device.
        val transform = PageTransform()
        transform.update(824, 1648)

        assertEquals(560f, transform.templatePitchVirtual(7f), 0.001f)
        assertEquals(400f, transform.templatePitchVirtual(5f), 0.001f)
        assertEquals(800f, transform.templatePitchVirtual(10f), 0.001f)
    }

    @Test
    fun templatePitchVirtualIsDeviceIndependent() {
        // The same page pitch must resolve to identical virtual units on any panel/aspect, so the
        // ruled lines land at the same virtual coordinates as the (device-independent) ink.
        val mini = PageTransform()
        mini.update(824, 1648, longAxis = 20_000)

        val other = PageTransform()
        other.update(1440, 1920) // different size AND aspect

        assertEquals(mini.templatePitchVirtual(7f), other.templatePitchVirtual(7f), 0.001f)
    }

    @Test
    fun templatePitchVirtualWorksBeforeLayout() {
        // No update() yet — pitch no longer depends on layout (fitScale), so it is already correct.
        val transform = PageTransform()

        assertEquals(560f, transform.templatePitchVirtual(7f), 0.001f)
    }

    @Test
    fun templatePitchVirtualIsZoomIndependent() {
        // The template lives in page space; its pitch in virtual units is a property of the page,
        // NOT of zoom — zoom scaling falls out when the layer is projected.
        val transform = PageTransform()
        transform.update(824, 1648)

        val atFit = transform.templatePitchVirtual(7f)
        transform.setZoom(3f, preserveCenter = false)
        val atZoom = transform.templatePitchVirtual(7f)

        assertEquals(atFit, atZoom, 0.001f)
    }

    @Test
    fun pageRectScreenLetterboxesToFit() {
        val transform = PageTransform()
        transform.update(1000, 1500, longAxis = 12_556)

        val rect = transform.pageRectScreen()
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(1000f, rect.right, 0.001f)   // fits by short axis: 10000 * 0.1
        assertEquals(1255.6f, rect.bottom, 0.01f) // 12556 * 0.1 → bottom letterbox is blank
    }

    @Test
    fun pageRectScreenExceedsScreenWhenZoomed() {
        val transform = PageTransform()
        transform.update(1000, 1500, longAxis = 12_556)
        transform.setZoom(2f, preserveCenter = false)

        val rect = transform.pageRectScreen()
        assertEquals(0f, rect.left, 0.001f)
        assertTrue(rect.right > transform.screenWidth) // page wider than the viewport → caller clips
    }

    @Test
    fun pageRectScreenBeforeLayoutDoesNotCrash() {
        val transform = PageTransform()
        val rect = transform.pageRectScreen()
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
    }

    @Test
    fun zoomScalesCoordinates() {
        val transform = PageTransform()
        transform.update(1000, 1500)
        transform.setZoom(2f, preserveCenter = false)

        assertEquals(0.2f, transform.scale, 0.001f)
        assertEquals(1000f, transform.toScreenX(5000), 0.001f)
    }

    @Test
    fun panMovesViewportAndRoundTripsCoordinates() {
        val transform = PageTransform()
        transform.update(1000, 1500)
        transform.setZoom(2f, preserveCenter = false)
        transform.panByScreen(250f, 500f)

        assertEquals(1250, transform.toVirtualX(0f))
        assertEquals(2500, transform.toVirtualY(0f))
        assertEquals(0f, transform.toScreenX(1250), 0.001f)
        assertEquals(0f, transform.toScreenY(2500), 0.001f)
    }

    @Test
    fun panClampsToPageBounds() {
        val transform = PageTransform()
        transform.update(1000, 1500)
        transform.setZoom(2f, preserveCenter = false)
        transform.panByScreen(100_000f, 100_000f)

        assertEquals(5000, transform.toVirtualX(0f))
        assertEquals(5833, transform.toVirtualY(0f))
    }

    @Test
    fun updateHonorsPerNotebookLongAxis() {
        // A note created on a 1:2 device (e.g. Palma 824x1648 → longAxis 20000) keeps that shape
        // even when shown on a 3:4 viewport: uniform fitScale (min) letterboxes, never distorts.
        val transform = PageTransform()
        transform.update(1440, 1920, longAxis = 20_000)

        assertEquals(20_000, transform.virtualLongAxis)
        // Fits by the long axis here (1920/20000 = 0.096 < 1440/10000 = 0.144) → letterboxed sides.
        assertEquals(1920f / 20_000f, transform.fitScale, 0.0001f)
        assertEquals(transform.fitScale, transform.scale, 0.0001f)
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

        assertEquals(PageTransform.VIRTUAL_LONG_AXIS, portraitStandard.virtualLongAxis)
        assertEquals(PageTransform.VIRTUAL_LONG_AXIS, portrait4K.virtualLongAxis)

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
