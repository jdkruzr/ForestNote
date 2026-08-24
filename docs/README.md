# ForestNote documentation

This directory contains both current product documentation and the archaeological layers that led
to it. Start with the current guides; older plans are retained because they explain important data
and rendering decisions, but they are not a description of the shipped interface.

## Using ForestNote

- [User guide](user-guide.md) — the public entry point
- [Getting started](guide/getting-started.md) — install, storage, local-only use, and first backup
- [Editor](guide/editor.md) — drawing tools, pages, templates, lasso, text, and viewport controls
- [Library and files](guide/library-and-files.md) — organization, search, export, backup, and restore
- [Recognition and connections](guide/recognition-and-connections.md) — optional OCR, endpoints,
  UltraBridge, and CalDAV
- [Device notes](guide/device-notes.md) — Viwoods, Boox, generic Android, and troubleshooting

## Building and releasing

- [Build from source](development/building.md) — required sibling repositories, toolchain, tests,
  and signed-release boundaries
- [ForestNote 2.0 test plan](test-plans/2026-08-23-forestnote-2.0.md) — release validation and live
  device evidence
- [Manual test checklist](manual-test-checklist.md) — older broad regression checklist
- [UltraBridge/Rhizome dev-box workflow](ultrabridge-rhizome-devbox-workflow.md) — coordinating the
  sync repositories

The module-level `CLAUDE.md` files are maintainer references close to the code. They describe
invariants and implementation boundaries rather than user workflows.

## Current design and research

- [Portable brushes in 2.0](design-notes/portable-brushes-2.0.md)
- [Future directions](design-notes/future-directions.md)
- [Viwoods native ink, August 2026](research/viwoods-native-ink-2026-08.md)
- [Viwoods IPC interoperability](research/viwoods-ipc-interop.md)
- [WiNote pen rendering](research/winote-pen-rendering.md)

Files prefixed `wip-` capture unresolved or recently resolved investigations. Verify them against
the code and current test plan before treating them as a specification.

## Historical archive

`design-plans/`, `implementation-plans/`, older `test-plans/`, and `implementation-summaries/`
record how earlier milestones were designed and delivered. They intentionally remain in place so
commits and cross-references do not rot. Their dates and acceptance criteria are historical; the
[user guide](user-guide.md), [README](../README.md), and current code win when they disagree.
