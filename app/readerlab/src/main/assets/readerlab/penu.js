export function createPenu($, { onChange, rememberedWidth }) {
const widthValues = [15, 24, 30, 35, 42, 50, 70, 100, 140];
function syncPenChoice() {
  $('activePenName').textContent = $('pen').selectedOptions[0].textContent;
  for (const button of $('widthPresets').querySelectorAll('button')) button.setAttribute('aria-pressed', String(Number(button.dataset.width) === Number($('width').value)));
  for (const button of $('penGroups').querySelectorAll('button')) button.setAttribute('aria-pressed', String(button.dataset.pen === $('pen').value));
}
for (const [index, group] of [...$('pen').querySelectorAll('optgroup')].entries()) {
  const section = document.createElement('section'), heading = document.createElement('h3'), rows = document.createElement('div');
  section.className = 'penCategory penuSection'; section.setAttribute('role', 'group');
  heading.id = `penCategory${index}`; heading.textContent = group.label; heading.className = 'penuSectionTitle';
  section.setAttribute('aria-labelledby', heading.id); rows.className = 'penRows penuRows';
  for (const option of group.querySelectorAll('option')) {
    const button = document.createElement('button'); button.dataset.pen = option.value; button.textContent = option.textContent;
    button.onclick = () => { $('pen').value = option.value; $('width').value = rememberedWidth(option.value); onChange(); };
    rows.append(button);
  }
  section.append(heading, rows); $('penGroups').append(section);
}
for (const [index, width] of widthValues.entries()) {
  const button = document.createElement('button'), sample = document.createElement('span'), label = document.createElement('span');
  button.dataset.width = width; button.setAttribute('aria-label', `Thickness ${index + 1}`); button.title = `Thickness ${index + 1} (${width})`;
  sample.className = 'widthSample'; sample.style.height = `${Math.max(1, width / 140 * 12)}px`; sample.setAttribute('aria-hidden', 'true');
  label.textContent = index + 1; button.append(sample, label);
  button.onclick = () => { $('width').value = width; onChange(); };
  $('widthPresets').append(button);
}
return { sync: syncPenChoice };
}
