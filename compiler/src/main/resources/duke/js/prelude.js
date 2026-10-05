// The runtime, written in the language it compiles. It runs before the program.
//
// __peek, __poke, __syscall and friends are intrinsics: the compiler expands them to a few
// instructions. Addresses are integers. Everything on the heap is made of 8-byte words that are
// themselves JavaScript values, so the layouts below can be read back with __peek:
//   string    [1][length][bytes...]
//   array     [2][length][capacity][address of elements]
//   object    [3][count][capacity][address of (key, value) pairs]
//   function  [4][code address][properties object or undefined][captured variable boxes...]
// Numbers are 63-bit integers for now, so there is no NaN, Infinity or fraction anywhere.

// ---- basics ----

// 0 number, 1 string, 2 array, 3 object, 4 function, 5 undefined, 6 null, 7 boolean
function __kind(v) {
  if (__isInt(v)) return 0;
  if (__isPtr(v)) return __peek(__addr(v));
  if (v === undefined) return 5;
  if (v === null) return 6;
  return 7;
}

function __write(fd, s) {
  __syscall(1, fd, __addr(s) + 16, __strLen(s));
}

function __fail(message) {
  __write(2, __strConcat(message, "\n"));
  __syscall(231, 1);
  return undefined;
}

function __divideByZero() {
  return __fail("RangeError: division by zero (there is no Infinity or NaN yet)");
}

function __notFunction() {
  return __fail("TypeError: value is not a function");
}

function __nan(what) {
  return __fail("TypeError: " + what + " would be NaN, which is not supported yet");
}

// ---- strings ----

function __strLen(s) {
  return __peek(__addr(s) + 8);
}

function __strByte(s, i) {
  return __peekByte(__addr(s) + 16 + i);
}

function __newString(len) {
  var a = __alloc(16 + len);
  __poke(a, 1);
  __poke(a + 8, len);
  return __ptr(a);
}

function __strConcat(a, b) {
  var la = __strLen(a);
  var lb = __strLen(b);
  var r = __newString(la + lb);
  __copy(__addr(r) + 16, __addr(a) + 16, la);
  __copy(__addr(r) + 16 + la, __addr(b) + 16, lb);
  return r;
}

function __substring(s, start, end) {
  var n = end - start;
  if (n <= 0) return "";
  var r = __newString(n);
  __copy(__addr(r) + 16, __addr(s) + 16 + start, n);
  return r;
}

function __strEq(a, b) {
  var n = __strLen(a);
  if (n !== __strLen(b)) return false;
  for (var i = 0; i < n; i++) {
    if (__strByte(a, i) !== __strByte(b, i)) return false;
  }
  return true;
}

function __strIndexOf(s, t, from) {
  var n = __strLen(s);
  var m = __strLen(t);
  for (var i = from; i + m <= n; i++) {
    var j = 0;
    while (j < m && __strByte(s, i + j) === __strByte(t, j)) j++;
    if (j === m) return i;
  }
  return -1;
}

function __strictEqualsSlow(a, b) {
  if (__kind(a) === 1 && __kind(b) === 1) return __strEq(a, b);
  return false;
}

function __intToString(n) {
  if (n === 0) return "0";
  var neg = n < 0;
  if (neg) n = -n;
  var digits = 0;
  var t = n;
  while (t > 0) {
    digits++;
    t = t / 10;
  }
  var len = neg ? digits + 1 : digits;
  var s = __newString(len);
  var base = __addr(s) + 16;
  var i = len - 1;
  while (n > 0) {
    __pokeByte(base + i, 48 + n % 10);
    n = n / 10;
    i--;
  }
  if (neg) __pokeByte(base, 45);
  return s;
}

function __str(v) {
  var k = __kind(v);
  if (k === 1) return v;
  if (k === 0) return __intToString(v);
  if (k === 5) return "undefined";
  if (k === 6) return "null";
  if (k === 7) return v ? "true" : "false";
  if (k === 2) return __join(v, ",");
  if (k === 4) return "function () { [native code] }";
  return "[object Object]";
}

