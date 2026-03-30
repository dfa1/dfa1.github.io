---
title: "Coding with Claude Code: A Week on hosh"
author: Davide Angelocola
date: 2026-03-29
---

# Coding with Claude Code: A Week on hosh

*29 March 2026*

I've been experimenting with [Claude Code](https://code.claude.com) on my side
project [hosh](https://github.com/hosh-shell/hosh), an experimental shell
written in Java. After a few days of pairing with an AI agent on real tasks —
adding features, fixing bugs, resolving SonarQube warnings — here's what I
found.

## The experiment

The idea was simple: take a mature codebase (~1,500 commits, `JDK11`, `Maven`,
junit, archunit, >90% test coverage) and see how productive I could be with an
AI coding agent. Not on a greenfield toy project, but on something with real
constraints — a module system, a [SPI](https://en.wikipedia.org/wiki/Service_provider_interface) and some opinions about code style.

I started by throwing tasks at Claude Code the way you'd throw them at a new
team member: 
- "add a new small feature";
- "consolidate the usage of this helper class"
- "fix this SonarQube warning about thread-safety";
- "upgrade Mockito to work with JDK 25".

The results were mixed. Some changes were good. Others were technically correct
but stylistically wrong — the kind of code that passes CI but fails code review.

## The need for guardrails

The turning point was recognising that an AI agent without context is like *an
engineer without onboarding*. It will produce *something*, but not necessarily
what you want. And every wrong turn costs tokens.

So I wrote a `CLAUDE.md` file. This is a project-level document that Claude Code
reads at the start of each session. Mine covers the build commands, the
architecture (SPI, runtime, modules), the testing philosophy (TDD, no mocks of
types you don't own), the code style (boring code over clever code, explicit
over implicit), and the things that are off-limits.

The effect was immediate. With guardrails in place, Claude stopped reinventing
decisions that were already made. It respected the module boundaries. It wrote
tests first. It stopped suggesting Gradle or Kotlin.

This is the part that surprised me: the `CLAUDE.md` is not just documentation
for the agent. It forced me to articulate things I had only kept in my head —
project conventions, architectural invariants, the reasoning behind certain
trade-offs. Writing guardrails for an AI turned out to be a useful exercise in
making implicit knowledge explicit. **Writing is thinking made visible.**

## The fast loop

What makes Claude Code (or any other coding agent) genuinely useful is the
feedback loop speed. The cycle is: describe a task, review the proposed changes,
accept or reject, refine. Each iteration takes seconds, not hours. You can
experiment with approaches — "try it with an `AtomicReference`", "now try it
with an immutable snapshot" — and compare the results almost instantly. Design
options are cheaper to explore.

But the loop only works well if you stay in it. The moment I tried to delegate a
larger task and walk away, the quality dropped. Not because the agent isn't
capable, but because the accumulated micro-decisions in a mature codebase
require judgment that no amount of context can fully replace.

The best sessions were the ones where I acted as a director: setting the goal,
reviewing each step, steering corrections early. The worst were the ones where I
tried to use Claude Code as an autonomous worker.

## Human in the loop, not human out of the loop

This maps surprisingly well to what Anthropic themselves have found. Their
research on agent autonomy shows that experienced users shift from approving
each individual action to a monitoring-and-intervening approach — but they also
interrupt *more* often, not less. Trust grows, but so does the skill of knowing
*when* to intervene (see [Anthropic's research on agent
autonomy](https://www.anthropic.com/research/measuring-agent-autonomy)).

There's a deeper question here: does the "human as director" model resonate with
how AI tools should work? I think it does. The most productive pattern I found
was collaboration — not delegation. The agent handles the mechanical work
(boilerplate, test scaffolding, refactoring), while the human handles the
architectural judgment, the "should we even do this?" questions, and the quality
bar.

This isn't a limitation. It's the point. A fully autonomous agent that produces
code I have to review anyway doesn't save me time — it shifts the work from
writing to reading, which is arguably harder. An agent that works *with* me,
proposing changes I can steer in real time, actually makes me faster.

## What I learned

A few concrete takeaways:

**Invest in `CLAUDE.md` early.** It pays for itself in the first session. Think
of it as onboarding documentation — for an engineer who forgets everything
between sessions.

**Keep the loop tight.** Small, focused tasks with immediate review produce
better results than large, vague ones. This is true for human engineers too, but
with AI agents the effect is amplified.

**The agent is a multiplier, not a replacement.** It multiplies whatever
direction you give it. Good direction, good output. No direction, plausible but
wrong output.

**Token cost is a design constraint.** Every unnecessary file read, every wrong
turn that needs correction, every overly broad context window costs real money.
Guardrails aren't just about quality — they're about efficiency.

The hype around AI coding tends to oscillate between "it will replace
developers" and "it's just autocomplete". The reality, at least for now, is more
interesting than either: it's a new kind of collaboration that rewards the same
skills good engineering always has — clarity of thought, explicit constraints,
and knowing when to trust your tools and when to override them.

## Outcome

- `JDK11` → `JDK25` - upgraded all other dependencies to 2026
- fully modular classpath with [JPMS](https://en.wikipedia.org/wiki/Java_Platform_Module_System)
- enforced *zero-warnings* policy
- code base is now more friendly to humans
- better [Domain Primitives](https://www.oreilly.com/library/view/secure-by-design/9781617294358/Text/c05.xhtml)
- many new built-in commands were written from scratch by Claude (**checksum**,
**from-csv**, **to-csv**, etc)
- a couple of nasty race conditions were fixed too

---

*The [hosh](https://github.com/hosh-shell/hosh) source code, including the
`CLAUDE.md`, is available on GitHub.*

