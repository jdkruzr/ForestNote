package com.forestnote.app.notes.ocr

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceOcrSchedulerTest {

    @Test
    fun `automatic OCR may fill missing text or refresh its own result`() {
        assertTrue(DeviceOcrScheduler.mayWriteAutomaticResult(null))
        assertTrue(DeviceOcrScheduler.mayWriteAutomaticResult("mlkit-digital-ink:en-US"))
    }

    @Test
    fun `automatic OCR cannot overwrite an explicitly requested endpoint result`() {
        assertFalse(DeviceOcrScheduler.mayWriteAutomaticResult("openai-compatible:vision-model"))
        assertFalse(DeviceOcrScheduler.mayWriteAutomaticResult("anthropic-compatible:vision-model"))
    }
}
