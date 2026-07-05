# Argue Once, Write It Down

*9 May 2026*

*Once in a while, someone shares an "X rules of software engineering" list and I find myself agreeing with about a third of it. The rest is either too generic to act on, or too specific to someone else's context to be useful in mine. This is my pass through one such list[^kamina] — keeping what I've actually had to write down somewhere, dropping what I haven't, and rewriting the rest in language I'd defend in a code review.*

*The [Zen of Python](https://peps.python.org/pep-0020/) was the first such list I admired — `import this` felt like a secret handshake when I was younger. Its strength is also its trap: aphorisms without reasons get cargo-culted. So the principle here is the one from [Write Down the Why](https://dfa1.github.io/articles/write-down-the-why): a rule without a reason is a checklist; a rule with a reason is guidance.*

*Each rule ends with an extract from the story where it was earned — we remember stories better than rules.*

## The Rules

### 0. Write down the *why*. The *what* is in the code already.

Every rule, every design decision, every constraint should carry a *because*. Not "use fixed snapshots" but "use fixed snapshots *because* tests that fail for external reasons erode trust." For decisions, the *why* includes the alternatives you rejected: an undocumented trade-off is indistinguishable from a mistake, and the next engineer who reads the architecture and wonders why will either burn a day reconstructing the reasoning, or "fix" what was deliberate.

The mechanism is [Architecture Decision Records](https://adr.github.io/): one short Markdown file per decision, numbered, committed next to the code it explains — the decision, the alternatives rejected, the why. [vortex-java](https://github.com/dfa1/vortex-java/tree/main/adr) and [zstd-java](https://github.com/dfa1/zstd-java/tree/main/adr) each carry a top-level `adr/` directory. In the repo, because the audience has doubled: most of the code in both projects is written with AI assistance, and an agent reads `adr/` the same way a new engineer does. A decision recorded there keeps months of later sessions aligned with it; a decision in a wiki might as well not exist.

> *Adding a distributed in-memory cluster was a deliberate reversal of our complexity-reduction principle — but the alternative, coordinating locks through the database, was slower and more fragile at the write volumes we were seeing.* — [The Slow Fix § Team maturity](https://dfa1.github.io/articles/the-slow-fix#months-1318-team-maturity)

### 1. Treat unfamiliar code as a system to understand, not an enemy to rewrite.

The full rewrite is the single worst strategic mistake a team can make[^spolsky]. The old system contains years of accumulated domain knowledge — bugs that turned into features, edge cases silently handled, compensations for upstream failures. Throw it away and you won't know what you've lost until production tells you. You don't get to a simple system by demanding simplicity at the start: you get there by living through the complexity, understanding it, and having the patience to remove what doesn't earn its place.

> *Early on, one of the developers proposed a full rewrite. […] Starting clean felt like the obvious answer. It wasn't.* — [The Slow Fix § Tactical and Strategic](https://dfa1.github.io/articles/the-slow-fix#tactical-and-strategic)

### 2. But be willing to delete what you wrote last quarter — dependencies included.

Code you wrote is not sacred: every line has to be maintained, secured, debugged, and explained to the next person or AI agent. A dependency is worse — a permanent commitment to someone else's release schedule, security posture, and design choices — so justify each one, and treat removing one as engineering too. The best code is the code that doesn't exist.

> *The codebase was at 180,000 lines — 70,000 fewer than when we started, despite two years of new features.* — [The Slow Fix § Stability](https://dfa1.github.io/articles/the-slow-fix#months-1924-stability)

### 3. Coding standards exist to argue once, not every PR.

Standards don't prevent arguments — they move the argument up one level: argue once about the rule, then stop relitigating taste in every PR. A rule with a reason outlasts the meeting where it was decided. In [vortex-java](https://github.com/dfa1/vortex-java) and [zstd-java](https://github.com/dfa1/zstd-java) that means a `checkstyle.xml` enforced by the build, so formatting arguments end before they start, and a `CLAUDE.md` stating the conventions — which makes the standards bind the AI agents writing most of the code, not just the humans reviewing it.

> *We use squash, rebase and fast-forward only. Why? Because sometimes merging two "green" PRs produces a build error.* — [Write Down the Why § Branching strategy](https://dfa1.github.io/articles/write-down-the-why#branching-strategy)

### 4. Commit messages are documentation.

JIRA reference, brief summary, then the *why* — what was the problem, what was the solution, what trade-offs were made. `Fix` and `Updates...` are noise that you'll regret in two years when `git blame` is your only witness. None of this means agonizing over every local commit: commit often, perfect later, publish once[^robertson]. The history you publish is the documentation; the history you keep while working is scaffolding — rebase the second into the first before it leaves your machine.

> *Commit often, publish once — we don't want to see on develop dozens of commits like "WIP", "fix", "fix unit test" […]. They are noise. We want to see what is inside each feature or bugfix.* — [Write Down the Why § Branching strategy](https://dfa1.github.io/articles/write-down-the-why#branching-strategy)

### 5. Move complexity from runtime into code, where it can be read.

Versioned endpoints, isolated DTOs, decorator stacks — they look like more moving parts. Operationally, they are simpler, because the complexity is now visible: read it, test it, reason about it. The opposite — a single unversioned endpoint, DTOs shared to avoid minor duplication, no monitoring — is what produces incidents and deployments that have to be coordinated across every consumer.

> *Isolated DTOs are what an independent release cycle looks like in practice, in the presence of breaking changes. The duplication is the solution.* — [Make the Implicit Explicit § Versioned endpoints](https://dfa1.github.io/articles/make-the-implicit-explicit#versioned-endpoints)

### 6. Fix root causes. Symptoms come back.

Restarting the job that dies every week is not a fix; it is a schedule. The cause is still there, producing the next failure, while workarounds accumulate around it — extra code whose only purpose is to survive a bug nobody understood. Empty `catch` blocks are where that road ends: a codebase that has stopped asking why.

> *Hibernate, combined with some reflection hacks and connection-pooling issues, made it unstable — it frequently failed, and we had to manually restart it the following day.* — [The Slow Fix § Hard choices](https://dfa1.github.io/articles/the-slow-fix#months-46-hard-choices)

### 7. Ship small, ship often. If merging hurts, do it more often.

[Trunk-based development](https://trunkbaseddevelopment.com/), small PRs, feature flags. The pain of merging *decreases* as merges become smaller and more frequent, because conflicts grow with the distance between branches. The same goes for everything else that hurts: deployments and refactors.

> *We also started deploying smaller releases more often — instead of once every 3 months, we shipped every 3 weeks.* — [The Slow Fix § Infra as code](https://dfa1.github.io/articles/the-slow-fix#months-79-infra-as-code)

## When a checklist is the right tool

Not every rule needs a *why*. Pre-flight checks, surgical timeouts, deploy runbooks — situations with known steps, high stakes, and a real cost of forgetting one — are exactly where a checklist beats judgment. Atul Gawande's *[The Checklist Manifesto](https://en.wikipedia.org/wiki/The_Checklist_Manifesto)* makes the case better than I can.

The mistake is using a checklist where the situation changes, the steps vary, and the cost isn't forgetting but misunderstanding. Then the checklist hides the reasoning that would let you adapt — which is what rule 0 was about.

## Closing

This is what I've written down. None of it is original — most of it was earned the slow way, by being wrong about it first. And the work I'm proudest of isn't code at all: it's the team I left behind in [The Slow Fix](https://dfa1.github.io/articles/the-slow-fix) — mentorship is how impact compounds.

The list is not finished. It will not be next year either. That's the point.

---

[^kamina]: Adapted from [18 Subtle Rules of Software Engineering](https://kaminagroup.com/content/69/18-subtle-rules-of-software-engineering/), filtered through what I've actually had to write down.

[^spolsky]: Joel Spolsky, [*Things You Should Never Do, Part I*](https://www.joelonsoftware.com/2000/04/06/things-you-should-never-do-part-i/) (2000).

[^robertson]: Seth Robertson, [*Commit Often, Perfect Later, Publish Once — Git Best Practices*](https://sethrobertson.github.io/GitBestPractices/).
