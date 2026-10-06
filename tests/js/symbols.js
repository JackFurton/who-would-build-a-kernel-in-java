// backends: java
// Symbols as values and keys, and the iteration protocol behind for...of, spread and destructuring.
const s1 = Symbol("a"), s2 = Symbol("a"), s3 = Symbol();
console.log(typeof s1, s1 === s2, s1.toString(), s1.description, s3.description, String(s1), s1);
console.log(Symbol.for("k") === Symbol.for("k"), Symbol.keyFor(Symbol.for("k")), Symbol.keyFor(s1));
const o = { [s1]: 1, regular: 2, [Symbol.for("shared")]: 3 };
o[s2] = "two";
console.log(o[s1], o[s2], s1 in o, Object.keys(o), Object.getOwnPropertySymbols(o).length);
console.log(Object.getOwnPropertyNames(o));
try { "" + s1; } catch (e) { console.log(e.name); }
try { `${s1}`; } catch (e) { console.log(e.name); }
try { new Symbol(); } catch (e) { console.log(e.name); }

const iterable = {
  from: 1, to: 4,
  [Symbol.iterator]() {
    let cur = this.from, last = this.to;
    return { next: () => cur <= last ? { value: cur++, done: false } : { value: undefined, done: true } };
  },
};
console.log([...iterable], Math.max(...iterable));
for (const x of iterable) console.log("x", x);
const [first, second, ...others] = iterable;
console.log(first, second, others);

const closing = {
  [Symbol.iterator]() {
    let i = 0;
    return { next() { return { value: i++, done: false }; }, return() { console.log("closed"); return {}; } };
  },
};
for (const v of closing) { if (v === 2) break; }
const [c0, c1] = closing;
console.log(c0, c1);
try { for (const v of closing) { throw new Error("thrown inside"); } } catch (e) { console.log(e.message); }
outer: for (const a of [1, 2]) { for (const b of closing) { continue outer; } }

const arr = ["a", "b"];
const it = arr[Symbol.iterator]();
console.log(it.next(), it.next(), it.next(), typeof it[Symbol.iterator], it[Symbol.iterator]() === it);
console.log([...arr.keys()], [...arr.entries()], [...arr.values()], [..."abc"]);
for (const [i, v] of arr.entries()) console.log(i, v);
const grow = [1];
for (const v of grow) { if (grow.length < 4) grow.push(v + 1); }
console.log(grow);

class Countdown {
  constructor(n) { this.n = n; }
  [Symbol.iterator]() {
    let n = this.n;
    return { next() { return n > 0 ? { value: n--, done: false } : { done: true }; } };
  }
}
console.log([...new Countdown(3)], Array.isArray(new Countdown(2)));
try { for (const x of 5) {} } catch (e) { console.log(e.name); }
try { for (const x of { a: 1 }) {} } catch (e) { console.log(e.name); }
try { [...undefined]; } catch (e) { console.log(e.name); }

class Even { static [Symbol.hasInstance](n) { return n % 2 === 0; } }
console.log(2 instanceof Even, 3 instanceof Even);
const tagged = { [Symbol.toStringTag]: "Custom" };
console.log(Object.prototype.toString.call(tagged), String(tagged));
