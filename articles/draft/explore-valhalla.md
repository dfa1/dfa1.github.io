# Your Compiler Is Already Part of Your Security Team — Part 2: And Now It's Free

*16 May 2026*

*[Part 1](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team) made the case for domain primitives: encode constraints in types, the compiler enforces them forever. The recurring objection — a wrapper class per `int` is one heap object per value, plus a pointer to reach it. Fine at the boundary; questionable in a hot loop where allocations and cache misses dominate.*

*Project Valhalla in Java 27 EA changes the trade-off. Value classes let the JVM flatten the wrapper into the array slot, the register, the enclosing object — no header, no indirection. So the question worth exploring: can a refined `Port` or `Probability` now carry its compile-time guarantee into the inner loop without paying the memory tax that made the objection real in the first place?*

---

## The idea: constraints as types

The rule from part 1: encode the domain in the type. A `Port` whose constructor rejects anything outside `[0, 65535]` is a proof — once you hold one, no further check is needed. Boundary code parses; inner code consumes.

The reaction in my inbox split into two pushbacks. The first — *"my team won't write all those wrapper classes"* — is real but tractable: write them once, put them in a shared module, and the cost is paid once. The second was harder, and is what this article is about.

## The blocker: wrapper overhead

A `class Volume { final long v; }` is 24 bytes on HotSpot — 12-byte header plus 8-byte long, padded to alignment — and the array holding it stores 4-byte references rather than the values themselves. One trading session of order-book ticks is millions of `Volume` instances scattered across the heap, each one a cache miss waiting to happen, each one tracked by the GC. The wrapper costs roughly 3× the memory of the `long` it wraps, and the pointer chase costs one L2 cache miss per access.

The practical rule, until now, has been: refine your types at the boundary, then quit before you hit the inner loop. You can refine your boundaries; you cannot refine your hot path. Refined types stayed in the parsing layer; the loops over millions of events kept using raw `int`, raw `float`, raw `String`.

That was the friction. Value classes lift it — and the same pattern now fits a wider set of use cases.

## Enter Project Valhalla — value classes

Value classes are objects without identity. They carry behavior and invariants, but store like primitives — the JVM is free to inline them wherever they appear: into a register, into another object's fields, into an array slot.

