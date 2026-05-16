# Explore Valhalla

*16 May 2026*

*[Part 1](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team) made the case for domain primitives: encode constraints in types, the compiler enforces them forever. The recurring objection — a wrapper class per `int` is one heap object per value, plus a pointer to reach it. Fine at the boundary; questionable in a hot loop where allocations and cache misses dominate.*

*Project Valhalla in Java 27 EA changes the trade-off. Value classes let the JVM flatten the wrapper into the array slot, the register, the enclosing object — no header, no indirection. So the question worth exploring: can a refined `Port` or `Probability` now carry its compile-time guarantee into the inner loop without paying the memory tax that made the objection real in the first place?*

---

## The idea: constraints as types

The rule from part 1: encode the domain in the type. A `Port` whose constructor rejects anything outside `[0, 65535]` is a proof — once you hold one, no further check is needed. Boundary code parses; inner code consumes.

The reaction in my inbox split into two pushbacks. The first — *"my team won't write all those wrapper classes"* — is real but tractable: write them once, put them in a shared module, and the cost is paid once. The second was harder, and is what this article is about.

## The blocker: wrapper overhead

A `class Price { final long v; }` is 24 bytes on HotSpot — 12-byte header plus 8-byte long, padded to alignment (8-byte header with `-XX:+UseCompactObjectHeaders`, production-ready since JDK 24[^compact-headers]) — and the array holding it stores 4-byte references rather than the values themselves. An end-of-day push distributes the closing `Price` for every traded instrument to every connected client — millions of `Price` instances scattered across the heap, each one a cache miss waiting to happen, each one tracked by the GC. The wrapper costs roughly 3× the memory of the `long` it wraps, and the pointer chase costs one L2 cache miss per access.

The practical rule, until now, has been: refine your types at the boundary, then quit before you hit the inner loop. You can refine your boundaries; you cannot refine your hot path. Refined types stayed in the parsing layer; the loops over millions of events kept using raw `int`, raw `float`, raw `String`.

That was the friction. Value classes lift it — and the same pattern now fits a wider set of use cases.

## Enter Project Valhalla — value classes

Value classes are objects without identity. They carry behavior and invariants, but store like primitives — the JVM is free to inline them wherever they appear: into a register, into another object's fields, into an array slot.

Java 27 EA ships [Project Valhalla](https://openjdk.org/projects/valhalla/) preview[^valhalla-jep]. The new keyword is `value`:

```java
public value class Price implements RefinedLong {
    private final long value; // in cents

    public Price(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("price cannot be negative: " + value);
        }
        this.value = value;
    }

    @Override public long value() { return value; }
}
```

Same shape as a regular wrapper. Two things change underneath:

1. **No identity.** `==` is a field-wise substitutability test, `null` is not assignable to the null-restricted form, synchronizing on the value throws `IdentityException`, `System.identityHashCode` derives from field values, not identity.
2. **Flat layout.** The JVM is allowed to inline the fields wherever a `Price` lives — into a register, into another object, into an array slot. No header, no pointer chasing.

The constructor still runs. The validation still happens. The compile-time guarantee — *anywhere I see a `Price`, the value is non-negative* — still holds.

What disappears is the runtime cost.

## The numbers

I built [`refined-type`](https://github.com/dfa1/refined-type), a small library that lifts `Isin` from part 1 and adds `Email`, `HostName`, `Port`, `Slug`, the unsigned integers, `Size`, `Percentage`, `Probability`, and more. Everything is a `value class`. JOL says:

```
UnsignedInt[100]:   416 bytes  (flat inline storage)
   Integer[100]:  2816 bytes  (array shell + 100 heap objects)
```

To see why, compare the two layouts:

```
Integer[10]  — reference array

  ┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
  │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │  ← 4-byte refs
  └──┬──┴──┬──┴──┬──┴─────┴─────┴─────┴─────┴─────┴─────┴──┬──┘
     │     │     │                   ...                     │
     ▼     ▼     ▼                                           ▼
  ┌──────┐┌──────┐┌──────┐                             ┌──────┐
  │header││header││header│          ...                │header│  ← 12 bytes each
  │  v0  ││  v1  ││  v2  │                             │  v9  │
  └──────┘└──────┘└──────┘                             └──────┘
  10 heap objects scattered — one cache miss per element


UnsignedInt[10]  — value array

  ┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐
  │ v0 │ v1 │ v2 │ v3 │ v4 │ v5 │ v6 │ v7 │ v8 │ v9 │  ← values inline
  └────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘
  contiguous in memory — sequential reads hit the cache line
```

**~6.8× less memory**, scanned in cache-line strides instead of jumping pointer-to-pointer across the heap. JMH on the array-traversal benchmark shows the loop matches a bare `int[]`. The "wrapper tax" is gone.

This is not a benchmark trick. It is the layout the JVM chooses when it has permission. Identity costs space; saying *I don't need identity* is the permission slip.

The cost argument is off. The compile-time guarantee is unchanged. If you want the full picture — the type catalog, trade-offs, and where the pattern pays off — it is all in the [`refined-type` README](https://github.com/dfa1/refined-type).

---

> Code: [github.com/dfa1/refined-type](https://github.com/dfa1/refined-type) — Java 27 EA, MIT.

[^valhalla-jep]: [Project Valhalla](https://openjdk.org/projects/valhalla/) — the umbrella effort. Two preview JEPs ship the surface used here: *Value Classes and Objects* (syntax and semantics of `value class`) and *Null-Restricted and Nullable Types* (the `Port!` form referenced below). JEP numbers have shifted across drafts; the project page links to the current ones.

[^compact-headers]: [JEP 450: Compact Object Headers](https://openjdk.org/jeps/450) (production-ready, JDK 24+). Reduces the object header from 12 to 8 bytes on 64-bit HotSpot by merging the mark word and class pointer. Enabled with `-XX:+UseCompactObjectHeaders`.
