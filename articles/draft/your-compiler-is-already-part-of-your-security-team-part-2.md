# Your Compiler Is Already Part of Your Security Team — Part 2: And Now It's Free

*Draft, May 2026*

*Six months after [part 1](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team) the most frequent reply was always the same: "Sure, fine for boundary code. But you can't put a wrapper around every `int` in a hot loop. The allocations would kill us."*

*The objection was right. Until now.*

---

## The objection

Part 1 made one argument: encode constraints in types, the compiler enforces them forever, you stop writing the same boundary checks at every callsite. The examples were `Isin`, `Email`, `ApiToken`, `DataQuality`. The thesis was that the type system is the cheapest, most underused security tool you already own.

Two pushbacks landed in my inbox.

The first was easy. *"My team won't write all those wrapper classes."* That is a culture problem, not a type-system problem, and it is solved by writing them once and putting them in a shared module.

The second was harder. *"A `class Port { final int v; }` is one heap object — twelve bytes of header on HotSpot, padded to sixteen, plus a pointer in whatever holds it. An array of a thousand `Port` is a thousand objects scattered across the heap, each one a cache miss waiting to happen. You can refine your boundaries; you cannot refine your hot path."*

Valhalla closes that gap.

## Enter Project Valhalla — value classes

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

1. **No identity.** `==` is structural, `null` is not assignable, no synchronization, no `System.identityHashCode`.
2. **Flat layout.** The JVM is allowed to inline the fields wherever a `Port` lives — into a register, into another object, into an array slot. No header, no pointer chasing.

The constructor still runs. The validation still happens. The compile-time guarantee — *anywhere I see a `Port`, the value is in `[0, 65535]`* — still holds.

What disappears is the runtime cost.

## The numbers

I built [`refined-type`](https://github.com/dfa1/refined-type), a small library that lifts `Isin` from part 1 and adds `Email`, `HostName`, `Port`, `Slug`, the unsigned integers, `Size`, `Percentage`, `Probability`. Everything is a `value class`. JOL says:

```
UnsignedInt[100]:   416 bytes  (flat inline storage)
   Integer[100]:  2816 bytes  (array shell + 100 heap objects)
```

**6.8× smaller**, scanned in cache-line strides instead of jumping pointer-to-pointer across the heap. JMH on the array-traversal benchmark shows the loop matches a bare `int[]`. The "wrapper tax" is gone.

This is not a benchmark trick. It is the layout the JVM chooses when it has permission. Identity costs space; saying *I don't need identity* is the permission slip.

## What this changes for security

In part 1 the rule was: refine your types at the boundary, then quit before you hit the inner loop. With value classes, the inner loop is the boundary. Three concrete consequences.

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

### 3. Slug, HostName, BoundedString — the strings that bite

Strings are where part 1 lived and where most of the bugs hide. ReDoS, header injection, path traversal, SSRF — all rooted in `String` being whatever the caller felt like. The library's `Slug` and `HostName` enforce the format up front:

- `Slug`: `^[a-z0-9](-?[a-z0-9])*$`, length ≤ 64, length-checked **before** the regex. Since `maxLen` is a fixed constant, validation runs in constant time regardless of input size.
- `HostName`: RFC 1123, plus an SSRF guard that rejects `localhost`, RFC 1918 ranges, and link-local `169.254.x.x` — the AWS instance-metadata endpoint, the origin of the 2019 Capital One breach[^capital-one].

The value-class part is what lets you keep these on the inside of the system, not just at the parser. A `Map<HostName, RateLimit>` becomes practical to use everywhere a `String` host used to flow, because the wrapper costs nothing.

## What value classes don't fix

Three caveats worth saying out loud:

- **Preview.** Java 27 EA. Production deployment requires `--enable-preview`. The shape is stable; the spec is not yet final.
- **Equality.** `value class` `equals` is structural by default — fine for `Port`, wrong for `ApiToken`. For tokens you still override `equals` to use `MessageDigest.isEqual` for constant-time comparison. The compiler doesn't know your equality is timing-sensitive; you do.
- **Null-restricted types are opt-in.** A plain `value class` is still nullable as a reference type. Null is excluded only when you mark the type null-restricted (`Port!`). That is mostly good news (no NPE on the restricted path) but it changes patterns: a missing `Port` is `Optional<Port>`, not `Port port = null`. Migration friction, not a deal-breaker.

## Where this lands

Part 1's claim was that the type system is a security tool you already own. The reply — *fine, but I can't afford it in the hot path* — was honest and is now obsolete.

Value classes turn the cost argument off. A wrapper is a primitive with a constructor. The constructor runs once at the boundary, the JIT inlines the access, the array is flat, no per-element heap allocations for the GC to track. The compile-time guarantee is unchanged.

The type system is now affordable in the inner loop too.

---

> Code: [github.com/dfa1/refined-type](https://github.com/dfa1/refined-type) — Java 27 EA, MIT.

[^valhalla-jep]: [JEP 401: Value Classes and Objects (Preview)](https://openjdk.org/jeps/401). Tracks the syntax and semantics described here. The companion [JEP 402: Null-Restricted and Nullable Types](https://openjdk.org/jeps/402) covers the `Port!` form referenced below.

[^capital-one]: [Capital One Cyber Incident](https://www.capitalone.com/about/newsroom/cyber-incident/). The attacker exploited a misconfigured WAF to reach `169.254.169.254` — the EC2 instance-metadata endpoint — and exfiltrate IAM credentials. A `HostName` type that rejects link-local addresses by construction would have closed that path.
