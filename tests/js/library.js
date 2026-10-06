// backends: java
// Library methods on numbers, strings, arrays and the global object.
console.log((255).toString(16), (255).toString(2), (-10).toString(8), (5).toString());
console.log(true.toString(), Boolean(0), Boolean("x"), Boolean([]));
console.log(isNaN("12"), isNaN("x"), isFinite(5), isFinite("z"), globalThis === globalThis.globalThis);
console.log(Number.isSafeInteger(5), Number.isFinite(3), Number.isNaN(3), Number.MIN_SAFE_INTEGER);
console.log(Math.imul(7, 6), Math.clz32(1), Math.clz32(0));

const E = String.fromCharCode(233);
console.log(encodeURIComponent("a b&c/d?" + E), encodeURI("http://x/a b?q=" + E + "&r=1#h"));
console.log(decodeURIComponent("a%20b%26c%2Fd%3F%C3%A9") === "a b&c/d?" + E, decodeURI("a%20b%2Fc"));
console.log(btoa("hello"), btoa("ab"), btoa("a"), atob("aGVsbG8="), atob("YWI="));
try { decodeURIComponent("%zz"); } catch (e) { console.log(e.name); }

console.log(Array.from("abc"), Array.from([1, 2, 3], x => x * 2), Array.from({ length: 3 }, (v, i) => i * i));
console.log(Array.from(new Set([1, 1, 2])), Array.from({ length: 2, 0: "a", 1: "b" }));

console.log("a-b-c".replace("-", "+"), "a-b-c".replaceAll("-", "+"), "abc".replace("b", m => m.toUpperCase()));
console.log("x".replace("x", "$$"), "x".replace("x", "[$&]"), "abc".replaceAll("", "-"));
