# Rethink Domain Primitives with Valhalla

*16 May 2026*

*[Your Compiler Is Already Part of Your Security Team](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team) made the case for domain primitives — types that encode business constraints, as opposed to Java primitives (`int`, `double`, `long`). Wrap an `int` in a `PositiveInt` and the compiler rejects invalid states forever. The recurring objection: a wrapper class per `int` is one heap object per value, plus a pointer to reach it. Fine at the boundary; questionable in a hot loop where allocations and cache misses dominate.*

*Project Valhalla changes the trade-off. Value classes let the JVM flatten the wrapper into the array slot, the register, the enclosing object — no header, no indirection. All experiments here run on `openjdk 27-jep401ea3`, the current Valhalla EA build implementing [JEP 401](https://openjdk.org/jeps/401).*

---

## The blocker: wrapper overhead

A `class PositiveInt { final int v; }` is 16 bytes on HotSpot — 12-byte header plus 4-byte int (8-byte header with `-XX:+UseCompactObjectHeaders`, production-ready since JDK 25[^compact-headers]) — and the array holding it stores 4-byte references rather than the values themselves. A stream processor carrying millions of `PositiveInt` sequence numbers, one per event, means millions of heap objects — each one a cache miss waiting to happen, each one tracked by the GC. The wrapper costs 4× the memory of the `int` it wraps, and the pointer chase costs an extra cache-line load per access — a cache miss on random access patterns.

The practical rule that I followed, until now, has been: refine your types at the boundary, then stop before you hit any performance-sensitive code. You can refine your boundaries; you cannot refine your hot path. Refined types stayed in the outermost layer; the loops over millions of events kept using raw `int`, raw `float`, raw `String`.

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

A concrete refined type extends `Refined<T>` and supplies the predicate — here, a positive integer:

```java
private static class PositiveInt extends Refined<Integer> {

    public PositiveInt(int value) {
        super(i -> i > 0, value);
    }
}
```

A utility class provides the static factories:

```java
public class Refining {

    public static PositiveInt positiveInt(int value) {
        return new PositiveInt(value);
    }
}
```

That [2019 gist](https://gist.github.com/dfa1/f6fdca0513730dc7dc7d6a5d89629709) only scratched the surface of the design space, and left performance as an open question — all primitive types were boxed. [refined-type](https://github.com/dfa1/refined-type) — a library of domain primitives backed by value classes — is the answer.

## Enter Project Valhalla

Value classes are objects without identity. They carry behavior and invariants, but store like primitives — the JVM is free to inline them wherever they appear: into a register, into another object's fields, into an array slot.

Records share the same compact carrier syntax but are identity classes — one heap object per value, references in arrays, object header included. The `value` keyword is the distinction: it tells the JVM this type has no identity, which is what allows flat layout.

Java 27 EA ships [Project Valhalla](https://openjdk.org/projects/valhalla/) preview[^valhalla-jep]. The new keyword is `value`:

```java
public value class PositiveInt implements RefinedInt<PositiveInt> {
    private final int value;

    public PositiveInt(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("must be positive: " + value);
        }
        this.value = value;
    }

    @Override public int value() { return value; }
}

// Refined<T> is now RefinedInt, to avoid boxing
// marker interface — F-bounded so Probability.compareTo(Price) won't compile
public interface RefinedInt<T extends RefinedInt<T>> extends Comparable<T> {

    int value();

    @Override
    default int compareTo(T that) {
        return Integer.compare(this.value(), that.value());
    }
}
```

Same shape as a regular wrapper. The constructor still runs. The validation still happens. The static guarantee — *anywhere I see a `PositiveInt`, the value is positive* — still holds.
The JVM is allowed to inline the fields wherever a `PositiveInt` lives — into a register, into another object, into an array slot. No header, no pointer chasing.

The pattern scales to multi-field types. `Coordinate` carries a `Latitude` and a `Longitude`, each a `double`-backed value class. The JVM inlines both doubles per slot — 16 bytes contiguous, no pointers:

```java
public value class Coordinate {
    private final Latitude latitude;
    private final Longitude longitude;

    public Coordinate(Latitude latitude, Longitude longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
```

A `Coordinate[10]` array stores 160 bytes of contiguous doubles. An identity-class equivalent stores 10 references pointing to 10 scattered heap objects, each carrying its own header.

One caveat: the flat layout applies only when the static type is `PositiveInt`. Code holding a `RefinedInt` reference — an interface parameter, a field, a collection element — forces heap allocation.

## The numbers

Memory footprint for arrays of 10 elements, measured on 64-bit HotSpot with compressed oops (the JVM default for heaps under 32 GB):[^bench-config]

```
int[10]                    :  56 bytes (bare primitive: cheap but not domain-aware)
PositiveInt[10] (identity) : 216 bytes (identity class: domain-aware but expensive)
PositiveInt[10] (value)    :  56 bytes (value class: cheap and domain-aware)
```

The value class matches the bare primitive; the identity class costs ~4×. The layouts show why:

```
PositiveInt[10]  — identity class

  ┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
  │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │ ptr │  ← 4-byte refs
  └──┬──┴──┬──┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴──┬──┘
     │     │                        ...                    │
     ▼     ▼                                               ▼
  ┌──────┐┌──────┐┌──────┐                             ┌──────┐
  │header││header││header│          ...                │header│  ← 8-12 bytes each
  │  v0  ││  v1  ││  v2  │                             │  v9  │
  └──────┘└──────┘└──────┘                             └──────┘
  10 heap objects scattered — extra cache-line load per element on random access


PositiveInt[10]  — value class

  ┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐
  │ v0 │ v1 │ v2 │ v3 │ v4 │ v5 │ v6 │ v7 │ v8 │ v9 │  ← values inline
  └────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘
  contiguous in memory — same layout as int[]
```

Same size, same layout, same cache behavior. The JVM chooses this when it has permission. Identity costs space; saying *I don't need identity* is the permission slip.

The original overhead objection no longer applies. The static guarantee is unchanged. The library includes several domain types, all backed by value classes:

| Domain | Types |
|---|---|
| Network | `Email`, `HostName`, `Port`, `Slug` |
| Geography | `Latitude`, `Longitude`, `Coordinate`, `Distance` |
| Finance | `Price`, `CurrencyCode`, `Percentage`, `CusipNumber`, `SwissValorNumber`, `Isin` |
| Measurement | `Age`, `Size`, `Velocity`, `Volume`, `Probability` |
| Unsigned integers | `UnsignedByte`, `UnsignedShort`, `UnsignedInt`, `UnsignedLong` |
| ML | `Float16` |

String-backed types (`Email`, `HostName`, `Slug`, etc.) drop the wrapper's object header but still hold a reference to the `String` — the indirection remains.

## Remaining considerations

**`==` semantics.** Value classes compare by value, not by pointer. `==` on a `PositiveInt` tests field-wise substitutability — equivalent to a well-implemented `equals`, but different from the pointer comparison `==` performed on the identity class. Migrating an existing identity class to `value` silently changes any `==` comparisons that relied on reference equality. Audit before converting.

**Null.** Value classes cannot be null. A companion JEP to 401 is introducing null-restricted references — tentatively `PositiveInt!` for the non-null form and `PositiveInt?` for the nullable form; the syntax is still in flux on this EA build. On the hot path the practical effect is already visible: the type proves the value exists, so null-checks disappear.

**Generics still box.** `List<PositiveInt>` and `Optional<PositiveInt>` box today — erasure forces each element to the heap. JEP 402 (generic specialization) is not yet shipped. Flat layout applies only to typed arrays (`PositiveInt[]`) and value-typed fields. In performance-critical code, use arrays; collections remain heap-heavy until specialization lands.

**Framework integration.** Jackson, JPA, and Bean Validation expect primitives and `String`. Each value type needs a thin adapter. A Jackson deserializer for `PositiveInt` is a few lines:

```java
class PositiveIntDeserializer extends StdDeserializer<PositiveInt> {
    PositiveIntDeserializer() { super(PositiveInt.class); }

    @Override
    public PositiveInt deserialize(JsonParser p, DeserializationContext ctx)
            throws IOException {
        return new PositiveInt(p.getIntValue());
    }
}
```

The library includes adapters for Jackson and JPA; register them with the usual module mechanism.

## Conclusion

Valhalla is on track to remove the last reason to keep primitive types out of domain modeling. The promise — *codes like a class; works like an int* — is close: value classes are in preview, not yet production-ready. Domain primitives, as described in [Your Compiler Is Already Part of Your Security Team](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team), may soon carry no performance penalty at all.

The code is at [github.com/dfa1/refined-type](https://github.com/dfa1/refined-type).
Issues, discussions, and pull requests are welcome!

---

[^valhalla-jep]: [Project Valhalla](https://openjdk.org/projects/valhalla/) — the umbrella effort. The main preview JEP is [JEP 401: Value Classes and Objects](https://openjdk.org/jeps/401) (syntax and semantics of `value class`). Null-restricted types are covered by a companion JEP. JEP numbers may advance as the feature progresses; the project page links to the current ones.

[^compact-headers]: [JEP 519: Compact Object Headers](https://openjdk.org/jeps/519) (product feature, JDK 25+). Reduces the object header from 12 to 8 bytes on 64-bit HotSpot by merging the mark word and class pointer. Opt-in via `-XX:+UseCompactObjectHeaders`. (JEP 450 shipped the same feature as experimental in JDK 24, requiring `-XX:+UnlockExperimentalVMOptions`.)

[^bench-config]: With compressed oops disabled (`-XX:-UseCompressedOops`), refs are 8 bytes and each `Integer` is 24 bytes — giving ~336 bytes total for 10 elements.
