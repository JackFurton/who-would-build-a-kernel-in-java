// backends: java
// Map, Set, WeakMap and WeakSet.
const m = new Map([["a", 1], ["b", 2]]);
m.set("c", 3).set("a", 10);
console.log(m, m.size, m.get("a"), m.get("zz"), m.has("b"), m.delete("b"), m.delete("b"), m.size);
console.log([...m], [...m.keys()], [...m.values()], [...m.entries()]);
for (const [k, v] of m) console.log(k, v);
m.forEach((v, k, map) => console.log(k, v, map === m));

const keys = [{ id: 1 }, [2], function f() {}, 3, "3", null, undefined, true];
const mixed = new Map(keys.map((k, i) => [k, i]));
console.log(mixed.size, mixed.get(keys[0]), mixed.get({ id: 1 }), mixed.get(3), mixed.get("3"), mixed.get(null), mixed.get(undefined), mixed.get(true));

const live = new Map([[1, "a"]]);
for (const [k] of live) { if (k < 4) live.set(k + 1, "x"); }
console.log([...live.keys()]);
const del = new Map([[1, 1], [2, 2], [3, 3]]);
for (const [k] of del) { del.delete(k + 1); }
console.log([...del.keys()]);
del.clear();
console.log(del, del.size);

const s = new Set([1, 2, 2, 3, "3", 1]);
s.add(4).add(2);
console.log(s, s.size, s.has(3), s.has("3"), s.delete(1), [...s], [...s.entries()][0]);
console.log(new Set("hello"), new Set(), new Map(), new Set([[1], [1]]).size);
s.forEach((v, k, set) => { if (v !== k || set !== s) console.log("bad"); });

const [first, ...rest] = new Set([10, 20, 30]);
console.log(first, rest, Math.max(...new Set([5, 9, 7])), Object.keys(m), typeof m, String(m), Object.prototype.toString.call(s));
console.log(m instanceof Map, m instanceof Object, s instanceof Set, s instanceof Map, [] instanceof Map);
console.log(new Map([[{ nested: { deep: new Map([[1, new Set([2])]]) } }, 1]]));

const wm = new WeakMap();
const key = {};
wm.set(key, "v");
console.log(wm.get(key), wm.has(key), wm.has({}), wm.delete(key), wm.has(key), wm);
const ws = new WeakSet([key]);
console.log(ws.has(key), ws.has({}), ws);
try { wm.set(1, 1); } catch (e) { console.log(e.name); }
try { ws.add("x"); } catch (e) { console.log(e.name); }
try { Map(); } catch (e) { console.log(e.name); }
try { new Map([1, 2]); } catch (e) { console.log(e.name); }

const it = m[Symbol.iterator]();
console.log(it.next(), typeof it[Symbol.iterator]);
const groups = new Map();
for (const w of ["apple", "avocado", "banana", "blueberry", "cherry"]) {
  const k = w[0];
  groups.set(k, [...(groups.get(k) || []), w]);
}
console.log(groups);
const A = new Set([1, 2, 3]), B = new Set([2, 3, 4]);
console.log([...A].filter(x => B.has(x)), [...new Set([...A, ...B])]);
m.extra = "prop";
console.log(m.extra, "size" in m, "nope" in m);
const big = new Map();
for (let i = 0; i < 200; i++) big.set(i, i);
for (let i = 0; i < 190; i++) big.delete(i);
for (let i = 200; i < 210; i++) big.set(i, i);
console.log(big.size, [...big.keys()].join(","));
