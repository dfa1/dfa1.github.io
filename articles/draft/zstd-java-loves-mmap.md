# zstd-java <3 mmap

*20 July 2026*

*[zstd-java](https://github.com/dfa1/zstd-java) offers a zero-copy `MemorySegment`
API alongside the usual `byte[]` API, built on Java's Foreign Function & Memory
API. The pitch is that a native `MemorySegment` is already a stable pointer, so
compressing a memory-mapped file should skip a copy that the classic
`ByteBuffer`-based binding, `zstd-jni`, can't avoid. I wanted a number to back
that claim, not just an architecture diagram — what followed was a full
afternoon of measuring, reversing, and re-measuring, with an AI coding
assistant doing most of the typing.*

---

## The claim

A heap `byte[]` can move during a call into native code — the JVM's garbage
collector is free to relocate it — so any JNI or FFM call has to either pin it
or copy it into native memory first. A `MemorySegment` backed by native memory
doesn't have that problem: it already *is* a stable address, so a zero-copy
call can hand the pointer straight to `libzstd`.

There's a second, sharper claim tied to the same API. `java.nio.ByteBuffer` —
what `zstd-jni` is built on — is `int`-indexed. `FileChannel.map(mode, pos,
size)` throws `IllegalArgumentException` for any `size` above
`Integer.MAX_VALUE` bytes, roughly 2 GiB. The newer overload,
`FileChannel.map(mode, pos, size, Arena)`[^map], returns a `long`-indexed
`MemorySegment` and has no such cap.

Two different claims, worth keeping separate from the start: *capability*
(can you address a mapping the old API can't even open) and *throughput* (is
the zero-copy path actually faster). The rest of this is what happened when I
tried to measure the second one and it didn't behave.

## Proving the capability

First step was the boring one: prove the cap is real and prove the FFM path
clears it. A test generates a file just over 2 GiB (~2.25 GiB, deterministic
content so it can be verified byte-for-byte without holding a second copy in
memory), shows the classic `FileChannel.map()` throwing
`IllegalArgumentException`, then shows the `MemorySegment` overload mapping
the whole file in one call, addressing bytes past the `Integer.MAX_VALUE`
boundary correctly, and round-tripping it through zstd-java's zero-copy
streaming compressor.

That's now a committed integration test, disabled by default —
`@EnabledIfSystemProperty` — because it writes multi-gigabyte files to disk
and nobody wants that running on every `mvn test`. The capability claim is
settled: it's not a footnote, it's a real ceiling that only the FFM API
clears.

## The first performance question, and a quiet reversal

The natural next question: is the zero-copy path also *faster*, not just
more capable? First measurement, local macOS (Apple Silicon, 10 cores, 32 GB
RAM), default zstd level 3, single-threaded, 10 repetitions each after
warmup:

| size | mmap | zstd-jni | winner |
|---|---|---|---|
| 2.25 GiB | ~285ms | ~352ms | mmap, ~19% faster |
| 10 GiB | ~2230ms | ~1535ms | zstd-jni, ~31% faster |

The win reversed as the file got bigger. Turning on multithreaded
compression (`NB_WORKERS=4`, matching the machine's four performance cores)
didn't fix it — at 10 GiB mmap dropped to ~1578ms, but zstd-jni dropped
further, to ~822ms, an even wider relative gap in zstd-jni's favor.

## The gut check

Before writing a single word about this being a "performance win," it's
worth asking directly: is it honest to call it that when the result flips
depending on file size? It isn't. The capability claim is unconditional —
mmap addresses files the `ByteBuffer` API structurally cannot. The
throughput claim is conditional, and at that point it was actively wrong at
the size (10 GiB) where it should matter most.

That's the split worth publishing: don't let an unconditional claim borrow
credibility from a conditional one just because they share an API.

## Taking it to real hardware

macOS on a laptop is one data point. Rather than speculate about
OS differences, I pushed a throwaway branch with a scratch GitHub Actions
workflow, ran it on Linux and Windows runners, then deleted both. Same
methodology, zstd level 3, 5 repetitions:

| OS | 2.25 GiB | 4 GiB |
|---|---|---|
| Linux (ubuntu, 4 vCPU) | mmap ~376ms vs jni ~751ms — mmap ~2x faster | mmap ~661ms vs jni ~1322ms — mmap ~2x faster |
| Windows (4 vCPU) | mmap ~1178ms vs jni ~908ms — jni ~30% faster | mmap ~2115ms vs jni ~1690ms — jni ~25% faster |
| macOS CI (3-vCPU shared runner, noisy) | roughly a wash | mmap ~11000ms vs jni ~4500ms — jni ~2.4x faster |

This is the real finding at this point in the investigation: **the
zero-copy mmap advantage is not universal.** Linux favored it cleanly at
both sizes tested. Windows never favored it, not even below the 2 GiB
boundary where local macOS had. macOS depended on size. "mmap is faster" is
not a defensible sentence; "it depends on the OS, and on macOS it depends on
size" is.

## A wrong turn that turned out to be useful

Working hypothesis for the macOS regression at 10 GiB: maybe it's
virtual-memory or page-fault management overhead from mapping and unmapping
a huge region every run, not the cost of copying bytes. To test it, the
compression path was rewritten to write directly into one large,
pre-sized native destination buffer (`Zstd.compressBound(size)`, ~10 GiB),
removing even the small heap-array bounce the original implementation used
to collect compressed output. If copying bytes were the bottleneck, this
should help.

It made things worse, and far more erratic. Steady-state 10 GiB time went
from a tight ~2230ms to a wildly variable 3249–7765ms. A second huge native
mapping on top of the source mapping, recreated every repetition, doubled
the large-VMA churn instead of removing a copy that mattered.

Worth keeping in the writeup rather than editing out: this failed
experiment was itself evidence. It *supported* the page-fault hypothesis, by
making the page-fault-adjacent cost worse, and it *disproved* "it's really
about copy bytes" — removing a copy that wasn't the bottleneck couldn't have
helped, and it didn't.

## Removing zstd from the equation

The move that actually got somewhere: stop measuring compression and
isolate pure I/O. A harness with no libzstd calls at all — one path
memory-maps the file and reads every byte straight off the mapped segment,
8 bytes at a time via `MemorySegment.get(JAVA_LONG, ...)`, no intermediate
copy buffer, matching exactly how the zero-copy compress call consumes the
source. The other path reads the file through a `BufferedInputStream` into a
reused heap `byte[]`. Both just sum bytes into an accumulator so the JIT
can't dead-code-eliminate the read.

The first version of this harness had a bug: it copied each 8 MiB chunk from
the mapped segment into a scratch buffer before summing — an extra `memcpy`
the real zero-copy compress call never does. Caught and fixed before
drawing any conclusion from it. It's a small thing, but it's the difference
between a benchmark you can trust and one that quietly reintroduces the
exact confound you built the harness to remove.

Local macOS results, pure I/O, no compression, 10 repetitions:

| size | mmap (touch only) | `read()` | winner |
|---|---|---|---|
| 4 MiB | ~200µs (after JIT warms up) | ~510µs | mmap |
| 64 MiB | ~3570µs | ~3350µs | wash |
| 2.25 GiB | ~138ms | ~105ms | `read()`, ~30% faster |
| 4 GiB | ~570ms | ~185ms | `read()`, ~3x faster |

This is the real *aha*: **mmap was already losing to plain buffered
`read()` by 2.25 GiB, with zero compression involved.** The earlier
compression benchmarks looked good for mmap at that size only because
`ZSTD_compressStream2`'s own CPU cost was masking a growing I/O penalty
underneath it. Subtracting the I/O-only numbers from the full compression
numbers (compression time minus I/O-only time ≈ pure compute) showed
zstd-jni's non-I/O remainder was consistently larger than mmap's — that's
what let mmap win overall at medium sizes despite already standing on a
worse I/O foundation. By 10 GiB the masked penalty had grown past what the
compute side could hide, and the total flipped.

## The fix: nobody told the kernel what was coming

Do we need `madvise`? `FileChannel.map()` — both overloads — just calls
`mmap()` (or `MapViewOfFile` on Windows) and nothing else. It never hints
the access pattern to the OS, so the kernel falls back to its default
readahead heuristic.

`posix_madvise(addr, len, advice)`[^madvise] is a raw FFM downcall to a
libc symbol — the same mechanism zstd-java already uses to call `libzstd`
itself, just pointed at a different function. Tested `POSIX_MADV_SEQUENTIAL`
and `POSIX_MADV_WILLNEED` against the unadvised baseline, still pure I/O, no
compression:

| size | mmap, no hint | + `SEQUENTIAL` | + `WILLNEED` | `read()` |
|---|---|---|---|---|
| 4 MiB | ~200–400µs | ~200µs | **~125µs** | ~570µs |
| 64 MiB | ~3800µs | ~3700µs | **~1960µs** | ~3900µs |
| 2.25 GiB | ~137ms | ~136ms (no help) | **~70ms** | ~120ms |
| 4 GiB | ~570ms | **~275ms** | **~250ms** | ~215ms |

`WILLNEED` roughly halved mmap's own cost at every size from 64 MiB up, and
at 2.25 GiB it didn't just close the gap to `read()` — it beat it by about
40%. `SEQUENTIAL` was inconsistent: no measurable help at 2.25 GiB, a big
help at 4 GiB. I don't have an explanation for that split; it's a real
wrinkle, not smoothed over here.

## Putting zstd back with the fix applied

Final test: real `ZstdCompressStream` compression again, `posix_madvise`
applied right after mapping, compared against zstd-jni, at the two sizes
that mattered most — 2.25 GiB, where mmap already won, and 10 GiB, where it
had lost:

| size | mmap, no hint | mmap + `WILLNEED` | zstd-jni |
|---|---|---|---|
| 2.25 GiB | ~283ms | **~190ms** | ~346ms |
| 10 GiB | ~2255ms | **~1310ms** | ~1630ms |

At 10 GiB, the hint alone cut mmap's own time by about 42% and flipped the
comparison entirely: unadvised mmap lost to zstd-jni by about 38%; advised
mmap beat it by about 24%. The "reversal at scale" from the very first
benchmark wasn't a fact about zero-copy mapping at all — it was a fact
about a one-line OS hint the JDK never applies for you.

## What's still open

- All of this is single-machine — mostly local macOS, with the cross-OS
  numbers coming from one CI run each on Linux and Windows — using ad hoc
  timing loops, not the JMH-backed, fork-isolated benchmarks the project
  treats as authoritative for real performance claims. Directional, not
  definitive.
- `madvise` was only tried on macOS. Linux exposes the same POSIX call but
  it's untested there — plausible it helps less, since Linux already won
  without any hint. Windows has no direct equivalent (`PrefetchVirtualMemory`
  is a structurally different API) and hasn't been tried at all.
- Whether zstd-java should call `madvise` internally, expose it as an
  opt-in flag, or just document the recipe for callers is an open design
  decision, not something built yet.

## Closing

None of this — cross-OS validation, isolating a confound by stripping the
subject algorithm out entirely, catching a bug in your own benchmark, trying
a plausible fix that makes things worse, trying the actual right fix and
having it invert the conclusion — is cheap in engineer-hours. Most projects
would ship the first plausible-looking benchmark number and move on. What
made it affordable here wasn't that the assistant wrote code faster than I
would have; it's that every experiment lived in a throwaway branch or a
scratch method with no cost to being wrong and discarding it. That's what
made it rational to keep chasing the question instead of stopping at the
first answer that sounded right.

---

[^map]: `FileChannel.map(MapMode, long, long, Arena)`, added by [JEP 454](https://openjdk.org/jeps/454) alongside the rest of the FFM API — see the [`FileChannel` javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html#map(java.nio.channels.FileChannel.MapMode,long,long,java.lang.foreign.Arena)).

[^madvise]: [`posix_madvise(3)`](https://man7.org/linux/man-pages/man3/posix_madvise.3.html) — the portable POSIX wrapper around `madvise(2)`; `POSIX_MADV_WILLNEED` asks the kernel to start readahead now rather than on first fault, `POSIX_MADV_SEQUENTIAL` doubles the default readahead window and allows early eviction of pages already read.
