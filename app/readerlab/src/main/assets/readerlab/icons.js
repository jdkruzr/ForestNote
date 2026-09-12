/** Shared, font-independent dots for toolbar and dynamically-created menu triggers. */
export function moreIcon() {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('class', 'moreIcon');
  svg.setAttribute('aria-hidden', 'true'); svg.setAttribute('focusable', 'false');
  for (const x of [5, 12, 19]) {
    const dot = document.createElementNS(ns, 'circle');
    dot.setAttribute('cx', String(x)); dot.setAttribute('cy', '12'); dot.setAttribute('r', '1.8');
    svg.append(dot);
  }
  return svg;
}
