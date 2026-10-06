// backends: java
// Default and rest parameters, spread, arguments, computed keys and tagged templates.
function greet(name = "world", punct = "!") { return "hello " + name + punct; }
console.log(greet(), greet("kernel"), greet("x", "?"), greet(undefined, "."), greet(null));
function later(a, b = a * 2, c = a + b) { return [a, b, c]; }
console.log(later(1), later(1, 5), later(1, 5, 9));
function sum(first, ...rest) { return first + rest.reduce((s, x) => s + x, 0) + ":" + rest.length; }
console.log(sum(1), sum(1, 2, 3), sum(10, 20));
var arrow = (a, b = 10, ...more) => [a, b, more];
console.log(arrow(1), arrow(1, 2, 3, 4));
function count() { return arguments.length + ":" + arguments[0] + ":" + arguments[1]; }
console.log(count(), count("a"), count("a", "b", "c"));
function nested() { var f = () => arguments[0]; return f(99); }
console.log(nested("outer"));
var counter = 0;
function fresh(x = ++counter) { return x; }
console.log(fresh(), fresh(), fresh(7), counter);

var nums = [1, 2, 3];
console.log(Math.max(...nums), Math.max(0, ...nums, 10), [...nums, 4], [0, ...nums, ...[7, 8]], [..."abc"]);
function three(a, b, c) { return a + b + c; }
console.log(three(...nums), three(1, ...[2, 3]), [...[], ...[]]);
var o = { a: 1, b: 2 };
console.log({ ...o, c: 3 }, { z: 0, ...o }, { ...o, a: 9 }, { ...null, ...undefined, ..."hi", ...[5] });
function Pt(x, y) { this.x = x; this.y = y; }
console.log(new Pt(...[1, 2]), new Pt(...[3]));
var obj = { f(...args) { return args.length; } };
console.log(obj.f(...nums, ...nums), obj?.f(...[]));
try { three(...5); } catch (e) { console.log(e.name); }

var key = "dyn";
var n = 1;
console.log({ [key]: 1, [key + "2"]: 2, ["a" + n]: 3, [n + 1]: 4, plain: 5 });
var m = { [key]() { return "method"; } };
console.log(m.dyn(), Object.keys(m));

function tag(strings, ...vals) {
  return strings.join("|") + " / " + vals.join(",") + " / " + strings.raw.join("|") + " / " + strings.length;
}
console.log(tag`a${1}b${2}c`, tag`no subs`, tag`${"only"}`);
console.log(tag`tab\there`.length, String.raw`C:\temp\n${1 + 1}`, String.raw`x${1}y${2}`);
var holder = { prefix: ">>", tag(s, v) { return this.prefix + s[0] + v + s[1]; } };
console.log(holder.tag`x${5}y`);
