# Argue Once, Write It Down

*9 May 2026*

*Once in a while, someone shares an "X rules of software engineering" list and I find myself agreeing with about a third of it. The rest is either too generic to act on, or too specific to someone else's context to be useful in mine. This is my pass through one such list[^kamina] — keeping what I've actually had to write down somewhere, dropping what I haven't, and rewriting the rest in language I'd defend in a code review.*

*The [Zen of Python](https://peps.python.org/pep-0020/) was the first such list I admired — `import this` felt like a secret handshake when I was younger. Its strength is also its trap: aphorisms without reasons get cargo-culted. So the principle here is the one from [Write Down the Why](https://dfa1.github.io/articles/write-down-the-why): a rule without a reason is a checklist; a rule with a reason is guidance.*

*The stories behind these rules — the articles where they were earned — are collected at the end.*

## The Rules

### 0. Every decision is a trade-off. Document the alternatives you rejected.

Adding a Hazelcast cluster to a codebase we had spent years simplifying was a deliberate reversal, taken because the alternative — coordinating locks through the database — was worse. That reasoning belongs in writing, for the next engineer who reads the architecture and wonders why.

The mechanism is [Architecture Decision Records](https://adr.github.io/): one short Markdown file per decision, numbered, committed next to the code it explains — the decision, the alternatives rejected, the why. [vortex-java](https://github.com/dfa1/vortex-java/tree/main/adr) and [zstd-java](https://github.com/dfa1/zstd-java/tree/main/adr) each carry a top-level `adr/` directory. In the repo, because the audience has doubled: most of the code in both projects is written with AI assistance, and an agent reads `adr/` the same way a new engineer does. A decision recorded there keeps months of later sessions aligned with it; a decision in a wiki might as well not exist.

### 1. Write down the *why*. The *what* is in the code already.

Every rule, every design decision, every constraint should carry a *because*. Not "use fixed snapshots" but "use fixed snapshots *because* tests that fail for external reasons erode trust."

### 2. Be willing to delete what you wrote last quarter — dependencies included.

That same codebase lost 70,000 lines while gaining two years of features. Lambdaj, Drools, jBPM, custom logging wrappers, hand-rolled JS minification — all gone. Code you wrote is not sacred: every line has to be maintained, secured, debugged, and explained to the next person or AI agent. The best code is the code that doesn't exist.

Dependencies deserve the same scrutiny. Drools pulled Eclipse JDT, ANTLR, ASM, protobuf, xstream, and half a dozen `commons-*` libraries — to evaluate three trivial business rules. JNI required a C++ glue layer and a portable native build; FFM replaces both with `--enable-native-access`. Every dependency is a permanent commitment to someone else's release schedule, security posture, and design choices — justify each one, and treat removing one as engineering too.

### 3. Treat unfamiliar code as a system to understand, not an enemy to rewrite.

The full rewrite is the single worst strategic mistake a team can make[^spolsky]. The old system contains years of accumulated domain knowledge — bugs that turned into features, edge cases silently handled, compensations for upstream failures. Throw it away and you won't know what you've lost until production tells you.

### 4. Coding standards exist to argue once, not every PR.

Standards don't prevent arguments — they move the argument up one level: argue once about the rule, then stop relitigating taste in every PR. A rule with a reason outlasts the meeting where it was decided.

### 5. Commit messages are documentation.

JIRA reference, brief summary, then the *why* — what was the problem, what was the solution, what trade-offs were made. `Fix` and `Updates...` are noise that you'll regret in two years when `git blame` is your only witness. None of this means agonizing over every local commit: commit often, perfect later, publish once[^robertson]. The history you publish is the documentation; the history you keep while working is scaffolding — rebase the second into the first before it leaves your machine.

### 6. Move complexity from runtime into code, where it can be read.

Versioned endpoints, isolated DTOs, decorator stacks — they look like more moving parts. Operationally, they are simpler, because the complexity is now visible: read it, test it, reason about it. The opposite — a single unversioned endpoint, DTOs shared to avoid minor duplication, no monitoring — is what produces incidents and deployments that have to be coordinated across every consumer.
### 7. Fix root causes. Symptoms come back.

A data warehouse job dying weekly was a symptom; the real cause was Hibernate plus reflection plus connection pooling. Empty `catch` blocks were a symptom; the real cause was a culture that didn't want to look at exceptions. Compensating logic accumulates fast when nobody asks why — and silencing a warning is just the fastest way to ship the wrong fix.

### 8. Ship small, ship often. If merging hurts, do it more often.

Trunk-based development, small PRs, feature flags. Quarterly releases became weekly, then several per week. The pain of merging *decreases* as merges become smaller and more frequent. The same goes for everything else that hurts: deployments, refactors, hard conversations.

### 9. Simplicity follows complexity, not the other way around.

> *Simplicity does not precede complexity, but follows it.* — Alan Perlis

You don't get to a simple system by demanding simplicity at the start. You get there by living through the complexity, understanding it, and having the patience to remove what doesn't earn its place.

## When a checklist is the right tool

Not every rule needs a *why*. Pre-flight checks, surgical timeouts, deploy runbooks — situations with known steps, high stakes, and a real cost of forgetting one — are exactly where a checklist beats judgment. Atul Gawande's *[The Checklist Manifesto](https://en.wikipedia.org/wiki/The_Checklist_Manifesto)* makes the case better than I can.

The mistake is using a checklist where the situation changes, the steps vary, and the cost isn't forgetting but misunderstanding. Then the checklist hides the reasoning that would let you adapt — which is what rule 1 was about.

## If you're a Junior Engineer right now

- Ask questions fearlessly — and ask them in writing where others can read the answer.
- Take ownership before you feel ready. The confidence comes after, not before.
- Read more code than you write. The codebase you inherit is a body of decisions; learn to read them before you challenge them.
- Don't wait for permission to learn a new system. Curiosity beats credentials.

## If you're a Senior Engineer right now

- Teach the *why*, not the *what*. A new engineer who knows the reasoning can handle cases the document never anticipated.
- Lead by example, not by edict. The team copies what you do, not what you say.
- Use code review to spread context, not to gatekeep. Ask Socratic questions; let the author find the answer.
- Listen before you correct. Sometimes the team's "wrong" approach is solving a constraint you don't know about.
- Mentorship is how impact compounds. The team I left behind in [The Slow Fix](https://dfa1.github.io/articles/the-slow-fix) was the work I'm proudest of — not the code.

## If you're leading work right now

**On context**

- Write things down where the next person finds them: in the repo, not the wiki.
- The onboarding doc is now also the agent's prompt. If you never wrote down the why, you can't align humans or machines either.
- Code review is how context spreads. Treat it as teaching, not gatekeeping.

**On estimates**

- Estimates are probability distributions, not dates. Communicate ranges, and the assumptions underneath them.
- Re-estimate when reality changes. Anchoring on the first guess is dishonest about uncertainty.
- The right answer to "when?" is often "what do you mean by *done*?"

**On roadmap**

- Sequence by what unlocks the next move, not by what hurts most. Tactical work has visible payoff; strategic work looks like overhead until it doesn't.
- The roadmap is a hypothesis. Defend the *why*, not the dates.

## The stories behind the rules

Most of these rules were earned in stories already told here. If a rule reads like a fragment of a longer story, it is:

- [The Slow Fix](https://dfa1.github.io/articles/the-slow-fix) — the shrinking codebase, the Hazelcast reversal, the root causes (rules 0, 2, 3, 7, 8)
- [Write Down the Why](https://dfa1.github.io/articles/write-down-the-why) — rules with reasons, commit messages, coding standards (rules 1, 4, 5, 8)
- [Make the Implicit Explicit](https://dfa1.github.io/articles/make-the-implicit-explicit) — trade-offs documented, complexity made visible (rules 0, 6)
- [The Joy of Proper Encapsulation](https://dfa1.github.io/articles/the-joy-of-proper-encapsulation) — warnings worth listening to (rules 6, 7)
- [Java + RocksDB − JNI](https://dfa1.github.io/articles/java-plus-rocksdb-minus-jni) — the dependency removed with FFM (rule 2)

## Closing

This is what I've written down. None of it is original — most of it was earned the slow way, by being wrong about it first.

The list is not finished. It will not be next year either. That's the point.

---

[^kamina]: Adapted from [18 Subtle Rules of Software Engineering](https://kaminagroup.com/content/69/18-subtle-rules-of-software-engineering/), filtered through what I've actually had to write down.

[^spolsky]: Joel Spolsky, [*Things You Should Never Do, Part I*](https://www.joelonsoftware.com/2000/04/06/things-you-should-never-do-part-i/) (2000).

[^robertson]: Seth Robertson, [*Commit Often, Perfect Later, Publish Once — Git Best Practices*](https://sethrobertson.github.io/GitBestPractices/).
