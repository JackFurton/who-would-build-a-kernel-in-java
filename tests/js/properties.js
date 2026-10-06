// backends: java
"use strict";
// Getters and setters, Object.defineProperty and descriptors, freeze/seal/preventExtensions.
var temp = { c: 20, get f() { return this.c * 9 / 5 + 32; }, set f(v) { this.c = (v - 32) * 5 / 9; } };
console.log(temp.f, temp);
temp.f = 212;
console.log(temp.c, temp.f, Object.keys(temp));
var only = { get x() { return 1; } };
var wo = { set y(v) { this.stored = v; } };
console.log(only, wo, only.x, wo.y);
wo.y = 5;
console.log(wo.stored);
try { only.x = 2; } catch (e) { console.log(e.name); }
var key = "dyn";
var comp = { get [key]() { return "computed getter"; }, ["set" + 1]: 1 };
console.log(comp.dyn, comp.set1);
var plain = { get: 1, set: 2, get2() { return 3; } };
console.log(plain, plain.get2());
var inherits = Object.create({ get v() { return this.base + 1; } });
inherits.base = 9;
console.log(inherits.v, Object.keys(inherits));

var o = {};
Object.defineProperty(o, "hidden", { value: 1 });
Object.defineProperty(o, "visible", { value: 2, enumerable: true });
Object.defineProperty(o, "rw", { value: 3, writable: true, enumerable: true, configurable: true });
console.log(o, Object.keys(o), o.hidden, Object.getOwnPropertyNames(o));
try { o.hidden = 99; } catch (e) { console.log(e.name, o.hidden); }
o.rw = 30;
console.log(o.rw, delete o.rw, "rw" in o);
try { delete o.hidden; } catch (e) { console.log(e.name); }
try { Object.defineProperty(o, "hidden", { value: 2 }); } catch (e) { console.log(e.name); }
var acc = {};
var store = 1;
Object.defineProperty(acc, "v", { get: function () { return store; }, set: function (n) { store = n * 2; }, enumerable: true });
acc.v = 5;
console.log(acc.v, acc, Object.getOwnPropertyDescriptor(acc, "v").enumerable);
console.log(Object.getOwnPropertyDescriptor({ a: 1 }, "a"), Object.getOwnPropertyDescriptor(o, "hidden"));
console.log(Object.getOwnPropertyDescriptor({}, "nope"), Object.getOwnPropertyDescriptor([5], 0), Object.getOwnPropertyDescriptor([5], "length"));
console.log(Object.getOwnPropertyDescriptors({ x: 1, y: 2 }));
Object.defineProperties(o, { p1: { value: "one", enumerable: true }, p2: { value: "two" } });
console.log(o.p1, o.p2, Object.keys(o));

var frozen = Object.freeze({ a: 1, nested: { b: 2 } });
try { frozen.a = 2; } catch (e) { console.log(e.name); }
try { frozen.added = 1; } catch (e) { console.log(e.name); }
try { delete frozen.a; } catch (e) { console.log(e.name); }
frozen.nested.b = 3;
console.log(frozen, Object.isFrozen(frozen), Object.isFrozen(frozen.nested), Object.isSealed(frozen), Object.isExtensible(frozen));
var sealed = Object.seal({ s: 1 });
sealed.s = 2;
try { sealed.t = 1; } catch (e) { console.log(e.name); }
try { delete sealed.s; } catch (e) { console.log(e.name); }
console.log(sealed, Object.isSealed(sealed), Object.isFrozen(sealed));
var noExtend = Object.preventExtensions({ k: 1 });
noExtend.k = 2;
try { noExtend.z = 1; } catch (e) { console.log(e.name); }
delete noExtend.k;
console.log(noExtend, Object.isExtensible(noExtend), Object.isExtensible({}));
var fa = Object.freeze([1, 2, 3]);
try { fa.push(4); } catch (e) { console.log(e.name); }
try { fa[0] = 9; } catch (e) { console.log(e.name); }
console.log(fa, Object.isFrozen(fa), Object.isFrozen([]), Object.isFrozen("s"), Object.isFrozen(5));

console.log(Object.is(1, 1), Object.is("a", "a"), Object.is({}, {}), Object.is(null, undefined));
console.log(Object.fromEntries([["a", 1], ["b", 2]]), Object.fromEntries(Object.entries({ x: 1, y: 2 }).map(([k, v]) => [k, v * 2])));
var fnProps = function () {};
Object.defineProperty(fnProps, "tag", { value: "t", enumerable: true });
console.log(fnProps.tag, Object.keys(fnProps));