function __join(a, sep) {
  var n = __arrLen(a);
  var r = "";
  for (var i = 0; i < n; i++) {
    if (i > 0) r = __strConcat(r, sep);
    var e = __arrGet(a, i);
    if (e !== undefined && e !== null) r = __strConcat(r, __str(e));
  }
  return r;
}

// Parses an optionally signed decimal prefix of s starting at i. Returns the number, or fails.
function __parseInteger(s, strict) {
  var n = __strLen(s);
  var i = 0;
  var neg = false;
  var r = 0;
  while (i < n && __strByte(s, i) === 32) i++;
  if (i < n && __strByte(s, i) === 45) {
    neg = true;
    i++;
  } else if (i < n && __strByte(s, i) === 43) {
    i++;
  }
  var start = i;
  while (i < n) {
    var c = __strByte(s, i);
    if (c < 48 || c > 57) break;
    r = r * 10 + (c - 48);
    i++;
  }
  if (i === start) {
    if (strict && i === n) return 0;
    return __nan("converting the string '" + s + "' to a number");
  }
  if (strict) {
    while (i < n && __strByte(s, i) === 32) i++;
    if (i !== n) return __nan("converting the string '" + s + "' to a number");
  }
  return neg ? -r : r;
}

// ---- numbers and operators the compiler does not inline ----

function __toNumber(v) {
  var k = __kind(v);
  if (k === 0) return v;
  if (k === 7) return v ? 1 : 0;
  if (k === 6) return 0;
  if (k === 1) return __parseInteger(v, true);
  return __nan("converting " + __typeof(v) + " to a number");
}

function __negate(v) {
  return 0 - __toNumber(v);
}

function __add(a, b) {
  var ka = __kind(a);
  var kb = __kind(b);
  if (ka === 1 || kb === 1 || (ka >= 2 && ka <= 4) || (kb >= 2 && kb <= 4)) {
    return __strConcat(__str(a), __str(b));
  }
  return __toNumber(a) + __toNumber(b);
}

function __binary(op, a, b) {
  a = __toNumber(a);
  b = __toNumber(b);
  if (op === "-") return a - b;
  if (op === "*") return a * b;
  if (op === "/") return a / b;
  if (op === "%") return a % b;
  if (op === "&") return a & b;
  if (op === "|") return a | b;
  if (op === "^") return a ^ b;
  if (op === "<<") return a << b;
  if (op === ">>") return a >> b;
  return a >>> b;
}

function __pow(a, b) {
  a = __toNumber(a);
  b = __toNumber(b);
  if (b < 0) return __nan("raising to a negative power");
  var r = 1;
  while (b > 0) {
    r = r * a;
    b--;
  }
  return r;
}

// Returns -1, 0 or 1. Only reached when an operand is not an integer.
function __compare(a, b) {
  if (__kind(a) === 1 && __kind(b) === 1) {
    var la = __strLen(a);
    var lb = __strLen(b);
    var n = la < lb ? la : lb;
    for (var i = 0; i < n; i++) {
      var x = __strByte(a, i);
      var y = __strByte(b, i);
      if (x !== y) return x < y ? -1 : 1;
    }
    return la < lb ? -1 : (la > lb ? 1 : 0);
  }
  var p = __toNumber(a);
  var q = __toNumber(b);
  return p < q ? -1 : (p > q ? 1 : 0);
}

function __looseEquals(a, b) {
  if (__identical(a, b)) return true;
  var ka = __kind(a);
  var kb = __kind(b);
  if (ka === 1 && kb === 1) return __strEq(a, b);
  var anull = ka === 5 || ka === 6;
  var bnull = kb === 5 || kb === 6;
  if (anull || bnull) return anull && bnull;
  if ((ka >= 2 && ka <= 4) || (kb >= 2 && kb <= 4)) return false;
  return __toNumber(a) === __toNumber(b);
}

function __truthy(v) {
  if (__isInt(v)) return v !== 0;
  if (__isPtr(v)) {
    if (__peek(__addr(v)) === 1) return __strLen(v) !== 0;
    return true;
  }
  return false;
}

