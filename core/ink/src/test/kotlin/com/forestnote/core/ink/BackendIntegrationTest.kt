package com.forestnote.core.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.mock

/**
 * Integration tests for backend detection and fallback chain.
 *
 * Verifies AC3.1 (detection), AC3.4 (GenericBackend rendering), and AC3.5 (graceful fallback).
 *
 * These tests run on standard JVM (non-AiPaper), where ViwoodsBackend.isAvailable()
 * returns false because android.os.enote.ENoteSetting doesn't exist.
 * This simulates the fallback behavior on non-e-ink devices and verifies GenericBackend
 * as the reliable fallback.
 */
class BackendIntegrationTest {

    private lateinit var mockContext: Context
    private lateinit var mockBitmap: Bitmap

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockBitmap = mock(Bitmap::class.java)
    }

    // ============================================
    // AC3.1 & AC3.4: GenericBackend on non-e-ink
    // ============================================

    @Test
    fun backendDetection_onNonEinkDevice_returnsGenericBackend() {
        // AC3.1: BackendDetector returns GenericBackend on non-AiPaper
        // AC3.4: GenericBackend can render strokes via standard Canvas
        val result = BackendDetector.detect(mockContext)

        assertNotNull("Backend should not be null", result.backend)
        assertTrue("Backend should be GenericBackend instance",
            result.backend is GenericBackend)
        assertFalse("isEInk should be false on non-e-ink device",
            result.isEInk)
    }

    @Test
    fun backendDetection_onNonEinkDevice_doesNotCrash() {
        // AC3.4: GenericBackend initialization should not throw
        // even though ViwoodsBackend.isAvailable() fails silently
        val result = assertDoesNotThrow(
            { BackendDetector.detect(mockContext) },
            "BackendDetector should handle missing Viwoods API gracefully"
        )

        assertNotNull(result)
    }

    @Test
    fun detectionResult_containsValidBackendInstance() {
        // AC3.1: Returned backend is functional and initialized
        val result = BackendDetector.detect(mockContext)

        assertTrue("Backend should be available",
            result.backend.isAvailable())
        assertTrue("Backend should be initializable",
            result.backend.init(mockContext))
    }

    // ============================================
    // AC3.5: Graceful fallback on init failure
    // ============================================

    @Test
    fun backendFallback_whenViwoodsUnavailable_fallsBackToGeneric() {
        // AC3.5: When ViwoodsBackend.isAvailable() returns false
        // (as it does on JVM since ENoteSetting class doesn't exist),
        // BackendDetector should fall back to GenericBackend without crashing
        val viwoods = ViwoodsBackend()
        assertFalse("ViwoodsBackend.isAvailable() should be false on JVM",
            viwoods.isAvailable())

        val result = BackendDetector.detect(mockContext)

        assertTrue("Should fall back to GenericBackend",
            result.backend is GenericBackend)
        assertFalse("isEInk should be false",
            result.isEInk)
    }

    @Test
    fun backendFallback_doesNotPropagateException() {
        // AC3.5: Graceful handling of missing Viwoods API
        // should not throw even if ViwoodsBackend.init() fails
        val result = assertDoesNotThrow(
            { BackendDetector.detect(mockContext) },
            "BackendDetector.detect() should handle ViwoodsBackend failure gracefully"
        )

        assertNotNull("Should return a valid DetectionResult", result)
        assertNotNull("Should return a valid backend", result.backend)
    }

    // ============================================
    // GenericBackend full lifecycle
    // ============================================

    @Test
    fun genericBackendLifecycle_fullSequence_executesWithoutError() {
        // AC3.4: GenericBackend supports full lifecycle without error
        val backend = GenericBackend()
        val viewLocation = intArrayOf(100, 200)
        val dirtyRect = Rect(10, 20, 110, 120)

        // All methods should execute without throwing
        assertTrue("init should return true", backend.init(mockContext))
        backend.setDisplayMode(DisplayMode.FAST)
        backend.startStroke(mockBitmap, viewLocation)
        backend.renderSegment(dirtyRect)
        backend.endStroke()
        backend.release()

        // If we reach here without exception, test passes
    }

    @Test
    fun genericBackendLifecycle_multipleStrokes() {
        // AC3.4: GenericBackend can handle multiple strokes in sequence
        val backend = GenericBackend()
        val viewLocation = intArrayOf(50, 75)

        // First stroke
        backend.startStroke(mockBitmap, viewLocation)
        for (i in 0..5) {
            val rect = Rect(i * 10, i * 10, (i + 1) * 10, (i + 1) * 10)
            backend.renderSegment(rect)
        }
        backend.endStroke()

        // Second stroke
        backend.startStroke(mockBitmap, viewLocation)
        for (i in 0..5) {
            val rect = Rect(i * 15, i * 15, (i + 1) * 15, (i + 1) * 15)
            backend.renderSegment(rect)
        }
        backend.endStroke()

        backend.release()

        // If we reach here without exception, test passes
    }

    @Test
    fun genericBackendLifecycle_displayModeTransitions() {
        // AC3.4: GenericBackend handles display mode transitions
        val backend = GenericBackend()

        backend.init(mockContext)

        // Verify mode transitions work
        backend.setDisplayMode(DisplayMode.FAST)
        backend.setDisplayMode(DisplayMode.NORMAL)
        backend.setDisplayMode(DisplayMode.FULL_REFRESH)
        backend.setDisplayMode(DisplayMode.FAST)

        backend.release()

        // If we reach here without exception, test passes
    }

    @Test
    fun genericBackendLifecycle_isIdempotent() {
        // AC3.4: GenericBackend release/reinit is safe
        val backend = GenericBackend()

        // Multiple init/release cycles
        for (i in 0..2) {
            assertTrue("init should succeed", backend.init(mockContext))
            backend.release()
        }

        // If we reach here without exception, test passes
    }

    // ============================================
    // Robustness: Fallback consistency
    // ============================================

    @Test
    fun backendFallback_consistentAcrossMultipleCalls() {
        // AC3.5: Multiple detect calls return consistent fallback
        val result1 = BackendDetector.detect(mockContext)
        val result2 = BackendDetector.detect(mockContext)
        val result3 = BackendDetector.detect(mockContext)

        assertTrue("All should be GenericBackend",
            result1.backend is GenericBackend &&
            result2.backend is GenericBackend &&
            result3.backend is GenericBackend)

        assertFalse("All should have isEInk=false",
            result1.isEInk || result2.isEInk || result3.isEInk)
    }

    @Test
    fun backendFallback_eachCallReturnsFreshInstance() {
        // AC3.5: No singleton state leakage
        val result1 = BackendDetector.detect(mockContext)
        val result2 = BackendDetector.detect(mockContext)

        assertNotSame("DetectionResult instances should be fresh",
            result1, result2)
        assertNotSame("Backend instances should be fresh",
            result1.backend, result2.backend)
    }

    // ============================================
    // Helper: JUnit 4 compatible assertDoesNotThrow
    // ============================================

    private fun <T> assertDoesNotThrow(
        executable: () -> T,
        message: String? = null
    ): T {
        return try {
            executable()
        } catch (e: Throwable) {
            fail((message ?: "Unexpected exception") + ": ${e.message}")
            throw e
        }
    }
}
