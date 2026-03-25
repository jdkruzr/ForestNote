# Ink Domain (core:ink)

Last verified: 2026-03-25

## Purpose
Abstracts device-specific e-ink rendering behind a common interface and provides the stroke data model, coordinate system, and geometry operations used by all other modules.

## Contracts
- **Exposes**: `InkBackend` interface, `BackendDetector.detect()`, `Stroke`/`StrokeBuilder`/`StrokePoint` types, `Tool` sealed class, `PageTransform`, `PressureCurve`, `StrokeGeometry`
- **Guarantees**: `BackendDetector.detect()` always returns a working backend (GenericBackend fallback). All stroke/point data uses virtual coordinates. PageTransform is the sole virtual-to-screen converter.
- **Expects**: Android Context for backend init. View dimensions for PageTransform.update().

## Dependencies
- **Uses**: Android framework, Viwoods ENote APIs (via reflection), Jetpack Ink geometry
- **Used by**: `core:format` (Stroke/StrokePoint types), `app:notes` (everything)
- **Boundary**: Must not depend on `core:format` or `app:notes`

## Key Decisions
- Reflection bridge for Viwoods APIs: device SDK is undocumented, reflection isolates from API changes and allows compilation on any machine
- Virtual coordinate space (short axis = 10,000): strokes are resolution-independent across device orientations and screen sizes
- Millipressure (0-1000 int): avoids float precision issues in storage and serialization
- Logarithmic pressure curve: `width = min + range * ln(3p+1) / ln(4)`, matches Viwoods first-party feel

## Invariants
- StrokePoint coordinates are always in virtual space, never screen pixels
- PageTransform is the ONLY class that converts between virtual and screen coordinates
- ViwoodsBackend.isAvailable() returns false on non-AiPaper devices (checks ENoteSetting class)
- Sub-strokes from splitStroke() always have 2+ points

## Key Files
- `InkBackend.kt` - Backend interface (init, startStroke, renderSegment, endStroke, release)
- `ViwoodsBackend.kt` - AiPaper fast ink via ENoteBridge reflection
- `GenericBackend.kt` - Fallback using standard View.invalidate()
- `BackendDetector.kt` - Runtime backend selection (Viwoods > Generic)
- `Stroke.kt` - Immutable Stroke + mutable StrokeBuilder
- `PageTransform.kt` - Virtual/screen coordinate conversion
- `StrokeGeometry.kt` - Intersection testing and stroke splitting for erasers

## Gotchas
- ENoteBridge uses reflection; any method call can throw. Always wrap in try/catch.
- WritingBufferQueue is a shared device resource: release() in onPause, reacquire() in onResume
- ViwoodsBackend.reacquire() is not on the InkBackend interface; caller must cast
