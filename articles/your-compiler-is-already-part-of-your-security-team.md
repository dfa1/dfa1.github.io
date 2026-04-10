# Your Compiler is already part of your Security Team

*4 January 2021*

*It's 3am. Your phone is ringing.
A few hours earlier you deployed a hotfix — straightforward change, reviewed in a hurry,
went out clean. Now some important data is publishing under the wrong market. Instruments from
exchange A showing up under exchange B. Clients are seeing it. Someone is already asking
if it's a breach.
It isn't. It's worse, in a way: the hotfix swapped two arguments. The method took a
`marketId` and an `instrumentId`, both ints, and the caller got them the wrong way
round. The compiler saw two ints, both positive, both in range. Code compiled, tests
passed, CI was green.*

```java
void publishClosePrice(int marketId, int instrumentId, double closePrice) {
  ...
}
```

*Nothing stops a caller from swapping those arguments. Nothing in the type system distinguishes
one int from the other. Your safety net was discipline only... but that scales poorly.*

---

## Make Illegal State Unrepresentable

This is the starting point: encode in your type what is the domain of the value.
`MarketId` could be just a 3-digit non-negative number (representable with `short`),
an `InstrumentId` could be representable with 32 bits int with non-negative constraint.

Design types such that invalid or dangerous values **cannot be constructed**.

The shift in framing matters. Validation is a check you add to code that already runs.
It can be forgotten, skipped under a time-pressed refactor, or called in the wrong order.
A type whose constructor rejects bad input cannot be bypassed — it is structural, not
procedural. You stop asking *"did we remember to validate this"?* and start asking *"can this
type even hold that value?* — a question the compiler answers for you at every call site.

---

## Validate at the boundary — once

Every value entering your system from the outside world is **untrusted**.
Wrap it into a domain primitive at the boundary *once*. Then, make sure the value cannot change
after construction. A domain primitive is not just a validation wrapper. It is a value — and values do not
change. Mutability opens a gap: an object passes validation on construction, then a setter
quietly puts it into an invalid or dangerous state.

In Java this means `final` fields on immutable types, no setters, and a `final` class. That last point matters:
a subclass can override `toString`, `equals`, or `hashCode` in ways you did not anticipate.
In modern Java a `record` could be used to lower the effort writing systematically domain primitives.

But validation itself has a pitfall people miss if a regex is used: **regex on unbounded input is a
vulnerability**. A crafted input of a few thousand characters can cause a backtracking regex to run for
seconds or minutes — a Denial of Service with a single HTTP request (ReDoS). The fix is
simple: **always check length before applying regex**. It's one of those rules that once
you see it you can't unsee it.

