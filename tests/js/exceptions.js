// backends: java
// throw, try/catch/finally and the Error types.
function risky(n) {
  if (n > 2) throw new RangeError("too big: " + n);
  return n;
}
try {
  risky(1);
  risky(5);
  console.log("unreachable");
} catch (e) {
  console.log(e.name, e.message, e instanceof RangeError, e instanceof Error, e instanceof TypeError);
} finally {
  console.log("cleanup");
}

try { throw 42; } catch (v) { console.log(v, typeof v); }
try { throw { code: 7 }; } catch (o) { console.log(o.code, o); }
try { throw "plain string"; } catch (s) { console.log(s); }
try { throw null; } catch (n) { console.log(n); }
try { throw undefined; } catch (u) { console.log(u); }
try { console.log("no error"); } catch { console.log("not reached"); }
try { throw new Error("no binding"); } catch { console.log("caught without a binding"); }

function returns() {
  try { return "from try"; } finally { console.log("finally runs before the return"); }
}
function overrides() {
  try { throw new Error("lost"); } catch (e) { return "caught " + e.message; } finally { console.log("overrides finally"); }
}
function finallyWins() {
  try { return "try"; } finally { return "finally"; }
}
console.log(returns(), overrides(), finallyWins());

function loops() {
  for (var i = 0; i < 4; i++) {
    try {
      if (i === 1) continue;
      if (i === 3) break;
      console.log("body", i);
    } finally {
      console.log("finally", i);
    }
  }
}
loops();

function rethrow() {
  try {
    try { throw new TypeError("inner"); }
    catch (e) { console.log("inner caught", e.message); throw e; }
    finally { console.log("inner finally"); }
  } catch (e) {
    console.log("outer caught", e.name);
  }
}
rethrow();

try { null.x; } catch (e) { console.log(e.name + ": " + e.message, e instanceof TypeError); }
try { undefined.y = 1; } catch (e) { console.log(e.name + ": " + e.message); }
try { (void 0)(); } catch (e) { console.log(e instanceof TypeError); }
try { new (() => 1)(); } catch (e) { console.log(e instanceof TypeError); }
try { ({}) instanceof 5; } catch (e) { console.log(e.name); }

var err = new Error("boom");
console.log(String(err), err + "", err.toString(), err.message, err.name, typeof err.stack, Object.keys(err));
console.log(new TypeError("t").name, new RangeError("r") instanceof Error, Error("called").message, TypeError("x") instanceof TypeError);
console.log(new Error().message === "", new Error(undefined).message === "", Object.prototype.hasOwnProperty.call(err, "message"));

function MyError(message) { this.message = message; this.name = "MyError"; }
MyError.prototype = Object.create(Error.prototype);
MyError.prototype.constructor = MyError;
try { throw new MyError("custom"); } catch (e) {
  console.log(e instanceof MyError, e instanceof Error, String(e), e.name, e.message);
}

function deep(n) { return n === 0 ? 0 : 1 + deep(n - 1); }
function recurse() { return recurse() + 1; }
try { recurse(); } catch (e) { console.log(e instanceof RangeError, e.name); }
console.log(deep(100));

var log = [];
function a() { try { log.push("a"); b(); } finally { log.push("a-finally"); } }
function b() { try { log.push("b"); throw new Error("from b"); } finally { log.push("b-finally"); } }
try { a(); } catch (e) { log.push(e.message); }
console.log(log);

var counter = (function () {
  var count = 0;
  return function () { try { return ++count; } finally { count += 10; } };
})();
console.log(counter(), counter(), counter());
