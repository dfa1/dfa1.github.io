# The Cost of Implicitness

*2026-05-01*

*You don't pay for implicit assumptions when you write them. You pay when a new joiner
interprets them differently, when two teams deploy on different schedules and discover the
shared understanding was never shared, when a hotfix swaps two `int` arguments that meant
two different things and the compiler had no opinion. The cost is deferred, invisible, and
always larger than expected.*

## Three posts, one move

Each of the three posts below applies this discipline at a different layer of the stack.

[Write Down the Why](https://dfa1.github.io/articles/write-down-the-why) is about the
process layer. A team without written rationale produces engineers who follow rules they
don't understand — and override them the first time the rules are inconvenient. Writing
down the *why* behind a testing philosophy or a branching model is not documentation; it
is the verifiable contract between the team and its future members.

[Your Compiler Is Already Part of Your Security
Team](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team)
is about the code layer. A domain primitive — `MarketId`, `InstrumentId`, `ApiToken` —
encodes a constraint that a raw `int` or `String` cannot carry. The type system becomes
the enforcement mechanism: the invalid operation does not compile, the secret does not
print, the wrong-order argument is a build failure, not a 3am page.

[Make the Implicit
Explicit](https://dfa1.github.io/articles/make-the-implicit-explicit) is about the
boundary layer. Two services sharing an informal agreement about a DTO are one field
rename away from a coordinated rollback. Versioned endpoints, isolated DTOs, and
point-in-time contracts move the agreement out of both teams' heads and into something
the CI pipeline can verify.

Same move — encode the assumption in a representation the build can check — applied at
process, code, and boundary. The layer changes; the principle does not.

## What TDD and DDD gave us

TDD and DDD didn't just introduce disciplines — they made the codebase safe to change. A
suite of requirement-backed tests means a refactor has a safety net; a regression is a red
test, not a support ticket. A domain model expressed in types means a renamed concept
propagates as a compile error, not a grep across twelve services. Both disciplines reduce
the cost of being wrong and increase the confidence to move.

That is what makes iterative work tractable: not velocity, but reversibility. You can take
the next step because the previous one is verified.

## The chain

The discipline doesn't stop at tests and types. It connects every artifact in the system.
Requirements point to tests — a failing test means a violated requirement, not an implementation detail.
Tests point to the code they exercise — coverage is not a vanity metric, it is a
map of what is and isn't verified. Code encodes domain invariants in types — not as
comments, not as runtime checks buried in service logic, but as constraints the compiler
enforces at every call site. The architecture follows rules that a tool like
[ArchUnit](https://www.archunit.org) verifies on every build — if the dependency graph
matters, it belongs in CI, not in a diagram that rots.

Every link in this chain is a pointer. A broken pointer is not a documentation problem —
it is a liability. A test that no longer covers its requirement is a false green. A type
that stopped enforcing its invariant is a hole in the boundary. An architecture rule that
lives in a document but not in the pipeline is a rule that will be violated the first time
someone is in a hurry.

The goal is a system where a new reader can enter at any point — a type, a test, a
service boundary — and trace outward without asking anyone. Not because the documentation
is thorough, but because the structure of the system makes the connections traversable.
This is what explorable means in practice: not an IDE feature, not a style preference —
a property of the design that either holds under load or doesn't.

## 2026: the cost doubles

What was always true is now structurally more expensive to ignore. AI agents are routine
contributors to codebases. They read your system the way a new joiner would — inferring
intent from structure, filling gaps with plausible guesses, moving fast where the path is
clear. In an implicit system, the guesses compound. In an explicit, wired system, the
types constrain the agent the same way they constrain a human: the dangerous path is the
hard one to write.

An agent navigating a codebase of raw `String` and `long` has no map. An agent navigating
a codebase of `InstrumentId`, `DataQuality`, and `ApiToken` — with architecture rules in
the CI and rationale in the commit history — has the same map a senior engineer has.

Implicitness was always expensive. Now the cost scales.
