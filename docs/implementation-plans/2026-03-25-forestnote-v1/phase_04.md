# ForestNote v1 Implementation Plan — Phase 4: SQLDelight Storage

**Goal:** Per-notebook `.forestnote` SQLite files with stroke persistence via auto-save on pen-up.

**Architecture:** SQLDelight generates type-safe Kotlin from `.sq` schema files. `StrokeSerializer` encodes `List<StrokePoint>` as compact `IntArray` BLOBs. `NotebookRepository` provides CRUD operations and manages database file open/close. V1 uses a single implicit notebook with one page.

**Tech Stack:** SQLDelight 2.0.2, AndroidSqliteDriver (runtime), JdbcSqliteDriver (tests)

**Scope:** 8 phases from original design (phase 4 of 8)

**Codebase verified:** 2026-03-25

---

## Acceptance Criteria Coverage

This phase implements and tests:

### forestnote-v1.AC2: Storage & Persistence
- **forestnote-v1.AC2.1 Success:** Strokes auto-save to a .forestnote SQLite file on pen-up
- **forestnote-v1.AC2.2 Success:** All strokes are restored exactly when the app is killed and relaunched
- **forestnote-v1.AC2.3 Success:** StrokePoint data (x, y, pressure, timestamp) survives a serialize/deserialize round-trip without data loss
- **forestnote-v1.AC2.4 Failure:** Corrupted or missing .forestnote file results in a new empty document, not a crash
- **forestnote-v1.AC2.5 Success:** Strokes created on a 1440x1920 device render at correct proportions on a different screen resolution

---

## Codebase Verification Findings

- **`:core:format` module** exists as empty skeleton after Phase 1 (build.gradle.kts + AndroidManifest.xml only)
- **No SQLDelight configuration** anywhere in the project — must be added
- **`gradle/libs.versions.toml`** includes `sqldelight = "2.0.2"` and related library entries from Phase 1
- **StrokePoint, Stroke** types exist in `:core:ink` from Phase 3
- **No existing database or storage code** — created from scratch
- **Design deviation:** `:core:format` will depend on `:core:ink` for `StrokePoint`/`Stroke` types, since the serializer needs those types. The design says core modules don't depend on each other, but sharing data types is pragmatic for v1.

**SQLDelight research findings:**
- Plugin: `id("app.cash.sqldelight")` — must add `sqldelight-gradlePlugin` to `build-logic/build.gradle.kts`
- Runtime driver: `AndroidSqliteDriver(schema, context, "filename.forestnote")`
- Test driver: `JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)` with `sqlite-driver` dependency
- BLOB columns map to `ByteArray` automatically
- `.sq` files go in `src/main/sqldelight/com/forestnote/core/format/`

---

<!-- START_TASK_1 -->
### Task 1: Add SQLDelight Gradle plugin and test driver to build config

**Files:**
- Modify: `build-logic/build.gradle.kts` (add sqldelight-gradlePlugin dependency)
- Modify: `gradle/libs.versions.toml` (add sqlite-driver test dependency)
- Modify: `core/format/build.gradle.kts` (add SQLDelight plugin, dependencies, configure database)

**Step 1: Add SQLDelight Gradle plugin to build-logic**

Add the SQLDelight Gradle plugin as a dependency in `build-logic/build.gradle.kts` so convention plugins and modules can apply it:

```kotlin
dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.sqldelight.gradlePlugin)  // ADD THIS LINE
}
```

**Step 2: Add JVM test driver to version catalog**

Add to `gradle/libs.versions.toml` in the `[libraries]` section:

```toml
sqldelight-sqlite-driver = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sqldelight" }
```

**Step 3: Configure `:core:format` module**

Replace `core/format/build.gradle.kts` with:

```kotlin
plugins {
    id("forestnote.android.library")
    id("app.cash.sqldelight")
}

android {
    namespace = "com.forestnote.core.format"
}

sqldelight {
    databases {
        create("NotebookDatabase") {
            packageName.set("com.forestnote.core.format")
        }
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(project(":core:ink"))
    implementation(libs.findLibrary("sqldelight-android-driver").get())
    implementation(libs.findLibrary("sqldelight-runtime").get())

    testImplementation(libs.findLibrary("sqldelight-sqlite-driver").get())
}
```

**Step 4: Verify build**

```bash
./gradlew :core:format:assembleDebug
```

