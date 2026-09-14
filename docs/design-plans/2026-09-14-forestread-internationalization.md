# Queued — shared UI internationalization

Requested during D57/D58 integration. Return hook:
[larger integration review, item 3](2026-09-12-forestread-progress-review.md).

Start at an opportune integration checkpoint, preferably as shared Settings and the
writer Penu are extracted. This is queued work, not a claim of current localization
coverage, and does not require choosing additional languages now.

## Direction

- Move user-visible native UI literals into Android string resources. Domain-organized
  files under `res/values/` are all part of the same Android resource system; use stable,
  descriptive keys and standard locale variants rather than a custom native loader.
- Inventory the reader HTML/JavaScript chrome too. Design a generated or injected
  resource dictionary so it follows the selected application locale without manually
  maintained duplicate English copies. Validate the build/runtime boundary before
  choosing the exact bridge; do not replace native Android resources with ad hoc JSON.
- Include dialogs, accessibility descriptions, menus, status/error messages, empty
  states and search hints. Use plurals, named/positional formatting appropriate to
  each runtime, and locale-aware numbers/dates; avoid assembled English sentences.
- Establish shared terminology and context for translators. Reuse a key only when its
  meaning is genuinely identical, not merely because the English text happens to match.
- Keep protocol identifiers, enum names, command names, database values and document/user
  text untouched. Translate presentation, never the storage/sync contract.
- Exercise pseudo-locales, expanded text, RTL layout, three-line titles and narrow
  e-ink hosts. Include content descriptions and physical tap targets; scaling text down
  to conceal clipping is not the acceptance criterion.
- Migrate incrementally by surface, with checks preventing new hard-coded chrome from
  creeping back in. Existing resource-backed D53–D58 controls provide a starting point.

Actual language packs and a language-picker policy can follow the extraction; neither
is implicitly required by this request. Production activation, PDF and document text
translation are separate work.

## Started

- D60: native writer Penu labels and accessibility text in `penu.xml`.
- [D61](2026-09-14-forestread-settings-resources.md): existing Settings layout and dynamic
  presentation in `settings.xml`, with plural/format tests and incremental source guards.
- Reader HTML dictionary, locale-aware model language names, pseudo-locales/RTL and
  remaining native surfaces are still open. No full-app localization claim yet.
