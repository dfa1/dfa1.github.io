---
title: "Your Compiler Is Already on the Security Team"
date: 2026-04-04
tags: [secure-by-design, java, domain-primitives, security]
---

It's 3am. Your phone is ringing.
A few hours earlier you deployed a hotfix — straightforward change, reviewed in a hurry,
went out clean. Now market data is publishing under the wrong market. Instruments from
exchange A showing up under exchange B. Clients are seeing it. Someone is already asking
if it's a breach.
It isn't. It's worse, in a way: the hotfix swapped two arguments. The method took a
`marketId` and an `instrumentId`, both ints, and the caller got them the wrong way
round. The compiler saw two ints. Both positive. Both in range. Code compiled, tests
passed, CI was green.

```java
void publish(int marketId, int instrumentId, double price) { ... }
```

Nothing stops a caller from swapping those arguments. Nothing in the type system distinguishes
one int from the other. Your safety net was discipline — and discipline frays at 5pm on
a Friday when there's a production incident and a hotfix that needs to go out.

This is **primitive obsession**, and it is a vulnerability class, not just a code smell.
What follows are the principles I discuss with every team I work with — and that usually do the persuading on their own.

---

## The Principle: Make Illegal State Unrepresentable

Design types such that invalid or dangerous values **cannot be constructed**. Not "validate
them on the way in." Cannot exist at all — construction throws.

The shift in framing matters. Validation is a check you add to code that already runs.
It can be forgotten, skipped under a time-pressed refactor, or called in the wrong order.
A type whose constructor rejects bad input cannot be bypassed — it is structural, not
procedural. You stop asking "did we remember to validate this?" and start asking "can this
type even hold that value?" — a question the compiler answers for you at every call site.

---

## Validate at the boundary — once, correctly, in order

Every value entering your system from the outside world is untrusted. Wrap it into a
domain primitive at the boundary. Once. Never pass raw strings into your domain.

But validation itself has a pitfall people miss: **regex on unbounded input is a
vulnerability**.

A crafted input of a few thousand characters can cause a backtracking regex to run for
seconds or minutes — a Denial of Service with a single HTTP request (ReDoS). The fix is
simple: **always check length before applying regex**. It's one of those rules that once
you see it you can't unsee it.

```java
public final class Isin {
    private static final int MAX_LENGTH = 12;
    private static final Pattern FORMAT = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");

    private final String value;

    public Isin(String value) {
        if (value == null || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid ISIN");
        }
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid ISIN");
        }
        this.value = value;
    }

    public String value() { return value; }
}
```

Length check first — cheap, O(1), kills ReDoS before it starts. Regex second — now safe
because the input is bounded.

The call site inside your domain never sees a raw string:

```java
// At the HTTP boundary — the ONLY place this conversion happens
Isin isin = new Isin(request.getParam("isin"));

// Everywhere inside the domain, only Isin travels
instrumentService.lookup(isin);
```

