# Format Domain (core:format)

Last verified: 2026-03-25

## Purpose
Persists notebook data (strokes) to a SQLite database file (.forestnote) using SQLDelight. Provides auto-save on pen-up and full restore on app launch.

## Contracts
- **Exposes**: `NotebookRepository` (open, saveStroke, loadStrokes, deleteStroke, clearPage, close), `StrokeSerializer` (encode/decode)
- **Guarantees**: Corrupted databases are deleted and recreated (AC2.4). Stroke round-trip through serialize/deserialize is lossless. V1 operates on a single implicit page.
- **Expects**: Android Context for database creation. `Stroke`/`StrokePoint` types from `core:ink`.

## Dependencies
- **Uses**: `core:ink` (Stroke, StrokePoint types), SQLDelight (AndroidSqliteDriver)
- **Used by**: `app:notes` (NotebookRepository)
- **Boundary**: Must not depend on `app:notes` or Android UI

## Key Decisions
- SQLDelight over Room: type-safe SQL, smaller footprint, no annotation processing
- BLOB encoding for points: 5 ints per point (x, y, pressure, tsHigh, tsLow), little-endian. Compact and fast.
- Single-file database named `default.forestnote`: V1 simplicity, extensible to multi-notebook later
- Factory methods (open/forTesting/openExisting) instead of public constructor: controls lifecycle

## Invariants
- Database always has at least one page row after open
- StrokeSerializer rejects truncated BLOBs (returns empty list, never crashes)
- Stroke IDs from saveStroke() are always > 0

## Key Files
- `NotebookRepository.kt` - Storage facade (CRUD operations)
- `StrokeSerializer.kt` - Binary point encoding/decoding
- `notebook.sq` - SQLDelight schema and queries

## Gotchas
- NotebookRepository.open() silently deletes and recreates on any database error
- Stroke.id=0 means unsaved; only saved strokes have positive IDs