function __typeof(v) {
  var k = __kind(v);
  if (k === 0) return "number";
  if (k === 1) return "string";
  if (k === 4) return "function";
  if (k === 5) return "undefined";
  if (k === 7) return "boolean";
  return "object";
}

// ---- arrays ----

function __arrLen(a) {
  return __peek(__addr(a) + 8);
}

function __arrGet(a, i) {
  return __peek(__peek(__addr(a) + 24) + 8 * i);
}

function __newArray() {
  var a = __alloc(32);
  __poke(a, 2);
  __poke(a + 8, 0);
  __poke(a + 16, 4);
  __poke(a + 24, __alloc(32));
  return __ptr(a);
}

function __push(arr, v) {
  var a = __addr(arr);
  var n = __peek(a + 8);
  var cap = __peek(a + 16);
  var data = __peek(a + 24);
  if (n === cap) {
    var ncap = cap * 2;
    var grown = __alloc(8 * ncap);
    __copy(grown, data, 8 * n);
    __poke(a + 16, ncap);
    __poke(a + 24, grown);
    data = grown;
  }
  __poke(data + 8 * n, v);
  __poke(a + 8, n + 1);
  return n + 1;
}

// ---- objects ----

function __newObject() {
  var a = __alloc(32);
  __poke(a, 3);
  __poke(a + 8, 0);
  __poke(a + 16, 4);
  __poke(a + 24, __alloc(64));
  return __ptr(a);
}

// Returns the address of the value slot for key k in the object at address a, or 0.
function __objFind(a, k) {
  var n = __peek(a + 8);
  var data = __peek(a + 24);
  for (var i = 0; i < n; i++) {
    var key = __peek(data + 16 * i);
    if (__identical(key, k) || __strEq(key, k)) return data + 16 * i + 8;
  }
  return 0;
}

function __objAppend(a, k, v) {
  var n = __peek(a + 8);
  var cap = __peek(a + 16);
  var data = __peek(a + 24);
  if (n === cap) {
    var ncap = cap * 2;
    var grown = __alloc(16 * ncap);
    __copy(grown, data, 16 * n);
    __poke(a + 16, ncap);
    __poke(a + 24, grown);
    data = grown;
  }
  __poke(data + 16 * n, k);
  __poke(data + 16 * n + 8, v);
  __poke(a + 8, n + 1);
}

function __keyString(k) {
  var kind = __kind(k);
  if (kind === 1) return k;
  if (kind === 0) return __intToString(k);
  return __str(k);
}

function __protoGet(proto, k) {
  var s = __objFind(__addr(proto), k);
  if (s === 0) return undefined;
  return __peek(s);
}

function __get(o, k) {
  var kind = __kind(o);
  if (kind === 3) return __protoGet(o, __keyString(k));
  if (kind === 2) {
    if (__isInt(k)) {
      if (k >= 0 && k < __arrLen(o)) return __arrGet(o, k);
      return undefined;
    }
    if (k === "length") return __arrLen(o);
    return __protoGet(__ArrayProto, k);
  }
  if (kind === 1) {
    if (__isInt(k)) {
      if (k >= 0 && k < __strLen(o)) return __substring(o, k, k + 1);
      return undefined;
    }
    if (k === "length") return __strLen(o);
    return __protoGet(__StringProto, k);
  }
  if (kind === 4) {
    var props = __peek(__addr(o) + 16);
    if (props === undefined) return undefined;
    return __protoGet(props, __keyString(k));
  }
  if (kind === 0 || kind === 7) return undefined;
  return __fail("TypeError: Cannot read properties of " + __str(o) + " (reading '" + __str(k) + "')");
}

