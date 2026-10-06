// backends: java
// JSON.parse and JSON.stringify.
const text = '{"a": [1, 2, {"b": null}], "c": "x\\ny", "d": true, "e": {}, "f": [], "g": -12}';
const v = JSON.parse(text);
console.log(v, v.a[2].b === null, v.c.length);
console.log(JSON.stringify(v));
console.log(JSON.stringify(v, null, 2));
console.log(JSON.stringify(v, null, "--"));
console.log(JSON.stringify([undefined, () => 1, Symbol("s"), 1 === 2, "q\"\\\t\u0001"]));
console.log(JSON.stringify({ a: undefined, b: () => 1, c: 1 }), JSON.stringify(undefined), JSON.stringify(null));
console.log(JSON.stringify({ a: 1, b: 2, c: { a: 3, d: 4 } }, ["a", "c"]));
console.log(JSON.stringify({ a: 1, b: [2, 3] }, (k, x) => typeof x === "number" ? x * 10 : x));
console.log(JSON.stringify({ toJSON() { return { z: 1 }; } }), JSON.stringify({ d: { toJSON(k) { return "key:" + k; } } }));
console.log(JSON.parse("[1,2,3]", (k, x) => Array.isArray(x) ? x : x + 1), JSON.parse(" 7 "), JSON.parse('"s"'));
console.log(JSON.parse('{"a":{"b":2},"c":3}', (k, x) => k === "c" ? undefined : x));
const bad = ['{', '[1,]', '{"a":}', "tru", '{a:1}', '1 2', '"abc', ""];
for (const b of bad) {
  try { JSON.parse(b); console.log("parsed", b); } catch (e) { console.log(e.name); }
}
const cyc = {}; cyc.self = cyc;
try { JSON.stringify(cyc); } catch (e) { console.log(e.name); }
console.log(JSON.stringify(new Map([[1, 2]])), JSON.stringify("x"), JSON.stringify(5), JSON.stringify(false));
console.log(JSON.stringify({ k: [1, [2, []], {}] }, null, 1));
