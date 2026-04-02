# Write Down the Why

1 October 2020

*Late 2019, early 2020. A newly formed team at SIX Group, building the next generation of a financial market data
platform from scratch. Several developers that never worked together, a greenfield project, a deadline, and two problems
we had not yet named: a testing approach that would take us a while to outgrow, and a branching model we had not agreed on yet.
Every team has a moment where brute force stops working. Ours came on two fronts at once.
Without a shared “why” every engineer filled in the gaps differently.*

## The Starting Point

Our system is responsible for data mappings from various upstream sources, applying entitlements and
exposing an easy-to-use set of APIs.

The initial approach to testing is what I call **shotgun testing**: write tests that assert data mappings
against a live system. Point the suite at a running environment, fire requests, capture the responses, assert they match
expectations. The intent is honest — test against reality, catch regressions early. The problem is that the tests are coupled to
the live system, not to the code. Data changes. Environments drift. A field gets updated upstream, a mapping gets
adjusted, and suddenly twenty tests are red for reasons that have nothing to do with what we just wrote.

The suite breaks often. Not from bugs we introduce, but from the world moving underneath us. A test suite that
fails for external reasons is not a safety net. It is noise. And noise trains people to ignore failures.

The root cause is a confusion between two things that look the same but are not: **test coverage** (exercising your
code paths) and **data coverage** (exercising every possible input value). Our model has around **4000 fields** for
reference data alone, each with its own mapping logic, conditional branches, and chains of relationships. The
permutations are effectively infinite. No test suite can achieve data coverage at this scale. Confusing the two goals
leads to shotgun testing; naming the distinction is what frees the team to focus on what actually matters.