function __set(o, k, v) {
  var kind = __kind(o);
  if (kind === 3) {
    var key = __keyString(k);
    var a = __addr(o);
    var slot = __objFind(a, key);
    if (slot !== 0) __poke(slot, v);
    else __objAppend(a, key, v);
    return v;
  }
  if (kind === 2) {
    var n = __arrLen(o);
    if (__isInt(k)) {
      if (k < 0) return __fail("RangeError: negative array indexes are not supported");
      if (k < n) {
        __poke(__peek(__addr(o) + 24) + 8 * k, v);
        return v;
      }
      while (n < k) {
        __push(o, undefined);
        n++;
      }
      __push(o, v);
      return v;
    }
    if (k === "length") {
      if (v < n) __poke(__addr(o) + 8, v);
      while (n < v) {
        __push(o, undefined);
        n++;
      }
      return v;
    }
    return __fail("TypeError: arrays cannot have a property named '" + __str(k) + "' yet");
  }
  if (kind === 4) {
    var f = __addr(o);
    var props = __peek(f + 16);
    if (props === undefined) {
      props = __newObject();
      __poke(f + 16, props);
    }
    return __set(props, k, v);
  }
  if (kind === 5 || kind === 6) {
    return __fail("TypeError: Cannot set properties of " + __str(o) + " (setting '" + __str(k) + "')");
  }
  return v;
}

function __length(v) {
  var k = __kind(v);
  if (k === 2) return __arrLen(v);
  if (k === 1) return __strLen(v);
  return __fail("TypeError: " + __typeof(v) + " is not iterable");
}

// ---- array methods ----

// Resolves a relative index argument (negative counts from the end) and clamps it to [0, n].
function __index(i, n, dflt) {
  if (i === undefined) return dflt;
  i = __toNumber(i);
  if (i < 0) i = i + n;
  if (i < 0) return 0;
  if (i > n) return n;
  return i;
}

var __ArrayProto = {
  push: function (v) {
    var n = __argc();
    for (var i = 0; i < n; i++) __push(this, __arg(i));
    return __arrLen(this);
  },
  pop: function () {
    var n = __arrLen(this);
    if (n === 0) return undefined;
    var v = __arrGet(this, n - 1);
    __poke(__addr(this) + 8, n - 1);
    return v;
  },
  shift: function () {
    var n = __arrLen(this);
    if (n === 0) return undefined;
    var v = __arrGet(this, 0);
    var data = __peek(__addr(this) + 24);
    __copy(data, data + 8, 8 * (n - 1));
    __poke(__addr(this) + 8, n - 1);
    return v;
  },
  unshift: function (v) {
    var n = __arrLen(this);
    __push(this, undefined);
    var data = __peek(__addr(this) + 24);
    for (var i = n; i > 0; i--) __poke(data + 8 * i, __peek(data + 8 * (i - 1)));
    __poke(data, v);
    return n + 1;
  },
  slice: function (start, end) {
    var n = __arrLen(this);
    var from = __index(start, n, 0);
    var to = __index(end, n, n);
    var r = __newArray();
    for (var i = from; i < to; i++) __push(r, __arrGet(this, i));
    return r;
  },
  concat: function (other) {
    var r = __newArray();
    var n = __arrLen(this);
    for (var i = 0; i < n; i++) __push(r, __arrGet(this, i));
    var argc = __argc();
    for (var j = 0; j < argc; j++) {
      var a = __arg(j);
      if (__kind(a) === 2) {
        var m = __arrLen(a);
        for (var k = 0; k < m; k++) __push(r, __arrGet(a, k));
      } else {
        __push(r, a);
      }
    }
    return r;
  },
  join: function (sep) {
    return __join(this, sep === undefined ? "," : __str(sep));
  },
  indexOf: function (x) {
    var n = __arrLen(this);
    for (var i = 0; i < n; i++) {
      if (__arrGet(this, i) === x) return i;
    }
    return -1;
  },
  includes: function (x) {
    var n = __arrLen(this);
    for (var i = 0; i < n; i++) {
      if (__arrGet(this, i) === x) return true;
    }
    return false;
  },
  reverse: function () {
    var n = __arrLen(this);
    var data = __peek(__addr(this) + 24);
    for (var i = 0; i < n - 1 - i; i++) {
      var t = __peek(data + 8 * i);
      __poke(data + 8 * i, __peek(data + 8 * (n - 1 - i)));
      __poke(data + 8 * (n - 1 - i), t);
    }
    return this;
  },
  forEach: function (f) {
    var n = __arrLen(this);
    for (var i = 0; i < n; i++) f(__arrGet(this, i), i, this);
    return undefined;
  },
  map: function (f) {
    var n = __arrLen(this);
    var r = __newArray();
    for (var i = 0; i < n; i++) __push(r, f(__arrGet(this, i), i, this));
    return r;
  },
  filter: function (f) {
    var n = __arrLen(this);
    var r = __newArray();
    for (var i = 0; i < n; i++) {
      var x = __arrGet(this, i);
      if (f(x, i, this)) __push(r, x);
    }
    return r;
  },
  reduce: function (f, init) {
    var n = __arrLen(this);
    var i = 0;
    var acc = init;
    if (__argc() < 2) {
      if (n === 0) return __fail("TypeError: Reduce of empty array with no initial value");
      acc = __arrGet(this, 0);
      i = 1;
    }
    for (; i < n; i++) acc = f(acc, __arrGet(this, i), i, this);
    return acc;
  },
  some: function (f) {
    var n = __arrLen(this);
    for (var i = 0; i < n; i++) {
      if (f(__arrGet(this, i), i, this)) return true;
    }
    return false;
  },
  every: function (f) {
    var n = __arrLen(this);
    for (var i = 0; i < n; i++) {
      if (!f(__arrGet(this, i), i, this)) return false;
    }
    return true;
  },
  find: function (f) {
    var n = __arrLen(this);
    for (var i = 0; i < n; i++) {
      var x = __arrGet(this, i);
      if (f(x, i, this)) return x;
    }
    return undefined;
  },
  findIndex: function (f) {
    var n = __arrLen(this);
    for (var i = 0; i < n; i++) {
      if (f(__arrGet(this, i), i, this)) return i;
    }
    return -1;
  },
  // Stable insertion sort. Without a comparator elements compare as strings, like JavaScript.
  sort: function (f) {
    var n = __arrLen(this);
    var data = __peek(__addr(this) + 24);
    for (var i = 1; i < n; i++) {
      var x = __peek(data + 8 * i);
      var j = i - 1;
      while (j >= 0) {
        var y = __peek(data + 8 * j);
        var after = f === undefined ? __compare(__str(y), __str(x)) > 0 : f(y, x) > 0;
        if (!after) break;
        __poke(data + 8 * (j + 1), y);
        j--;
      }
      __poke(data + 8 * (j + 1), x);
    }
    return this;
  }
};

