# ForestNote user guide

How to use ForestNote, feature by feature. For what it is and how to install it, see the
[README](../README.md).

The editor has a top bar with your notebook name, page arrows, a page counter, and the tool
cells. Most tools are a single tap; a small `▾` on a tool's label means tapping it opens a
chooser (pens, erasers, fonts, templates).

## Writing

Pick a pen from the **Fountain** cell. Tapping it opens a modal chooser with 17 brushes and a
row of width chips. The families include fountain, HB/2B/4B/6B/8B pencils, brush, ballpoint,
translucent and opaque markers, fineliner, four calligraphy nibs, highlighter, and dashed line.

- **Fountain** — varies with pen pressure, like a real fountain nib.
- **Fineliner** — an even line that ignores pressure.
- **Highlighter** — a translucent wash that's drawn *behind* your ink, so it never covers what
  you've already written and never darkens where two strokes overlap.

The nine width chips set the line thickness. Levels 8 and 9 extend beyond the old extra-large
setting for pens whose pressure range otherwise feels too light. Each brush remembers its own width, so a thin
Fineliner and a fat Highlighter coexist without re-picking every time you switch.

Finger touches are ignored — only the stylus draws. That's deliberate, so you can rest your
hand on the panel.

### Viwoods fast ink

On a supported Viwoods firmware, ForestNote receives pen samples directly from the tablet's ENote
service and sends only the changed bitmap region to the panel. This is enabled by default and gives
ForestNote native-class writing latency. It runs inside the ordinary ForestNote app: **root is not
required**, and the app does not invoke `su` or install a privileged helper.

Calligraphy brushes use their canonical nib angle in the live Mini preview. Viwoods reports tilt
magnitude but not pen azimuth, so the four nibs use stable brush-specific angles until pen-up rather
than pretending the preview can rotate with the physical pen.

If a Viwoods firmware update causes trouble, turn off **Settings → Debug → Use fastest Viwoods ink**.
ForestNote will restart its ink controller and use the compatible Android-input fallback. This
switch only changes how live ink reaches the screen; it does not change or convert your notes.

## Erasing

The **Stroke** cell holds two erasers:

- **Stroke eraser** removes a whole line wherever you touch it.
- **Pixel eraser** rubs out only the part you drag over, splitting a stroke if you erase through
  its middle.

If your stylus has a hardware eraser button, flipping the pen erases too. It uses whichever
eraser type you picked last.

## Page templates

Tap **Template** to set the background for the current page: blank, dot, ruled, or grid, at
5, 7, or 10 mm spacing. **Use default** follows the notebook's global template; picking a
specific one overrides it for just that page. The lines are sized to the panel's true physical
resolution, so 5 mm on screen is actually 5 mm.

## Notebooks, pages, and the Library

Tap the grid icon at the top-left of the editor to open the **Library** — a card grid of your
folders and notebooks.

- **Open a notebook** by tapping its card. **Descend into a folder** by tapping it; the
  breadcrumb and the Up button walk you back out.
- **Create** a notebook or folder from the **Notebook ▾** / **Folder ▾** cells in the Library
  header.
- **Long-press a notebook** for its properties — created and modified dates, page count, rename,
  and delete.
- **Search** from the magnifier in the header. It matches notebook and folder names and jumps
  straight to a result.
- **Add pages** with the `+` page arrow in the editor; move between pages with the `◀` `▶` arrows.

ForestNote reopens your last notebook and page on launch. If there's nothing to resume, it opens
the Library instead. You can force "always start in the Library" in Settings.

### Recycle bin

Deleting a notebook or folder moves it to the **Recycle** bin (reachable from the Library
header), not straight to oblivion. Restore items from there, or empty the bin to delete for good.
The bin also auto-empties after a number of days you set in Settings.

## Selecting, moving, and pasting

Switch to the **Lasso** tool and draw a loop around what you want. The lasso grabs both
handwriting and text boxes inside the loop. A floating menu appears:

- **Cut / Copy / Delete** act on the whole selection.
- **Drag** anything inside the loop to move the selection around the page.
- **Paste** is tap-to-place: tap Paste to arm it, then tap where you want the content to land.
  It keeps the relative spacing of everything you copied.

