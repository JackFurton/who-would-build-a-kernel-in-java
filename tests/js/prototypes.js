// backends: java
// Constructor functions, prototypes, instanceof, in, delete, for...in and the Object statics.
function Animal(name) { this.name = name; }
Animal.prototype.speak = function () { return this.name + " makes a sound"; };
function Dog(name) { Animal.call(this, name); }
Dog.prototype = Object.create(Animal.prototype);
Dog.prototype.constructor = Dog;
Dog.prototype.speak = function () { return Animal.prototype.speak.call(this) + " (woof)"; };

var d = new Dog("Rex");
console.log(d.speak(), d instanceof Dog, d instanceof Animal, d instanceof Object, d instanceof Array);
console.log(d, new Animal("Cat"), new Animal);
console.log(Object.getPrototypeOf(d) === Dog.prototype, Dog.prototype.isPrototypeOf(d), Animal.prototype.isPrototypeOf(d));
console.log(d.hasOwnProperty("name"), d.hasOwnProperty("speak"), "speak" in d, "nope" in d, d.constructor === Dog);
var seen = [];
for (var k in d) seen.push(k);
console.log(seen);

var ns = { Thing: function (x) { this.x = x; } };
console.log(new ns.Thing(5), new ns.Thing(6).x, typeof new ns.Thing(1));
function Odd() { this.a = 1; return { custom: true }; }
function Plain() { this.a = 1; return 7; }
console.log(new Odd(), new Plain());

var o = { a: 1, b: 2, c: 3 };
console.log(delete o.b, o, "b" in o, delete o.missing, delete o["a"], o);
var arr = [1, 2, 3];
console.log(1 in arr, 5 in arr, "length" in arr, "0" in arr);
for (var i in arr) { console.log(i, typeof i, arr[i]); arr[i] = arr[i] * 10; }
console.log(arr, arr["1"], "x"["0"]);
for (var ch in "ab") console.log(ch, "ab"[ch]);
console.log(void 0, typeof void 0, void "x" === undefined);

var mixed = { b: 1, 2: "x", a: 2, 1: "y", 10: "z" };
console.log(mixed, Object.keys(mixed), Object.values(mixed), Object.entries({ p: 1, q: [2] }));
console.log(Object.assign({ a: 1 }, { b: 2 }, { a: 3 }), Object.assign({}, null, { z: 0 }));
var bare = Object.create(null);
bare.k = "v";
console.log(bare, Object.getPrototypeOf(bare), Object.getPrototypeOf({}) === Object.prototype);
var child = Object.create({ inherited: 1 });
child.own = 2;
console.log(child, child.inherited, Object.keys(child), child.hasOwnProperty("inherited"), Object.hasOwn(child, "own"));
for (var key in child) console.log("for-in", key);
Object.setPrototypeOf(child, { swapped: true });
console.log(child.swapped, child.inherited);

function greet(greeting, punct) { return greeting + ", " + this.who + punct; }
var who = { who: "world" };
console.log(greet.call(who, "hello", "!"), greet.apply(who, ["hi", "?"]), greet.bind(who, "hey")("."));
console.log(Math.max.apply(null, [3, 9, 4]), Math.min.call(null, 8, 2));
console.log(Object.prototype.toString.call([]), Object.prototype.toString.call(null), Object.prototype.toString.call({}),
  Object.prototype.toString.call(greet));

var add = (a, b) => a + b;
var named = { method: function () {}, arrow: () => {} };
function declared() {}
console.log(add, named, declared, function () {}, (() => 1), declared.name, add.name, named.method.name);
declared.extra = 1;
console.log(declared, typeof declared.prototype, typeof add.prototype);

var shout = { toString: function () { return "SHOUT"; } };
console.log("" + shout, `${shout}`, String(shout), [shout] + "");
console.log(Array(1, 2, 3), Array.of(7), new Array(1, "a"), Array.isArray(new Array(2, 3)), [] instanceof Array);
console.log({ deep: { deeper: { deepest: new Animal("x") } } });
console.log(new Object() instanceof Object, Object(5) === 5, typeof Object());
