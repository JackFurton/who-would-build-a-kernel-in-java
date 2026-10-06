// backends: java
"use strict";
// Classes: fields, private names, accessors, statics, inheritance, super, and the built-in Error.
class Animal {
  legs = 4;
  #secret = "hidden";
  static count = 0;
  static registry = [];
  constructor(name) { this.name = name; Animal.count++; Animal.registry.push(name); }
  speak() { return this.name + " makes a sound"; }
  get description() { return `${this.name} has ${this.legs} legs`; }
  set nickname(v) { this._nick = v.toUpperCase(); }
  get secret() { return this.#secret; }
  static create(name) { return new this(name); }
  static get total() { return Animal.count; }
  #privateMethod() { return "private " + this.name; }
  callPrivate() { return this.#privateMethod(); }
  toString() { return "Animal(" + this.name + ")"; }
}
class Dog extends Animal {
  tricks = [];
  constructor(name, breed) { super(name); this.breed = breed; }
  speak() { return super.speak() + " (woof)"; }
  get description() { return super.description + " and a tail"; }
  addTrick(t) { this.tricks.push(t); return this; }
  static create(name) { const d = super.create(name); d.breed = "mutt"; return d; }
}
class Puppy extends Dog {}

const a = new Animal("Generic");
const d = new Dog("Rex", "lab");
console.log(a.speak(), d.speak(), d.description, a.description);
console.log(a, d, new Puppy("Pup", "pug"));
console.log(d instanceof Dog, d instanceof Animal, d instanceof Puppy, Object.getPrototypeOf(Dog) === Animal,
  Object.getPrototypeOf(Dog.prototype) === Animal.prototype);
console.log(Animal.count, Animal.total, Dog.count, Animal.registry);
d.nickname = "rexy";
console.log(d._nick, d.secret, a.callPrivate(), Object.keys(d), `${a}`, String(d));
console.log(Dog.create("Made"), Puppy.create("PupMade").constructor.name);
console.log(d.addTrick("sit").addTrick("roll").tricks);
console.log(Animal, Dog, Puppy, class {}, class Foo { static x = 1; });
console.log(typeof Animal, typeof Animal.prototype.speak, Animal.name, Dog.name, (class {}).name);
try { Animal("x"); } catch (e) { console.log(e.name); }
try { class Bad extends 5 {} } catch (e) { console.log(e.name); }
console.log(Object.getOwnPropertyNames(Animal.prototype).join(), Object.keys(Animal.prototype));

const log = [];
class Init {
  static a = log.push("static a");
  static { log.push("block"); }
  static b = log.push("static b");
  x = log.push("field x");
  constructor() { log.push("ctor"); }
}
new Init();
new Init();
console.log(log.join(", "));

class Counter { n = 0; inc = () => ++this.n; double = this.n * 2; }
const c = new Counter();
const inc = c.inc;
inc();
inc();
console.log(c.n, c.double, c);

const key = "dyn";
const Expr = class { [key]() { return "computed"; } static [key + "S"]() { return "static computed"; } get [key + "G"]() { return "getter"; } };
console.log(new Expr().dyn(), Expr.dynS(), new Expr().dynG, Expr.name);

class MyError extends Error {
  constructor(msg, code) { super(msg); this.name = "MyError"; this.code = code; }
}
try { throw new MyError("boom", 42); } catch (e) {
  console.log(e instanceof MyError, e instanceof Error, e.name, e.message, e.code, String(e));
}
class NoProto extends null {}
console.log(Object.getPrototypeOf(NoProto.prototype));

class A1 { who() { return "A1"; } }
class B1 extends A1 { who() { return super.who() + ">B1"; } }
class C1 extends B1 { who() { return super.who() + ">C1"; } }
console.log(new C1().who());
class P { static sm() { return "P.sm"; } static get sg() { return "P.sg"; } }
class Q extends P { static sm() { return super.sm() + "+Q"; } }
console.log(Q.sm(), Q.sg);
const detached = d.speak;
try { detached(); } catch (e) { console.log(e.name); }