Expected: Compiles (no .sq files yet, but plugin is configured).

**Step 5: Commit**

```bash
git add build-logic/build.gradle.kts gradle/libs.versions.toml core/format/build.gradle.kts
git commit -m "chore(format): configure SQLDelight plugin and drivers

Adds sqldelight-gradlePlugin to build-logic, sqlite-driver for JVM tests,
and configures :core:format with NotebookDatabase and :core:ink dependency."
```
<!-- END_TASK_1 -->

<!-- START_SUBCOMPONENT_A (tasks 2-3) -->
<!-- START_TASK_2 -->
### Task 2: notebook.sq schema and StrokeSerializer

**Files:**
- Create: `core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq`
- Create: `core/format/src/main/kotlin/com/forestnote/core/format/StrokeSerializer.kt`

**Step 1: Create `notebook.sq`**

The schema defines tables for the `.forestnote` file format. V1 populates only `page` and `stroke` tables. Future tables are defined but empty in v1.

```sql
-- Page table: each notebook has one or more pages
CREATE TABLE page (
    id INTEGER PRIMARY KEY NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

-- Stroke table: vector strokes on a page
-- points is a compact IntArray BLOB encoded by StrokeSerializer
CREATE TABLE stroke (
    id INTEGER PRIMARY KEY NOT NULL,
    page_id INTEGER NOT NULL REFERENCES page(id) ON DELETE CASCADE,
    color INTEGER NOT NULL DEFAULT -16777216,
    pen_width_min INTEGER NOT NULL DEFAULT 7,
    pen_width_max INTEGER NOT NULL DEFAULT 35,
    points BLOB NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX stroke_page_id ON stroke(page_id);

-- V1 queries --

getPage:
SELECT * FROM page WHERE id = ?;

getFirstPage:
SELECT * FROM page ORDER BY sort_order ASC LIMIT 1;

insertPage:
INSERT INTO page(sort_order, created_at) VALUES (?, ?);

lastInsertRowId:
SELECT last_insert_rowid();

getStrokesForPage:
SELECT * FROM stroke WHERE page_id = ? ORDER BY id ASC;

insertStroke:
INSERT INTO stroke(page_id, color, pen_width_min, pen_width_max, points, created_at)
VALUES (?, ?, ?, ?, ?, ?);

deleteStroke:
DELETE FROM stroke WHERE id = ?;

deleteStrokesForPage:
DELETE FROM stroke WHERE page_id = ?;
```

**Step 2: Create `StrokeSerializer.kt`**

Encodes `List<StrokePoint>` to `ByteArray` (for BLOB storage) and decodes back. Format: 5 ints per point (x, y, pressure, timestampHigh, timestampLow).

```kotlin
package com.forestnote.core.format

import com.forestnote.core.ink.StrokePoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes and decodes StrokePoint lists to/from compact ByteArray BLOBs.
 *
 * Format: Little-endian IntArray where each point is 5 consecutive ints:
 *   [x, y, pressure, timestampHigh, timestampLow]
 *
 * Total bytes = numPoints * 5 * 4
 */
object StrokeSerializer {

    private const val INTS_PER_POINT = 5

    fun encode(points: List<StrokePoint>): ByteArray {
        val buffer = ByteBuffer.allocate(points.size * INTS_PER_POINT * Int.SIZE_BYTES)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        for (p in points) {
            buffer.putInt(p.x)
            buffer.putInt(p.y)
            buffer.putInt(p.pressure)
            buffer.putInt((p.timestampMs ushr 32).toInt())
            buffer.putInt(p.timestampMs.toInt())
        }

        return buffer.array()
    }

    fun decode(blob: ByteArray): List<StrokePoint> {
        if (blob.isEmpty()) return emptyList()

        // Defensive: reject truncated or corrupted BLOBs
        val bytesPerPoint = INTS_PER_POINT * Int.SIZE_BYTES
        if (blob.size % bytesPerPoint != 0) return emptyList()

        val buffer = ByteBuffer.wrap(blob)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        val numPoints = blob.size / bytesPerPoint
        val points = ArrayList<StrokePoint>(numPoints)

        repeat(numPoints) {
            val x = buffer.getInt()
            val y = buffer.getInt()
            val pressure = buffer.getInt()
            val tsHigh = buffer.getInt().toLong() and 0xFFFFFFFFL
            val tsLow = buffer.getInt().toLong() and 0xFFFFFFFFL
            val timestampMs = (tsHigh shl 32) or tsLow

            points.add(StrokePoint(x, y, pressure, timestampMs))
        }

        return points
    }
}
```

