# Announcing zstd-java

*26 July 2026*

*I needed Zstandard compression for [vortex-java](https://github.com/dfa1/vortex-java), a pure-Java implementation of the [Vortex](https://github.com/vortex-data/vortex) columnar format I've been building. The obvious choice was [zstd-jni](https://github.com/luben/zstd-jni) — it's mature, it tracks upstream zstd closely, it ships to Maven Central and Android, and plenty of production systems depend on it. But it's also a JNI binding: a compiled shim per platform, built against whatever Java version its users still run. I wanted to know what a Zstd binding looks like if you start from JDK 25 instead of JDK 8. The result is [zstd-java](https://github.com/dfa1/zstd-java) — not a replacement for zstd-jni, but a bet on three things modern Java now gives you for free.*

---

## Why not just use zstd-jni

There's nothing wrong with zstd-jni — it's "the excellent zstd-jni," to quote zstd-java's own README, which doesn't pretend otherwise[^zstd-java-readme]. It's production-ready, tracks the zstd release branch, and compiles down to Java 8 bytecode[^zstd-jni-build] so it runs anywhere from an old Android app to a JDK 25 server. That reach is the point of a JNI binding: it doesn't get to assume anything about the runtime.

zstd-java makes the opposite trade, and the downside has to be stated plainly: it does not run on anything older than JDK 25. There's no fallback path, no JNI escape hatch for an older JVM — `java.lang.foreign` isn't there to call. If your deployment target is Java 8, 11, 17, or 21, zstd-jni is still the only option, full stop. In exchange for giving up that reach, zstd-java gets three things a JNI binding built for Java 8 compatibility structurally cannot have.

## Point one: FFM instead of JNI

The [Foreign Function & Memory API](https://openjdk.org/jeps/454) — final in JDK 22, stable in the first LTS to carry it, JDK 25 — replaces the JNI shim with typed Java: a `MethodHandle` bound straight to the C symbol, no `.c` glue file, no generated header.

```java
// size_t ZSTD_compress(void* dst, size_t dstCap, const void* src, size_t srcSize, int level)
static final MethodHandle COMPRESS =
        NativeLibrary.lookup("ZSTD_compress",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));
```

That's the entire binding for one function: the comment is the C prototype, the `FunctionDescriptor` is the same prototype in Java. No JNI means no per-platform shared object built from hand-written C++ glue — just the native `libzstd` itself, cross-compiled with Zig for six platforms including a working Windows `.dll` produced on a Linux runner[^zig-adr].

The bigger difference isn't fewer moving parts, it's what a mistake costs. A `MemorySegment` carries its own size and thread confinement, so an out-of-range access throws instead of corrupting the heap; an `Arena` owns the lifetime of everything allocated in it, so touching a segment after its arena closes is a use-after-free that throws, not a segfault three frames away. JNI's raw pointer has none of that — a wrong offset or a stale handle just corrupts memory or crashes the JVM, with nothing in the type system to catch it first. That's also why a downcall gets to be a restricted, permissioned operation at all: FFM gave the JVM something typed enough to gate.

Throughput tells a narrower story than a single headline number would suggest. Benchmarked against zstd-jni's own zero-copy `ByteBuffer` path — both sides linking the identical zstd 1.5.7 — the honest read is parity on the payload size that matters most: decompressing 200 KiB is a +0.9% tie once codec throughput dominates the call overhead. The edge that does show up is neither large nor uniform: +9.8% compressing 1.2 KiB, +22.9% decompressing 1.2 KiB, and still +9.4% compressing 200 KiB — compression doesn't converge the way decompression does, at least on the two sizes measured. Sizes below roughly 2 KiB, where per-call overhead is most of the cost, are also the unusual case for a compression library, not the typical one[^bench].

Running any of this still requires the flag that makes that gate real:

```bash
java --enable-native-access=ALL-UNNAMED Demo.java
```

## Point two: module support

`ALL-UNNAMED` grants native access to everything on the classpath — every dependency, not just zstd-java. On the module path, the grant can name a single module instead. zstd-java ships a real `module-info.java` declaring exactly that module:

```java
module io.github.dfa1.zstd {
    exports io.github.dfa1.zstd;
}
```

Put your app on the module path alongside it, and the flag names only the module doing the native call:

```bash
java --module-path app.jar:zstd-platform.jar \
     --enable-native-access=io.github.dfa1.zstd \
     -m myapp/com.example.Main
```

One package exported; the `MethodHandle`s that actually touch native memory never leave the module[^adr-0011]. zstd-jni has no equivalent — it ships as a plain jar with no `module-info.java`, so on the module path it resolves as an automatic module with a name derived from the jar filename and no export control at all. That's not a knock on zstd-jni: JNI never had a restricted operation for a module system to gate. The distinction only exists because FFM created something worth scoping in the first place.

## Point three: domain primitives, with value types on the horizon

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

Be precise about what that constructor actually guarantees: non-negativity, nothing more. A hostile zstd frame that declares a large-but-technically-valid decompressed size still constructs a perfectly legal `ZstdByteSize` — the record catches malformed input, not implausible input, and those are different guarantees. The decompression-bomb defense in zstd-java is a separate API decision, not the type's constructor: the one-argument `decompress(byte[])` trusts the frame header's declared size outright, but its javadoc says so and points at the bounded overload, `decompress(byte[], ZstdByteSize)`, which caps the allocation at a bound the caller picks regardless of what the frame claims[^bomb]. The type closes off malformed sizes; deciding how much of an untrusted claim to trust is still a judgment call the API has to expose, not something a validating constructor can absorb on its own.

zstd-java's v0.12 changelog frames the domain-primitive sweep — `ZstdByteSize`, `ZstdCompressionLevel`, `ZstdWindowLog`, `ZstdMagicVariant`, `ZstdVersion` — as a direct application of the case made in [Your Compiler Is Already Part of Your Security Team](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team.html)[^changelog].

These are ordinary `record`s today — identity classes, one heap allocation apiece, cheap at an API boundary and not free in a per-chunk hot loop. That's the trade-off [Rethink Domain Primitives with Valhalla](https://dfa1.github.io/articles/rethink-domain-primitives-with-valhalla.html) measured directly: a wrapper `record` costs roughly 4× the bare primitive it replaces. Project Valhalla's `value class`[^valhalla-jep] removes that cost by letting the JVM flatten the same fields into the array slot or register instead of the heap — still a JDK 27 preview, not something a Maven Central release can depend on yet, but the migration from here is mechanical: the types are already `final`, immutable, and validate once at construction. Nothing about their design has to change to stop paying for identity once the JVM stops charging for it.

## Proof: vortex-java

The three points above aren't a pitch in the abstract — they're why vortex-java's `vortex.zstd` column encoding runs on zstd-java today. Vortex's columnar encodings include dictionary, delta, FastLanes, and a Zstd fallback, and until v0.10.0 that fallback went through `io.airlift:aircompressor-v3`, a pure-Java Zstd decoder. Migrating it to `io.github.dfa1.zstd:zstd` picked up framed, sliceable payloads, nullable-column support, and shared-dictionary decode in one change[^vortex-changelog]. Consumers pull in exactly one dependency, `zstd-platform`, and get the binding plus native `libzstd` for every supported platform — no per-platform artifact juggling on their side either.

The lesson generalizes further than the boundary it started at. To split a large column into independently decodable frames, `vortex.zstd` writes its own per-frame metadata — an `uncompressed_size` field vortex-java itself defines, not the zstd frame header `ZstdByteSize` guards. A later security fix found that field summed into a `long` with no validation at all before being handed to `arena.allocate`: a crafted value could overflow the running total to a small positive (under-allocating) or simply claim an oversized one[^vortex-security]. `ZstdByteSize` never saw it, because it isn't zstd-java's field to see — vortex.zstd is a format vortex-java layered on top, and every layer that parses a size out of untrusted bytes has to bring its own guard. Domain primitives at one boundary don't propagate to the next one for free.

Worth being precise about scope here: zstd-jni hasn't gone away from vortex-java entirely. It's still what the Parquet reader uses to decode ZSTD-compressed Parquet pages — an unrelated integration, solving a different problem (reading someone else's file format, not writing Vortex's own). Nothing here claims zstd-jni was replaced project-wide; only the `vortex.zstd` encoding moved.

## What's next

zstd-java is at v0.12, pre-1.0. The domain-primitive sweep this release completed was itself preparation for 1.0: replacing naked `int`/`long` at the API boundary means breaking changes, and those are cheaper to make now than after a 1.0 tag asks for stability. vortex-java is the first real consumer, not the intended only one — the project is actively looking for more early adopters on JDK 25+ willing to try an FFM-based alternative to zstd-jni and report back before the API locks down.

zstd-java is on Maven Central, BSD 3-Clause licensed, JDK 25+ only, and honest about being early: it's for early adopters, not a drop-in swap for an existing zstd-jni integration that needs to keep running on older JVMs. If that's you, the [repository](https://github.com/dfa1/zstd-java) has a quickstart, dictionary compression, and a zero-copy `MemorySegment` API to start from — issues and pull requests are welcome.

---

[^zstd-java-readme]: [zstd-java README](https://github.com/dfa1/zstd-java#readme) — "an FFM-based alternative to the excellent zstd-jni for early adopters on JDK 25+."

[^zstd-jni-build]: [zstd-jni `build.sbt`](https://github.com/luben/zstd-jni/blob/master/build.sbt) targets `--release 8`, and the project publishes an Android `.aar` alongside the JVM jar — reach that a JNI binding can offer and an FFM one, tied to JDK 25+, currently cannot.

[^bench]: Golden-corpus JMH run: 3 forks × 3 warmup × 5 measurement iterations with `-prof gc`, error bars at 99.9% confidence intervals — publication-grade for the cut quoted here, per zstd-java's own methodology notes. Measured on one machine only, an Apple M5 laptop (JDK 25, zstd-jni 1.5.7-11, both sides linking zstd 1.5.7); call-overhead deltas like these are sensitive to the CPU and memory subsystem, and there's no server-class run yet to confirm the margins hold. Full tables in zstd-java's [`docs/benchmarks.md`](https://github.com/dfa1/zstd-java/blob/master/docs/benchmarks.md).

[^zig-adr]: [ADR 0002 — Zig as the native C compiler](https://github.com/dfa1/zstd-java/blob/master/adr/0002-zig-cc-native-build.md), zstd-java.

[^bomb]: [`Zstd.decompress(byte[])` javadoc](https://github.com/dfa1/zstd-java/blob/master/zstd/src/main/java/io/github/dfa1/zstd/Zstd.java): "a hostile frame can declare a large size ... and force a correspondingly large allocation — a decompression-bomb denial of service. For input you do not control, use `decompress(byte[], ZstdByteSize)` with a sane bound instead."

[^adr-0011]: [ADR 0011 — JPMS module descriptor](https://github.com/dfa1/zstd-java/blob/master/adr/0011-jpms-module-descriptor.md), zstd-java, accepted 2026-06-27.

[^changelog]: [CHANGELOG.md](https://github.com/dfa1/zstd-java/blob/master/CHANGELOG.md), zstd-java v0.12, 2026-07-26.

[^valhalla-jep]: [JEP 401: Value Classes and Objects](https://openjdk.org/jeps/401) — preview, current EA builds target JDK 27.

[^vortex-changelog]: [CHANGELOG.md](https://github.com/dfa1/vortex-java/blob/master/CHANGELOG.md), vortex-java v0.10.0, 2026-06-26: "The `vortex.zstd` encoding now compresses and decompresses through `io.github.dfa1.zstd:zstd` ... instead of `io.airlift:aircompressor-v3`."

[^vortex-security]: vortex-java commit [2df4e3a7](https://github.com/dfa1/vortex-java/commit/2df4e3a7): "decode summed untrusted per-frame uncompressed_size into a long with no validation, then arena.allocate'd it; a crafted metadata could wrap the total to a small positive (under-allocation) or claim a huge size (allocation DoS)." Fixed alongside [adc445e8](https://github.com/dfa1/vortex-java/commit/adc445e8).