Two of the menu buttons — **Recognize** and **To-do** — only appear when the selection contains
handwriting, since both work on strokes. They're covered next.

## Turning handwriting into text

Lasso some handwriting and tap **Recognize**. ForestNote runs it through Google's on-device
handwriting recognizer and drops the result onto the page as an editable text box. Nothing
leaves the device.

Recognition needs Google Play Services and a language model. The first time you use a language,
ForestNote prompts you to download it (about 20 MB). Manage installed languages under
**Settings → Recognition models**.

For a whole page, tap **OCR**, choose **ForestNote transcription**, and use **Run local** for ML
Kit. You can optionally configure an OpenAI-compatible or Anthropic-compatible service under
**Settings → Full-page transcription** and then use **Run endpoint**. Endpoint transcription is
manual: opening a notebook or the OCR viewer never uploads anything. The configured API key is
stored in Android's encrypted credential store and is excluded from ForestNote backups. The result
is searchable locally; if network sync is enabled, it follows the same client-transcription sync
path as local ML Kit text.

You can also type a text box directly: pick the **Text** tool and drag a box. A full-screen
editor opens for the text, font, size, weight, border, and whether the box sits above or below
your ink. Tap an existing box to select it, then drag to move or use the corner handles to
resize.

## Sending a to-do to your calendar

Lasso a handwritten task and tap **To-do**. If you've set up a calendar (below), ForestNote
recognizes the text, shows a task sheet where you can edit the title, set a due date, add a
note, and optionally attach the full recognized text, then sends it as a task (a CalDAV VTODO).

Tasks go out through an **offline queue**. If you're not connected, the task waits and sends
itself once you're back on Wi-Fi — you won't lose it. Check the queue under **Settings →
Queued tasks**.

Set up your calendar under **Settings → Calendar (CalDAV tasks)**: the collection URL, username,
and password. Any CalDAV server (Nextcloud, Radicale, Fastmail, and others) will store the task.
The task also carries a back-link to the page it came from, but that link only resolves if your
server is UltraBridge; other servers keep the task itself just fine.

## Syncing across devices

ForestNote starts as a **local-only app**. Nothing in the note library needs an account or server.
If you want sync, configure a self-hosted **UltraBridge** server under **Settings → Sync**, save
the URL/credentials, and turn on **Enable network sync**. Once enabled, the app syncs when it
comes to the foreground, on a periodic timer, and when you close the Library or recycle bin with
unsaved changes.

Sync is **multi-master** — edit the same notebook on two devices and the changes merge per row,
last-writer-wins, without a manual conflict step. Credentials are stored encrypted (Android
Keystore), not in plain settings.

Turning sync off does not hide, delete, or downgrade local notes.

## Exporting and backing up

In the Library, tap **Select**, choose one or more notebooks, then tap **Export**. Pick PDF or SVG
and choose a destination in Android's file picker. A single notebook becomes one PDF; a single-page
SVG becomes one SVG. Multiple notebooks—or a multi-page SVG notebook—are packaged as a ZIP with
stable page ordering.

For a complete local copy, open **Settings → Local data → Create backup**. A
`.forestnote-backup` contains the whole notebook database and app settings but never passwords.
**Restore backup** validates the archive, keeps the current database beside the replacement as a
pre-restore recovery copy, then restarts ForestNote on the restored library.

## Settings reference

Reach Settings from the gear in the Library header.

| Section | What it controls |
|---------|------------------|
| Startup | Start in the Library or your last notebook; pre-fill new notebook names with a timestamp; sync when you close an overlay |
| Local data | Complete backup and restore; credentials are excluded |
| Sync | Explicit network-sync switch, UltraBridge URL, and credentials |
| Full-page transcription | Optional OpenAI-compatible or Anthropic-compatible endpoint, model, encrypted API key, and connection test |
| Calendar (CalDAV tasks) | CalDAV collection URL and credentials, plus a connection test |
| Recognition models | Download or delete handwriting languages |
| Recycle bin | How many days before the bin auto-empties |
| Debug | Viwoods fast-ink fallback switch (shown only on Viwoods) and on-device file logging |
| About | App version |
