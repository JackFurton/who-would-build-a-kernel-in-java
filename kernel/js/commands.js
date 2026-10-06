// Shell commands written in JavaScript. jsc turns this file into Java at build time, and dukec
// compiles that into the kernel along with everything else. Kernel.* is what the kernel exposes.

Kernel.command("hello", function () {
  console.log("hello from JavaScript running in the Duke kernel");
});

Kernel.command("fib", function (args) {
  var n = args.length > 0 ? parseInt(args[0]) : 10;
  var a = 0;
  var b = 1;
  for (var i = 0; i < n; i++) {
    var next = a + b;
    a = b;
    b = next;
  }
  console.log("fib(" + n + ") = " + a);
});

Kernel.command("meminfo", function () {
  var frames = Kernel.freeFrames();
  console.log({
    freeFrames: frames,
    freeMiB: frames * 4096 / 1048576 | 0,
    heapKiB: Kernel.heapUsed() >> 10,
  });
});

// Allocates a few hundred MiB of short-lived objects while holding on to some, which makes the
// collector run in the middle of JavaScript code. The totals prove nothing was freed early.
Kernel.command("churn", function () {
  var before = Kernel.collections();
  var keep = [];
  var total = 0;
  for (var i = 0; i < 200000; i++) {
    var o = { id: i, tags: [i, i + 1], label: "item" + i };
    if (i % 100 === 0) keep.push(o);
    total += o.tags[1];
  }
  var ids = 0;
  for (var k of keep) ids += k.id + k.label.length;
  console.log("churn: kept " + keep.length + ", total " + total + ", ids " + ids + ", collections " +
    (Kernel.collections() - before > 0 ? "ran" : "did not run"));
});

// Exceptions inside the kernel: a throw, a runtime error and a stack overflow, all caught.
Kernel.command("catches", function () {
  var results = [];
  try { throw new RangeError("thrown"); } catch (e) { results.push(e.name + ": " + e.message); }
  try { null.x; } catch (e) { results.push(e.name); }
  function recurse() { return recurse() + 1; }
  try { recurse(); } catch (e) { results.push(e.message); }
  try { results.push("try"); } finally { results.push("finally"); }
  console.log("catches: " + results.join(" | "));
});