**Step 3: Verify build**

```bash
./gradlew :core:format:assembleDebug
```

Expected: SQLDelight generates `NotebookDatabase` class and query interfaces from `notebook.sq`.

**Step 4: Commit**

```bash
git add core/format/src/
git commit -m "feat(format): add notebook.sq schema and StrokeSerializer

SQLDelight schema defines page and stroke tables. StrokeSerializer
encodes StrokePoint lists as compact IntArray BLOBs (5 ints per point)."
```
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: NotebookRepository — database file management and CRUD

**Verifies:** forestnote-v1.AC2.1, forestnote-v1.AC2.2, forestnote-v1.AC2.4

**Files:**
- Create: `core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt`

**Step 1: Create `NotebookRepository.kt`**

Manages per-notebook `.forestnote` database files. Opens or creates the database, provides stroke CRUD, handles the v1 single-page model.

```kotlin
package com.forestnote.core.format

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokePoint

/**
 * Storage facade for .forestnote notebook files.
 *
 * V1 operates on a single implicit notebook with one page.
 * Opens the database on construction, creates schema if new.
 */
class NotebookRepository private constructor(
    private val driver: SqlDriver,
    private val db: NotebookDatabase
) {
    private var currentPageId: Long = -1

    companion object {
        private const val DEFAULT_FILENAME = "default.forestnote"

        /**
         * Open or create the default v1 notebook.
         * If the file is corrupted, deletes it and starts fresh (AC2.4).
         */
        fun open(context: Context): NotebookRepository {
            return try {
                val driver = AndroidSqliteDriver(
                    schema = NotebookDatabase.Schema,
                    context = context,
                    name = DEFAULT_FILENAME
                )
                val db = NotebookDatabase(driver)
                NotebookRepository(driver, db).also { it.ensurePage() }
            } catch (e: Throwable) {
                // Corrupted database — delete and recreate (AC2.4)
                context.deleteDatabase(DEFAULT_FILENAME)
                val driver = AndroidSqliteDriver(
                    schema = NotebookDatabase.Schema,
                    context = context,
                    name = DEFAULT_FILENAME
                )
                val db = NotebookDatabase(driver)
                NotebookRepository(driver, db).also { it.ensurePage() }
            }
        }

        /**
         * Create a new repository with schema creation (for testing new databases).
         */
        fun forTesting(driver: SqlDriver): NotebookRepository {
            NotebookDatabase.Schema.create(driver)
            val db = NotebookDatabase(driver)
            return NotebookRepository(driver, db).also { it.ensurePage() }
        }

        /**
         * Open an existing database without running schema creation (for testing
         * persistence across driver instances — Phase 8 integration tests).
         */
        fun openExisting(driver: SqlDriver): NotebookRepository {
            val db = NotebookDatabase(driver)
            return NotebookRepository(driver, db).also { it.ensurePage() }
        }
    }

    private fun ensurePage() {
        val page = db.notebookQueries.getFirstPage().executeAsOneOrNull()
        if (page != null) {
            currentPageId = page.id
        } else {
            db.notebookQueries.insertPage(
                sort_order = 0,
                created_at = System.currentTimeMillis()
            )
            currentPageId = db.notebookQueries.lastInsertRowId().executeAsOne()
        }
    }

    /**
     * Save a completed stroke. Called on pen-up for auto-save (AC2.1).
     * Returns the database ID of the inserted stroke.
     */
    fun saveStroke(stroke: Stroke): Long {
        val blob = StrokeSerializer.encode(stroke.points)
        db.notebookQueries.insertStroke(
            page_id = currentPageId,
            color = stroke.color.toLong(),
            pen_width_min = stroke.penWidthMin.toLong(),
            pen_width_max = stroke.penWidthMax.toLong(),
            points = blob,
            created_at = System.currentTimeMillis()
        )
        return db.notebookQueries.lastInsertRowId().executeAsOne()
    }

    /**
     * Load all strokes for the current page. Used on app restore (AC2.2).
     */
    fun loadStrokes(): List<Stroke> {
        return db.notebookQueries.getStrokesForPage(currentPageId)
            .executeAsList()
            .map { row ->
                Stroke(
                    id = row.id,
                    points = StrokeSerializer.decode(row.points),
                    color = row.color.toInt(),
                    penWidthMin = row.pen_width_min.toInt(),
                    penWidthMax = row.pen_width_max.toInt()
                )
            }
    }

    /**
     * Delete a single stroke by ID. Used by stroke eraser.
     */
    fun deleteStroke(strokeId: Long) {
        db.notebookQueries.deleteStroke(strokeId)
    }

    /**
     * Delete all strokes on the current page. Used by Clear tool.
     */
    fun clearPage() {
        db.notebookQueries.deleteStrokesForPage(currentPageId)
    }

    /**
     * Close the database connection.
     */
    fun close() {
        driver.close()
    }
}
```

