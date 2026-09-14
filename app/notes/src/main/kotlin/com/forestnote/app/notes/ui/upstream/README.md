# Adopted Composables sources

These are real MIT source imports, not a dependency on the full latest binary toolkit.
Both upstreams support modification/distribution under MIT; the complete notice ships
in `assets/licenses/composables.txt` as well as the source headers.

- Compose Unstyled revision `448c4f33503754a726c9dca5782f46d9fe1e20e5`:
  `composeunstyled-button/src/commonMain/kotlin/com/composeunstyled/Button.kt` and
  `composeunstyled-build-modifier/src/commonMain/kotlin/com/composeunstyled/BuildModifier.kt`.
  Local changes: package relocation and provenance comment only.
- Composables UI revision `ebe24f3d32e5948e96f6388cbec89fa0bb4ae868`:
  `ui/src/commonMain/kotlin/com/composables/ui/components/Button.kt` is the source for
  the **adapted subset** in `../EinkButton.kt`. Retains the outlined/primary button
  composition over UnstyledButton; maps visuals to existing FN resources. Omits bounce,
  ripple, translucency, hover-only link variants, adaptive padding and the upstream 2.x
  theming APIs. Adds static pressed/focused states, e-ink label sizes and contrast.
  D54 adds optional leading FN icons, explicit primary/outlined variants, unboxed
  tabs inside a shared frame, and named Library surface radii. These remain local
  adaptations, not additional upstream component imports.

Pinned host: AndroidX Compose 1.7.8, Kotlin Compose compiler 2.0.21. This avoids silently
upgrading all FN modules to the current upstream Kotlin 2.4/Compose toolchain. Before
adopting additional components, inspect their dependencies and record the source hash
and local differences here; never overwrite these adaptations via a CLI update.
