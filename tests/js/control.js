var out = [];
for (var i = 1; i <= 15; i++) out.push(i % 15 === 0 ? "FizzBuzz" : i % 5 === 0 ? "Buzz" : i % 3 === 0 ? "Fizz" : i);
console.log(out.join(" "));

var n = 27, steps = 0;
while (n !== 1) { n = n % 2 === 0 ? n / 2 : 3 * n + 1; steps++; }
console.log("collatz", steps);

var k = 0;
do { k += 3; } while (k < 10);
console.log(k);

var found = -1;
for (var x = 0; x < 10; x++) {
  if (x * x > 50) { found = x; break; }
}
console.log(found);

var sum = 0;
for (var y = 0; y < 100; y++) { if (y % 2) continue; sum += y; }
console.log(sum);

var sieve = [], primes = [];
for (var p = 0; p < 100; p++) sieve.push(true);
for (var p2 = 2; p2 < 100; p2++) {
  if (!sieve[p2]) continue;
  primes.push(p2);
  for (var m = p2 * p2; m < 100; m += p2) sieve[m] = false;
}
console.log(primes.length, primes[24]);

function gcd(a, b) { while (b) { var t = b; b = a % b; a = t; } return a; }
console.log(gcd(1071, 462), gcd(17, 5));

var i2 = 0;
for (;;) { if (++i2 > 4) break; }
console.log(i2);

for (var r = 0, s = 10; r < s; r += 3, s -= 3) console.log(r, s);

if (0) console.log("no"); else if ("") console.log("no"); else if ("0") console.log("string zero is truthy");
if (undefined) console.log("no"); else console.log("undefined is falsy");
if ([]) console.log("empty array is truthy");
if ({}) console.log("empty object is truthy");
{
  let scoped = 1;
  { let scoped = 2; console.log(scoped); }
  console.log(scoped);
}
let t1 = 1;
const t2 = t1 + 1;
console.log(t1, t2);
