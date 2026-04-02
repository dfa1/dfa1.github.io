# Write Down the Why

1 October 2020

*Late 2019, early 2020. A newly formed team at SIX Group, building the next generation of a financial market data
platform from scratch. Several developers that never worked together, a greenfield project, and a testing approach that
would take us a while to
outgrow. Every team has a moment where brute force stops working. Mine came the day I realized shotgun testing wasn’t a
strategy — it was a symptom.*

## The Starting Point

The initial approach to testing was what I came to call **shotgun testing**: write tests that assert data mappings
against a live system. Point the suite at a running environment, fire requests, capture the responses, assert they match
expectations. Our system was responsible for data mappings from various upstream sources, applying entitlements and exposing an
easy-to-use set of APIs.

The intent was honest — test against reality, catch regressions early. The problem was that the tests were coupled to
the live system, not to the code. Data changes. Environments drift. A field gets updated upstream, a mapping gets
adjusted, and suddenly twenty tests are red for reasons that have nothing to do with what we just wrote.

The suite broke often. Not from bugs we introduced, but from the world moving underneath us. A test suite that
fails for external reasons is not a safety net. It is noise. And noise trains people to ignore failures.

## The Model

To understand why data-driven testing was never going to work here, you need to understand the scale of the model:
reference data and market data (minus streaming).

For reference data — the descriptive attributes of a financial instrument — we have around **4000 fields**. Each field
has a mapping behind it: sometimes trivial, sometimes involving complex
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

The project started with exploratory testing to validate *some* operations. Initially it was
really fast and effective. But soon we discovered some problems with the approach — in the form
of random breakage of the tests. We were able to quickly fix the breakages but more and more
started to pop up. Then we started to ask what was worth keeping and what was not. It was clear that we needed
to focus on coordinating our efforts better, but also that testing some data was good (at the time we defined
"basic" tests and "special cases").

