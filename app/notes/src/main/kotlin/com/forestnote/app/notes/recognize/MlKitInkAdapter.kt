package com.forestnote.app.notes.recognize

import com.google.mlkit.vision.digitalink.Ink

// pattern: Adapter / Anti-Corruption Layer (the *only* file that touches MLKit's Ink type;
// the rest of the recognize pipeline stays in pure intermediate types so it's unit-testable).

/**
 * Build an MLKit [Ink] from the pure [InkRequest] intermediate. The conversion is
 * trivial enough that exercising it requires the MLKit runtime — covered by
 * on-device verification rather than JVM unit tests. Pressure is intentionally
 * omitted (the Latin-script Digital Ink model ignores it).
 */
fun InkRequest.toMlKitInk(): Ink {
    val inkBuilder = Ink.builder()
    for (s in strokes) {
        val strokeBuilder = Ink.Stroke.builder()
        for (p in s.points) {
            strokeBuilder.addPoint(Ink.Point.create(p.x.toFloat(), p.y.toFloat(), p.t))
        }
        inkBuilder.addStroke(strokeBuilder.build())
    }
    return inkBuilder.build()
}
