# D61 — Settings presentation resources

Return hook: [integration review, item 3](2026-09-12-forestread-progress-review.md),
and [internationalization plan](2026-09-14-forestread-internationalization.md).

The existing Settings layout and its dynamic presentation strings now use standard
Android resources in `res/values/settings.xml`: labels, hints, toasts, model dialogs,
connection results and queued-task descriptions. There are 98 string/plural resources.
Formatted messages use positional placeholders; retry descriptions use Android plurals.
User text and remote diagnostic messages remain arguments, not format strings. URL and
numeric examples plus the ForestNote product name are explicitly non-translatable.

Protocol syntax, preference keys, stored enums, model identifiers and credentials are
unchanged. This does not connect Settings to the shared shelf or alter any network,
backup, restore, model download or deletion behavior. English copy is largely preserved;
model-dialog titles use the established menu capitalization. Language names still come
from the existing RecognitionModelManager and are a separate localization follow-up.

JVM source guards prohibit new inline presentation attributes in the Settings layout
and common inline text/dialog/toast literals in SettingsView. Native tests inflate the
resource-backed layout without constructing a repository or network/model manager, and
check English plural/format output with percent signs and non-Latin user text. These
are incremental checks, not a complete Kotlin parser or whole-application i18n audit.

## Shared Settings integration boundary

1. Extract reusable local preference controls first: default template/pitch, naming,
   startup behavior, and device presentation. Keep load guards and worker-backed writes;
   a delayed load must not overwrite a user's newer draft or execute a write on binding.
2. Attach those controls to the shared shelf using the existing NotebookStore. No second
   repository, hidden legacy owner, credential store or SyncController is permitted.
3. Add explicit capabilities for model management and other services, with lifecycle
   ownership and recognition backfill retained by the same owner.
4. Route enrollment, sync policy/history and recovery/backup to their already-qualified
   owner workflows. Do not transplant the legacy Settings network/restore controls and
   accidentally activate production storage or mix notebook-only backup with reader data.
5. Qualify cancel/recreation, slow loads, unchanged unrelated settings and no unexpected
   network/outbox activity. Follow with HTML dictionary, pseudo-locales, RTL and expanded
   text; no additional language packs or locale-picker policy are selected yet.

Evidence shares `/home/jtd/.cache/forestread-writer-penu-Rrnerx/` with the immediately
following Penu interaction revision. Source/build/native checks are recorded there;
the native resource test performs no settings actions or external writes.

`build-modal-final2.log` passes both app builds and 486 app JVM tests.
`native-modal-full.log` passes 47 Go tests, including the two Settings resource checks.
The artifact hashes and preserved-data comparison are in the
[D60 revision evidence](2026-09-14-forestread-writer-penu.md).
