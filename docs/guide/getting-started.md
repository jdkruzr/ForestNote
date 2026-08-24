# Getting started

[← User guide](../user-guide.md)

ForestNote requires Android 11 (API 30) or newer. A stylus is strongly recommended: stylus input
draws, while ordinary finger touches are reserved for controls and viewport gestures.

## Installing and updating

Download the APK from [GitHub Releases](https://github.com/jdkruzr/ForestNote/releases/latest), open
it on the tablet, and allow installation from that file source if Android asks.

To update ForestNote, install the newer APK over the existing app. Do **not** uninstall the old app
first: uninstalling removes an app-private library. A normal signed upgrade preserves the library
and migrates its database automatically. Creating a ForestNote backup before a major upgrade is
still sensible insurance.

ForestNote is currently distributed by direct APK rather than the Play Store. It does not need root,
`su`, a privileged helper, or an account.

## Choosing where the library lives

On first launch, ForestNote offers two local storage choices.

### Grant All files access

ForestNote stores the library at:

```text
/sdcard/ForestNote/default.forestnote
```

This location survives ordinary app updates and can survive uninstall/reinstall. Android displays a
broad **All files access** permission because a top-level `/sdcard/ForestNote` database cannot be
maintained through the narrower document picker. ForestNote uses it for its own library and optional
debug log.

If an app-private library already exists when access is granted, ForestNote migrates it to the
external location and reopens it.

### Keep private

ForestNote stores the library inside its private application data. Every feature still works,
including document-picker export and backup. The tradeoff is that Android removes this library if
ForestNote is uninstalled or its app data is cleared.

Whichever location you choose, make portable backups from **Settings → Local data**. A raw database
file is not a substitute for the validated backup workflow.

## First notebook

Tap the Library icon at the far left of the editor, then tap **Notebook**. Enter a name and open the
new card. The right page arrow creates a page when you are already on the notebook's final page; the
page counter opens the thumbnail page browser.

ForestNote normally reopens the last notebook and page. To start in the Library instead, choose
**Settings → Startup → Library**. Settings can also pre-fill new notebook names with a timestamp.

## Local-only operation

A fresh installation has network sync disabled and full-page endpoint transcription set to
**Off (local-only)**. Writing, text boxes, folders, search, recycle bin, PDF/SVG export, backups, and
restores need no account or server.

On-device handwriting recognition requires a one-time language-model download of roughly 20 MB per
language. Once installed, that recognition runs locally. UltraBridge sync, CalDAV tasks, and manual
endpoint transcription remain opt-in and are described in
[Recognition and connections](recognition-and-connections.md).

## Make a first backup

Open **Library → Settings → Local data → Create backup…** and choose a destination. The resulting
`.forestnote-backup` contains the complete notebook database and ordinary app settings. Passwords
and API keys are deliberately excluded.

Keep at least one backup somewhere other than the tablet before changing storage, moving devices,
or experimenting with synchronization. See [Library and files](library-and-files.md#complete-backup-and-restore)
for restore behavior.
