# Phase 3: TextBoxSerializer

**Goal:** Add a hand-written `TextBoxSerializer` with `toJson(box): String` / `fromJson(json): TextBox?` so the future B1 phase (`app_state.clipboard_json`) can round-trip text boxes through the same persisted-clipboard path strokes will use, without another contract change.

**Architecture:** Single-element JSON object encoding every `TextBox` field, including `zBand` as a `"BOTTOM"`/`"TOP"` string. `fromJson` returns `null` on malformed / partially-malformed input rather than throwing — defensive-coding house rule.

**Tech Stack:** `kotlinx.serialization.json` (`buildJsonObject` + `Json.parseToJsonElement`), JUnit 4 + `kotlin.test`, `./gradlew :app:notes:test`.

**Scope:** Phase 3 of 6.

---

## ⚠️ Deviation from the design plan — read first

The design plan's "Existing Patterns" section and Glossary entry for `TextBoxSerializer` describe the existing `StrokeSerializer` as a `org.json.JSONObject`-style hand-written JSON serializer at `app/notes/src/main/kotlin/com/forestnote/app/notes/StrokeSerializer.kt`, and ask the new `TextBoxSerializer` to mirror that pattern. The investigator found this is **factually incorrect about the current codebase**:

- The actual `StrokeSerializer` lives at `core/format/src/main/kotlin/com/forestnote/core/format/StrokeSerializer.kt` and is a **binary** ByteBuffer little-endian codec (5 ints per `StrokePoint`), used for the `stroke.points` BLOB column. It is **not** a JSON serializer.
- The codebase has **no `org.json` usage at all** — `grep -r "import org.json"` returns zero hits. JSON in this codebase is `kotlinx.serialization.json` (see `Settings.kt` `@Serializable`, `SyncWire.kt` `buildJsonObject { … }`).

The design's *intent* (`AC4.3`: `toJson` / `fromJson` round-trip) is JSON, which matches the future `app_state.clipboard_json` use case. So this phase honors the *intent* (a JSON serializer with `toJson` / `fromJson` returning `null` on malformed) while using the codebase-consistent `kotlinx.serialization.json` library instead of the absent-from-the-codebase `org.json`. The deviation is documented here and called out in the eventual "future serialization-cleanup" note the design already anticipated.

**Where the file goes:** Place it at `app/notes/src/main/kotlin/com/forestnote/app/notes/TextBoxSerializer.kt` as the design specifies (not `core/format/`). This keeps the clipboard-JSON encoder in the same module as `Clipboard.kt` and the future B1 `app_state.clipboard_json` glue, and avoids pulling a clipboard concern into `core/format`. If a future cleanup pass moves both serializers into `core/format` together, that's a separate migration the design already anticipates.

---

**Codebase verified:** 2026-05-29 via codebase-investigator. Key facts:
- `TextBox` at `core/ink/src/main/kotlin/com/forestnote/core/ink/TextBox.kt` — full field list: `id: String`, `x: Int`, `y: Int`, `width: Int`, `height: Int`, `text: String`, `fontName: String`, `fontSize: Int`, `color: Int (= COLOR_BLACK = 0xFF000000.toInt())`, `weight: Int (= WEIGHT_NORMAL = 400)`, `borderWidth: Int (= DEFAULT_BORDER_WIDTH = 2)`, `zBand: ZBand (= ZBand.BOTTOM)`. Companion holds `COLOR_BLACK`, `WEIGHT_NORMAL`, `DEFAULT_BORDER_WIDTH`.
- `ZBand` enum lives in the same file: `BOTTOM(0)`, `TOP(1)`, with `ZBand.fromValue(z: Int)` (`if (z == TOP.value) TOP else BOTTOM`).
- `app/notes` module already depends on `kotlinx-serialization-json` transitively via `core/format` (used by Settings/SyncWire). Confirm via:
  `./gradlew :app:notes:dependencies | grep kotlinx-serialization`
  If it's not on the classpath, add the dependency to `app/notes/build.gradle.kts` mirroring the line in `core/format/build.gradle.kts`: `implementation(libs.findLibrary("kotlinx-serialization-json").get())`.
- No `StrokeSerializerTest`-equivalent test exists in `app/notes/`; the binary `StrokeSerializerTest` is in `core/format`. So we create `TextBoxSerializerTest` fresh in `app/notes/`. Pattern: JUnit 4, `kotlin.test` assertions (matches other `app/notes/` tests).

