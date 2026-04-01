# From Shotgun Testing to a Testing Philosophy

1 May 2020

*Late 2019, early 2020. A newly formed team at SIX Group, building the next generation of a financial market data
platform from scratch. Eight people, a greenfield project, and a testing approach that would take us a while to
outgrow. Every team has a moment where brute force stops working. Mine came the day I realized shotgun testing wasn’t a
strategy — it was a symptom.*


## The Starting Point

The initial approach to testing was what I came to call **shotgun testing**: write tests that assert data mappings
against a live system. Point the suite at a running environment, fire requests, capture the responses, assert they match
expectations.

The intent was honest — test against reality, catch regressions early. The problem was that the tests were coupled to
the live system, not to the code. Data changes. Environments drift. A field gets updated upstream, a mapping gets
adjusted, and suddenly twenty tests are red for reasons that have nothing to do with what we just wrote.

The suite broke constantly. Not from bugs we introduced, but from the world moving underneath us. A test suite that
fails for external reasons is not a safety net. It is noise. And noise trains people to ignore failures.

## The Model

To understand why data-driven testing was never going to work here, you need to understand the scale of the model.

For reference data — the descriptive attributes of a financial instrument — we have around **4000 fields** (in 2026 they
are almost **8000**). Each field has a mapping behind it: sometimes trivial, sometimes involving complex
transformations, conditional logic, and chains of relationships across hundreds of classes. The object graph is deep.
The permutations are effectively infinite.

Market data is even more complex. Every data point is identified not by a single key but by four dimensions. A single
instrument can have hundreds of valid combinations. Writing a test per combination is not a strategy. It is a different
problem.

No test suite can achieve **data coverage** at this scale. There is simply too much data, too many valid states, too
many combinations. This was the first thing we had to name explicitly — the difference between **test coverage** (
exercising your code paths) and **data coverage** (exercising every possible input value). Confusing the two is what
leads to shotgun testing. Accepting that data coverage is unachievable is what frees you to focus on what actually
matters.

## The Shift

The reframe was this: instead of testing every data point, test every *type of mapping*.
The mappings themselves come in a handful of distinct forms:

- **Simple mapping**: `a = b` — direct field assignment, no logic
- **Mapping with fallback**: `a = b ?: c` — use `b`, fall back to `c` if null
- **Constant mapping**: `a = "FIXED_VALUE"` — the destination is always a literal, regardless of source
- **Concatenation**: `a = b + "T" + c` — compose a value from multiple fields and literals
- **Conditional mapping**: if `a = 1` and `b = 2` then `c = 10` — destination depends on a predicate over source fields
- **Set-based mapping**: map destination `c` when source fields fall within defined value sets:

```
source {
  a in [1, 2, 3]
  b in [4, 5, 6]
  dest = c
}
```

Each of these is a distinct type of transformation with its own failure modes. Testing one instance of each type gives
far more coverage than testing a hundred random data points that all happen to exercise the same simple mapping.

Our data pipeline has a compiler-like structure — data flows through distinct transformation stages, each implemented as
a dedicated mapper. Rather than asserting that field X of instrument Y equals Z against a live system, we started
testing each mapper in isolation:

- does this mapper handle a missing optional field correctly?
- does it apply the right fallback when the upstream value is null?
- does it preserve precision through this transformation?

These are unit tests against the mapping logic itself. They are fast, deterministic, and entirely independent of live
data. They test *behavior*, not *data*.

For integration confidence we kept a small set of tests based on **fixed snapshots** — a curated, frozen slice of real
data checked into the repository. Not a live system. Not a moving target. A known input with a known expected output,
version-controlled alongside the code.

The combination gave us something we had not had before: a test suite that was stable by construction.

## The Branching Question

Around the same time, the team was converging on a branching strategy. The proposal on the table was git flow:
long-lived feature branches, one per OpenShift environment, merge when the feature is complete.

The appeal was obvious — isolation, reviewability, nothing half-finished in the main branch. But I kept coming back to
the same observation: this is not continuous integration. It is **continuous branching**. The word "continuous" was
doing dishonest work. Integration was deferred to the end of each feature, which meant feedback was deferred too.
Branches would diverge. Merges would become events. The fragile test suite would break in unpredictable combinations
when two long-lived branches finally met.

I made a counter-proposal: trunk-based development. Small branches, measured in hours or days rather than weeks. A
handful of commits, rebased and squashed before merge. **Feature flags** to decouple deployment from release — a
half-finished feature could live in trunk behind a flag, invisible to users, without blocking anyone else's work.

I was not the only one who felt this way. Another engineer on the team had worked with trunk-based dev before and
understood the tradeoffs. Having an internal ally mattered — not to win an argument, but to demonstrate that this was
not a personal preference. It was a known, practiced approach with a track record.

The team adopted it. Not in a single decision, but gradually — as people tried the workflow, found the feedback loops
faster, and stopped dreading merge day. There was no single moment where it clicked. It became the default because it
worked.

## Codifying the Philosophy

Both shifts — the testing approach and the branching model — emerged from working practice. But practice alone does not
transfer. We put the reasoning on an internal wiki.

The page was not a procedural checklist. It focused on *why*: why testing the mapper is better than testing the data,
why data coverage is the wrong goal in this domain, why short-lived branches and feature flags make integration cheaper.
It included concrete examples: a good unit test next to a bad one, with an explanation of what made the difference.
Actual code, actual trade-offs, not abstract principles.

That page became our onboarding document. Every new engineer joining the team since then has read it. It is still in use
today.

## What We Learned

Neither the testing philosophy nor the branching model was adopted because someone mandated it. Both spread because they
were demonstrably better, and because the reasoning was written down in a form that others could inspect, question, and
build on. Teams don’t align because someone enforces rules.
They align when they share a philosophy — a way of seeing the system.

A working alternative, demonstrated in practice and supported by a colleague who had seen it work before, is more
persuasive than any proposal. And an explicit document that captures the *why* outlasts any individual on the team.

**Write down the why. Make it visible. The alignment follows.**
