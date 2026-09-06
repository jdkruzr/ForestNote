# Foliate paginator extension

Pinned upstream: `78914aef4466eb960965702401634c2cb348e9b1`.
`scripts/prepare.mjs` verifies each original file against `foliate-lock.json`, then
applies these changes to generated assets only:

```diff
     render(layout) {
-        if (!layout) return
+        if (!layout || !this.document?.body) return
     expand() {
+        if (!this.document?.body) return
+        this.#contentRange.selectNodeContents(this.document.body)
         const { documentElement } = this.document
    #afterScroll(reason) {
+        if (!this.#view?.document?.body) return
         const range = this.#getVisibleRange()
```

The stock paginator captures a DOM Range once when loading the chapter. Replacing
the body contents during an annotation reflow collapses that live range to zero
length; pagination subsequently measures zero content width and hides the iframe.
Re-selecting body contents before measurement permits rebuilding annotated content.
The guards handle pending ResizeObserver/font callbacks during iframe load or teardown.
The scroll guard prevents delayed relocation callbacks from walking a removed document
while switching/reloading books (observed in the existing-highlight regression test).

This is a maintained local extension, not an upstream submission. Original files,
their SHA-256 digests, and the upstream MIT license are included in generated assets.