---

## Acceptance Criteria Coverage

This phase implements and tests:

### lasso-textboxes.AC4: Clipboard contract widens to ClipboardPayload; TextBoxSerializer lands

- **lasso-textboxes.AC4.3 Success (serializer round-trip):** `TextBoxSerializer.toJson(box)` followed by `fromJson(json)` round-trips every field of `TextBox` to an identity-equal box.
- **lasso-textboxes.AC4.4 Failure (malformed input):** `TextBoxSerializer.fromJson(malformed)` returns null and does not throw.

### lasso-textboxes.AC7: Unit-test coverage updated

- **lasso-textboxes.AC7.3 Success:** `TextBoxSerializerTest` covers field round-trip for every field, defensive defaults for missing fields, and invalid-JSON returning null.

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->
<!-- START_TASK_1 -->
### Task 1: Verify (or add) `kotlinx-serialization-json` on `:app:notes` classpath

**Verifies:** None (infrastructure prep)

**Files:**
- Possibly modify: `app/notes/build.gradle.kts`

**Implementation:**

Run:
```
./gradlew :app:notes:dependencies --configuration debugRuntimeClasspath | grep kotlinx-serialization-json
```

- If the line appears (transitively via `:core:format`), no change needed.
- If the line is **missing**, add to `app/notes/build.gradle.kts` in the `dependencies { ... }` block, mirroring the `:core:format` line:

  ```kotlin
  implementation(libs.findLibrary("kotlinx-serialization-json").get())
  ```

Also confirm `gradle/libs.versions.toml` has `kotlinx-serialization-json` defined — the investigator confirmed it does.

**Verification:**

```
./gradlew :app:notes:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. (No production code uses it yet — Task 2 introduces the first import.)

**No commit yet** — bundles with Task 2.
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Create `TextBoxSerializer`

**Verifies:** lasso-textboxes.AC4.3 (round-trip identity), lasso-textboxes.AC4.4 (malformed returns null)

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/TextBoxSerializer.kt`

**Implementation:**

```kotlin
package com.forestnote.app.notes

import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ZBand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Hand-written JSON serializer for [TextBox] (lasso-textboxes.AC4.3, AC4.4).
 *
 * Mirrors the *intent* of the design's "TextBoxSerializer like StrokeSerializer":
 * a JSON encoder for the future B1 `app_state.clipboard_json` round-trip. The
 * design plan referred to a hypothetical `org.json.JSONObject` `StrokeSerializer`
 * which does not exist (today's [com.forestnote.core.format.StrokeSerializer] is a
 * binary BLOB codec for `stroke.points`). This serializer uses `kotlinx.serialization.json`
 * instead, which is the only JSON library on the codebase's classpath (Settings.kt,
 * SyncWire.kt). A future serialization-cleanup pass can migrate both serializers
 * together if a single JSON shape is desired.
 *
 * Defensive contract:
 * - [toJson] never throws.
 * - [fromJson] returns null on ANY of: invalid JSON, non-object root, missing required
 *   field, wrong type for any field, unknown [ZBand] string. It never throws.
 *
 * Encoded shape (every field present, no defaults skipped — wire is explicit):
 * ```
 * {
 *   "id": "01H...",
 *   "x": 100, "y": 200, "width": 800, "height": 320,
 *   "text": "hello",
 *   "fontName": "Roboto-Regular.ttf",
 *   "fontSize": 32,
 *   "color": -16777216,
 *   "weight": 400,
 *   "borderWidth": 2,
 *   "zBand": "BOTTOM"
 * }
 * ```
 *
 * `color` is the signed-int ARGB value as-is (Int range). Matches the in-memory shape;
 * sync wire (SyncWire.textBoxCols) does the unsigned-Long encoding separately at a
 * different boundary.
 */
object TextBoxSerializer {

    private val json = Json { ignoreUnknownKeys = true }

    fun toJson(box: TextBox): String = buildJsonObject {
        put("id", JsonPrimitive(box.id))
        put("x", JsonPrimitive(box.x))
        put("y", JsonPrimitive(box.y))
        put("width", JsonPrimitive(box.width))
        put("height", JsonPrimitive(box.height))
        put("text", JsonPrimitive(box.text))
        put("fontName", JsonPrimitive(box.fontName))
        put("fontSize", JsonPrimitive(box.fontSize))
        put("color", JsonPrimitive(box.color))
        put("weight", JsonPrimitive(box.weight))
        put("borderWidth", JsonPrimitive(box.borderWidth))
        put("zBand", JsonPrimitive(box.zBand.name))
    }.toString()

    fun fromJson(raw: String): TextBox? {
        val obj: JsonObject = runCatching { json.parseToJsonElement(raw) }
            .getOrNull()
            ?.let { it as? JsonObject }
            ?: return null

        return runCatching {
            TextBox(
                id = obj.stringOrNull("id") ?: return null,
                x = obj.intOrNull("x") ?: return null,
                y = obj.intOrNull("y") ?: return null,
                width = obj.intOrNull("width") ?: return null,
                height = obj.intOrNull("height") ?: return null,
                text = obj.stringOrNull("text") ?: return null,
                fontName = obj.stringOrNull("fontName") ?: return null,
                fontSize = obj.intOrNull("fontSize") ?: return null,
                color = obj.intOrNull("color") ?: return null,
                weight = obj.intOrNull("weight") ?: return null,
                borderWidth = obj.intOrNull("borderWidth") ?: return null,
                zBand = parseZBand(obj["zBand"]) ?: return null,
            )
        }.getOrNull()
    }

    private fun parseZBand(el: JsonElement?): ZBand? {
        val name = (el as? JsonPrimitive)?.contentOrNull ?: return null
        return when (name) {
            "BOTTOM" -> ZBand.BOTTOM
            "TOP" -> ZBand.TOP
            else -> null
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull
}
```

