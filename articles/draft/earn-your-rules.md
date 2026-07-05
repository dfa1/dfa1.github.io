# Earn Your Rules

*4 July 2026*

*Once in a while, someone shares an "X rules of software engineering" list and I find myself agreeing with about a third of it. The rest is either too generic to act on, or too specific to someone else's context to be useful in mine. This is my pass through one such list[^kamina] — keeping what I've actually had to write down somewhere, dropping what I haven't, and rewriting the rest in my own language.*

*The [Zen of Python](https://peps.python.org/pep-0020/) was the first such list I admired — `import this` felt like a secret handshake when I was younger. Its strength is also its trap: aphorisms without reasons get cargo-culted. So the principle here is the one from [Write Down the Why](https://dfa1.github.io/articles/write-down-the-why): a rule without a reason is a checklist; a rule with a reason is guidance.*

*So yes, this is another rules list — but with narrative attached: each rule opens with an extract from the story where it was earned, because we remember stories better than rules. Hopefully you like the format.*

## My Rules

(yes, I start counting from 0!)

- [0. Write down the *why*. The *what* is in the code already.](#rule-0)
- [1. Treat unfamiliar code as a system to understand, not an enemy to rewrite.](#rule-1)
- [2. But be willing to delete what you wrote last quarter — dependencies included.](#rule-2)
- [3. Coding standards exist to argue once, not every PR.](#rule-3)
- [4. Commit messages are documentation.](#rule-4)
- [5. Move complexity from runtime into code, where it can be read.](#rule-5)
- [6. Fix root causes. Symptoms come back.](#rule-6)
- [7. Ship small, ship often. If merging hurts, do it more often.](#rule-7)

### 0. Write down the *why*. The *what* is in the code already. {#rule-0}

> *Adding a distributed in-memory cluster was a deliberate reversal of our complexity-reduction principle — but the alternative, coordinating locks through the database, was slower and more fragile at the write volumes we were seeing.* — [The Slow Fix § Team maturity](https://dfa1.github.io/articles/the-slow-fix#months-1318-team-maturity)

Every rule, every design decision, every constraint should carry a *because*. Not "use fixed snapshots" but "use fixed snapshots *because* tests that fail for external reasons erode trust." For decisions, the *why* includes the alternatives you rejected: an undocumented trade-off is indistinguishable from a mistake, and the next engineer will either burn a day reconstructing the reasoning, or "fix" what was deliberate.

There are many mechanisms — code comments, a project wiki — but my favorite is [Architecture Decision Records](https://adr.github.io/): one short Markdown file per decision, numbered, committed next to the code it explains. [vortex-java](https://github.com/dfa1/vortex-java/tree/main/adr) and [zstd-java](https://github.com/dfa1/zstd-java/tree/main/adr) each carry a top-level `adr/` directory — in the repo, because the audience has doubled: most of the code in both projects is written with AI assistance, and an agent reads `adr/` the same way a new engineer does. A decision recorded there keeps months of later sessions aligned; a decision in a wiki might as well not exist.

### 1. Treat unfamiliar code as a system to understand, not an enemy to rewrite. {#rule-1}

> *Early on, one of the developers proposed a full rewrite. […] Starting clean felt like the obvious answer. It wasn't.* — [The Slow Fix § Tactical and Strategic](https://dfa1.github.io/articles/the-slow-fix#tactical-and-strategic)

The full rewrite is the single worst strategic mistake a team can make[^spolsky]. The old system contains years of accumulated domain knowledge — bugs that turned into features, edge cases silently handled, compensations for upstream failures. Throw it away and you won't know what you've lost until production tells you. You don't get to a simple system by demanding simplicity at the start: you get there by living through the complexity, understanding it, and having the patience to remove what doesn't earn its place.

### 2. But be willing to delete what you wrote last quarter — dependencies included. {#rule-2}

> *The codebase was at 180,000 lines — 70,000 fewer than when we started, despite two years of new features.* — [The Slow Fix § Stability](https://dfa1.github.io/articles/the-slow-fix#months-1924-stability)

Code you wrote is not sacred: every line has to be maintained, secured, debugged, and explained to the next person or AI agent. A dependency is worse — a permanent commitment to someone else's release schedule, security posture, and design choices — so justify each one, and treat removing one as engineering too. The best code is the code that doesn't exist; the challenge is judging how and when to retire a subsystem, keeping the whole as simple as possible.

### 3. Coding standards exist to argue once, not every PR. {#rule-3}

> *We use squash, rebase and fast-forward only. Why? Because sometimes merging two "green" PRs produces a build error.* — [Write Down the Why § Branching strategy](https://dfa1.github.io/articles/write-down-the-why#branching-strategy)

Standards don't prevent arguments — they move the argument up one level: argue once about the rule, document it, share it with the team — then stop relitigating taste in every PR. A rule with a reason outlasts the meeting where it was decided.

For code formatting, I love the `go fmt` philosophy — one canonical format, no debate — and reproduce it in my Java projects with Checkstyle. In [vortex-java](https://github.com/dfa1/vortex-java) and [zstd-java](https://github.com/dfa1/zstd-java), a `checkstyle.xml` enforced by the build ends formatting arguments before they start, and a `CLAUDE.md` states the conventions — binding the AI agents writing most of the code, not just the humans reviewing it.

Formatting is the easy case. Neal Ford's *fitness functions*[^ford] extend the same move to any property you can check automatically. [hosh](https://github.com/hosh-shell/hosh) carries ArchUnit tests literally named `*FitnessTest`: every public command must declare a `@Description` and `@Examples`, each example must compile against the shell's own grammar, and every `@Mock` in a test must actually be used. Documentation and test hygiene, enforced the same way formatting is — argue once, then let the build remember.

### 4. Commit messages are documentation. {#rule-4}

> *Commit often, publish once — we don't want to see on develop dozens of commits like "WIP", "fix", "fix unit test" […]. They are noise. We want to see what is inside each feature or bugfix.* — [Write Down the Why § Branching strategy](https://dfa1.github.io/articles/write-down-the-why#branching-strategy)

JIRA reference, brief summary, then the *why* — what was the problem, what was the solution, what trade-offs were made. `Fix` and `Updates...` are noise that you'll regret in two years when `git blame` is your only witness. None of this means agonizing over every local commit: commit often, perfect later, publish once[^robertson]. The history you publish is the documentation; the history you keep while working is scaffolding — rebase the second into the first before it leaves your machine.

### 5. Move complexity from runtime into code, where it can be read. {#rule-5}

> *Isolated DTOs are what an independent release cycle looks like in practice, in the presence of breaking changes. The duplication is the solution.* — [Make the Implicit Explicit § Versioned endpoints](https://dfa1.github.io/articles/make-the-implicit-explicit#versioned-endpoints)

This one is harder to explain: by *runtime* I mean production — the external integrations, the dependencies, the extension points that only show up when the system runs. Understand them, document them, then make them *explicit* in the code.

Versioned endpoints, isolated DTOs, decorator stacks — they look like more moving parts. Operationally, they are simpler, because the complexity is now visible: read it, test it, reason about it. The opposite — a single unversioned endpoint, DTOs shared to avoid minor duplication, no monitoring — looks "simple," but it is what produces incidents and deployments that have to be coordinated across every consumer.

### 6. Fix root causes. Symptoms come back. {#rule-6}

> *Hibernate, combined with some reflection hacks and connection-pooling issues, made it unstable — it frequently failed, and we had to manually restart it the following day.* — [The Slow Fix § Hard choices](https://dfa1.github.io/articles/the-slow-fix#months-46-hard-choices)

This is my favorite: every problem is an opportunity to understand your system a bit better. Often the root cause is easy to see; sometimes it is not, and fixing it requires discipline and perseverance — two of the qualities I value most in software engineers.

Restarting the job that dies every week is not a fix; it is a schedule. The cause is still there, producing the next failure, while workarounds accumulate around it — extra code whose only purpose is to survive a bug nobody understood. Empty `catch` blocks are where that road ends: a team that has stopped asking why and just wants to look good.

Fixing root causes makes you a better engineer; explaining the process to others — what to check, in which order, and why — makes the whole team better.

### 7. Ship small, ship often. If merging hurts, do it more often. {#rule-7}

> *We also started deploying smaller releases more often — instead of once every 3 months, we shipped every 3 weeks.* — [The Slow Fix § Infra as code](https://dfa1.github.io/articles/the-slow-fix#months-79-infra-as-code)

I guess this shouldn't be a surprise anymore. During my years as a consultant I saw two types of projects: those iterating fast with almost no bugs, and those always late, hard to iterate on, with an endless stream of bugs coming from late integration of branches.

[Trunk-based development](https://trunkbaseddevelopment.com/), small PRs, feature flags. The pain of merging *decreases* as merges become smaller and more frequent, because conflicts grow with the distance between branches. The same goes for everything else that hurts: deployments and refactors.

## When a checklist is the right tool

Not every rule needs a *why*. A checklist is a substitute for memory: pre-flight checks, [surgical timeouts](https://en.wikipedia.org/wiki/WHO_Surgical_Safety_Checklist), deploy runbooks — situations with known steps, high stakes, and a real cost of forgetting one — are exactly where a checklist beats judgment. Atul Gawande's *[The Checklist Manifesto](https://en.wikipedia.org/wiki/The_Checklist_Manifesto)* makes the case better than I can.

A rule is a substitute for judgment, and the mistake is confusing the two: a checklist tells you what to do, a rule tells you how to decide. Where the situation changes, the steps vary, and the cost isn't forgetting but misunderstanding, a checklist hides the reasoning that would let you adapt. Strip the reason from a rule and that's what it becomes — which is what rule 0 was about.

## Closing

This is what I've written down. None of it is original — most of it was earned the slow way, by being wrong about it first. And the work I'm proudest of isn't code at all: it's the team I left behind in [The Slow Fix](https://dfa1.github.io/articles/the-slow-fix) — mentorship is how impact compounds.

The list is not finished. It will not be next year either. That's the point.

---

[^kamina]: Adapted from [18 Subtle Rules of Software Engineering](https://kaminagroup.com/content/69/18-subtle-rules-of-software-engineering/), filtered through what I've actually had to write down.

[^spolsky]: Joel Spolsky, [*Things You Should Never Do, Part I*](https://www.joelonsoftware.com/2000/04/06/things-you-should-never-do-part-i/) (2000).

[^ford]: Neal Ford, Rebecca Parsons, Patrick Kua, [*Building Evolutionary Architectures*](https://www.oreilly.com/library/view/building-evolutionary-architectures-2nd/9781492097532/) (O'Reilly, 2nd ed. 2022): a fitness function is any mechanism that gives an objective, automated assessment of an architectural characteristic.

[^robertson]: Seth Robertson, [*Commit Often, Perfect Later, Publish Once — Git Best Practices*](https://sethrobertson.github.io/GitBestPractices/).