Then I proposed to look at the [Testing Pyramid](https://martinfowler.com/articles/practical-test-pyramid.html). 
The reframe was this: instead of testing every *data* point, test every *type of mapping*.
The mappings themselves come in a handful of distinct forms:

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
That week, we documented the following:

> **Tests should break for the right reasons.** A test that fails because the world changed underneath it — shotgun testing — is worse than no test: it erodes trust. A test should fail only when the code it tests is wrong.
>
> **Test behavior, not implementation**. Assert what a function does, not how it does it. If you refactor internals
> and tests break, the tests were testing the wrong thing.
> This is the core insight behind mapper-type testing: test the contract of each transformation, not the
> specific data flowing through production.
>
> **Determinism is non-negotiable**. A test that passes 99% of the time is not a passing test — it's a flaky test you haven't caught yet. No network calls, no clock dependencies, no shared mutable state between tests.
> If you need external systems, use fixed snapshots (as we do) or in-memory fakes.
>
> **Tests should be fast**. Especially unit tests, they should take a few milliseconds each.
> 
> **Bug-ticket-test-fix cycle**. Bug is reported → open a ticket → write a failing test that reproduces it → fix. 
> This guarantees every bug becomes a regression test. It also forces you to understand the bug before fixing it.

Then we linked the aforementioned Testing Pyramid and some best practices/guidance, like using Junit4/Mockito/AssertJ,
and keeping test code to the same standard as production code.
The point about determinism was really important so we started to use [Gateway pattern](https://martinfowler.com/articles/gateway-pattern.html)
systematically for every new upstream source. 

## Branching strategy

Around the same time, the team was converging on a branching strategy. The proposal on the table was [git flow](https://nvie.com/posts/a-successful-git-branching-model):
long-lived feature branches, deployed in isolated OpenShift environments, merge when the feature is complete. The appeal was
obvious — isolation, reviewability, nothing half-finished in the main branch. But I kept coming back to
the same observation: this is not continuous integration. You can call this **continuous branching** or 
**continuous delay**, and it felt wrong for our team's mission. 
Branches would diverge. Merges would become events, with lots of conflicts to resolve. The fragile test suite would
break in unpredictable ways when two long-lived branches finally met.

I made a counter-proposal: if merging hurts, do it more often. Multiple times per day. 
The idea was essentially [trunk-based development](https://trunkbaseddevelopment.com). Small branches, measured in hours or days rather than weeks. 
A  handful of commits, rebased and squashed before merge to keep the history clean. **Feature flags** to decouple deployment from release — a
half-finished feature could live in trunk behind a flag, invisible to users, without blocking anyone else's work.

I was not the only one who felt this way. Another engineer on the team had worked with trunk-based dev before and
understood the tradeoffs. Having an internal ally mattered — not to win an argument, but to demonstrate that this was
not a personal preference. It was a known, practiced approach with a track record.

The team adopted it. Not in a single decision, but gradually — as people tried the workflow, found the feedback loops
faster, and stopped dreading merge day. There was no single moment where it clicked. It became the default because it
worked.

Extract for the wiki:
> **Principles**
> 
> **commit often**, **publish once** Why? we don't want to see on develop tens of commits like "wip", "fix unit test", "review items", "merge from master"
> since they are noise. We just want to see what is inside each feature or bugfix.
> 
> **small pull-requests** Why? Because it is easier to understand and to review.
> 
> *First make the change easy, then make the easy change* -- Kent Beck

And then some guidance and ideal best practices:

> we decided to use squash, rebase and fast-forward only
> - sometimes merging 2 "green" PRs produces a build error/test error
> 
> bugfixes must be atomic 
>  - small and very focused, ideally touching 1 production class + test cases
>  - why? need to be cherry-picked to the release branch, merge conflicts are expensive to solve
> 
> invest time in writing meaningful commit messages, please include:
> - JIRA reference in square brackets
> - brief summary in the first line
> - second line empty
> - some nice description of what is the problem, what is the solutions, document trade-offs, trade-off, etc.
> - commit message should link the story and tell the "why" a particular solution has been used
> - be descriptive... future readers of the history (including yourself) will thank you
> - Avoid commits like "Fix" or "Updates..."
> 
> we encourage breaking big changes into multiple PRs
>   [An example of preparatory refactoring](https://martinfowler.com/articles/preparatory-refactoring-example.html)

Finally, we included some documentation on *how* (which git commands to use, how to do it in IntelliJ) and even 
some common error. 

## Codifying the decisions

Both shifts — the testing approach and the branching model — emerged from working practice. But practice alone does not
transfer. We put the reasoning, the constraints and some guiding principles in the internal wiki.

The page was not a procedural checklist. It focused on *why*: why testing the mapper is better than testing the data,
why data coverage is the wrong goal in this domain, why short-lived branches and feature flags make integration cheaper.
It included concrete examples: a good unit test next to a bad one, with an explanation of what made the difference.
Actual code, actual trade-offs, not abstract principles. We had even screenshots of bad git history from other projects
to show why we want this way as we are a small team (I remember one particulary bad with like 40 parallel lines with a lot of merges 
from master branch in both directions).

## What We Learned

Neither the testing philosophy nor the branching model was adopted because someone mandated it. Both spread because they
were demonstrably better, and because the reasoning was written down in a form that others could inspect, question, and
build on. Teams don’t align because someone enforces rules.
They align when they share a philosophy — a way of seeing the system.

An explicit document that captures *why* outlasts any individual on the team; it shapes the culture of the team.

**Write down the why. Make it visible. The alignment follows.**

---

### Update on 2 April 2026

This was 2020. Six years later, the same principle applies with even greater urgency. Today we routinely delegate coding
tasks to AI agents — and an agent without context will produce the same kind of misaligned output that a new team member
without onboarding would. It will write shotgun tests. It will pick the wrong abstraction. It will optimize for the
metric you gave it, not the one you meant. The document that captures your testing philosophy, your branching model,
your "why" — that is not just onboarding material for humans anymore. It is the prompt. If you never wrote down the why,
you will not be able to align the machine either.
