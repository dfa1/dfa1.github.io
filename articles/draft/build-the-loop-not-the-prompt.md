# Build the Loop, Not the Prompt

*27 June 2026*

*I built three small native-adjacent Java libraries —
[rocksdbffm](https://github.com/dfa1/rocksdbffm) (a RocksDB wrapper),
[vortex-java](https://github.com/dfa1/vortex-java) (a pure-Java reader for the Vortex
columnar format), and [zstd-java](https://github.com/dfa1/zstd-java) (Zstandard bindings).
Most of the code was written by an AI agent. The lesson that stuck wasn't about the model:
an agent is only as good as the harness around it — a safe language to write in, and a tight
loop of tools to catch it when it's wrong.*

---

<!-- Working title above. Alternatives: "Give the Agent a Harness" · "Let the Tools Correct the Agent" -->

## The thesis

AI coding is not "use a better model." It's "build a feedback loop the model can close by
itself."[^validation] The agent proposes; something else has to say whether the proposal is
wrong — cheaply, automatically, before a human looks.

```
        ┌─────────────┐   proposes change   ┌──────────────────────────────────┐
        │  AI agent   │ ──────────────────► │  harness                         │
        │             │                     │  language · tests · PIT · Sonar  │
        │  fixes it   │ ◄────────────────── │  cross-impl oracle · ADR         │
        └─────────────┘   readable failure  └──────────────────────────────────┘
                 ▲                                        │
                 └─────────── human steers ◄──────────────┘
```

This isn't only my framing. Anthropic describes agents this way from the start — LLMs using
tools on environmental feedback in a loop, gaining "ground truth" from tool calls and code
execution at each step.[^agents] The whole article below is about engineering that ground
truth: making the environment answer "is this wrong?" honestly and cheaply.

Two things made the loop work across all three projects: **a safe language** (fewer ways for
generated code to be silently catastrophic) and **a harness of tools** (each turning a class
of "looks fine, is wrong" into an automatic failure). The projects are the evidence, not the
subject.

## The three projects

| | What | Distinct angle |
|---|---|---|
| **[rocksdbffm](https://github.com/dfa1/rocksdbffm)** | [RocksDB](https://rocksdb.org/)'s stable [C API](https://github.com/facebook/rocksdb/blob/main/include/rocksdb/c.h) via [FFM](https://openjdk.org/jeps/454), not JNI | Type-safe: `ReadWriteDB` vs `ReadOnlyDB`, so `put` on a read-only handle won't compile. [Earlier post](https://dfa1.github.io/articles/java-plus-rocksdb-minus-jni). |
| **[vortex-java](https://github.com/dfa1/vortex-java)** | The [Vortex](https://github.com/spiraldb/vortex) columnar format, *no native library at all* | Pure Java, memory-mapped, zero-copy. Runs on Windows (the [Rust bindings](https://github.com/spiraldb/vortex) don't). |
| **[zstd-java](https://github.com/dfa1/zstd-java)** | [zstd](https://github.com/facebook/zstd)'s C library via FFM | Native [dictionary compression](https://github.com/facebook/zstd#the-case-for-small-data-compression), zero-copy `MemorySegment` API, hermetic [Zig](https://ziglang.org/) build for 6 OS/arch combos. |

Two bind a native library; one reimplements a format. All three converge on the *same*
`MemorySegment`/`Arena` core — FFM isn't a "JNI replacement," it's one memory model covering
native calls and mapped files alike.

## Half one: a safe language to generate

The old way to do off-heap or native interop in Java was `sun.misc.Unsafe`, hand-rolled JNI,
and `ByteBuffer` tricks. All three projects refuse it. The shared toolkit:

- `MemorySegment` — typed, bounds-checked handle to off-heap or mapped memory.
- `Arena` — deterministic lifetime: close it, free the segments. No `Cleaner` guesswork.
- `Linker`/`downcallHandle` — call C directly, no glue `.so`.

Why it matters *for generated code*: with `Unsafe`, a wrong offset is silent memory
corruption — the worst thing to hand an agent, because nothing complains.

```java
// Unsafe: a bad offset corrupts memory and limps on
long v = unsafe.getLong(base + offset);
// MemorySegment: a bad offset throws, with a stack trace the agent can read and fix
long v = segment.get(JAVA_LONG, offset);   // IndexOutOfBoundsException if out of range
```

The language doing the catching is the cheapest tool in the harness. JDK 25 baseline, no
shims, `--enable-native-access` where downcalls happen.

## Half two: the harness that closes the loop

Each tool converts one category of "looks fine, is wrong" into a readable failure:

| Tool | Catches | Agent can't fake it because |
|---|---|---|
| Integration tests | binding that compiles but segfaults / returns garbage | native call actually runs |
| Mutation testing (PIT) | assertion-free tests; dead code | mutants must be killed, not just covered |
| Sonar | security hotspots, leaks, null, concurrency | external ruleset |
| Cross-impl oracle | bytes/behavior that disagree with the reference | a second, independent implementation |

**Integration tests are the self-correction signal.** Unit tests catch the local mistake;
integration tests catch the binding that crashes at runtime. For FFM this is non-negotiable —
a wrong `FunctionDescriptor` type-checks fine and crashes when called (map `size_t` to
`JAVA_INT` instead of `JAVA_LONG`: green unit tests, a corrupt round-trip on a large buffer).
No suite means the human *is* the test runner.

**Mutation testing keeps the tests honest — and finds real bugs.** PIT flips a condition or
drops a line; if no test fails, the test was theater. The headline payoff in vortex-java was a
shippable bug ([`473256b1`](https://github.com/dfa1/vortex-java/commit/473256b1f745f90592aad305811d41752a2f128d)).
The format can store a column with few distinct values as a *dictionary* — the values once,
plus compact codes pointing at them. The writer happily dictionary-encoded small-integer
columns; the reader's fast-path decoder only knew how to unpack the wider numeric types. So a
small-integer column produced a file the reader then refused to open — *"unsupported type for
lazy dict"* — one you could write but never read back.

But a survivor has *three* meanings, and only one is "add a test." vortex-java's `CLAUDE.md`
says it directly: *"read survivors as a simplify-first signal, not only a test-gap signal."*

| Survivor means | Do | Example |
|---|---|---|
| Real boundary case | add a test | the dictionary bug; column-statistics gaps (`a60f9502`, `474beabf`) |
| Dead clause | **delete it** | `36328285`: redundant `offset > fileSize` check, mutations 110→106 |
| Interchangeable heuristic | leave it | two encoders, equally valid output (#174) |

The deletion is the instructive case. Mutating `offset > fileSize` never changed an outcome:
once `length >= 0`, an offset past `fileSize` makes `fileSize - offset` negative, so the
existing `length > fileSize - offset` clause already fires. The clause was unreachable;
removing it *eliminated* four dead mutants instead of papering over them with unkillable
tests. An agent's reflex is to assert on every survivor — which grows unkillable tests around
dead code and freezes heuristics into false contracts. (That's why #173/#174 are coverage
hardening, not bug fixes: the dictionary bug was the exception that paid for the loop.)

**Cross-implementation interop is the oracle the agent can't fake.** A test the agent wrote,
judged by the agent's own code, is a closed circle — both sides can be wrong together. A
second implementation breaks it:

```
   vortex-java  ──writes──►  file  ──reads──►  vortex-rust     ┐
   vortex-rust  ──writes──►  file  ──reads──►  vortex-java     ┘ divergence = bug on one side
```

This is what actually nailed the dictionary bug: it round-tripped fine *within Java*; only the
Rust cross-check exposed the writer as the outlier. `@ParameterizedTest` scales it cheaply —
one body, every data type, column size, and encoding as parameters, run both directions: a
combinatorial space no hand-written cases would cover. zstd-java uses the same idea with a
different oracle — the upstream **zstd golden corpus**, a set of known-good compressed files
shipped by the project: decompress one and you must get the documented bytes back.
[Verify the corpus is wired; cite the test.]

**Write the threat model down, then let the agent execute it.** vortex-java's `TODO.md` states
a hard contract: *the reader parses untrusted binary input; every malformed file must fail with
one clean `VortexException` — never a raw out-of-bounds, out-of-memory, or stack-overflow
crash.* Under it, a checklist of hostile inputs to defend against — string offsets that run
backward or past the buffer, dictionary codes pointing outside the value table, bit-widths out
of range. The spec became a wall of executed commits — `cap array-node recursion depth`,
`validate segment specs`, `sanitize HTTP Content-Range`. A precise contract plus a checklist is
exactly what an agent grinds through best; "make it secure" is not.

**Sonar is the cheap broad sweep.** Static analysis finds security hotspots, leaks, and dodgy
concurrency far cheaper than tests or review, and the agent acts on each finding directly.
Pairs with [the compiler is part of your security
team](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team):
push detection left and automate it.

## Feed the agent ground truth, not room to guess

Many failures weren't bad reasoning — they were the agent *inventing* a fact it should have
looked up. vortex-java's `CLAUDE.md` is blunt: *"Never reverse-engineer wire formats by
probing bytes. Read the [...] Rust source for the exact schema, then implement from spec"* —
and hands over the access path (`gh api repos/spiraldb/vortex/contents/<path>`).
A format inferred from examples is right until the one example you didn't have; a format read
from the spec is just right. Same split as the oracle: Rust source is ground truth for
*behavior*, the Rust binary for *bytes*.

## Encode what you know as rules the agent can't skip

Guardrails also front-load hard-won knowledge so the mistake never happens.

**Turn a past regression into a tripwire.** vortex-java's `CLAUDE.md` bans per-element modulo
in hot loops — it blocks C2 auto-vectorization — and cites the regression chain
(`ed658b7`→`051a794`→`442021f`, a 5–10× slowdown) plus the fix (branch-split: hoist the check
once, gate two specialized loops). The agent can't *know* a single `i % cap` in a million-row
body tanks throughput; written down, it's a constraint generated code is checked against.

**Codify recurring work as skills.** Repeating tasks became `.claude/skills/` —
`improve-performance`, `review-performance`, `proto-compat-audit`, `release`: the workflow as a
versioned command, not a re-explanation each session. Same instinct as `CLAUDE.md`, one level
more granular — procedures, not facts.

**Record the *why* as ADRs.** vortex-java carries 18 numbered Architecture Decision Records,
and `CLAUDE.md` cites them as authority (*"ADR 0017"* for the in-house FlatBuffers codegen,
*"ADR 0003 Phase E"* for the exception-sanitization work). They're the antidote to an agent's
worst habit: relitigating a settled decision because nothing on disk says it was settled. An
ADR records the decision *and the reasoning* — so the agent reads "we hand-rolled the codegen
to drop the `flatc`/protobuf runtime dependency" instead of helpfully proposing to add it back.
This is [Write Down The Why](https://dfa1.github.io/articles/write-down-the-why) with a new
beneficiary: the *why* now also onboards the agent, every session, for free.

## Where the human stayed

Both newer READMEs draw the same line — Claude Code implements, humans own architecture:
*"all decisions are human-driven"* (vortex-java), *"Claude Code for implementation — C header
mapping, test generation, docs"* (zstd-java).

The mechanical binding work — allocate arena, copy in, invoke, check error pointer, free — is
boring, repetitive, auditable: ideal for an agent. What stayed human: the type-safe API shapes,
the "should this exist at all" calls, the benchmark design, and *choosing the harness itself*.
The agent runs inside the loop; deciding what the loop is remains the engineer's job. Builds on
[Coding with Claude Code](https://dfa1.github.io/articles/coding-with-claude-code). [Add what's
new across three repos: did the harness transfer between projects?]

## Lessons

- AI coding is loop-building, not prompt-writing — the model is one tool in a harness.
- A safe language is the first tool: `MemorySegment` turns silent corruption into a readable
  exception. "No Unsafe" is engineering, not branding.
- Integration tests are mandatory for FFM: wrong types pass compilation, fail at runtime.
- A mutation survivor has three responses, one of which is "add a test": edge → test; dead
  clause → delete (`36328285`); equivalent heuristic → leave it. An agent that only knows "add
  a test" grows unkillable tests around dead code.
- An independent implementation is the oracle the agent can't fake; `@ParameterizedTest` scales
  it across the whole matrix for the cost of one test body.
- Feed ground truth (read the spec) and encode hard-won rules (the regression tripwire) so the
  agent never makes the mistake at all.
- ADRs record the *why* so the agent doesn't relitigate settled decisions —
  [Write Down The Why](https://dfa1.github.io/articles/write-down-the-why), now also onboarding
  the agent every session.

[^validation]: The same argument at team scale: Michael Webster (CircleCI),
    [*AI Works, Pull Requests Don't*](https://www.infoq.com/presentations/ai-sdlc-pull-request/).
    AI now writes code far faster than humans can review it (his figure: ~1,500 lines in 30
    minutes against ~500 lines/hour of review), so the highest-leverage investment is
    validation infrastructure — testing, test-impact analysis, automated gates — not prompt
    engineering. The unguarded version shows up as "persistent technical debt accumulation": a
    short velocity boost, then regression to baseline. Same thesis as this article, one altitude
    up — the org's CI pipeline instead of one developer's test suite.
[^agents]: Anthropic, [*Building Effective Agents*](https://www.anthropic.com/research/building-effective-agents)
    (December 2024): *"agents are typically just LLMs using tools based on environmental feedback
    in a loop"*; *"it's crucial for the agents to gain 'ground truth' from the environment at
    each step (such as tool call results or code execution)."*
