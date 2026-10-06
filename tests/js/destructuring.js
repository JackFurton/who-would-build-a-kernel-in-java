// backends: java
// Array and object destructuring in declarations, assignments, parameters, loop heads and catch.
var [a, b, ...rest] = [1, 2, 3, 4, 5];
console.log(a, b, rest);
let [x = 10, y = 20, , z] = [undefined, null, "skipped", "third"];
console.log(x, y, z);
const { p, q: renamed, r = "dflt", ...others } = { p: 1, q: 2, s: 3, t: 4 };
console.log(p, renamed, r, others);
const { deep: { inner: [first, { value }] } } = { deep: { inner: ["one", { value: "v" }] } };
console.log(first, value);

var m = 1, n = 2;
[m, n] = [n, m];
console.log(m, n);
var obj = {};
({ a: obj.first, b: obj.second = "B" } = { a: "A" });
console.log(obj);
var arr = [];
[arr[0], arr[1]] = "hi";
console.log(arr, ([m, n] = [7, 8]), m, n);

function pt({ x, y = 0 }, [first2, second2] = [1, 2], ...tail) { return [x, y, first2, second2, tail]; }
console.log(pt({ x: 1 }), pt({ x: 5, y: 6 }, [7, 8], 9, 10));
var arrow = ([a, b], { c } = { c: "dc" }) => a + b + c;
console.log(arrow([1, 2]), arrow([1, 2], { c: 3 }));

for (const [k, v] of [["a", 1], ["b", 2]]) console.log(k, v);
for (var { id, tags: [t0] } of [{ id: 1, tags: ["x"] }, { id: 2, tags: ["y"] }]) console.log(id, t0);
for (let [c1, c2] in { ab: 1, cd: 2 }) console.log(c1, c2);
try { throw { code: 7, msg: "seven" }; } catch ({ code, msg }) { console.log(code, msg); }
try { throw [1, 2]; } catch ([e1, e2]) { console.log(e1 + e2); }

var fns = [];
for (const [i, w] of [[1, "a"], [2, "b"]]) fns.push(() => i + w);
console.log(fns.map(f => f()));

const [c1_, c2_, c3_] = "xyz";
const { length } = "four";
const { ["comp" + 1]: computed } = { comp1: "C" };
console.log(c1_, c2_, c3_, length, computed);

try { const { nope } = null; } catch (e) { console.log(e.name); }
try { const [nothing] = undefined; } catch (e) { console.log(e.name); }
try { var [z1] = 5; } catch (e) { console.log(e.name); }

function minmax(list) { return [Math.min(...list), Math.max(...list)]; }
const [lo, hi] = minmax([3, 1, 4, 1, 5]);
console.log(lo, hi);

var calls = 0;
function dflt() { calls++; return "d"; }
const [u1 = dflt(), u2 = dflt()] = ["given", undefined];
const { w1 = dflt(), w2 = dflt() } = { w1: null };
console.log(u1, u2, w1, w2, calls);

var [, second, , fourth] = [1, 2, 3, 4];
console.log(second, fourth, [, 1, , 2].length);
var { a: aa, ...none } = { a: 1 };
console.log(aa, none);
