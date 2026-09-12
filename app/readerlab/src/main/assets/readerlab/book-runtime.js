// Foliate's EPUB metadata parser uses Object.groupBy and Map.groupBy. WebView
// 101 predates them. Install only missing APIs in the trusted app realm; leave the
// vendor files and newer browsers' implementation unchanged.
export function ensureBookRuntime() {
  if (typeof Object.groupBy !== 'function') Object.defineProperty(Object, 'groupBy', {
    configurable: true, writable: true,
    value(items, callback) {
      if (typeof callback !== 'function') throw new TypeError('groupBy requires a callback');
      const groups = Object.create(null);
      let index = 0;
      for (const item of items) {
        // Computed property syntax performs ToPropertyKey exactly once, including
        // symbols returned by an object's coercion hook.
        const key = Reflect.ownKeys({ [callback(item, index++)]: true })[0];
        if (Object.prototype.hasOwnProperty.call(groups, key)) groups[key].push(item);
        else groups[key] = [item];
      }
      return groups;
    },
  });
  if (typeof Map.groupBy !== 'function') Object.defineProperty(Map, 'groupBy', {
    configurable: true, writable: true,
    value(items, callback) {
      if (typeof callback !== 'function') throw new TypeError('groupBy requires a callback');
      const groups = new Map();
      let index = 0;
      for (const item of items) {
        const key = callback(item, index++);
        if (groups.has(key)) groups.get(key).push(item);
        else groups.set(key, [item]);
      }
      return groups;
    },
  });
}
