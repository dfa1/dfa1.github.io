# Announcing zstd-java

*26 July 2026*

*I needed Zstandard compression for [vortex-java](https://github.com/dfa1/vortex-java), a pure-Java implementation of the [Vortex](https://github.com/vortex-data/vortex) columnar format I've been building. The obvious choice was [zstd-jni](https://github.com/luben/zstd-jni) — it's mature, it tracks upstream zstd closely, it ships to Maven Central and Android, and plenty of production systems depend on it. But it's also a JNI binding: a compiled shim per platform, built against whatever Java version its users still run. I wanted to know what a Zstd binding looks like if you start from JDK 25 instead of JDK 8. The result is [zstd-java](https://github.com/dfa1/zstd-java) — not a replacement for zstd-jni, but a bet on three things modern Java now gives you for free.*

---

## Why not just use zstd-jni

There's nothing wrong with zstd-jni — it's "the excellent zstd-jni," to quote zstd-java's own README, which doesn't pretend otherwise[^zstd-java-readme]. It's production-ready, tracks the zstd release branch, and compiles down to Java 8 bytecode[^zstd-jni-build] so it runs anywhere from an old Android app to a JDK 25 server. That reach is the point of a JNI binding: it doesn't get to assume anything about the runtime.

zstd-java makes the opposite trade: it assumes JDK 25+, and in exchange it gets three things a JNI binding built for Java 8 compatibility structurally cannot have.

## Pillar one: FFM instead of JNI

The [Foreign Function & Memory API](https://openjdk.org/jeps/454) — final in JDK 22, stable in the first LTS to carry it, JDK 25 — replaces the JNI shim with typed Java: a `MethodHandle` bound straight to the C symbol, no `.c` glue file, no generated header.

```java
// size_t ZSTD_compress(void* dst, size_t dstCap, const void* src, size_t srcSize, int level)
static final MethodHandle COMPRESS =
        NativeLibrary.lookup("ZSTD_compress",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));
```

That's the entire binding for one function: the comment is the C prototype, the `FunctionDescriptor` is the same prototype in Java. No JNI means no per-platform shared object built from hand-written C++ glue — just the native `libzstd` itself, cross-compiled with Zig for six platforms including a working Windows `.dll` produced on a Linux runner.

It isn't only simpler to build — it holds up on throughput too. Benchmarked against zstd-jni's own zero-copy `ByteBuffer` path, both linking the identical zstd 1.5.7, zstd-java's zero-copy `MemorySegment` path leads by +9.8% compressing a 1.2 KiB payload and +22.9% decompressing it, converging to a tie once the payload grows to 200 KiB and codec throughput dominates the call overhead[^bench]. The full methodology, including the comparisons that came out as honest ties, is in a companion piece, *Breaking Java's Old Trade-off*.

## Pillar two: module support

FFM downcalls are a restricted operation — the JVM refuses one without an explicit `--enable-native-access` grant. zstd-java ships a real `module-info.java` that scopes that grant to itself:

```java
@SuppressWarnings("module") // dfa1 is my username in github
module io.github.dfa1.zstd {
    exports io.github.dfa1.zstd;
}
```

One package exported; the `MethodHandle`s that actually touch native memory never leave the module. A caller on the module path grants access to `io.github.dfa1.zstd` by name, not to everything on the classpath[^adr-0011]. zstd-jni has no equivalent — it ships as a plain jar with no `module-info.java`, so on the module path it resolves as an automatic module with a name derived from the jar filename and no export control at all. That's not a knock on zstd-jni; JNI never had a "restricted operation" for a module system to gate. It's a distinction that only exists because FFM created something worth scoping in the first place.

## Pillar three: domain primitives, with value types on the horizon

The public API takes no naked `int` or `long` for a size, a compression level, or a window log — each is a validated `record`:

```java
public record ZstdByteSize(long value) {
    public ZstdByteSize {
        if (value < 0) {
            throw new IllegalArgumentException("size " + value + " must not be negative");
        }
    }
    // ofKiB, ofMiB, fromFrameContentSize, fromUnsignedFrameHeaderField ...
}
```

That matters most exactly where a zstd frame is attacker-controlled: the decompressed-size field in the header is what a hostile frame gets to lie about, and `ZstdByteSize` is the one place that lie gets caught, once, instead of trusted at every call site that happens to remember to check. zstd-java's v0.12 changelog frames the whole sweep — `ZstdByteSize`, `ZstdCompressionLevel`, `ZstdWindowLog`, `ZstdMagicVariant`, `ZstdVersion` — as a direct application of the domain-primitives case made in [Your Compiler Is Already Part of Your Security Team](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team.html)[^changelog].

These are ordinary `record`s today — identity classes, one heap allocation apiece, cheap at an API boundary and not free in a per-chunk hot loop. That's the trade-off [Rethink Domain Primitives with Valhalla](https://dfa1.github.io/articles/rethink-domain-primitives-with-valhalla.html) measured directly: a wrapper `record` costs roughly 4× the bare primitive it replaces. Project Valhalla's `value class`[^valhalla-jep] removes that cost by letting the JVM flatten the same fields into the array slot or register instead of the heap — still a JDK 27 preview, not something a Maven Central release can depend on yet, but the migration from here is mechanical: the types are already `final`, immutable, and validate once at construction. Nothing about their design has to change to stop paying for identity once the JVM stops charging for it.

## Proof: vortex-java

The three pillars above aren't a pitch in the abstract — they're why vortex-java's `vortex.zstd` column encoding runs on zstd-java today. Vortex compresses columnar data (dictionaries, delta, FastLanes, and a Zstd fallback among its encodings), and until v0.10.0 that Zstd fallback went through `io.airlift:aircompressor-v3`, a pure-Java Zstd decoder. Migrating it to `io.github.dfa1.zstd:zstd` picked up framed, sliceable payloads, nullable-column support, and shared-dictionary decode in one change[^vortex-changelog]. Consumers pull in exactly one dependency, `zstd-platform`, and get the binding plus native `libzstd` for every supported platform — no per-platform artifact juggling on their side either.

The domain-primitive story carries straight through: a later vortex-java security pass added bounds-checking on each frame's declared uncompressed size before allocating, and overflow-checking the total — the same decompression-bomb concern `ZstdByteSize` exists to catch, now enforced on the vortex-java side of the boundary too[^vortex-security].

Worth being precise about scope here: zstd-jni hasn't gone away from vortex-java entirely. It's still what the Parquet reader uses to decode ZSTD-compressed Parquet pages — an unrelated integration, solving a different problem (reading someone else's file format, not writing Vortex's own). Nothing here claims zstd-jni was replaced project-wide; only the `vortex.zstd` encoding moved.

## Try it

zstd-java is on Maven Central, BSD 3-Clause licensed, JDK 25+ only, and honest about being early: it's for early adopters, not a drop-in swap for an existing zstd-jni integration that needs to keep running on older JVMs. If you're on JDK 25 and starting a new integration, or you want to see what an FFM-based native binding looks like end to end, the [repository](https://github.com/dfa1/zstd-java) has a quickstart, dictionary compression, and a zero-copy `MemorySegment` API to start from.

---

[^zstd-java-readme]: [zstd-java README](https://github.com/dfa1/zstd-java#readme) — "an FFM-based alternative to the excellent zstd-jni for early adopters on JDK 25+."

[^zstd-jni-build]: [zstd-jni `build.sbt`](https://github.com/luben/zstd-jni/blob/master/build.sbt) targets `--release 8`, and the project publishes an Android `.aar` alongside the JVM jar — reach that a JNI binding can offer and an FFM one, tied to JDK 25+, currently cannot.

[^bench]: Golden-corpus JMH run, Apple M5, JDK 25, zstd-jni 1.5.7-11, both sides linking zstd 1.5.7. Full tables in zstd-java's [`docs/benchmarks.md`](https://github.com/dfa1/zstd-java/blob/master/docs/benchmarks.md).

[^adr-0011]: [ADR 0011 — JPMS module descriptor](https://github.com/dfa1/zstd-java/blob/master/adr/0011-jpms-module-descriptor.md), zstd-java, accepted 2026-06-27.

[^changelog]: [CHANGELOG.md](https://github.com/dfa1/zstd-java/blob/master/CHANGELOG.md), zstd-java v0.12, 2026-07-26.

[^valhalla-jep]: [JEP 401: Value Classes and Objects](https://openjdk.org/jeps/401) — preview, current EA builds target JDK 27.

[^vortex-changelog]: [CHANGELOG.md](https://github.com/dfa1/vortex-java/blob/master/CHANGELOG.md), vortex-java v0.10.0, 2026-06-26: "The `vortex.zstd` encoding now compresses and decompresses through `io.github.dfa1.zstd:zstd` ... instead of `io.airlift:aircompressor-v3`."

[^vortex-security]: vortex-java, `vortex.zstd` decode bounds-checking commits [2df4e3a7](https://github.com/dfa1/vortex-java/commit/2df4e3a7) and [adc445e8](https://github.com/dfa1/vortex-java/commit/adc445e8).
