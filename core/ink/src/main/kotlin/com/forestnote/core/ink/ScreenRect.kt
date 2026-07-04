package com.forestnote.core.ink

/**
 * A rectangle in screen pixels, as plain floats — no `android.graphics.RectF`, so geometry that
 * produces it ([PageTransform.pageRectScreen]) and consumes it ([FirmwareLimitRectLogic]) stays
 * unit-testable on the JVM. May extend beyond the viewport (the page projected under zoom/pan);
 * callers intersect with the surface as needed.
 */
data class ScreenRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