Around the same time, the team has not agreed on a branching strategy either. The proposal on the table is
[git flow](https://nvie.com/posts/a-successful-git-branching-model): long-lived feature branches, deployed in isolated
OpenShift environments, merge when the feature is complete. The appeal is obvious — isolation, reviewability, nothing
half-finished in the main branch. But the appeal hides a cost: branches diverge, merges become events, and the fragile
test suite breaks in unpredictable ways when two long-lived branches finally meet.

Two problems, one root: the team has no shared philosophy on either front.

## The Shift

I propose looking at the [Testing Pyramid](https://martinfowler.com/articles/practical-test-pyramid.html).
The reframe is this: instead of testing every *data* point, test every *type of mapping*. The mappings come in a handful of
distinct forms:

- **Simple mapping**: `a = b` — direct field assignment, no logic
- **Mapping with fallback**: `a = b ?: c` — use `b`, fall back to `c` if null
- **Constant mapping**: `a = "FIXED_VALUE"` — the destination is always a literal, regardless of source
- **Concatenation**: `a = b + "T" + c` — compose a value from multiple fields and literals
- **Conditional mapping**: if `a = 1` and `b = 2` then `c = 10` — destination depends on a predicate over source fields
- **Set-based mapping**: map destination `c` when source fields fall within defined value sets:

```
source {
  source.a in [1, 2, 3]
  target = source.c
}
```

Each of these is a distinct type of transformation with its own failure modes. Testing one instance of each type gives
far more coverage than testing a hundred random data points that all happen to exercise the same simple mapping.

Our data pipeline has a compiler-like structure — data flows through distinct transformation stages, each implemented as
a dedicated mapper. Rather than asserting that field X of instrument Y equals Z against a live system, we start
testing each mapper in isolation: does it handle a missing optional field? Does it apply the right fallback? Does it
preserve precision?

For integration confidence we keep a small set of tests based on **fixed snapshots** — a curated, frozen slice of real
data checked into the repository. A known input with a known expected output, version-controlled alongside the code.

The combination gives us something we did not have before: a test suite that is stable by construction. But the
technical shift is only half the work. The other half is agreement — and agreement needs to be written down. Not as a
procedure, but as a set of principles with their reasons:

> **Tests should break for the right reasons.** A test that fails because the world changed underneath it — shotgun
> testing — is worse than no test: it erodes trust. A test should fail only when the code it tests is wrong.
>
> **Test behavior, not implementation**. Assert what a function does, not how it does it. If you refactor internals
> and tests break, the tests are testing the wrong thing. This is the core insight behind mapper-type testing: test
> the contract of each transformation, not the specific data flowing through production.
>
> **Determinism is non-negotiable**. A test that passes 99% of the time is not a passing test — it's a flaky test
> you haven't caught yet. No network calls, no clock dependencies, no shared mutable state between tests. If you need
> external systems, use fixed snapshots (as we do) or in-memory fakes.
>
> **Tests should be fast**. Especially unit tests, they should take a few milliseconds each.
>
> **Bug-ticket-test-fix cycle**. Bug is reported → open a ticket → write a failing test that reproduces it → fix.
> This guarantees every bug becomes a regression test. It also forces you to understand the bug before fixing it.

Notice that each principle carries a *because*. Not "use fixed snapshots" but "use fixed snapshots *because* a test
that fails for external reasons erodes trust." That is what makes it guidance rather than a checklist. A new engineer
reading this does not just know *what* to do — they know *why*, which means they can reason about cases the document
never anticipated.

We also link the Testing Pyramid, recommend Junit4/Mockito/AssertJ, adopt the
[Gateway pattern](https://martinfowler.com/articles/gateway-pattern.html) for every upstream source to enforce the
determinism principle, and keep test code to the same standard as production code. But those are the *how*. The
document above is the *why*.

## Branching strategy

If merging hurts, do it more often: *multiple times per day*. The idea is essentially
[trunk-based development](https://trunkbaseddevelopment.com). Small branches, measured in hours or days rather than
weeks. A handful of commits, rebased and squashed before merge. **Feature flags** to decouple deployment from release —
a half-finished feature lives in trunk behind a flag, invisible to users, without blocking anyone else's work.

I am not the only one who feels this way. Another engineer on the team has worked with trunk-based dev before and
understands the tradeoffs. Having an internal ally matters — not to win an argument, but to demonstrate that this is
not a personal preference. It is a known, practiced approach with a track record.

The team adopts it. Not in a single decision, but gradually — as people try the workflow, find the feedback loops
faster, and stop dreading merge day. There is no single moment where it clicks. It becomes the default because it
works.

And again, the agreement needs to be written down:

> **commit often, publish once** — we don't want to see on develop tens of commits like "wip", "fix unit test",
> "review items", "merge from master". They are noise. We want to see what is inside each feature or bugfix.
>
> **small pull-requests** — easier to understand and to review.
>
> *First make the change easy, then make the easy change* — Kent Beck
>
> We use squash, rebase and fast-forward only. Why? Because sometimes merging two "green" PRs produces a build error.
>
> Bugfixes must be atomic — small and focused, ideally touching one production class and its tests. Why? They need to
> be cherry-picked to the release branch, and merge conflicts are expensive to solve.
>
> Invest time in writing meaningful commit messages: JIRA reference, brief summary, then the *why* — what is the
> problem, what is the solution, what trade-offs were made. Future readers of the history (including yourself) will
> thank you. Avoid commits like "Fix" or "Updates...".
>
> Break big changes into multiple PRs.
> [An example of preparatory refactoring.](https://martinfowler.com/articles/preparatory-refactoring-example.html)

The same pattern: every rule has a reason. "Bugfixes must be atomic" is a procedure; "bugfixes must be atomic *because*
they need to be cherry-picked and merge conflicts are expensive" is guidance. One you follow blindly, the other you can
adapt when the situation is slightly different.

We include documentation on *how* as well — which git commands to use, how to do it in IntelliJ, common errors. But
that part is short. The principles above are the part that actually shapes how people work.

## What We Learned

Both shifts — testing and branching — emerge from the same pattern: name the problem, reach agreement, write down the
reasoning.

The page in the wiki is not a procedural checklist. It focuses on *why*: why testing the mapper is better than testing
the data, why data coverage is the wrong goal in this domain, why short-lived branches and feature flags make
integration cheaper. It includes concrete examples — a good unit test next to a bad one, with an explanation of what
makes the difference. We even include screenshots of bad git history from other projects: one particularly bad one with
about 40 parallel lines and merges from master in both directions.

An explicit document that captures *why* outlasts any individual on the team; it shapes the culture of the team.

**Write down the why and make it visible.**

---

### Update on 2 April 2026

This was 2020. Six years later, the same principle applies with even greater urgency. Today we routinely delegate coding
tasks to AI agents — and an agent without context will produce the same kind of misaligned output that a new team member
without onboarding would. It will write shotgun tests. It will pick the wrong abstraction. It will optimize for the
metric you gave it, not the one you meant. The document that captures your testing philosophy, your branching model,
your "why" — that is not just onboarding material for humans anymore. It is the prompt. If you never wrote down the why,
you will not be able to align the machine either.
