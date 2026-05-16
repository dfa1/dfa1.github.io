# Explore Valhalla

*16 May 2026*

*[Your Compiler Is Already Part of Your Security Team](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team) made the case for domain primitives: encode constraints in types, the compiler enforces them forever. The recurring objection — a wrapper class per `int` is one heap object per value, plus a pointer to reach it. Fine at the boundary; questionable in a hot loop where allocations and cache misses dominate.*

*Project Valhalla in Java 27 EA changes the trade-off. Value classes let the JVM flatten the wrapper into the array slot, the register, the enclosing object — no header, no indirection.*

*I found [a gist I wrote in 2019](https://gist.github.com/dfa1/f6fdca0513730dc7dc7d6a5d89629709) about refined types and wanted to explore the design further.*

---

## The blocker: wrapper overhead

A `class PositiveInt { final int v; }` is 16 bytes on HotSpot — 12-byte header plus 4-byte int (8-byte header with `-XX:+UseCompactObjectHeaders`, production-ready since JDK 24[^compact-headers]) — and the array holding it stores 4-byte references rather than the values themselves. A stream processor carrying millions of `PositiveInt` sequence numbers, one per event, means millions of heap objects — each one a cache miss waiting to happen, each one tracked by the GC. The wrapper costs 4× the memory of the `int` it wraps, and the pointer chase typically costs one L2 cache miss per access.

The practical rule that I followed, until now, has been: refine your types at the boundary, then quit before you hit any performance-sensitive code. You can refine your boundaries; you cannot refine your hot path. Refined types stayed in the outermost layer; the loops over millions of events kept using raw `int`, raw `float`, raw `String`.

That was the friction. Value classes lift it — and the same pattern now fits far more use cases.

## What is a refined type?

In Scala and similar languages, it is possible to narrow a base type with a predicate and get a compile-time rejection on invalid literal values. Java has no such mechanism — the constraint check runs at construction time, not compile time:

```java
public class Refined<T> {

    private final T value;

    public Refined(Predicate<T> validator, T value) {
        if (!validator.test(value)) {
            throw new IllegalArgumentException("invalid value");
        }
        this.value = value;
    }

    public final T getValue() {
        return value;
    }

    // ...provide equals/hashCode/toString
}
```

A concrete refined type extends `Refined<T>` and supplies the predicate.
To represent a positive integer coming from an external interface:

```java
private static class PositiveInt extends Refined<Integer> {

    public PositiveInt(int value) {
        super(i -> i > 0, value);
    }
}
```

Companion classes provide the static factories:

```java
public class Refining {

    public static PositiveInt positiveInt(int value) {
        return new PositiveInt(value);
    }
}
```

That gist only scratched the surface of the design space, and the approach was not efficient —
it forced all primitive types to be boxed.

## Enter Project Valhalla

Value classes are objects without identity. They carry behavior and invariants, but store like primitives — the JVM is free to inline them wherever they appear: into a register, into another object's fields, into an array slot.

Java 27 EA ships [Project Valhalla](https://openjdk.org/projects/valhalla/) preview[^valhalla-jep]. The new keyword is `value`:

```java
public value class PositiveInt implements RefinedInt {
    private final int value;

    public PositiveInt(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("must be positive: " + value);
        }
        this.value = value;
    }

    @Override public int value() { return value; }
}
```

Same shape as a regular wrapper. Two things change underneath:

1. **No identity.** `==` is a field-wise substitutability test, `null` is not assignable to the null-restricted form, synchronizing on the value throws `IdentityException`, `System.identityHashCode` derives from field values, not identity.
2. **Flat layout.** The JVM is allowed to inline the fields wherever a `PositiveInt` lives — into a register, into another object, into an array slot. No header, no pointer chasing.

The constructor still runs. The validation still happens. The compile-time guarantee — *anywhere I see a `PositiveInt`, the value is positive* — still holds.

## The numbers

```
PositiveInt[100]:   416 bytes (flat inline storage)
    Integer[100]:  2816 bytes (array shell + 100 heap objects)
```

Numbers measured on 64-bit HotSpot without compressed oops.[^bench-config] To see why, compare
the two layouts:
```
Integer[10]  — reference array

  ┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
  │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │  ← 4-byte refs
  └──┬──┴──┬──┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴──┬──┘
     │     │                        ...                    │
     ▼     ▼                                               ▼
  ┌──────┐┌──────┐┌──────┐                             ┌──────┐
  │header││header││header│          ...                │header│  ← 8-12 bytes each
  │  v0  ││  v1  ││  v2  │                             │  v9  │
  └──────┘└──────┘└──────┘                             └──────┘
  10 heap objects scattered — one cache miss per element


PositiveInt[10]  — value array

  ┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐
  │ v0 │ v1 │ v2 │ v3 │ v4 │ v5 │ v6 │ v7 │ v8 │ v9 │  ← values inline
  └────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘
  contiguous in memory — sequential reads hit the cache line
```

This is not a benchmark trick. It is the layout the JVM chooses when it has permission. Identity
costs space; saying *I don't need identity* is the permission slip.

The original overhead objection no longer applies. The compile-time guarantee is unchanged. If you
want the full picture — the type catalog, trade-offs, and where the pattern pays off — the library
covers the full pattern.[^refined-type]

The library covers a range of domains — all value classes[^refined-type]:

| Domain | Types |
|---|---|
| Network | `Email`, `HostName`, `Port`, `Slug` |
| Geography | `Latitude`, `Longitude`, `GeoPoint`, `Distance` |
| Finance | `Price`, `CurrencyCode`, `Percentage`, `CusipNumber`, `SwissValorNumber`, `Isin` |
| Measurement | `Age`, `Size`, `Velocity`, `Volume`, `Probability` |
| Unsigned integers | `UnsignedByte`, `UnsignedShort`, `UnsignedInt`, `UnsignedLong` |
| ML | `Float16` |

## Conclusion

The remaining friction is integration work at the system boundary: converting to and from JSON, JPA, and similar frameworks. The library[^refined-type] includes example adapters for Jackson and JPA.

Valhalla removes the last reason to keep primitive types out of domain modeling.
*Codes like a class; stores like an int,* is reality. Domain primitives, like those described in
[Your Compiler Is Already Part of Your Security Team](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team), no longer carry a performance penalty.

---

[^refined-type]: Code at [github.com/dfa1/refined-type](https://github.com/dfa1/refined-type) — Java 27 EA, MIT.

[^valhalla-jep]: [Project Valhalla](https://openjdk.org/projects/valhalla/) — the umbrella effort. Two preview JEPs ship the surface used here: *Value Classes and Objects* (syntax and semantics of `value class`) and *Null-Restricted and Nullable Types*. JEP numbers have shifted across drafts; the project page links to the current ones.

[^compact-headers]: [JEP 450: Compact Object Headers](https://openjdk.org/jeps/450) (production-ready, JDK 24+). Reduces the object header from 12 to 8 bytes on 64-bit HotSpot by merging the mark word and class pointer. Enabled with `-XX:+UseCompactObjectHeaders`.

[^bench-config]: Measured without `-XX:+UseCompressedOops`. With compressed oops (the default for heaps under 32 GB) each `Integer` is 16 bytes, giving ~2 016 bytes total.