// ---- string methods ----

var __StringProto = {
  charAt: function (i) {
    if (i === undefined) i = 0;
    if (i < 0 || i >= __strLen(this)) return "";
    return __substring(this, i, i + 1);
  },
  charCodeAt: function (i) {
    if (i === undefined) i = 0;
    if (i < 0 || i >= __strLen(this)) return __nan("charCodeAt out of range");
    return __strByte(this, i);
  },
  indexOf: function (t, from) {
    return __strIndexOf(this, t, from === undefined ? 0 : from);
  },
  includes: function (t) {
    return __strIndexOf(this, t, 0) >= 0;
  },
  startsWith: function (t) {
    return __strIndexOf(__substring(this, 0, __strLen(t)), t, 0) === 0;
  },
  endsWith: function (t) {
    var n = __strLen(this);
    var m = __strLen(t);
    return m <= n && __strEq(__substring(this, n - m, n), t);
  },
  slice: function (start, end) {
    var n = __strLen(this);
    return __substring(this, __index(start, n, 0), __index(end, n, n));
  },
  substring: function (start, end) {
    var n = __strLen(this);
    var a = start === undefined ? 0 : __toNumber(start);
    var b = end === undefined ? n : __toNumber(end);
    if (a < 0) a = 0;
    if (b < 0) b = 0;
    if (a > n) a = n;
    if (b > n) b = n;
    if (a > b) {
      var t = a;
      a = b;
      b = t;
    }
    return __substring(this, a, b);
  },
  split: function (sep) {
    var r = __newArray();
    var n = __strLen(this);
    if (sep === undefined) {
      __push(r, this);
      return r;
    }
    var m = __strLen(sep);
    if (m === 0) {
      for (var i = 0; i < n; i++) __push(r, __substring(this, i, i + 1));
      return r;
    }
    var from = 0;
    while (true) {
      var at = __strIndexOf(this, sep, from);
      if (at < 0) break;
      __push(r, __substring(this, from, at));
      from = at + m;
    }
    __push(r, __substring(this, from, n));
    return r;
  },
  toUpperCase: function () {
    return __mapBytes(this, 97, 122, -32);
  },
  toLowerCase: function () {
    return __mapBytes(this, 65, 90, 32);
  },
  trim: function () {
    var a = 0;
    var b = __strLen(this);
    while (a < b && __strByte(this, a) <= 32) a++;
    while (b > a && __strByte(this, b - 1) <= 32) b--;
    return __substring(this, a, b);
  },
  repeat: function (count) {
    var r = "";
    for (var i = 0; i < count; i++) r = __strConcat(r, this);
    return r;
  },
  padStart: function (width, fill) {
    return __pad(this, width, fill, true);
  },
  padEnd: function (width, fill) {
    return __pad(this, width, fill, false);
  },
  concat: function (s) {
    return __strConcat(this, __str(s));
  }
};

