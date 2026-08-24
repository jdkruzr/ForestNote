# ForestNote user guide

ForestNote is a handwriting-first notebook for e-ink tablets. It works as a completely local app:
you can write, organize, search, export, back up, and restore notes without creating an account or
configuring a server. Recognition, transcription, calendar tasks, and sync are optional additions.

For installation and project information, see the [README](../README.md).

## Start here

1. [Install or update ForestNote](guide/getting-started.md#installing-and-updating).
2. Choose where the local library lives when ForestNote first opens.
3. Open **Library**, create a notebook, and write with the stylus.
4. Make a local backup before experimenting with sync or moving to another device.

The first-run storage choice and backup behavior are explained in
[Getting started](guide/getting-started.md).

## Guide

| Page | Covers |
|------|--------|
| [Getting started](guide/getting-started.md) | Installation, updates, first-run storage, local-only operation, and backups |
| [Editor](guide/editor.md) | Toolbar, brushes, widths, erasers, text, lasso, pages, templates, undo/redo, and viewport controls |
| [Library and files](guide/library-and-files.md) | Folders, full-library search, selection, moving, recycle bin, PDF/SVG export, backup, and restore |
| [Recognition and connections](guide/recognition-and-connections.md) | On-device recognition, optional transcription endpoints, UltraBridge sync, and CalDAV tasks |
| [Device notes](guide/device-notes.md) | Viwoods fast ink, Boox behavior, generic Android fallback, and troubleshooting |

## Editor at a glance

The editor's top bar contains, from left to right:

- **Library**, previous/next page, and the tappable page counter;
- **Undo**, **Redo**, and viewport controls;
- the currently selected brush, **Lasso**, **Text**, and the selected eraser;
- **Paste**, **Clear**, **OCR**, and **Template**.

A small `▾` means tapping the cell opens a chooser. Finger touches never draw ink, but two-finger
gestures can move a zoomed page when the viewport is unlocked.

## What can use the network?

ForestNote starts with network sync off and full-page endpoint transcription off.

| Feature | Network behavior |
|---------|------------------|
| Writing, editing, Library, PDF/SVG export, backup/restore | Always local |
| Selection recognition and **Run local** page recognition | Local after a one-time language-model download |
| **Run endpoint** | Uploads the current page only when you tap it |
| UltraBridge sync | Runs only while **Enable network sync** is on |
| CalDAV tasks | Sends only tasks you explicitly create; queues them while offline |

Passwords and API keys are stored in Android's encrypted credential store and are excluded from
ForestNote backups.

## Getting help

The installed version appears at the bottom of **Settings → About**. When reporting a device issue,
include that version, the tablet model, Android/firmware version, stylus model, and whether the issue
also occurs after reopening the page. Debug file logging can be enabled under **Settings → Debug**;
see [Device notes](guide/device-notes.md#diagnostic-logs).
