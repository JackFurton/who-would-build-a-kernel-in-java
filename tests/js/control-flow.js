// backends: java
// switch, labels, optional chaining and number literal forms.
function kind(x) {
  switch (x) {
    case 1: return "one";
    case 2:
    case 3: return "two or three";
    case "a": return "letter";
    default: return "other";
  }
}
console.log(kind(1), kind(2), kind(3), kind("a"), kind(9), kind("1"));

function fallthrough(n) {
  var out = [];
  switch (n) {
    case 0: out.push("zero");
    case 1: out.push("one"); break;
    default: out.push("default");
    case 2: out.push("two");
  }
  return out;
}
console.log(fallthrough(0), fallthrough(1), fallthrough(2), fallthrough(5));

var order = [];
function t(v) { order.push(v); return v; }
switch (3) { case t(1): break; default: order.push("d"); break; case t(3): order.push("hit"); }
console.log(order);
switch (1) { case 1: let x = "x1"; var y = "y"; case 2: console.log(x, y); }
switch (7) { }
switch (7) { default: console.log("only default"); }

for (var i = 0; i < 5; i++) {
  switch (i) {
    case 1: continue;
    case 3: break;
    default: console.log("loop", i);
  }
  if (i === 3) console.log("after switch", i);
}

outer: for (var a = 0; a < 3; a++) {
  for (var b = 0; b < 3; b++) {
    if (b === 1) continue outer;
    if (a === 2) break outer;
    console.log("ab", a, b);
  }
}
var n = 0;
wl: while (true) { n++; do { if (n > 2) break wl; continue wl; } while (false); }
console.log(n);
block: { console.log("in block"); if (n) break block; console.log("skipped"); }
fo: for (var v of [1, 2, 3]) { for (var w of [10, 20]) { if (w === 20) continue fo; if (v === 3) break fo; console.log(v, w); } }
fi: for (var k in { p: 1, q: 2 }) { for (;;) { console.log(k); continue fi; } }
sw: switch (1) { case 1: for (;;) { break sw; } }
a1: b1: for (var z = 0; z < 2; z++) { if (z) break a1; continue b1; }

var o = { a: { b: [10, 20], f: function () { return this.k; }, k: "kay" }, n: null };
console.log(o?.a?.b[1], o.n?.x, o.missing?.deep.deeper, o.a?.["b"]?.[0], o?.a.f(), o.a.f?.(), o.a.nope?.(), o.n?.f());
var u;
console.log(u?.x, u?.[0], u?.(), u?.x.y.z, typeof u?.x);
console.log(o.a?.f?.(), o.a?.b.length, o?.a?.k.length);
var calls = 0;
function count() { calls++; return null; }
console.log(count()?.x.y(calls++), calls);
console.log(o.a.b?.map(function (x) { return x + 1; }), o.n?.map(1));
console.log(0b101, 0B11, 0o17, 0O7, 0xff, 1_000, 0b1010_1010, 0x1F_FF);