Java 27 EA ships [Project Valhalla](https://openjdk.org/projects/valhalla/) preview[^valhalla-jep]. The new keyword is `value`:

```java
public value class Port implements RefinedInt {
    public static final int MAX_VALUE = 65_535;

    private final int value;

    public Port(int value) {
        if (value < 0 || value > MAX_VALUE) {
            throw new IllegalArgumentException("port out of range [0, 65535]: " + value);
        }
        this.value = value;
    }

    @Override public int value() { return value; }
}
```

Same shape as a regular wrapper. Two things change underneath:

1. **No identity.** `==` is a field-wise substitutability test, `null` is not assignable to the null-restricted form, synchronizing on the value throws `IdentityException`, `System.identityHashCode` derives from field values, not identity.
2. **Flat layout.** The JVM is allowed to inline the fields wherever a `Port` lives — into a register, into another object, into an array slot. No header, no pointer chasing.

The constructor still runs. The validation still happens. The compile-time guarantee — *anywhere I see a `Port`, the value is in `[0, 65535]`* — still holds.

What disappears is the runtime cost.

## The numbers

I built [`refined-type`](https://github.com/dfa1/refined-type), a small library that lifts `Isin` from part 1 and adds `Email`, `HostName`, `Port`, `Slug`, the unsigned integers, `Size`, `Percentage`, `Probability`, and more. Everything is a `value class`. JOL says:

```
UnsignedInt[100]:   416 bytes  (flat inline storage)
   Integer[100]:  2816 bytes  (array shell + 100 heap objects)
```

To see why, compare the two layouts:

```
Integer[3]  — reference array

  ┌─────┬─────┬─────┐
  │ ptr │ ptr │ ptr │  ← array stores 4-byte references
  └──┬──┴──┬──┴──┬──┘
     │     │     └─────────────────┐
     │     └──────────┐            │
     ▼                ▼            ▼
  ┌────────┐      ┌────────┐   ┌────────┐
  │ header │      │ header │   │ header │  ← 12 bytes each
  │   42   │      │   17   │   │   99   │
  └────────┘      └────────┘   └────────┘
  scattered across the heap — one cache miss per element


UnsignedInt[3]  — value array

  ┌────┬────┬────┐
  │ 42 │ 17 │ 99 │  ← values stored inline, no indirection
  └────┴────┴────┘
  contiguous in memory — sequential reads hit the cache line
```

**~6.8× less memory**, scanned in cache-line strides instead of jumping pointer-to-pointer across the heap. JMH on the array-traversal benchmark shows the loop matches a bare `int[]`. The "wrapper tax" is gone.

This is not a benchmark trick. It is the layout the JVM chooses when it has permission. Identity costs space; saying *I don't need identity* is the permission slip.

## What this changes for security

In part 1 the rule was: refine your types at the boundary, then quit before you hit the inner loop. With value classes, refined types belong in the inner loop too. Three concrete consequences.

### 1. Refined integers everywhere

A `Port` field used to be `int port`, with the comment "must be 0..65535" and the runtime check no one wrote. With value classes you write `Port port` in the struct, in the array, in the network protocol parser, and the bound is enforced once and never paid again:

```java
public record Listener(HostName host, Port port, ConnectionLimit limit) { }
```

Three constraints, three types, zero heap objects added by the refinement.

### 2. Probability vs Percentage — at scale

A common boundary bug is `* 100` rot: a probability ([0,1]) flowing into code that expected a percentage ([0,100]) or vice versa. Two refined floats catch it:

```java
public value class Probability implements RefinedFloat {
    public Probability(float v) {
        if (Float.isNaN(v) || v < 0f || v > 1f) {
            throw new IllegalArgumentException("probability must be finite and in [0, 1]: " + v);
        }
        this.value = v;
    }

    public Probability and(Probability other)  { return new Probability(this.value * other.value); }
    public Probability or(Probability other)   { /* inclusion-exclusion, clamped */ }
    public Probability complement()            { return new Probability(1f - value); }
}
```

A risk-scoring loop over a million events — `or`, `and`, `complement` chained — now type-checks the same way it did at the API boundary, and runs on a flat `Probability[]`. The compiler refuses `new Probability(50f)` at line 1; the JIT inlines the rest.

### 3. Slug, HostName — the strings that bite

Strings are where part 1 lived and where most of the bugs hide. ReDoS, header injection, path traversal, SSRF — all rooted in `String` being whatever the caller felt like. The library's `Slug` and `HostName` enforce the format up front:

- [`Slug`](https://en.wikipedia.org/wiki/Clean_URL#Slug): `^[a-z0-9](-?[a-z0-9])*$`, length ≤ 64, length-checked **before** the regex — standard defensive practice for any string validation.
- `HostName`: RFC 1123, plus an SSRF guard that rejects `localhost`, RFC 1918 ranges, and link-local `169.254.x.x` — the AWS instance-metadata endpoint, the origin of the 2019 Capital One breach[^capital-one].

The value-class part is what lets you keep these on the inside of the system, not just at the parser. A `Map<HostName, RateLimit>` becomes practical to use everywhere a `String` host used to flow, because the wrapper costs nothing.

## What value classes don't fix

Three caveats worth saying out loud:

- **Preview.** Java 27 EA. Production deployment requires `--enable-preview`. The shape is stable; the spec is not yet final.
- **Equality.** `value class` `equals` is structural by default — fine for `Port`, wrong for `ApiToken`. For tokens you still override `equals` to use `MessageDigest.isEqual` for constant-time comparison. The compiler doesn't know your equality is timing-sensitive; you do.
- **Null-restricted types are opt-in.** A plain `value class` is still nullable as a reference type. Null is excluded only when you mark the type null-restricted — the `Port!` form. That is mostly good news (no NPE on the restricted path) but it changes patterns: a missing `Port` is `Optional<Port>`, not `Port port = null`. Migration friction, not a deal-breaker.

## The honest trade-offs

Cheap layout is not free design. Four costs to weigh before sprinkling refined types across a codebase.

- **Boilerplate.** Every primitive is a constructor, a validator, an accessor, equality, `toString`, maybe `compareTo`. Records soften the syntax; they don't erase the surface area. A hundred refined types is a hundred small files to read, review, and maintain.
- **Integration friction.** Jackson, JPA, MapStruct, OpenAPI generators, protobuf — every framework needs to know how to serialize a `Port` or hydrate a `HostName`. Most have hooks; none discover wrappers automatically. Budget a one-time wiring tax per integration, paid in custom (de)serializers.
- **Generic noise.** `List<Port>` and `Map<HostName, RateLimit>` are fine. Cross into reflection, erasure-sensitive code, or libraries that demand raw `String`/`int`, and the cost rises. Refined types pay off in the core domain; near the edges of the platform, you spend time bridging.
- **Narrow sweet spot.** A value that flows from one endpoint into one method and back out to JSON is rarely worth wrapping. The payoff scales with the number of call sites the value crosses and the number of invariants that must hold across all of them.

## Where this pattern shines

The trade-offs flip the right way when the domain is dense in identifiers, regulated, or arithmetic-heavy. The [`refined-type`](https://github.com/dfa1/refined-type) library packages the recurring building blocks for these cases as value classes, so you import them rather than rewriting the same constructor for the tenth time.

- **Regulated domains.** Finance, healthcare, payments. Audits and incident reviews ask the same question: *where does this value flow and how was it validated?* The compiler answers in one Find Usages.
- **Financial identifiers.** ISIN, CUSIP, LEI, FIGI, IBAN, BIC. Fixed formats, checksum rules, jurisdictional constraints. Each one is a textbook refined type — cheap to write, expensive to get wrong, ubiquitous in code paths.
- **Network boundaries.** `HostName`, `Port`, `IpAddress`, `CidrBlock` — all refined from raw `String` or `int`. SSRF and host-spoofing classes of bugs disappear when the type rejects link-local and private ranges at construction.
- **Geo and locale.** `CountryCode`, `Currency`, `LanguageTag`, `TimeZone`. Each has an authoritative list (ISO 3166, ISO 4217, BCP 47, IANA tz). A refined type pins the value to that list at the boundary — no more "is `gb` the same as `GB`?" downstream.
- **ML storage.** Feature vectors, embeddings, probability scores. `Float16` (IEEE 754 binary16) halves the memory footprint of a float array — a flat `Float16[]` of embeddings is dense, cache-friendly, and typed. The spot Valhalla pays off twice: correctness at the boundary, cache locality in the loop.

## Where this lands

Part 1's claim was that the type system is a security tool you already own. The reply — *fine, but I can't afford it in the hot path* — was honest and is now obsolete.

Value classes turn the cost argument off. A wrapper is a primitive with a constructor. The constructor runs once at the boundary, the JIT inlines the access, the array is flat, no per-element heap allocations for the GC to track. The compile-time guarantee is unchanged.

The type system is now affordable in the inner loop too.

---

> Code: [github.com/dfa1/refined-type](https://github.com/dfa1/refined-type) — Java 27 EA, MIT.

[^valhalla-jep]: [Project Valhalla](https://openjdk.org/projects/valhalla/) — the umbrella effort. Two preview JEPs ship the surface used here: *Value Classes and Objects* (syntax and semantics of `value class`) and *Null-Restricted and Nullable Types* (the `Port!` form referenced below). JEP numbers have shifted across drafts; the project page links to the current ones.

[^capital-one]: [Capital One hack highlights SSRF concerns for AWS](https://www.techtarget.com/searchsecurity/news/252467901/Capital-One-hack-highlights-SSRF-concerns-for-AWS) (TechTarget, 2019). The attacker exploited a misconfigured WAF to reach `169.254.169.254` — the EC2 instance-metadata endpoint — and exfiltrate IAM credentials. A `HostName` type that rejects link-local addresses by construction would have closed that path.