A few implementation notes for the executor:

- `intOrNull` collides with the imported extension — we shadow it as a private helper on `JsonObject` for ergonomics. If the executor prefers using the import directly, that's fine; either works.
- `runCatching { … getOrNull() }` is the outer safety net so any unforeseen failure inside the construction surfaces as `null` instead of a thrown exception. AC4.4 demands "never throws."
- We do *not* skip missing optional fields with defaults — the wire is explicit. This makes the format diffable and the round-trip identity strict (AC4.3 says "every field").

**Verification:**

```
./gradlew :app:notes:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

**Commit:** `feat(notes): TextBoxSerializer toJson/fromJson with defensive null-on-malformed`
<!-- END_TASK_2 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_TASK_3 -->
### Task 3: `TextBoxSerializerTest` — round-trip + malformed handling

**Verifies:** lasso-textboxes.AC4.3, lasso-textboxes.AC4.4, lasso-textboxes.AC7.3

**Files:**
- Create: `app/notes/src/test/kotlin/com/forestnote/app/notes/TextBoxSerializerTest.kt`

**Implementation:**

```kotlin
package com.forestnote.app.notes

import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ZBand
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

class TextBoxSerializerTest {

    private fun box(
        id: String = "01HNB6X9ZK7Q3M0NJ2WS5V6PWC",
        x: Int = 100, y: Int = 200,
        w: Int = 800, h: Int = 320,
        text: String = "hello",
        fontName: String = "Roboto-Regular.ttf",
        fontSize: Int = 32,
        color: Int = 0xFF112233.toInt(),
        weight: Int = 700,
        borderWidth: Int = 4,
        zBand: ZBand = ZBand.BOTTOM,
    ) = TextBox(
        id = id, x = x, y = y, width = w, height = h, text = text,
        fontName = fontName, fontSize = fontSize, color = color,
        weight = weight, borderWidth = borderWidth, zBand = zBand,
    )

    // AC4.3 — every field survives round-trip identity-equal.

