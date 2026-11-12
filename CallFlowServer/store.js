// Storage adapter. Firebase Realtime Database in production, memory for tests.
const clone = v => (v === undefined ? null : JSON.parse(JSON.stringify(v)));
const parts = p => p.split('/').filter(Boolean);

function memoryStore() {
  const root = {};
  const getAt = p => {
    let n = root;
    for (const k of parts(p)) { if (n == null || typeof n !== 'object') return null; n = n[k]; }
    return n === undefined ? null : n;
  };
  const setAt = (p, val) => {
    const ks = parts(p);
    let n = root;
    for (const k of ks.slice(0, -1)) { if (n[k] == null || typeof n[k] !== 'object') n[k] = {}; n = n[k]; }
    const last = ks[ks.length - 1];
    if (val === null) delete n[last]; else n[last] = clone(val);
  };
  return {
    async get(p) { return clone(getAt(p)); },
    async set(p, v) { setAt(p, v); },
    async update(p, obj) { for (const [k, v] of Object.entries(obj)) setAt(p + '/' + k, v); },
    async remove(p) { setAt(p, null); },
    async transaction(p, fn) {
      const next = fn(clone(getAt(p)));
      if (next === undefined) return false;
      setAt(p, next);
      return true;
    },
    async findBy(p, field, value) {
      const out = {};
      for (const [k, v] of Object.entries(getAt(p) || {})) if (v && v[field] === value) out[k] = clone(v);
      return out;
    },
  };
}

function firebaseStore(db) {
  return {
    async get(p) { return (await db.ref(p).get()).val(); },
    async set(p, v) { await db.ref(p).set(v); },
    async update(p, obj) { await db.ref(p).update(obj); },
    async remove(p) { await db.ref(p).remove(); },
    async transaction(p, fn) { return (await db.ref(p).transaction(cur => fn(cur))).committed; },
    async findBy(p, field, value) { return (await db.ref(p).orderByChild(field).equalTo(value).get()).val() || {}; },
  };
}

module.exports = { memoryStore, firebaseStore };