**Step 2: Commit**

```bash
git add core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt
git commit -m "feat(format): add NotebookRepository with auto-save and restore

Opens/creates .forestnote database files. Provides saveStroke (pen-up
auto-save), loadStrokes (app restore), and delete operations.
Handles corrupted databases by recreating (AC2.4)."
```
<!-- END_TASK_3 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 4-5) -->
<!-- START_TASK_4 -->
### Task 4: Unit tests for StrokeSerializer

**Verifies:** forestnote-v1.AC2.3

**Files:**
- Create: `core/format/src/test/kotlin/com/forestnote/core/format/StrokeSerializerTest.kt`

**Implementation:**

StrokeSerializer tests verify:
- Round-trip: encode then decode returns identical StrokePoint values (x, y, pressure, timestamp) — this is AC2.3
- Empty list encodes to empty byte array and decodes back to empty list
- Single point round-trip preserves all fields exactly
- Multiple points round-trip preserves order and all field values
- Large coordinates (near 10,000 virtual limit) and max pressure (1000) survive round-trip
- Timestamp values (Long) survive the high/low int split and recombination

**Testing:**
- forestnote-v1.AC2.3: StrokePoint data (x, y, pressure, timestamp) survives a serialize/deserialize round-trip without data loss

Follow JUnit 4 patterns. Pure JVM tests, no Android dependencies.

**Verification:**

```bash
./gradlew :core:format:test
```

Expected: All serializer tests pass.

**Commit:**

```bash
git add core/format/src/test/
git commit -m "test(format): add StrokeSerializer round-trip tests (AC2.3)

Verifies all StrokePoint fields survive encode/decode without data loss."
```
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: Unit tests for NotebookRepository

**Verifies:** forestnote-v1.AC2.1, forestnote-v1.AC2.2, forestnote-v1.AC2.4, forestnote-v1.AC2.5

**Files:**
- Create: `core/format/src/test/kotlin/com/forestnote/core/format/NotebookRepositoryTest.kt`

**Implementation:**

Uses `JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)` for in-memory database testing. Uses `NotebookRepository.forTesting(driver)` factory method.

NotebookRepository tests verify:
- Save a stroke and load it back — all fields match (AC2.1, AC2.2)
- Save multiple strokes and load all — order preserved, all data intact (AC2.2)
- Delete a stroke and verify it's gone from load results
- Clear page deletes all strokes
- Strokes stored in virtual coordinates (10,000-unit space) are loaded back identically regardless of any screen size concept — resolution independence is inherent in the storage format (AC2.5)
- Fresh database creates an initial page automatically
- `forTesting` with a new driver creates a working database (simulates AC2.4: corrupted → recreate)

**Testing:**
- forestnote-v1.AC2.1: `saveStroke()` persists stroke that is retrievable by `loadStrokes()`
- forestnote-v1.AC2.2: Strokes saved then loaded back match exactly
- forestnote-v1.AC2.4: Repository creates a valid empty database from scratch (simulating corruption recovery)
- forestnote-v1.AC2.5: Virtual coordinates stored and retrieved identically (resolution independence is inherent — test that coordinates are not transformed)

Follow JUnit 4 patterns. Uses JdbcSqliteDriver for JVM testing.

**Verification:**

```bash
./gradlew :core:format:test
```

Expected: All repository tests pass.

**Commit:**

```bash
git add core/format/src/test/
git commit -m "test(format): add NotebookRepository CRUD tests (AC2.1-2.5)

Verifies save/load round-trip, deletion, clear, and resolution-independent
storage using in-memory SQLite driver."
```
<!-- END_TASK_5 -->
<!-- END_SUBCOMPONENT_B -->