function __mapBytes(s, lo, hi, delta) {
  var n = __strLen(s);
  var r = __newString(n);
  for (var i = 0; i < n; i++) {
    var c = __strByte(s, i);
    if (c >= lo && c <= hi) c = c + delta;
    __pokeByte(__addr(r) + 16 + i, c);
  }
  return r;
}

function __pad(s, width, fill, atStart) {
  var n = __strLen(s);
  if (fill === undefined) fill = " ";
  var m = __strLen(fill);
  if (width <= n || m === 0) return s;
  var padding = "";
  while (__strLen(padding) < width - n) padding = __strConcat(padding, fill);
  padding = __substring(padding, 0, width - n);
  return atStart ? __strConcat(padding, s) : __strConcat(s, padding);
}

// ---- console.log ----

var __ctxDepth = 0;

function __spaces(n) {
  var s = "";
  for (var i = 0; i < n; i++) s = __strConcat(s, " ");
  return s;
}

function __quote(s) {
  var q = 39;
  var mark = "'";
  if (__strIndexOf(s, "'", 0) >= 0 && __strIndexOf(s, "\"", 0) < 0) {
    q = 34;
    mark = "\"";
  }
  var r = mark;
  var n = __strLen(s);
  for (var i = 0; i < n; i++) {
    var c = __strByte(s, i);
    if (c === 10) r = __strConcat(r, "\\n");
    else if (c === 9) r = __strConcat(r, "\\t");
    else if (c === 92) r = __strConcat(r, "\\\\");
    else if (c === q) r = __strConcat(r, __strConcat("\\", mark));
    else r = __strConcat(r, __substring(s, i, i + 1));
  }
  return __strConcat(r, mark);
}

function __isIdentifier(s) {
  var n = __strLen(s);
  if (n === 0) return false;
  for (var i = 0; i < n; i++) {
    var c = __strByte(s, i);
    var letter = (c >= 65 && c <= 90) || (c >= 97 && c <= 122) || c === 95 || c === 36;
    if (!letter && !(i > 0 && c >= 48 && c <= 57)) return false;
  }
  return true;
}

// Mirrors node's util.inspect closely enough for plain data: one line when it fits in 72 columns.
function __inspect(v, depth) {
  var k = __kind(v);
  if (k === 1) return __quote(v);
  if (k === 2) {
    if (depth > 2) return "[Array]";
    var n = __arrLen(v);
    if (n === 0) return "[]";
    __ctxDepth = depth;
    var out = __newArray();
    for (var i = 0; i < n; i++) __push(out, __inspect(__arrGet(v, i), depth + 1));
    return __combine(out, "[", "]", depth);
  }
  if (k === 3) {
    if (depth > 2) return "[Object]";
    var count = __peek(__addr(v) + 8);
    if (count === 0) return "{}";
    __ctxDepth = depth;
    var parts = __newArray();
    var data = __peek(__addr(v) + 24);
    for (var j = 0; j < count; j++) {
      var key = __peek(data + 16 * j);
      var shown = __isIdentifier(key) ? key : __quote(key);
      __push(parts, __strConcat(__strConcat(shown, ": "), __inspect(__peek(data + 16 * j + 8), depth + 1)));
    }
    return __combine(parts, "{", "}", depth);
  }
  if (k === 4) return "[Function (anonymous)]";
  return __str(v);
}

