function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
function fact(n) { return n <= 1 ? 1 : n * fact(n - 1); }
console.log(fib(20), fact(15));

function counter() {
  var n = 0;
  return { inc: function () { n++; return n; }, get: () => n };
}
var c1 = counter(), c2 = counter();
c1.inc(); c1.inc(); c2.inc();
console.log(c1.get(), c2.get());

var add = (a) => (b) => a + b;
console.log(add(3)(4), [1, 2, 3].map(add(10)));

var fns = [];
for (let i = 0; i < 3; i++) fns.push(() => i * i);
console.log(fns.map((f) => f()));
var fvars = [];
for (var j = 0; j < 3; j++) fvars.push(() => j);
console.log(fvars.map((f) => f()));

console.log((function (a, b) { return a * b; })(6, 7));
var fact2 = function self(n) { return n <= 1 ? 1 : n * self(n - 1); };
console.log(fact2(10));

function extra(a, b, c) { return [a, b, c]; }
console.log(extra(1), extra(1, 2, 3, 4));
function hoisted() { return later(); }
function later() { return "hoisted ok"; }
console.log(hoisted());

var obj = {
  base: 10,
  plus(n) { return this.base + n; },
  later() { return [1, 2].map((x) => this.base * x); },
};
console.log(obj.plus(5), obj.later());
var detached = obj.plus;
var o2 = { base: 100, plus: detached };
console.log(o2.plus(1));

function compose(f, g) { return (x) => f(g(x)); }
console.log(compose((x) => x + 1, (x) => x * 2)(5));
function apply(f, n, x) { for (var i = 0; i < n; i++) x = f(x); return x; }
console.log(apply((x) => x * 2, 10, 1));
function outer() {
  var a = 1;
  function mid() {
    var b = 2;
    return function inner() { a++; b++; return a * 10 + b; };
  }
  return mid();
}
var inner = outer();
console.log(inner(), inner(), inner());
console.log(typeof fib, typeof (() => 1));
