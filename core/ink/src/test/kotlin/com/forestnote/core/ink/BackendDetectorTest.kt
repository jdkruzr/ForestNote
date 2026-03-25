package com.forestnote.core.ink

import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.mock

/**
 * Unit tests for BackendDetector.
 *
 * Verifies device detection and backend fallback logic.
 * On JVM (non-Viwoods), detector should return GenericBackend.
 * This simulates AC3.1 (correct fallback) and AC3.5 (graceful fallback on failure).
 */
class BackendDetectorTest {

    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
    }

    @Test
    fun detect_onNonViwoodsDevice_returnsFreshDetectionResult() {
        // On JVM, ENoteSetting class is not available (ClassNotFoundException)
        // so ViwoodsBackend.isAvailable() returns false
        val result = BackendDetector.detect(mockContext)

        assertNotNull("DetectionResult should not be null", result)
        assertNotNull("Backend should not be null", result.backend)
    }

    @Test
    fun detect_onNonViwoodsDevice_returnsGenericBackend() {
        // Verify the backend is GenericBackend when Viwoods unavailable
        val result = BackendDetector.detect(mockContext)

        assertTrue("Backend should be GenericBackend instance",
            result.backend is GenericBackend)
    }

    @Test
    fun detect_onNonViwoodsDevice_setsIsEinkToFalse() {
        // AC3.1: Correct fallback marking
        val result = BackendDetector.detect(mockContext)

        assertFalse("isEInk should be false on non-Viwoods device", result.isEInk)
    }

    @Test
    fun detect_returnedBackendIsInitialized() {
        // Verify the returned backend is functional
        val result = BackendDetector.detect(mockContext)

        assertTrue("Returned backend should be available",
            result.backend.isAvailable())
        assertTrue("Returned backend should be initialized",
            result.backend.init(mockContext))
    }

    @Test
    fun detect_eachCallReturnsFreshInstance() {
        // AC3.5: No singleton state leakage between calls
        val result1 = BackendDetector.detect(mockContext)
        val result2 = BackendDetector.detect(mockContext)

        assertNotSame("Each call should return fresh DetectionResult",
            result1, result2)
        // Backends may be different instances (GenericBackend creates new ones)
        assertNotSame("Backend instances should be fresh",
            result1.backend, result2.backend)
    }

    @Test
    fun detect_fallsBackToGenericWhenViwoodsUnavailable() {
        // Comprehensive AC3.5 test: ViwoodsBackend unavailable
        // (ENoteSetting class missing on JVM) should fall back gracefully
        val result = BackendDetector.detect(mockContext)

        // Should complete without throwing
        assertNotNull(result)
        assertTrue(result.backend is GenericBackend)
        assertFalse(result.isEInk)
    }

    @Test
    fun detect_backendSurvivesMultipleCalls() {
        // Verify robustness: multiple detect calls don't corrupt state
        val result1 = BackendDetector.detect(mockContext)
        val result2 = BackendDetector.detect(mockContext)
        val result3 = BackendDetector.detect(mockContext)

        assertTrue(result1.backend is GenericBackend)
        assertTrue(result2.backend is GenericBackend)
        assertTrue(result3.backend is GenericBackend)

        assertFalse(result1.isEInk)
        assertFalse(result2.isEInk)
        assertFalse(result3.isEInk)
    }
}
