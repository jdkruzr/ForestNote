// Display-only normalization. Keep the book's bytes and text coordinates intact.
// object-fit protects HTML replaced images, but does not override an inline SVG's
// preserveAspectRatio="none" (a common full-screen cover wrapper).
const meet = element => {
  const value = element.getAttribute('preserveAspectRatio')?.trim() ?? '';
  if (/\bnone\b/.test(value)) element.setAttribute('preserveAspectRatio', 'xMidYMid meet');
  else if (/\bslice\b/.test(value)) element.setAttribute('preserveAspectRatio', value.replace(/\bslice\b/, 'meet'));
};
export function fitBookImages(doc) {
  for (const element of doc.querySelectorAll('img,svg,image')) {
    if (element.closest('[data-lab-generated],[data-annotation]')) continue;
    if (element.localName === 'svg' || element.localName === 'image') meet(element);
    if (element.localName === 'image') continue;
    // Publisher minimum sizes can defeat the paginator's maximum page size.
    // Keep authored preferred sizes (including small inline symbols), but let
    // artwork shrink to the column. Letterboxing is preferable to cropping.
    element.style.setProperty('min-width', '0', 'important');
    element.style.setProperty('min-height', '0', 'important');
    const maximum = doc.defaultView.getComputedStyle(element).maxWidth;
    element.style.setProperty('max-width', maximum === 'none' || maximum === '0px' ? '100%' : `min(100%, ${maximum})`, 'important');
    element.style.setProperty('object-fit', 'contain', 'important');
  }
}
