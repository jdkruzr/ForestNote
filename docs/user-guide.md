# ForestNote user guide

How to use ForestNote, feature by feature. For what it is and how to install it, see the
[README](../README.md).

The editor has a top bar with your notebook name, page arrows, a page counter, and the tool
cells. Most tools are a single tap; a small `▾` on a tool's label means tapping it opens a
chooser (pens, erasers, fonts, templates).

## Writing

Pick a pen from the **Fountain** cell. Tapping it opens a popup with three pen types and a
row of width chips:

- **Fountain** — varies with pen pressure, like a real fountain nib.
- **Fineliner** — an even line that ignores pressure.
- **Highlighter** — a translucent wash that's drawn *behind* your ink, so it never covers what
  you've already written and never darkens where two strokes overlap.

The seven width chips set the line thickness. Each pen type remembers its own width, so a thin
Fineliner and a fat Highlighter coexist without re-picking every time you switch.

Finger touches are ignored — only the stylus draws. That's deliberate, so you can rest your
hand on the panel.

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

ForestNote syncs notebooks against a self-hosted **UltraBridge** server. Configure it under
**Settings → Sync**: the server URL, username, and password. Once set, the app syncs when it
comes to the foreground, on a periodic timer, and when you close the Library or recycle bin with
unsaved changes.

Sync is **multi-master** — edit the same notebook on two devices and the changes merge per row,
last-writer-wins, without a manual conflict step. Credentials are stored encrypted (Android
Keystore), not in plain settings.

If you don't run an UltraBridge server, leave Sync blank and ForestNote works fully offline.

## Settings reference

Reach Settings from the gear in the Library header.

| Section | What it controls |
|---------|------------------|
| Startup | Start in the Library or your last notebook; pre-fill new notebook names with a timestamp; sync when you close an overlay |
| Sync | UltraBridge server URL and credentials |
| Calendar (CalDAV tasks) | CalDAV collection URL and credentials, plus a connection test |
| Recognition models | Download or delete handwriting languages |
| Recycle bin | How many days before the bin auto-empties |
| Debug | On-device file logging (off by default) |
| About | App version |
