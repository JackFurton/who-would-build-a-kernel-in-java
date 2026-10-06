# JavaScript language coverage

What `jsc` accepts when it translates JavaScript for the kernel (`jsc --java`), and what it doesn't
yet. The aim is as much of ES2015 as the kernel allows; gaps that wait on the kernel are listed
separately from gaps that are just unbuilt. Update this file with every change to the language.

Programs in `tests/js` are the reference: each one runs under node and through `jsc`, and the output
must match. The x86 Linux back end covers an older, smaller subset; a program that needs more starts
with `// backends: java` and skips it.

## Supported

| Area | What works |
| --- | --- |
| Declarations | `var`, `let`, `const`, function declarations (hoisted), per-iteration `let` in `for` loops |
| Functions | declarations, expressions, arrows, closures, `this`, `arguments`, default and rest parameters, `call`/`apply`/`bind`, name inference (`const f = () => 1` is named `f`) |
| Destructuring | array and object patterns (nested, with defaults, rest and computed keys) in declarations, assignments, parameters, `for...of`/`for...in` heads and `catch`; array holes |
| Spread | `...` in calls, `new`, array literals and object literals (arrays and strings are the iterables for now) |
| Classes | `class` declarations and expressions, `extends` (classes, constructor functions, `Error`, `null`), `super(...)`, `super.x` and `super.m()` in methods and static methods, instance and static fields, static blocks, private names (`#x`, fields and methods), getters and setters, computed member names; a class can't be called without `new` |
| Objects | literals, shorthand properties and methods, getters and setters, computed keys (`{[k]: v}`), computed access, `Object.defineProperty`/`defineProperties`, property descriptors (`getOwnPropertyDescriptor(s)`, `getOwnPropertyNames`), `freeze`/`seal`/`preventExtensions` and their `is...` checks, `Object.is`/`fromEntries`, prototypes, `new`, constructor functions, `instanceof`, `in`, `delete`, `for...in`, property enumeration order (indexes first) |
| Operators | arithmetic and bitwise (32-bit), comparison, `===`/`==`, logical, `??`, `?:`, optional chaining (`?.`, `?.[]`, `?.()`), `typeof`, `void`, `delete`, compound assignment, `++`/`--` |
| Literals | decimal, `0x`, `0b` and `0o` integers (with `_` separators), strings, template literals, array and object literals |
| Statements | `if`, `for`, `for...of`, `for...in`, `while`, `do...while`, `switch` (fallthrough, `default` anywhere), labels with `break`/`continue`, `break`, `continue`, blocks, `throw`, `try`/`catch`/`finally` (with or without a catch binding) |
| Errors | `Error`, `TypeError`, `RangeError`, `ReferenceError`, `SyntaxError`, `EvalError`, `URIError`; runtime errors (reading a property of `undefined`, calling a non-function, stack overflow) are catchable |
| Strings | literals, template literals and tagged templates (`String.raw` too), the usual methods (`slice`, `split`, `indexOf`, `padStart`, ...) |
| Arrays | literals, indexing, `length`, and the common methods (`push`, `map`, `filter`, `reduce`, `sort`, ...) |
| Built-ins | `console.log`/`error` (node-style formatting), `Math` (integer subset), `Object` statics (`keys`, `values`, `entries`, `assign`, `create`, `getPrototypeOf`, `setPrototypeOf`, `hasOwn`), `Object.prototype` methods, `Array.isArray`/`of`, `String`, `Number`, `parseInt` |

## Not yet (unbuilt)

In the order we expect to do them: `Symbol`, iterators and generators, `Map`/`Set`/`WeakMap`/`WeakSet`, modules
(`import`/`export`), `Promise` and `async`/`await`, `JSON`, typed arrays.

## Waiting on the kernel

| Feature | Needs |
| --- | --- |
| Fractions, `NaN`, `Infinity`, `Math.sqrt` and friends | floating point in `dukec` and SSE enabled in the kernel; numbers are 64-bit integers until then |
| Non-Latin-1 text, `\u` escapes above 0xFF, `codePointAt` | UTF-16 strings (the kernel's are Latin-1) |
| Regular expressions | a regex engine |
| `Date` | a clock API for JS, then the date arithmetic |
| `setTimeout`, timers | an event loop on top of the kernel timer |

## Cannot be supported

These need code compiled at run time, and there is no compiler or interpreter in the kernel:
`eval`, `new Function(...)`, `with`, dynamic `import()`, and `Proxy`/`Reflect` (which intercept every
property operation). Calling `new Function` throws an error that says so.

## Differences from node

- Numbers are integers, so `7 / 2` truncates and there is no `NaN`: operations that would produce one throw.
- Code always behaves as strict mode where it matters: writing to a read-only property or a frozen object, adding to a
  non-extensible one, or deleting a non-configurable property throws a `TypeError` (node only does in strict mode, and
  quietly ignores the write otherwise).
- Arrays have no holes: `delete a[i]` and growing past the end store `undefined` (node prints `<1 empty item>`).
- `console.log` doesn't group arrays of more than six items into columns, as node does.
- Methods in object literals can be used with `new`; node throws. `super` doesn't work in object-literal methods.
- Private names are properties whose names start with `#` that enumeration, `Object.keys` and `console.log` skip; unlike
  node, `obj["#x"]` can reach them from outside and there are no brand checks.
- A class can extend another class, a constructor function, `Error` or `null`, but not `Array`, `Map` or `Set`.
- `new.target` isn't supported, and a named class expression doesn't bind its own name inside the class.
- Field initializers assign rather than define, so a setter on the prototype chain would run.
- Array patterns read by index, so they work on arrays and strings only until iterators exist.
- Array literals with holes (`[1, , 2]`) store `undefined` there; node keeps a hole.
- `arguments` is a plain array with no link back to the parameters, and `console.log(arguments)` prints it as one.
- A tagged template's strings array is built on each call, so it is not the same object every time as in node.
- An undeclared name is a compile error, where node throws a `ReferenceError` when the line runs.
- Errors have no stack trace: `stack` is just `Name: message`, and `console.log(err)` prints that (inside an object, in
  brackets) where node prints the trace.
- An uncaught throw ends the shell command with `error: Uncaught ...`.
- `for...in` and `Object.keys` don't see inherited built-ins because they are non-enumerable; there is no way
  yet to create a non-enumerable property from JavaScript.
