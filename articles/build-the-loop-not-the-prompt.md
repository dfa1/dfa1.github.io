# Build the Loop, Not the Prompt

*27 June 2026*

*Recently I built three small Java libraries using JDK 25 and the
[Foreign Function & Memory API](https://openjdk.org/jeps/454):
[rocksdbffm](https://github.com/dfa1/rocksdbffm),
[vortex-java](https://github.com/dfa1/vortex-java), and
[zstd-java](https://github.com/dfa1/zstd-java).
Most of the code was written by an AI agent. The lesson that stuck wasn't about the model:
an agent is only as good as the harness[^harness] around it — a safe language to write in, and
a tight loop of tools to catch it when it's wrong.*

---

## The thesis

AI coding is not "use a better model to create an app with a single prompt." It's "build a
feedback loop the model can close by itself."[^validation] The agent proposes; something else has to say whether the proposal is
wrong — cheaply, automatically, before a human looks.

Everyone already runs a version of this loop by hand:

```
                                                     does it meet the
                                                    required standard?
                                                            ╱╲
        ┌──────────┐            ┌──────────┐               ╱  ╲
        │  Prompt  │ ─────────► │  Output  │ ────────────►╱    ╲
        └──────────┘            └──────────┘              ╲    ╱
              ▲                                            ╲  ╱
              │                                             ╲╱
              │                                              │ no
              └───────────── provide feedback ◄──────────────┘
                             (very important)
```

Why not just write better prompts? Because a prompt is open-loop control: it improves the
average and can't limit the worst case. A better prompt raises the hit rate; the misses still
ship unless something catches them — and with generated code the failures that hurt are
exactly the ones that *look* fine. The model doesn't know it's wrong; only the environment
does.

The deeper reason is an asymmetry: checking is cheaper than generating. A prompt complete
enough to need no checking would have to predict every possible failure in advance, in prose. A
compiler, a mutation run, a cross-implementation round-trip check the same properties
mechanically, on every change, forever. And harness effort compounds where prompt effort
doesn't: a tuned prompt is spent on the one task it was written for; a fitness function keeps
paying every session. The harness even writes the prompts — a readable failure message is the
most precise instruction an agent ever gets.

To be fair, nothing about the loop itself is new. Compilers, CI, code review, the
bug → failing test → fix cycle — software engineering has always run on feedback loops;
[The Slow Fix](https://dfa1.github.io/articles/the-slow-fix) is two years of building exactly
this machinery with only humans in it. What the agent changes is the economics: it writes code
faster than anyone can read it, so a loop that used to be good practice becomes the
precondition. A team that already had tight automatic feedback loops for its humans is —
almost by accident — ready for agents. But this is only true when "tight feedback loops" means
something specific — not "we communicate well" or "we do good code reviews." The only loops
that matter are:

- automatic
- cheap
- repeatable
- unambiguous
- machine-readable

The emphasis on *provide feedback* is right — feedback is the part that matters. What doesn't
scale is who provides it: a human, reading every output, every round. The whole point is
replacing that step with machinery:

```
        ┌─────────────┐   proposes change   ┌──────────────────────────────────┐
        │  AI agent   │ ──────────────────► │  harness                         │
        │             │                     │  language, tests, mutation       │
        │  fixes it   │ ◄────────────────── │  cross-impl oracle, ADR, Sonar   │
        └─────────────┘   readable failure  └──────────────────────────────────┘
                 ▲                                        │
                 └─────────── human steers ◄──────────────┘
```

## The loop in practice

Two things made the loop work across all three projects: **a safe language** (fewer ways for
generated code to be silently catastrophic) and **a harness of tools** (each turning a class
of "looks fine, is wrong" into an automatic failure). The projects are the evidence, not the
subject. The rest of the article is about engineering the ground truth: making the
environment answer "is this wrong?" honestly and cheaply.

None of the specific tools is the point — they're what a safe language and an honest
environment happen to look like for native-memory Java. Your context will pick differently: a
web service has no C offsets to get wrong, but it has API contracts, database migrations, and
load behavior — each with its own way of looking fine and being wrong. The question that
transfers is: which kind of "looks fine, is wrong" does *my* code produce, and what turns it
into an automatic, readable failure?

## First half: a safe language to generate

All three libraries work with *native memory* — memory outside the Java heap, where the
garbage collector doesn't watch over you and a wrong address doesn't throw an exception, it
silently corrupts data. Two of them also call C functions directly. The old way to do this in
Java was `sun.misc.Unsafe`, hand-written JNI glue, and `ByteBuffer` tricks — tools that assume
you make no mistakes and stay silent when you do. All three projects avoid all of it and use
the FFM API instead:

- `MemorySegment` — a handle to native memory that knows its own size: every read and write is
  bounds-checked.
- `Arena` — ties memory to a scope: close the arena and everything allocated in it is freed,
  at a moment you choose — not whenever the garbage collector gets around to it.
- `Linker` — calls a C function directly from Java, with no hand-written C glue to compile,
  ship, and load.

Why it matters *for generated code*: with `Unsafe`, a wrong offset either takes down the
whole JVM — a native crash dump, not a stack trace — or, worse, silently corrupts memory.
Neither failure is one the agent can read and fix.

```java
// Unsafe: a bad offset crashes the JVM or silently corrupts memory
long v = unsafe.getLong(base + offset);
// MemorySegment: a bad offset throws, with a stack trace the agent can read and fix
long v = segment.get(JAVA_LONG, offset);   // IndexOutOfBoundsException if out of range
```

The language doing the catching is the cheapest tool in the harness.

## Second half: the harness that closes the loop

Each tool converts one category of "looks fine, is wrong" into a failure the agent can read
and fix on its own:

| Tool | Catches | Agent can't fake it because |
|---|---|---|
| Integration tests | binding that compiles but segfaults / returns garbage | native call actually runs |
| Mutation testing (PIT) | assertion-free tests; dead code | mutants must be killed, not just covered |
| Sonar | security hotspots, leaks, null, concurrency | external ruleset |
| Cross-impl oracle | bytes/behavior that disagree with the reference | a second, independent implementation |
| Docs fitness functions | documentation that drifted from the code | FQNs, links, and tables asserted against the codebase |

**Integration tests are the self-correction signal.** Unit tests catch the local mistake;
integration tests catch the binding that crashes at runtime. For FFM this is non-negotiable —
a wrong `FunctionDescriptor` type-checks fine and crashes when called (map `size_t` to
`JAVA_INT` instead of `JAVA_LONG`: green unit tests, a corrupt round-trip on a large buffer).

**Mutation testing keeps the tests honest — and finds real bugs.** [PIT](https://pitest.org/)
flips a condition or drops a line; if no test fails, the test was just for show. The biggest payoff in vortex-java
was [a shippable bug](https://github.com/dfa1/vortex-java/commit/473256b1f745f90592aad305811d41752a2f128d).
The format can store a column with few distinct values as a *dictionary* — the values once,
plus compact codes pointing at them. The writer happily dictionary-encoded small-integer
columns; the reader's fast-path decoder only knew how to unpack the wider numeric types. So a
small-integer column produced a file the reader then refused to open — *"unsupported type for
lazy dict"* — one you could write but never read back.

A mutant that no test kills — a *survivor* — has three meanings, and only one is "add a
test." vortex-java's `CLAUDE.md` says it directly: *"read survivors as a simplify-first
signal, not only a test-gap signal."*

| Survivor means | Do | Example |
|---|---|---|
| Real boundary case | add a test | the dictionary bug; gaps in column statistics |
| Dead clause | **delete it** | a redundant `offset > fileSize` check — four mutants gone |
| Interchangeable heuristic | leave it | two encoders, equally valid output |

The deletion is the instructive case. One bounds check turned out to be unreachable — every
input that could trip it was already rejected by an earlier check. Mutating it never changed
any outcome, so no test could ever kill those mutants. The fix was not a cleverer test; it was
deleting the clause: four dead mutants gone, along with the dead code. An agent's reflex is
the opposite — write a test for every survivor — which wraps dead code in tests that assert
nothing and turns arbitrary implementation choices into promises the code never made. (Most
survivors led to better tests rather than bug fixes: the dictionary bug was the exception that
paid for the loop.)

**Cross-implementation interop is the oracle the agent can't fake.** An *oracle*, in testing
jargon, is an independent source of the correct answer. A test the agent wrote, judged by the
agent's own code, is a closed circle — both sides can be wrong together. A second
implementation breaks it:

```
   vortex-java  ──writes──►  file  ──reads──►  vortex-rust     ┐
   vortex-rust  ──writes──►  file  ──reads──►  vortex-java     ┘ divergence = bug on one side
```

This is what actually caught several bugs, the dictionary bug included: files round-tripped
fine *within Java*; only the Rust cross-check surfaced them. `@ParameterizedTest` scales it cheaply —
one body, every data type, column size, and encoding as parameters, run both directions: a
combinatorial space no hand-written cases would cover (property-based testing pushes the same
idea further). zstd-java uses the same idea with a
different oracle — the upstream
[**zstd golden corpus**](https://github.com/dfa1/zstd-java/blob/main/integration-tests/src/test/java/io/github/dfa1/zstd/it/GoldenCorpusTest.java),
the same known-good compressed files the C project uses in its own regression suite:
decompress one and you must get the documented bytes back.

**Write the threat model down, then let the agent execute it.** vortex-java's `TODO.md` states
a hard contract: *the reader parses untrusted binary input; every malformed file must fail with
one clean `VortexException` — never a raw out-of-bounds, out-of-memory, or stack-overflow
crash.* Under it, a checklist of hostile inputs to defend against — string offsets that run
backward or past the buffer, dictionary codes pointing outside the value table, bit-widths out
of range. The checklist became a steady stream of commits — `cap array-node recursion depth`,
`validate segment specs`, `sanitize HTTP Content-Range`. A precise contract plus a checklist is
exactly what an agent works through best; "make it secure" is not.

**Sonar is the cheap broad sweep.** Static analysis finds security hotspots, leaks, and
questionable concurrency far cheaper than tests or review — and it flags large chunks of
copy-pasted code, a habit agents fall into easily. The agent acts on each finding
directly.
Pairs with [the compiler is part of your security
team](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team):
push detection left and automate it.

**Fitness functions keep the documentation honest.** Neal Ford's term[^ford] for an automated
check on an architectural characteristic — here, the characteristic is documentation accuracy.
The stakes are higher than a stale README: `CLAUDE.md`, the ADRs, and `docs/*.md` are what the
agent reads every session, so a claim that drifted from the code is bad ground truth delivered
automatically. vortex-java
[gives the docs a compiler](https://github.com/dfa1/vortex-java/commit/08b6cd59c44f8306aa985a17289f4a0b7134adee):
every project FQN in the living docs must resolve against the codebase, every
`META-INF/services` mention must name a real file, relative links must not dangle, and every
backticked `Class.method(...)` claim must name a real method. The first run caught 11 fossils
that a manual audit had missed the same day — imports from a pre-refactor package layout,
`Registry.*` claims from two renames ago, an API that no longer existed. A
[second fitness function](https://github.com/dfa1/vortex-java/commit/a1505349fcc4a60b2914247a4636e85ee4028718)
guards completeness where the first guards accuracy: the encodings table in
`docs/compatibility.md` must list exactly the encodings the code registers via `ServiceLoader`.
Both assert and never generate — on drift they print the exact rows to add or fix, a readable
failure the agent closes like any other. Argue once, then let the build remember — the same
move as [Earn Your Rules](https://dfa1.github.io/articles/earn-your-rules#rule-3).

## Where the human stayed

This builds on [Coding with Claude Code](https://dfa1.github.io/articles/coding-with-claude-code).
The mechanical binding work — allocate arena, copy in, invoke, check error pointer, free — is
boring, repetitive, auditable: ideal for an agent. What stayed human: the type-safe API shapes,
the "should this exist at all" decisions, the benchmark design, and *choosing the harness
itself*.
The agent runs inside the loop; deciding what the loop is remains the engineer's job.

## Lessons

These were earned on these three projects; adapt them to yours:

- AI coding is loop-building, not prompt-writing; the loop predates the agent —
  agents change the economics, not the principle.
- Get an oracle the agent can't fake: a second implementation, or the upstream
  project's golden files.
- For FFM, integration tests are mandatory: wrong native types compile fine and
  fail at runtime.
- Read mutation survivors three ways: edge case → test, dead clause → delete,
  equivalent heuristic → leave it.
- A precise contract plus a checklist is what an agent works through best;
  "make it secure" or "make it fast" is not.
- Feed the agent ground truth — the spec, the source — instead of room to guess: a format
  inferred from examples is right until the one example you didn't have.[^groundtruth]
- Docs are part of the harness: fitness functions keep `CLAUDE.md`, ADRs, and
  `docs/*.md` true.
- The agent runs inside the loop; choosing the loop is the engineer's job.

This is the future of the software engineer: you no longer write the mechanical boilerplate;
your job is to design the architecture, write the threat models, and build the unforgiving
feedback loops that keep the agent on track.

---

[^harness]: A *harness* is the set of straps that lets you control a horse and put it to work.
    Software borrowed the word for the rig around code under test — the *test harness* — and AI
    tooling extended it to everything wrapped around a model: tools, checks, permissions, the
    loop itself. Both original senses apply here: it constrains the agent, and it puts it to work.

[^validation]: Michael Webster (CircleCI), [*AI Works, Pull Requests Don't*](https://www.infoq.com/presentations/ai-sdlc-pull-request/) — the same argument at team scale.

[^ford]: Neal Ford, Rebecca Parsons, Patrick Kua, [*Building Evolutionary Architectures*](https://www.oreilly.com/library/view/building-evolutionary-architectures-2nd/9781492097532/) (O'Reilly, 2nd ed. 2022).

[^groundtruth]: Many failures weren't bad reasoning — they were the agent *inventing* a fact
    it should have looked up. vortex-java's `CLAUDE.md` is blunt: *"Never reverse-engineer wire
    formats by probing bytes. Read the [...] Rust source for the exact schema, then implement
    from spec"* — and hands over the access path
    (`gh api repos/spiraldb/vortex/contents/<path>`). Same split as the oracle: the Rust source
    is ground truth for *behavior*, the Rust binary for *bytes*.
