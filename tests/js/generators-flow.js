// backends: java
"use strict";
// The hard corners of generators: jumps through finally blocks, yield in unusual positions, delegation.
const log = (...a) => console.log(...a);
function drain(it, ...sends) { const out = []; let r = it.next(); let i = 0; while (!r.done) { out.push(r.value); r = it.next(sends[i++]); } out.push("=>" + r.value); return out.join(" "); }

function* breakThroughFinally() {
  for (let i = 0; i < 4; i++) {
    try {
      try {
        if (i === 1) continue;
        if (i === 3) break;
        yield "body" + i;
      } finally { log("inner", i); yield "inner-fin" + i; }
    } finally { log("outer", i); }
  }
  return "end";
}
log(drain(breakThroughFinally()));

function* returnThroughFinallys() {
  try {
    try { yield 1; return "from-try"; }
    finally { log("f1"); }
  } finally { log("f2"); yield "in-f2"; }
  yield "not reached";
}
log(drain(returnThroughFinallys()));

function* labeledThroughFinally() {
  outer: for (const a of [1, 2, 3]) {
    for (const b of [1, 2, 3]) {
      try { if (b === 2) continue outer; if (a === 3) break outer; yield a * 10 + b; }
      finally { log("fin", a, b); }
    }
  }
}
log(drain(labeledThroughFinally()));

function* yieldInCatchAndFinally() {
  try { throw new Error("one"); }
  catch (e) { const x = yield "caught " + e.message; log("sent", x); try { throw new Error("two"); } catch (e2) { yield e2.message; } }
  finally { yield "finally"; }
  return "done";
}
log(drain(yieldInCatchAndFinally(), "S"));

function* rethrow() { try { yield 1; } catch (e) { log("catch", e); throw "again"; } finally { log("fin"); } }
const rt = rethrow();
rt.next();
try { rt.throw("first"); } catch (e) { log("outside", e); }
log(rt.next());

function* inWhileTest() { let n = 0; while ((yield n) < 3) n++; return n; }
log(drain(inWhileTest(), 1, 2, 3));
function* inDoWhile() { let i = 0; do { yield i; } while ((yield "?") && ++i < 2); }
log(drain(inDoWhile(), true, true, true));
function* inForParts() { for (let i = yield "init"; i < (yield "limit"); i += yield "step") yield i; }
log(drain(inForParts(), 0, 3, 1, 3, 2, 3));
function* inSwitch() { switch (yield "disc") { case yield "case1": return "one"; case yield "case2": return "two"; default: return "none"; } }
log(drain(inSwitch(), 2, 1, 2));
function* inArgs() { return Math.max(yield "a", yield "b", 0, yield "c") + `${yield "t"}!`; }
log(drain(inArgs(), 3, 9, 4, "T"));
function* inMethodCall() { const o = { v: 1, add(x, y) { return this.v + x + y; } }; return o.add(yield "x", yield "y"); }
log(drain(inMethodCall(), 10, 20));
function* inCompound() { let t = 5; t += yield "a"; const o = { n: 1 }; o.n *= yield "b"; const arr = [1, 2]; arr[yield "i"] = yield "v"; return [t, o.n, arr]; }
log(drain(inCompound(), 10, 3, 0, "z"));
function* inNew() { class P { constructor(a, b) { this.s = a + b; } } return new P(yield 1, yield 2).s; }
log(drain(inNew(), 3, 4));
function* inTernaryChain() { const v = (yield 1) ? (yield 2) : (yield 3) ? yield 4 : yield 5; return v; }
log(drain(inTernaryChain(), true, "two"), drain(inTernaryChain(), false, true, "four"), drain(inTernaryChain(), false, false, "five"));
function* inLogical() { const a = (yield 1) ?? (yield 2); const b = (yield 3) || (yield 4); const c = (yield 5) && (yield 6); return [a, b, c]; }
log(drain(inLogical(), null, "A", 0, "B", 1, "C"), drain(inLogical(), "x", "y", "z", "w"));
function* inObjectAndTemplate() { return { [yield "k"]: yield "v", t: `${yield "t1"}-${yield "t2"}` }; }
const oat = drain(inObjectAndTemplate(), "key", "val", "x", "y");
log(oat);

function* deleg() { const a = yield* (function* () { try { yield "i1"; yield "i2"; return "ret"; } finally { log("inner done"); } })(); yield a; }
const dg = deleg();
log(dg.next(), dg.return("cut"), dg.next());
const dg2 = deleg();
dg2.next();
try { dg2.throw(new Error("into inner")); } catch (e) { log(e.message); }
function* catching() { try { yield* (function* () { yield 1; })(); } catch (e) { yield "outer caught " + e; } }
const cg = catching();
cg.next();
log(cg.throw("x"), cg.next());
function* delegateArrayThrow() { try { yield* [1, 2, 3]; } catch (e) { yield "caught " + e.name; } }
const da = delegateArrayThrow();
log(da.next(), da.throw("t"), da.next());
function* sendThroughDelegate() { const got = yield* (function* () { const x = yield "ask"; return x * 2; })(); return got; }
log(drain(sendThroughDelegate(), 21));
function* deepRecursion(n) { if (n > 0) { yield n; yield* deepRecursion(n - 1); } }
log([...deepRecursion(5)].join(","));
const lazyPipeline = (function* () { for (let i = 1; ; i++) { yield i; } })();
function* mapG(it, f) { for (const v of it) yield f(v); }
function* filterG(it, p) { for (const v of it) if (p(v)) yield v; }
const piped = filterG(mapG(lazyPipeline, x => x * x), x => x % 2 === 1);
log(piped.next().value, piped.next().value, piped.next().value);
piped.return();
log(lazyPipeline.next().done);
