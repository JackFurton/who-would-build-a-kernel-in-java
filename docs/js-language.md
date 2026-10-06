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
| Functions | declarations, expressions, arrows, closures, `this`, `call`/`apply`/`bind`, name inference (`const f = () => 1` is named `f`) |
| Objects | literals, shorthand properties and methods, computed access, prototypes, `new`, constructor functions, `instanceof`, `in`, `delete`, `for...in`, property enumeration order (indexes first) |
| Operators | arithmetic and bitwise (32-bit), comparison, `===`/`==`, logical, `??`, `?:`, `typeof`, `void`, `delete`, compound assignment, `++`/`--` |
| Statements | `if`, `for`, `for...of`, `for...in`, `while`, `do...while`, `break`, `continue`, blocks |
| Strings | literals, template literals, the usual methods (`slice`, `split`, `indexOf`, `padStart`, ...) |
| Arrays | literals, indexing, `length`, and the common methods (`push`, `map`, `filter`, `reduce`, `sort`, ...) |
| Built-ins | `console.log`/`error` (node-style formatting), `Math` (integer subset), `Object` statics (`keys`, `values`, `entries`, `assign`, `create`, `getPrototypeOf`, `setPrototypeOf`, `hasOwn`), `Object.prototype` methods, `Array.isArray`/`of`, `String`, `Number`, `parseInt` |

## Not yet (unbuilt)

In the order we expect to do them: `throw`/`try`/`catch`/`finally` and the `Error` types, `switch`,
labels, classes (`extends`, `super`, `static`, getters and setters), default parameters, rest and
spread, destructuring, `arguments`, tagged templates, `Symbol`, iterators and generators, `Map`/`Set`/
`WeakMap`/`WeakSet`, modules (`import`/`export`), `Promise` and `async`/`await`, optional chaining,
`Object.defineProperty` and property descriptors, `Object.freeze`, `JSON`, typed arrays.

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
- Arrays have no holes: `delete a[i]` and growing past the end store `undefined` (node prints `<1 empty item>`).
- `console.log` doesn't group arrays of more than six items into columns, as node does.
- Methods in object literals can be used with `new`; node throws.
- `for...in` and `Object.keys` don't see inherited built-ins because they are non-enumerable; there is no way
  yet to create a non-enumerable property from JavaScript.