This is an example domain primitive in Java for [ISIN](https://en.wikipedia.org/wiki/International_Securities_Identification_Number)
one of the most popular identifiers for financial instruments (same logic applies cleanly to other domains):

```java
public final class Isin {

    // 2 letters for country code
    // 9 digits for national number
    // 1 checksum digit
    private static final int MAX_LENGTH = 12;
    private static final Pattern FORMAT = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}[0-9]$");

    private final String value;

    public Isin(String value) {
        if (value == null || value.length() != MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid ISIN");
        }
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid ISIN");
        }
        this.value = value;
    }

    // the value as string is always valid
    public String value() { return value; }

    // get Country domain primitive using the first 2 characters of the isin
    public Country country() {
       return Country.from(this);
    }

    // ...
    // add toString(), compareTo(), equals(), hashCode()
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

This is **[parse, don't validate](https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/)**. You don't pass a string and check it repeatedly; you
transform it into a type that is its own proof of validity.

---

## Make invalid combinations impossible

Three booleans on a method:

```java
void registerInstrument(String isin, boolean isActive, boolean isEquity, boolean isIndex) {
...
}
```

8 possible combinations. How many are actually valid for your domain? Probably not all
of them — and "active equity" versus "inactive bond" are very different things. A silent
flag swap here is a business-logic flaw waiting to be exploited.

Java gives you several tools here.

**Enums** for simple cases:

```java
public enum InstrumentStatus { ACTIVE, INACTIVE, IN_DISSOLUTION }
public enum InstrumentType   { BOND, EQUITY, INDEX, FIXED_INCOME }

void registerInstrument(Isin isin, InstrumentType type, InstrumentStatus status) {
  ...
}
```

**Records + sealed interfaces** for richer cases where variants carry different data.
Consider data quality in a financial feed — real-time, delayed by some minutes, or end of
day. These are not just labels; they carry different information.

```java
public sealed interface DataQuality
        permits DataQuality.RealTime, DataQuality.Delayed, DataQuality.EndOfDay {

    record RealTime()            implements DataQuality {}
    record Delayed(Duration lag) implements DataQuality {}
    record EndOfDay()            implements DataQuality {}
}
```

A `Delayed` value without its `lag` makes no sense. The type system enforces that the data travels with its
context — always.

Every `switch` on `DataQuality` is exhaustive — the compiler tells you when you've missed
a case. You cannot accidentally treat a 15-minute delayed price as real-time. The
dangerous path is the hard one to write.

---

## Invalid operations caught at compile time

In the financial domain, a codebase that hasn't embraced domain primitives
typically looks like this:

```java
int marketId = Integer.parseInt(request.getParam("marketId"));
int instrumentId = Integer.parseInt(request.getParam("instrumentId"));
double closePrice = fetchOpenPrice(instrumentId, marketId);

publishClosePrice(marketId, instrumentId, closePrice);
```

There is a subtler point too. An `int` is a number — the compiler will happily let you
write `instrumentId + 1` or `marketId * instrumentId`. These expressions compile cleanly.
They are also semantically meaningless: instrument identifiers are not quantities; you cannot add, subtract,
or scale them. A domain primitive exposes no arithmetic operators. The meaningless
operations are not just discouraged — they do not exist:

```java
MarketId marketId = new MarketId(request.getParam("marketId"));
InstrumentId instrumentId = new InstrumentId(request.getParam("instrumentId"));
Price closePrice = fetchOpenPrice(instrumentId, marketId);

publish(marketId + 1, instrumentId - 1, closePrice);
        ^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^ <-- compile time errors
```

---

## Security is built-in

So the first line of defense is the domain primitives: they prevent bad inputs to leak deep
into the business logic and they help to better document how the data is flowing.

Another underused practice: treat the CI build as a second line of defense by writing
`@ParameterizedTest` suites for every domain primitive and feed them adversarial inputs —
ask your security officer for their favorites. A crafted ReDoS string, a Unicode homoglyph,
a negative value disguised as a large long. If your type rejects them all at construction
time, you've shifted those checks left to where they cost nothing to run and can never be
skipped by a tired reviewer.

But sensitive values require one extra step: **control what the type exposes**. An `ApiToken`
that cheerfully prints itself is a secret waiting to leak into a log file or exception stacktrace.

```java
public final class ApiToken {
    private static final int LENGTH = 128;
    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_\\-]+$");

    private final String value;

    public ApiToken(String value) {
        if (value == null || value.length() != LENGTH) {
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

    private void readObject(ObjectInputStream in) throws IOException {
        throw new NotSerializableException("ApiToken");
    }
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
- `readObject()` throws unconditionally. Java deserialization bypasses constructors —
  an attacker with control over a serialized stream could reconstruct an `ApiToken`
  without passing any validation. This one method closes that path entirely.

Even so, `value()` can be called multiple times — nothing stops the secret from being read repeatedly after authentication. For credentials that should be consumed exactly once, the **read-once** pattern from **Secure by Design** closes that gap:

```java
public final class Password {

    private char[] value;

    public Password(char[] value) {
        this.value = Arrays.copyOf(value, value.length);
    }

    public char[] readOnce() {
        if (value == null) throw new IllegalStateException("Password already consumed");
        char[] result = value;
        value = null; // consumed
        return result;
    }

    @Override
    public String toString() {
        return "Password[REDACTED]";
    }
}
```

After the password is used to authenticate the user, it cannot be used again... even accidentally.

This isn't just good design. It directly contributes to eliminating whole vulnerability categories:
- the attacker cannot use `Isin` as a way to exploit a SQL injection as it cannot hold something like `'; DROP TABLE users; --`;
- the auditor won't find `ApiToken` leaks in the logs: `toString()` returns `"ApiToken[REDACTED]"`, so the secret cannot reach a log file or exception message;
- in general, it will be much harder for the attacker who controls an input to use it.

Security stops being a checklist applied at the end. It becomes a **property of the
design**, the security is **built-in**.

---

## Domain primitives make codebases explorable

This is a benefit that rarely gets mentioned alongside security, but it's just as real.

Press **Ctrl+Click** on `Integer` or `String` in your IDE. You will see thousands of usages across the
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

## You already depend on domain primitives

Is this new ideas? Not at all.

The JDK has shipped domain primitives for decades. `Path` wraps a raw file-system string
and validates it — you don't pass `String` to `Files.readAllBytes`. `URI` parses and
validates on construction; a malformed URI throws at the boundary, not deep inside your
HTTP client. `UUID` enforces its format. `Instant`, `Duration`, and `Period` replaced the
`long millisSinceEpoch` antipattern with types that carry their own semantics.

The people who built the platform you run on decided a raw `String` or `long` was not good
enough for these values. The same logic applies to your domain.

In [Hosh](https://github.com/dfa1/hosh), an experimental JVM shell I'm writing, `ExitStatus`
wraps an `int` exit code:

```java
public final class ExitStatus {
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
ceremony and even zero runtime cost.

**Haskell** has `newtype` — a zero-overhead wrapper erased at runtime that gives you a
fully distinct type:

```haskell
newtype InstrumentId = InstrumentId Int
newtype MarketId     = MarketId     Int

publish :: MarketId -> InstrumentId -> Price -> IO ()
```

Swapping the arguments is a compile error. There is no runtime cost. This is the ideal
the other languages approximate.

The type distinction — which prevents argument swapping — comes for free. With a numeric
inner type, validation is typically a range check in a smart constructor returning
`Maybe InstrumentId`.

**Rust** uses the same *newtype pattern* via tuple structs:

```rust
struct InstrumentId(i32);
struct MarketId(i32);

fn publish(market: MarketId, instrument: InstrumentId, price: Price) { ... }
```

Again, zero runtime overhead. Rust's ownership model adds a further benefit: you can
control whether the inner value is ever exposed at all by keeping the field private and
exposing only a validated constructor.

**C++** requires a hand-written wrapper + `explicit` constructors, but even a simple
struct does the job:

```cpp
struct InstrumentId { int value; };
struct MarketId     { int value; };

void publish(MarketId market, InstrumentId instrument, Price price);
```

Argument swapping is a compile error. For stronger guarantees — preventing implicit
construction from raw ints, enforcing validation — libraries like
[`type_safe`](https://github.com/foonathan/type_safe) or
[`strong_type`](https://github.com/rollbear/strong_type) provide newtype semantics
without additional boilerplate. The underlying principle is the same: one named type per
concept, incompatible by default, conversions only where you explicitly write them.

The vocabulary differs; the insight does not. If your language has a strong type system,
it can enforce your domain boundaries.

---

## Closing

**Your compiler is already part of your security team. It's been waiting for you to give it the
right types to work with.**

Of course, this is not enough alone. Use TLS with current cipher suites, proper
network segmentation, secrets management that keeps credentials out of source control,
dependency scanning, runtime monitoring — all of it still matters. Defense in depth means
every layer does its job. Why not start from the most basic pieces of the business logic?

---

If you want to go deeper, read [**Secure by Design**](https://www.manning.com/books/secure-by-design) (Bergh Johnsson, Deogun, Sawano —
Manning, 2019). It is one of the few books that treats security as a design discipline
rather than a bolt-on concern. Highly recommended.

The phrase "make illegal states unrepresentable" was coined by **Yaron Minsky** in the
context of OCaml. His original post — [*Effective ML*](https://blog.janestreet.com/effective-ml/)
— is worth reading even if you never write a line of OCaml. The insight transfers cleanly
to any language with a strong type system.

The phrase "parse, don't validate" comes from **Alexis King**'s 2019 post
[*Parse, Don't Validate*](https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/).
It is the clearest statement of the boundary-parsing idea I know of.

---

**April 2026**: when you add AI to this picture, the conclusion is not “AI replaces this”
— it is the opposite: AI makes these design principles more necessary, not less.

Why? Because AI is procedural whereas domain primitives are structural:
AI tools generate code procedurally: they produce sequences of steps, validations, and checks.
But domain primitives are structural: they encode the rules of your domain in the type system
itself. When humans and AI both write code, the compiler becomes the only actor that sees
everything and enforces the invariants consistently.

And, like already discussed
in [Coding With Claude Code](https://dfa1.github.io/articles/coding-with-claude-code), AI
thrives in codebases that are explorable: a codebase full of raw `String`, `int`, `long`
is ambiguous to humans and to AI. A system built from `InstrumentId`, `MarketId`, `ApiToken`, and `DataQuality` is explicit, navigable, and safe by construction.
In other words: the more AI you use, the more your compiler matters to quickly iterat
the software. Domain primitives are not just a design technique — they are the foundation
that lets both humans and AI write secure, correct software at scale.


