// backends: java
"use strict";
// Generators: yield, yield*, two-way values, finally/return/throw, and generators as iterators.
function* count(n) { for (let i = 0; i < n; i++) yield i; return "done"; }
console.log([...count(4)]);
const g = count(2);
console.log(g.next(), g.next(), g.next(), g.next());
function* naturals() { let n = 0; while (true) yield n++; }
function* take(it, n) { let i = 0; for (const v of it) { if (i++ >= n) return; yield v; } }
console.log([...take(naturals(), 5)]);

function* conv() { const a = yield "first"; const b = yield a + "!"; return [a, b]; }
const c = conv();
console.log(c.next("ignored"), c.next("A"), c.next("B"), c.next());

function* expr() {
  const x = (yield 1) + (yield 2);
  console.log("x", x);
  const arr = [yield "a", yield "b"];
  console.log(arr);
  const o = { k: yield "k", [yield "ck"]: yield "cv" };
  console.log(o);
  return Math.max(yield "m1", yield "m2");
}
const e = expr();
const sends = [10, 20, "A", "B", "v1", "ck", "cv", 5, 9];
let r = e.next();
let i = 0;
while (!r.done) { console.log(r.value); r = e.next(sends[i++]); }
console.log(r);

function* cond(flag) {
  const v = flag && (yield "in-and");
  const w = flag || (yield "in-or");
  const z = flag ? yield "then" : yield "else";
  return [v, w, z];
}
for (const flag of [true, false]) {
  const it = cond(flag);
  let step = it.next();
  const seen = [];
  while (!step.done) { seen.push(step.value); step = it.next("S" + seen.length); }
  console.log(seen, step.value);
}

function* inner() { const x = yield 1; yield x * 2; return "inner-result"; }
function* outer() { const res = yield* inner(); yield res; yield* [10, 20]; yield* "ab"; return "outer-done"; }
const o = outer();
console.log(o.next(), o.next(5), o.next(), o.next(), o.next(), o.next(), o.next(), o.next(), o.next());

function* res() { try { yield 1; yield 2; } finally { console.log("cleanup"); } }
for (const v of res()) { console.log(v); break; }
const rr = res();
rr.next();
console.log(rr.return("early"), rr.next());
const tt = res();
tt.next();
try { tt.throw(new Error("boom")); } catch (err) { console.log(err.message); }
console.log(tt.next());
const fresh = res();
console.log(fresh.return("never started"), fresh.next());
const fresh2 = res();
try { fresh2.throw(new Error("before start")); } catch (err) { console.log(err.message); }

function* safe() {
  while (true) {
    try { const v = yield; console.log("got", v); }
    catch (err) { console.log("caught", err); yield "recovered"; }
  }
}
const s = safe();
s.next();
s.next(1);
console.log(s.throw("oops"));
console.log(s.next());
s.next(2);

function* fin() {
  try {
    try { yield "a"; return "r"; }
    finally { yield "f1"; console.log("inner finally"); }
  } finally { console.log("outer finally"); yield "f2"; }
}
const f = fin();
console.log(f.next(), f.next(), f.next(), f.next(), f.next());

function* loops() {
  outer: for (let i = 0; i < 3; i++) {
    for (let j = 0; j < 3; j++) {
      if (j === 1) continue;
      if (i === 1) continue outer;
      if (i === 2) break outer;
      yield [i, j];
    }
  }
  do { yield "do"; } while (false);
  switch (2) {
    case 1: yield "one"; break;
    case 2: yield "two";
    case 3: yield "three"; break;
    default: yield "default";
  }
  lbl: { yield "in block"; break lbl; }
  for (const k in { p: 1, q: 2 }) yield k;
}
console.log([...loops()].join(" "));

class Tree {
  constructor(v, l, r) { this.v = v; this.l = l; this.r = r; }
  *[Symbol.iterator]() { if (this.l) yield* this.l; yield this.v; if (this.r) yield* this.r; }
  static *range(a, b) { for (let i = a; i < b; i++) yield i; }
}
const tree = new Tree(2, new Tree(1), new Tree(4, new Tree(3), new Tree(5)));
console.log([...tree], [...Tree.range(1, 4)]);
const obj = { *gen() { yield this.x; }, x: 7 };
console.log([...obj.gen()]);
const [d0, d1] = naturals();
console.log(d0, d1);
const it = count(1);
console.log(it[Symbol.iterator]() === it, typeof it.next, Object.prototype.toString.call(it));

function* selfRef() { try { me.next(); } catch (er) { console.log(er.name); } yield 1; }
const me = selfRef();
me.next();
function* withArgs(a, b = a + 1, ...rest) { yield a; yield b; yield rest.length; yield arguments.length; yield this === undefined; }
console.log([...withArgs(1, undefined, 3, 4)]);
function* misc() { const a = yield; const b = yield yield 1; console.log(a, b); }
const m = misc();
m.next();
console.log(m.next("A"), m.next("B"), m.next("C"), m.next("D"));
function* fib() { let [a, b] = [0, 1]; for (;;) { yield a; [a, b] = [b, a + b]; } }
console.log([...take(fib(), 10)].join(" "));
function* nestedLoops() { let total = 0; for (let i = 1; i <= 3; i++) { let j = 0; while (j < i) { total += yield `${i}.${j}`; j++; } } return total; }
const nl = nestedLoops();
let step = nl.next();
const keys = [];
while (!step.done) { keys.push(step.value); step = nl.next(10); }
console.log(keys, step.value);
console.log(typeof count, [...(function* () { yield* count(2); yield* count(1); })()]);