function __combine(out, open, close, depth) {
  var n = __arrLen(out);
  if (__ctxDepth - depth < 3) {
    var total = n + n + 2 * depth + __strLen(open) + 10;
    for (var i = 0; i < n; i++) total = total + __strLen(__arrGet(out, i));
    if (total <= 80) {
      var joined = __join(out, ", ");
      if (__strIndexOf(joined, "\n", 0) < 0) {
        return __strConcat(__strConcat(__strConcat(open, " "), joined), __strConcat(" ", close));
      }
    }
  }
  var indent = __strConcat("\n", __spaces(2 * depth));
  var body = __join(out, __strConcat(",", __strConcat(indent, "  ")));
  return __strConcat(__strConcat(open, __strConcat(indent, "  ")), __strConcat(__strConcat(body, indent), close));
}

function __logLine(fd, args) {
  var n = __arrLen(args);
  var line = "";
  for (var i = 0; i < n; i++) {
    var a = __arrGet(args, i);
    if (i > 0) line = __strConcat(line, " ");
    line = __strConcat(line, __kind(a) === 1 ? a : __inspect(a, 0));
  }
  __write(fd, __strConcat(line, "\n"));
}

var console = {
  log: function () {
    var args = __newArray();
    for (var i = 0; i < __argc(); i++) __push(args, __arg(i));
    __logLine(1, args);
  },
  error: function () {
    var args = __newArray();
    for (var i = 0; i < __argc(); i++) __push(args, __arg(i));
    __logLine(2, args);
  }
};

// ---- globals ----

function String(v) {
  return __str(v);
}
String.fromCharCode = function (c) {
  var s = __newString(1);
  __pokeByte(__addr(s) + 16, c);
  return s;
};

function Number(v) {
  return __toNumber(v);
}
Number.isInteger = function (v) {
  return __kind(v) === 0;
};
Number.MAX_SAFE_INTEGER = 9007199254740991;

function parseInt(s) {
  return __parseInteger(__str(s), false);
}

var Math = {
  floor: function (x) { return x; },
  ceil: function (x) { return x; },
  round: function (x) { return x; },
  trunc: function (x) { return x; },
  abs: function (x) { return x < 0 ? -x : x; },
  sign: function (x) { return x < 0 ? -1 : (x > 0 ? 1 : 0); },
  pow: function (a, b) { return __pow(a, b); },
  max: function () {
    var n = __argc();
    if (n === 0) return __nan("Math.max of no arguments");
    var m = __arg(0);
    for (var i = 1; i < n; i++) if (__arg(i) > m) m = __arg(i);
    return m;
  },
  min: function () {
    var n = __argc();
    if (n === 0) return __nan("Math.min of no arguments");
    var m = __arg(0);
    for (var i = 1; i < n; i++) if (__arg(i) < m) m = __arg(i);
    return m;
  }
};

var Array = {
  isArray: function (v) { return __kind(v) === 2; }
};

var Object = {
  keys: function (o) {
    var r = __newArray();
    var k = __kind(o);
    if (k === 3) {
      var n = __peek(__addr(o) + 8);
      var data = __peek(__addr(o) + 24);
      for (var i = 0; i < n; i++) __push(r, __peek(data + 16 * i));
    } else if (k === 2) {
      for (var j = 0; j < __arrLen(o); j++) __push(r, __intToString(j));
    }
    return r;
  },
  values: function (o) {
    var r = __newArray();
    var keys = Object.keys(o);
    for (var i = 0; i < __arrLen(keys); i++) __push(r, __get(o, __arrGet(keys, i)));
    return r;
  }
};