    @Test
    fun roundTripPreservesAllFieldsBottomBand() {
        val original = box(zBand = ZBand.BOTTOM)
        val json = TextBoxSerializer.toJson(original)
        val decoded = TextBoxSerializer.fromJson(json)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripPreservesAllFieldsTopBand() {
        val original = box(zBand = ZBand.TOP)
        val json = TextBoxSerializer.toJson(original)
        val decoded = TextBoxSerializer.fromJson(json)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripPreservesNegativeColorSignedInt() {
        // ARGB black is 0xFF000000 which is a negative Int. Make sure the sign survives.
        val original = box(color = 0xFF000000.toInt())
        val decoded = TextBoxSerializer.fromJson(TextBoxSerializer.toJson(original))
        assertNotNull(decoded)
        assertEquals(0xFF000000.toInt(), decoded.color)
    }

    @Test
    fun roundTripPreservesEmptyText() {
        val original = box(text = "")
        assertEquals(original, TextBoxSerializer.fromJson(TextBoxSerializer.toJson(original)))
    }

    @Test
    fun roundTripPreservesUnicodeText() {
        val original = box(text = "héllo 世界 🌳 \" quote \\ slash")
        assertEquals(original, TextBoxSerializer.fromJson(TextBoxSerializer.toJson(original)))
    }

    @Test
    fun roundTripPreservesZeroDimensions() {
        // Edge case: a degenerate 0-sized box. Should still round-trip (model doesn't reject it).
        val original = box(w = 0, h = 0)
        assertEquals(original, TextBoxSerializer.fromJson(TextBoxSerializer.toJson(original)))
    }

    // AC4.4 — malformed input returns null, never throws.

    @Test
    fun fromJsonReturnsNullOnInvalidJson() {
        assertNull(TextBoxSerializer.fromJson("not json at all"))
        assertNull(TextBoxSerializer.fromJson(""))
        assertNull(TextBoxSerializer.fromJson("{"))
    }

    @Test
    fun fromJsonReturnsNullOnNonObjectRoot() {
        assertNull(TextBoxSerializer.fromJson("[]"))
        assertNull(TextBoxSerializer.fromJson("42"))
        assertNull(TextBoxSerializer.fromJson("\"a string\""))
        assertNull(TextBoxSerializer.fromJson("null"))
    }

    @Test
    fun fromJsonReturnsNullOnMissingRequiredField() {
        // Build a payload missing "text".
        val original = box()
        val full = TextBoxSerializer.toJson(original)
        val missingText = full.replace(Regex("\"text\":\\s*\"[^\"]*\",?\\s*"), "")
        assertNull(TextBoxSerializer.fromJson(missingText))
    }

    @Test
    fun fromJsonReturnsNullOnWrongFieldType() {
        // "x" is "abc" instead of an int.
        val payload = """{"id":"a","x":"abc","y":0,"width":1,"height":1,"text":"t","fontName":"f","fontSize":1,"color":0,"weight":0,"borderWidth":0,"zBand":"BOTTOM"}"""
        assertNull(TextBoxSerializer.fromJson(payload))
    }

    @Test
    fun fromJsonReturnsNullOnUnknownZBand() {
        val payload = """{"id":"a","x":0,"y":0,"width":1,"height":1,"text":"t","fontName":"f","fontSize":1,"color":0,"weight":0,"borderWidth":0,"zBand":"MIDDLE"}"""
        assertNull(TextBoxSerializer.fromJson(payload))
    }

    // AC7.3 — defensive defaults / extra fields are tolerated.

    @Test
    fun fromJsonIgnoresUnknownExtraFields() {
        // A future schema adds a field; current code must still parse the known shape.
        val payload = """{"id":"a","x":0,"y":0,"width":1,"height":1,"text":"t","fontName":"f","fontSize":1,"color":0,"weight":0,"borderWidth":0,"zBand":"BOTTOM","futureField":"ignored"}"""
        val decoded = TextBoxSerializer.fromJson(payload)
        assertNotNull(decoded)
        assertEquals("a", decoded.id)
    }
}
```

**Verification:**

```
./gradlew :app:notes:test --tests com.forestnote.app.notes.TextBoxSerializerTest
```
Expected: All tests pass.

**Commit:** `test(notes): TextBoxSerializer round-trip + defensive-null on malformed`
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Final phase sweep

**Verifies:** Phase-level: AC4.3, AC4.4, AC7.3 all pass.

**Files:** (no edits)

**Verification:**

```
./gradlew :app:notes:test
./gradlew :app:notes:assembleDebug
```
Expected: BUILD SUCCESSFUL on both.

**Done when:** `TextBoxSerializer.toJson(box)` followed by `.fromJson(json)` returns an identity-equal `TextBox`, and `.fromJson` on every malformed shape from Task 3 returns `null`. No future B1 callers are wired in this phase — it is shelf-ready for that future use.
<!-- END_TASK_4 -->
