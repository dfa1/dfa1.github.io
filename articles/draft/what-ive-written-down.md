# What I've Written Down

*9 May 2026*

*Once in a while, someone shares an "X rules of software engineering" list and I find myself agreeing with about a third of it. The rest is either too generic to act on, or too specific to someone else's context to be useful in mine. This is my pass through one such list[^kamina] — keeping what I've actually had to write down somewhere, dropping what I haven't, and rewriting the rest in language I'd defend in a code review.*

*The principle behind the list is the same as [Write Down the Why](https://dfa1.github.io/articles/write-down-the-why): a rule without a reason is a checklist. A rule with a reason is guidance.*

## The Rules

### 0. You will pay for complexity on-call.

The bill always comes. Daily outages from a decade of stacked frameworks, OOM errors from unbounded accumulation, untraceable failures from reflection-heavy business logic — every shortcut a previous version of the team took, the next version paid back at 2 a.m. *([The Slow Fix](https://dfa1.github.io/articles/the-slow-fix))*

### 1. Be willing to delete what you wrote last quarter.

70,000 lines of code removed while adding three years of features. Lambdaj, Drools, jBPM, custom logging wrappers, hand-rolled JS minification — all gone. Code you wrote is not sacred. *([The Slow Fix](https://dfa1.github.io/articles/the-slow-fix))*

### 2. Every decision is a trade-off. Document the alternatives you rejected.

The Hazelcast cluster was a deliberate reversal of our complexity-reduction principle, taken because the alternative — coordinating locks through the database — was worse. That kind of reasoning needs to be in writing for the future joiner who reads the architecture and wonders why. *([Make the Implicit Explicit](https://dfa1.github.io/articles/make-the-implicit-explicit))*

### 3. Every line is a liability.

The codebase that shrinks while gaining features isn't an accident. Every line is something that has to be maintained, secured, debugged, and explained to the next person. The best code is the code that doesn't exist. *([The Slow Fix](https://dfa1.github.io/articles/the-slow-fix))*

### 4. Write down the *why*. The *what* is in the code already.

Every rule, every design decision, every constraint should carry a *because*. Not "use fixed snapshots" but "use fixed snapshots *because* tests that fail for external reasons erode trust." A rule with a reason can be reasoned about; a rule without one gets cargo-culted or ignored. *([Write Down the Why](https://dfa1.github.io/articles/write-down-the-why))*

### 5. Treat unfamiliar code as a system to understand, not an enemy to rewrite.

The full rewrite is the single worst strategic mistake a team can make[^spolsky]. The old system contains years of accumulated domain knowledge — bugs that turned into features, edge cases silently handled, compensations for upstream failures. Throw it away and you won't know what you've lost until production tells you. *([The Slow Fix](https://dfa1.github.io/articles/the-slow-fix))*

### 6. Justify every dependency. Removing one is also engineering.

Drools pulled Eclipse JDT, ANTLR, ASM, protobuf, xstream, and half a dozen `commons-*` libraries — to evaluate three trivial business rules. JNI brought five layers of build tooling for what's now a `--enable-native-access` flag. Every dependency is a permanent commitment to someone else's release schedule, security posture, and design choices. *([The Slow Fix](https://dfa1.github.io/articles/the-slow-fix), [Java + RocksDB − JNI](https://dfa1.github.io/articles/java-plus-rocksdb-minus-jni))*

### 7. Coding standards exist to argue once, not every PR.

Standards don't prevent arguments — they move the argument up one level. Argue once about the rule, then stop arguing in every PR about taste. Same pattern as Write Down the Why, at a smaller scale: a rule with a reason outlasts the meeting where it was decided. *([Write Down the Why](https://dfa1.github.io/articles/write-down-the-why))*

### 8. Commit messages are documentation. Future-you is the audience.

JIRA reference, brief summary, then the *why* — what was the problem, what was the solution, what trade-offs were made. "Fix" and "Updates..." are noise that you'll regret in two years when `git blame` is your only witness. *([Write Down the Why](https://dfa1.github.io/articles/write-down-the-why))*

### 9. Never stop learning.

I wrote a BPF packet sniffer in 2004. I'm reading eBPF kernel code in 2026. Tools change, fundamentals compound. *([From BPF to eBPF, Twenty Years Later](https://dfa1.github.io/articles/from-bpf-to-ebpf-twenty-years-later))*

### 10. Move complexity from runtime into code, where it can be read.

Versioned endpoints, isolated DTOs, decorator stacks — they look like more moving parts. Operationally they are simpler, because the complexity is now visible: read it, test it, reason about it. The opposite — implicit assumptions at runtime — is what produces 3 a.m. pages. The Java module system does the same thing: it makes the wrong thing look wrong, in the structure of the build itself. *([Make the Implicit Explicit](https://dfa1.github.io/articles/make-the-implicit-explicit), [The Joy of Proper Encapsulation](https://dfa1.github.io/articles/the-joy-of-proper-encapsulation))*

### 11. Fix root causes. Symptoms come back.

The data warehouse job dying weekly was a symptom; the real cause was Hibernate plus reflection plus connection pooling. Empty `catch` blocks were a symptom; the real cause was a culture that didn't want to look at exceptions. Compensating logic accumulates fast when nobody asks why. And sometimes the fastest path to a wrong fix is silencing the warning instead of listening to it. *([The Slow Fix](https://dfa1.github.io/articles/the-slow-fix), [The Joy of Proper Encapsulation](https://dfa1.github.io/articles/the-joy-of-proper-encapsulation))*

### 12. Software is never finished — and that's a feature.

BPF was finished in 1992. eBPF in 2014. The kernel observability story is still being written. Software that stops evolving is software whose context has stopped evolving — which usually means it's already irrelevant. *([From BPF to eBPF, Twenty Years Later](https://dfa1.github.io/articles/from-bpf-to-ebpf-twenty-years-later))*

### 13. Ship small, ship often. If merging hurts, do it more often.

Trunk-based development, small PRs, feature flags. Quarterly releases became weekly, then several per week. The pain of merging *decreases* as merges become smaller and more frequent. The same goes for everything else that hurts: deployments, refactorings, conversations. *([Write Down the Why](https://dfa1.github.io/articles/write-down-the-why), [The Slow Fix](https://dfa1.github.io/articles/the-slow-fix))*

### 14. Simplicity follows complexity, not the other way around.

> *Simplicity does not precede complexity, but follows it.* — Alan Perlis

You don't get to a simple system by demanding simplicity at the start. You get there by living through the complexity, understanding it, and having the patience to remove what doesn't earn its place.

## ► If you're a Junior Engineer right now

- Ask questions fearlessly — and ask them in writing where others can read the answer.
- Take ownership before you feel ready. The confidence comes after, not before. *([The Slow Fix](https://dfa1.github.io/articles/the-slow-fix))*
- Read more code than you write. The codebase you inherit is a body of decisions; learn to read them before you challenge them. *([The Slow Fix](https://dfa1.github.io/articles/the-slow-fix))*
- Don't wait for permission to learn a new system. Curiosity beats credentials.

## ► If you're a Senior Engineer right now

- Teach the *why*, not the *what*. A new engineer who knows the reasoning can handle cases the document never anticipated. *([Write Down the Why](https://dfa1.github.io/articles/write-down-the-why))*
- Lead by example, not by edict. The team copies what you do, not what you say.
- Use code review to spread context, not to gatekeep. Ask Socratic questions; let the author find the answer.
- Listen before you correct. Sometimes the team's "wrong" approach is solving a constraint you don't know about.
- Mentorship is how impact compounds. The team I left behind in [The Slow Fix](https://dfa1.github.io/articles/the-slow-fix) was the work I'm proudest of — not the code.

## ► If you're leading work right now

**On context**

- Write things down where the next person finds them: in the repo, not the wiki. *([The Slow Fix](https://dfa1.github.io/articles/the-slow-fix))*
- The onboarding doc is now also the agent's prompt. If you never wrote down the why, you can't align humans or machines either. *([Write Down the Why](https://dfa1.github.io/articles/write-down-the-why), 2026 update)*
- Code review is how context spreads. Treat it as teaching, not gatekeeping.

**On estimates**

- Estimates are probability distributions, not dates. Communicate ranges, and the assumptions underneath them.
- Re-estimate when reality changes. Anchoring on the first guess is dishonest, not professional.
- The right answer to "when?" is often "what do you mean by *done*?"

**On roadmap**

- Sequence by what unlocks the next move, not by what hurts most. Tactical work has visible payoff; strategic work looks like overhead until it doesn't. *([The Slow Fix — Tactical and Strategic](https://dfa1.github.io/articles/the-slow-fix))*
- The roadmap is a hypothesis. Defend the *why*, not the dates.
- Don't commit to a target whose preconditions aren't in place yet — the deferred Postgres migration in *The Slow Fix* is the canonical example.

## Closing

This is what I've written down. None of it is original — most of it was earned the slow way, by being wrong about it first.

The list is not finished. It will not be next year either. That's the point.

---

[^kamina]: Adapted from [18 Subtle Rules of Software Engineering](https://kaminagroup.com/content/69/18-subtle-rules-of-software-engineering/), filtered through what I've actually had to write down. I dropped four of the original rules, kept fourteen, and rewrote most of the kept ones.

[^spolsky]: Joel Spolsky, [*Things You Should Never Do, Part I*](https://www.joelonsoftware.com/2000/04/06/things-you-should-never-do-part-i/) (2000).
