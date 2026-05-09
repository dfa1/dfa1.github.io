# The Cost of Implicitness

*9 May 2026*

*You don't pay for implicit assumptions when you write them. You pay when a new joiner
interprets them differently, when two teams deploy on different schedules and discover the
shared understanding was never shared, when a hotfix swaps two `int` arguments that meant
two different things and the compiler had no opinion. The cost is deferred, invisible, and
always larger than expected.*

## Three Posts

This is a self-reflection post: each of the three posts
below explores the same pattern at a different layer.

[Write Down the Why](https://dfa1.github.io/articles/write-down-the-why) is about the
process layer. A team without written rationale produces engineers who follow rules they
don't understand — and override them the first time the rules are inconvenient. Writing
down the *why* behind a testing philosophy or a branching model is not documentation; it
is the verifiable contract between the team and its future members.

[Your Compiler Is Already Part of Your Security
Team](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team)
is about the code layer. Consider a method that accepts `(long instrumentId, long marketId)`:
transpose the arguments and the compiler says nothing. Replace both with `InstrumentId`
and `MarketId` and the wrong-order call is a build failure. A domain primitive encodes a
constraint that a raw `int` or `String` cannot carry — the invalid operation does not
compile, the secret does not print.

[Make the Implicit
Explicit](https://dfa1.github.io/articles/make-the-implicit-explicit) is about the
boundary layer. Two services sharing an informal agreement about a DTO are one field
rename away from a coordinated rollback. Versioned endpoints, isolated DTOs, and
point-in-time contracts move the agreement out of both teams' heads and into something
the CI pipeline can verify.

## The Chain

The underlying idea is to encode assumptions in a representation the build can check — applied at
process, code, and boundary. The layer changes; the principle does not. The move is not
new — it runs through *The Pragmatic Programmer*[^tpp] and *Secure by Design*[^sbd] — but it gains
force when applied consistently across all three layers at once.

What makes this tractable is not velocity, but reversibility: you can take the next step
because the previous one is verified. The chain connects every artifact in the system.

Tests point to requirements — a failing test means a violated requirement, not an implementation detail.
Tests point to the code they exercise — coverage is not a vanity metric; it is a
map of what is and isn't verified. Code encodes domain invariants in types — not as
comments, not as runtime checks buried in service logic, but as constraints the compiler
enforces at every call site.

The application architecture follows rules that a tool like
[ArchUnit](https://www.archunit.org) verifies on every build — if the dependency graph
matters, it belongs in CI, not in a diagram that rots.

Every link in this chain is a pointer. A broken pointer is not a documentation problem —
it is a liability. A test that no longer covers its requirement is a false green. A type
that stopped enforcing its invariant is a hole in the boundary. An architecture rule that
lives in a document but not in the pipeline is a rule that will be violated the first time
someone is in a hurry.

The goal is a system where a new reader, either human or AI, can enter at any point — a type, a test, a
service boundary — and trace outward without asking anyone. Not because the documentation
is thorough, but because the structure of the system makes the connections traversable.
This is what *explorable* means in practice: not an IDE feature, not a style preference —
a property of the design that holds whether the team is moving fast or not.

This is not new. TDD draws the requirements-to-tests link explicitly.[^tdd] DDD formalizes the practice of
encoding domain invariants in types rather than in comments or runtime logic.[^ddd] Both
disciplines converge on the same principle.

## 2026: The Cost Doubles

What was always true is now structurally more expensive to ignore. AI agents write
production code routinely, and they operate under ambiguity the same way junior engineers
do: fill the gap with a plausible default and move on. The difference is speed, and the
compounding of errors at speed.

The claim is not that agents fail on messy codebases — they often handle them impressively well.
The narrower claim is this: in an explicitly typed system, the variance of what an agent
can generate is lower. `MarketId` and `InstrumentId` cannot be silently swapped. `ApiToken`
cannot be logged. An ArchUnit rule cannot be quietly bypassed. The type system doesn't
make the agent smarter — it makes the dangerous path the hard one to write, regardless of
who is writing it.

Implicitness was always expensive. Now the cost scales.

---

[^tdd]: Kent Beck, *Test-Driven Development: By Example* (2002). The discipline of writing a failing test before the implementation is precisely what keeps the requirement-to-test pointer intact.

[^ddd]: Eric Evans, *Domain-Driven Design* (2003). Value objects and aggregates are the canonical form of the "encode the invariant in a type" move.

[^tpp]: Hunt & Thomas, *The Pragmatic Programmer* (1999), "Design by Contract." DbC formalizes the same move: replace implicit assumptions with explicit, checkable contracts at every interface.

[^sbd]: Johnsson, Deogun & Sawano, *Secure by Design* (2019). The book frames security as a design property — domain primitives, value objects, and type constraints that make insecure states unrepresentable rather than detectable.



