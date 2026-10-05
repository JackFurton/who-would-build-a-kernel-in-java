// Allocation-heavy loops: the heap is a bump allocator that never frees.
function build(n) {
  var list = [];
  for (var i = 0; i < n; i++) list.push({ id: i, label: "item" + i, tags: [i, i + 1] });
  return list;
}
var items = build(20000);
var total = 0;
for (var item of items) total += item.id + item.tags[1] + item.label.length;
console.log(items.length, total, items[19999].label);
var s = "";
for (var i = 0; i < 2000; i++) s = s + "x";
console.log(s.length);
function ackermann(m, n) { return m === 0 ? n + 1 : n === 0 ? ackermann(m - 1, 1) : ackermann(m - 1, ackermann(m, n - 1)); }
console.log(ackermann(2, 3));
var depth = 0;
function recurse(n) { return n === 0 ? 0 : 1 + recurse(n - 1); }
console.log(recurse(5000));
