package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.mock

/**
 * Unit tests for GenericBackend.
 *
 * Verifies that GenericBackend correctly implements InkBackend as a no-op
 * fallback for non-e-ink devices. All methods should execute without error.
 */
class GenericBackendTest {

    private lateinit var backend: GenericBackend
    private lateinit var mockContext: Context
    private lateinit var mockBitmap: Bitmap

    @Before
    fun setUp() {
        backend = GenericBackend()
        mockContext = mock(Context::class.java)
        mockBitmap = mock(Bitmap::class.java)
    }

    @Test
    fun isAvailable_alwaysReturnsTrue() {
        // GenericBackend is the fallback — always available
        assertTrue(backend.isAvailable())
    }

    @Test
    fun init_alwaysReturnsTrue() {
        // GenericBackend requires no initialization
        val result = backend.init(mockContext)
        assertTrue(result)
    }

    @Test
    fun setDisplayMode_executeWithoutError() {
        // No-op: generic devices don't have e-ink display modes
        backend.setDisplayMode(DisplayMode.FAST)
        backend.setDisplayMode(DisplayMode.NORMAL)
        backend.setDisplayMode(DisplayMode.FULL_REFRESH)
        // If we reach here without exception, test passes
    }

    @Test
    fun startStroke_executeWithoutError() {
        // No-op: no WritingSurface to configure
        val viewLocation = intArrayOf(0, 0)
        backend.startStroke(mockBitmap, viewLocation)
        // If we reach here without exception, test passes
    }

    @Test
    fun renderSegment_executeWithoutError() {
        // No-op: DrawView will call View.invalidate(dirtyRect) itself
        val dirtyRect = Rect(0, 0, 100, 100)
        backend.renderSegment(dirtyRect)
        // If we reach here without exception, test passes
    }

    @Test
    fun endStroke_executeWithoutError() {
        // No-op: no overlay to disable
        backend.endStroke()
        // If we reach here without exception, test passes
    }

    @Test
    fun release_executeWithoutError() {
        // No-op: nothing to release
        backend.release()
        // If we reach here without exception, test passes
    }

    @Test
    fun multipleInitCalls_alwaysReturnTrue() {
        // Verify idempotent behavior: multiple inits should work
        assertTrue(backend.init(mockContext))
        assertTrue(backend.init(mockContext))
        assertTrue(backend.init(mockContext))
    }

    @Test
    fun multipleReleaseCalls_executeWithoutError() {
        // Verify idempotent behavior: multiple releases should work
        backend.release()
        backend.release()
        backend.release()
        // If we reach here without exception, test passes
    }
}