This is **[parse, don't validate](https://lexi-lambda.github.io/blog/2019/11/05/parse-dont-validate/)**. You don't pass a string and check it repeatedly; you
transform it into a type that is its own proof of validity.

---

## For small state spaces, use types that make invalid combinations impossible

Two booleans on a method:

```java
void registerInstrument(String isin, boolean isActive, boolean isEquity) { ... }
```

Four possible combinations. How many are actually valid for your domain? Probably not all
of them — and "active equity" versus "inactive bond" are very different things. A silent
flag swap here is a business-logic flaw waiting to be exploited.

Modern Java gives you several tools here.

**Enums** for simple cases:

```java
public enum InstrumentStatus { ACTIVE, INACTIVE }
public enum InstrumentType   { BOND, EQUITY, INDEX, FIXED_INCOME }

void registerInstrument(Isin isin, InstrumentType type, InstrumentStatus status) { ... }
```

**Records + sealed interfaces** for richer cases where variants carry different data.
Consider data quality in a financial feed — real-time, delayed by some minutes, or end of
day. These are not just labels; they carry different information:

```java
public sealed interface DataQuality
        permits DataQuality.RealTime, DataQuality.Delayed, DataQuality.EndOfDay {

    record RealTime()                        implements DataQuality {}
    record Delayed(Duration lag)             implements DataQuality {}
    record EndOfDay() implements DataQuality {}
}
```

A `Delayed` value without its `lag` makes no sense. The type system enforces that the data travels with its
context — always.

Every `switch` on `DataQuality` is exhaustive — the compiler tells you when you've missed
a case. You cannot accidentally treat a 15-minute delayed price as real-time. The
dangerous path is the hard one to write.

---

## Name your domain, don't describe it with strings

In financial market infrastructure, a codebase that hasn't embraced domain primitives
typically looks like this:

```java
void publish(int marketId, int instrumentId, String isin, String lei, double price) { ... }
```

Five strings. Any of them can be passed in any position. The compiler cannot help you.
The reviewer cannot easily help you — every usage requires reading surrounding context to
understand what is flowing where.

With domain primitives:

```java
void publish(MarketId market, InstrumentId instrument, Isin isin, Lei lei, Price price) { ... }
```

Each type encodes its own validation rules. `Lei` knows it must match a specific 20-character
format. `Isin` knows its format. `MarketId` knows its format. `Price` knows it cannot be
negative. None of this knowledge leaks out or gets duplicated.

---

## Make domain primitives immutable — and control what they reveal

A domain primitive is not just a validation wrapper. It is a value — and values do not
change. Mutability opens a gap: an object passes validation on construction, then a setter
quietly puts it into an invalid or dangerous state. Immutability closes that gap for good.

In Java this means `final` fields, no setters, and a `final` class. That last point matters:
a subclass can override `toString`, `equals`, or `hashCode` in ways you did not anticipate.
Seal the type so the invariants you wrote are the invariants that hold.

Sensitive values require one extra step: **control what the type exposes**. An `ApiToken`
that cheerfully prints itself is a secret waiting to leak into a log file.

```java
public final class ApiToken {
    private static final int MAX_LENGTH = 128;
    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_\\-]{16,128}$");

    private final String value;

    public ApiToken(String value) {
        if (value == null || value.length() < 16 || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid API token");
        }
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid API token");
        }
        this.value = value;
    }

    /** Use only when passing the token to the remote endpoint; nowhere else. */
    public String value() { return value; }

    @Override public String toString() { return "ApiToken[REDACTED]"; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApiToken other)) return false;
        return MessageDigest.isEqual(
            value.getBytes(StandardCharsets.UTF_8),
            other.value.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Override public int hashCode() { return 0; } // intentionally constant — see prose below
}
```

A few deliberate choices here:

- `toString()` returns `"ApiToken[REDACTED]"`. If this object reaches a logger, a stack
  trace, or an exception message, the secret does not travel with it.
- `equals()` uses `MessageDigest.isEqual` — a constant-time comparison — instead of
  `String.equals`. A timing-sensitive caller cannot use equality checks to oracle the
  token value one character at a time.
- `hashCode()` returns a constant. Returning a hash of the value would leak information
  about the secret through HashMap bucket distribution. A constant means the type cannot
  be used as a map key efficiently — a small, intentional friction that discourages using
  tokens as lookup keys in the first place.

The compiler and the type system cannot read your mind. But they will enforce whatever
invariants you encode. Encode the right ones.

---

## You already depend on domain primitives

The JDK has shipped domain primitives for decades. `Path` wraps a raw file-system string
and validates it — you don't pass `String` to `Files.readAllBytes`. `URI` parses and
validates on construction; a malformed URI throws at the boundary, not deep inside your
HTTP client. `UUID` enforces its format. `Instant`, `Duration`, and `Period` replaced the
`long millisSinceEpoch` antipattern with types that carry their own semantics.

The people who built the platform you run on decided a raw `String` or `long` was not good
enough for these values. The same logic applies to your domain.

Smaller libraries make the same choice. In [Hosh](https://github.com/dfa1/hosh), a JVM
shell, `ExitStatus` wraps an `int` exit code:

```java
public class ExitStatus {
    private final int value;

    private ExitStatus(int value) { this.value = value; }

    public static ExitStatus success() { return new ExitStatus(0); }
    public static ExitStatus error()   { return new ExitStatus(1); }
    public static ExitStatus of(int value) { return new ExitStatus(value); }

    public static Optional<ExitStatus> parse(String str) { ... }

    public boolean isSuccess() { return value == 0; }
}
```

`ExitStatus.success()` is unambiguous. `0` is not. There is no risk of a caller passing
`1` when they meant `0`, or forgetting what the magic number means. The factory methods
name the common values; `parse` returns `Optional<ExitStatus>` rather than throwing,
because values arriving from user input are not guaranteed to be valid. The private
constructor means nobody constructs an `ExitStatus` in an unanticipated way.

`VariableName` in the same codebase goes further: it enforces a regex pattern *and* a
maximum length of 256 characters — the same ReDoS defence discussed above. The pattern
appears in every well-designed primitive because the problems it solves are universal.

---

## The same idea in other languages

Java wraps a class around the value. Other languages reach the same place with less
ceremony, which means there is even less excuse not to do it.

**Haskell** has `newtype` — a zero-overhead wrapper erased at runtime that gives you a
fully distinct type:

```haskell
newtype InstrumentId = InstrumentId Text
newtype MarketId     = MarketId     Text

publish :: MarketId -> InstrumentId -> Price -> IO ()
```

Swapping the arguments is a compile error. There is no runtime cost. This is the ideal
the other languages approximate.

The type distinction — which prevents argument swapping — comes for free. Validation
still requires a smart constructor: a function that checks the value before wrapping it,
returning `Maybe InstrumentId` or throwing on invalid input.

**Rust** uses the same *newtype pattern* via tuple structs:

```rust
struct InstrumentId(String);
struct MarketId(String);

fn publish(market: MarketId, instrument: InstrumentId, price: Price) { ... }
```

Again, zero runtime overhead. Rust's ownership model adds a further benefit: you can
control whether the inner value is ever exposed at all by keeping the field private and
exposing only a validated constructor.

**C++** requires a hand-written wrapper, but even a simple struct does the job:

```cpp
struct InstrumentId { std::string value; };
struct MarketId     { std::string value; };

void publish(MarketId market, InstrumentId instrument, Price price);
```

Argument swapping is a compile error. For stronger guarantees — preventing implicit
construction from raw strings, enforcing validation — libraries like
[`type_safe`](https://github.com/foonathan/type_safe) or
[`strong_type`](https://github.com/rollbear/strong_type) provide newtype semantics
without additional boilerplate. The underlying principle is the same: one named type per
concept, incompatible by default, conversions only where you explicitly write them.

The vocabulary differs; the insight does not. If your language has a type system, it can
enforce your domain boundaries.

---

## The security angle

This isn't just good design. It directly eliminates whole vulnerability categories:

- **ReDoS**: length-bounded inputs defang backtracking regex
- **Injection**: a `SqlIdentifier` type that only accepts safe identifiers cannot carry `'; DROP TABLE users; --`
- **Privilege escalation**: `AdminToken` and `UserToken` as incompatible types prevent accidental privilege bypass
- **Business logic flaws**: `NonNegativeMoney` eliminates negative transfer exploits; sealed `DataQuality` makes it impossible to treat delayed data as real-time

Security stops being a checklist applied at the end. It becomes a **property of the
design**.

One underused practice: treat the build as a second line of defense. Write
`@ParameterizedTest` suites for every domain primitive and feed them adversarial inputs —
ask your security officer for their favorites. A crafted ReDoS string, a Unicode homoglyph,
a negative value disguised as a large long. If your type rejects them all at construction
time, you've shifted those checks left to where they cost nothing to run and can never be
skipped by a tired reviewer.

---

## Domain primitives make codebases explorable

This is a benefit that rarely gets mentioned alongside security, but it's just as real.

Press **Ctrl+Click** on `String` in your IDE. You will see thousands of usages across the
entire codebase — every method parameter, every field, every local variable. It tells you
nothing.

Press **Ctrl+Click** on `InstrumentId`. You see exactly the methods that accept it, the
services that produce it, the repositories that store it. You have, in one gesture, a
precise map of where instrument identity flows through your system.

For a new joiner, this is the difference between orientation taking days and orientation
taking hours. For a security review, this is the difference between tracing a data flow
manually and having the type system draw it for you. **Domain primitives are
documentation that the compiler keeps accurate.**

---

## The objections

*"This is expensive."*

Compared to what? Compared to a 3am incident, a data breach disclosure, or scattering
validation logic across every layer of the stack and hoping every caller remembered to call
it? The cost of a domain primitive is one constructor and one test suite, written once.

*"This is verbose."*

Yes. About 10–20 extra lines per domain primitive. Weighed against: one injection bug,
one ReDoS incident, one privilege bypass, one new joiner spending a week
reverse-engineering data flows. The verbosity is the point — it is explicit, auditable,
and self-documenting. The compiler reviews it for free, forever.

---

## Closing

The most resilient systems make the dangerous path the hard one to write and the safe
path the path of least resistance. Domain primitives, sealed hierarchies, and
disciplined boundary validation make the type system your first line of defense — one
that never sleeps, never forgets, and never skips a step under deadline pressure.

None of this replaces the rest of the stack. TLS with current cipher suites, proper
network segmentation, secrets management that keeps credentials out of source control,
dependency scanning, runtime monitoring — all of it still matters. Defense in depth means
every layer does its job. What type-driven design does is harden the layer you own
completely: your own code. A secret that never reaches a log file does not need the log
pipeline to be secured. An injection payload that cannot be constructed does not need the
WAF to catch it. The fewer things that need to go right downstream, the more resilient
your whole system becomes.

Your compiler is already on the security team. It's been waiting for you to give it the
right types to work with.

---
The phrase "make illegal states unrepresentable" was coined by **Yaron Minsky** in the
context of OCaml. His original post — [*Effective ML*](https://blog.janestreet.com/effective-ml/)
— is worth reading even if you never write a line of OCaml. The insight transfers cleanly
to any language with a decent type system.

The phrase "parse, don't validate" comes from **Alexis King**'s 2019 post
[*Parse, Don't Validate*](https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/).
It is the clearest statement of the boundary-parsing idea I know of.

If you want to go deeper, read **"Secure by Design"** (Bergh Johnsson, Deogun, Sawano —
Manning, 2019). It is one of the few books that treats security as a design discipline
rather than a bolt-on concern. Highly recommended.
