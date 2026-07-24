# Breaking Java's Old Trade-off

*24 July 2026*

*For years, getting native-level performance out of Java meant reaching for `sun.misc.Unsafe` or writing JNI glue — trading safety and a sane build for speed. The Foreign Function & Memory API removes that trade-off. To see how far, I wrapped [Zstandard](https://github.com/facebook/zstd) as [zstd-java](https://github.com/dfa1/zstd-java): no JNI, no `Unsafe`, no hand-written C — and, once [Zig](https://ziglang.org/) is the compiler, no build matrix either. Here is what that costs, and what it buys.*

---

## Beyond `Unsafe` and JNI

Historically, calling a C library like zstd from Java meant picking your poison:

* `sun.misc.Unsafe` gave you raw off-heap pointers with zero bounds checking, silent memory corruption on a mistake, and a hard dependency on an unsupported internal API.
* JNI meant a C bridge layer, a generated header to keep in sync, a cross-compilation toolchain, and blind spots where a single mismanaged pointer crashed the whole JVM.

The [Foreign Function & Memory (FFM) API](https://openjdk.org/jeps/454) — final in JDK 22, and shipped in the first LTS to carry it stably, JDK 25 — replaces both with one standard model. There is no C to write. Each zstd function is declared as a `MethodHandle`, its signature transcribed straight from the [zstd manual](https://facebook.github.io/zstd/doc/api_manual_latest.html):

```java
// size_t ZSTD_compress(void* dst, size_t dstCap, const void* src, size_t srcSize, int level)
static final MethodHandle COMPRESS =
        NativeLibrary.lookup("ZSTD_compress",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));
```

The comment is the C prototype; the `FunctionDescriptor` is the same prototype in Java. That is the entire binding — no `.c` file, no generated header, no glue object to compile.

## Where the safety lives

Calling that handle is where `Unsafe` used to earn its name. Under FFM the dangerous parts are pinned down by the types instead:

```java
public static byte[] compress(byte[] src, ZstdCompressionLevel level) {
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment in   = copyIn(arena, src);
        ZstdByteSize bound = compressBound(new ZstdByteSize(src.length));
        MemorySegment out  = arena.allocate(bound.value());
        long written = NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS.invokeExact(
                out, bound.value(), in, (long) src.length, level.value()));
        return copyOut(out, written);
    }
}
```

Three guarantees `Unsafe` never gave you, all visible in that block:

- **Spatial bounds.** A `MemorySegment` knows its own size; an out-of-range access throws instead of corrupting the heap. Zero-copy entry points additionally reject a heap segment where a native address is required, rather than passing a bad pointer to C.
- **Temporal lifecycle.** The `Arena` owns the off-heap memory. Close it — here, at the end of the `try` — and every segment it handed out is invalidated; a use-after-free is an exception, not a segfault.
- **Thread confinement.** `Arena.ofConfined()` binds the memory to one thread. Touch it from another and the JVM stops you at the boundary.

Safety you can also *choose* to be explicit about. zstd frames record their decompressed size in the header, so the convenient path trusts it — fine for frames you produced. For input you do not control, that trust is a decompression bomb: a hostile header can declare a huge size and force the matching allocation. The API makes the safe path a separate, harder-to-misuse overload:

```java
byte[] out = Zstd.decompress(frame);                          // trusts the header
byte[] out = Zstd.decompress(frame, new ZstdByteSize(maxLen)); // caps the allocation
```

The native library itself is bundled in the jar and loaded once through `SymbolLookup.libraryLookup` — there is deliberately no path override, because loading native code is arbitrary code execution and the loader trusts only the signed artifact on the classpath.

## Skip the sysroot

FFM handles the calling side. The other half of wrapping a C library is building that C library, and that is where cross-compilation usually turns into a matrix of OS-specific CI runners, Docker images, and hand-assembled sysroots. With Zig as the compiler, that whole problem mostly disappears.

`zig cc` is a drop-in replacement for `cc` that bundles clang, its own libc, and the headers for every target Zig supports — inside the toolchain download itself. `zstd-java` skips zstd's build system entirely: the sources are vendored as plain `.c`, and the script globs and compiles them directly.

```bash
# scripts/build-zstd.sh
export CC="zig cc -target $ZIG_TARGET"
SRCS=$(find "$ZSTD_LIB/common" "$ZSTD_LIB/compress" "$ZSTD_LIB/decompress" \
            "$ZSTD_LIB/dictBuilder" -name '*.c' | sort)
...
zig cc -target "$ZIG_TARGET" $CFLAGS -c "$src" -o "$out"
```

Once cross-compiling is just a `-target` string, adding a platform is a line in a `case` statement, not a new CI runner. `zstd-java` maps six classifiers to six Zig target triples:

```bash
osx-aarch64)     ZIG_TARGET="aarch64-macos"
osx-x86_64)      ZIG_TARGET="x86_64-macos"
linux-x86_64)    ZIG_TARGET="x86_64-linux-gnu"
linux-aarch64)   ZIG_TARGET="aarch64-linux-gnu"
windows-x86_64)  ZIG_TARGET="x86_64-windows-gnu"
windows-aarch64) ZIG_TARGET="aarch64-windows-gnu"
```

That last pair is the one that stood out: a working Windows `.dll`, PE export table and all, produced from a Linux or macOS runner — no MinGW install, no Wine, no Windows box anywhere in the pipeline. Neither this project nor its C++ sibling [rocksdbffm](https://github.com/dfa1/rocksdbffm) runs one job per OS to get there[^ci]; one host builds every target, because nothing about the build depends on which OS it runs on.

One caveat: the Linux targets are `-gnu`, not `-musl`. The resulting `.so` still dynamically links glibc and expects a compatible one on the runtime host — Zig can target `-musl` for a fully static binary, but the project does not take that step here.

And it is not a niche trick: Uber has compiled every line of C/C++ in its Go monorepo with `zig cc`, for both x86_64 and arm64, since January 2023[^uber]. The experience generalizes past C, too — swap the language and the same essay gets written again[^justwork].

## What it costs

The pitch is only honest with numbers, so `zstd-java` ships a JMH suite that pits its FFM path against [zstd-jni](https://github.com/luben/zstd-jni) — the mature JNI binding — with **both sides linking the same zstd 1.5.7**, so any gap is binding overhead, not codec version[^bench].

The fair comparison is best against best: zstd-java's zero-copy `MemorySegment` API against zstd-jni's own zero-copy direct-`ByteBuffer` API, neither allocating per call. Throughput in ops/ms, higher is better:

| case (payload) | zstd-java `MemorySegment` | zstd-jni `ByteBuffer` | edge |
|----------------|--------------------------:|----------------------:|-----:|
| compress `http` (1.2 KiB)         | 353.6 | 322.1 | +9.8% |
| decompress `http`                 | 922.7 | 750.8 | +22.9% |
| decompress `large-literal` (200 KiB) | 56.1 | 55.6 | +0.9% (tie) |

Allocation is a dead heat: `gc.alloc.rate.norm` is ~0 B/op on both sides — both paths are genuinely zero-copy. The throughput edge is pure per-call overhead, and it behaves exactly the way FFM-vs-JNI should: biggest on the smallest, call-overhead-dominated payloads (+10–23%), shrinking to nothing once compute or memory bandwidth dominates. There is no free lunch at 64 MiB; there is a real one when you are calling into zstd a million times on small records.

The one claim that does *not* survive scrutiny: an early version of this benchmark reported the `MemorySegment` path as "allocation-free versus JNI." True — but only against zstd-jni's convenient `byte[]` API, which allocates the output every call; against its zero-copy `ByteBuffer` path the allocation advantage vanishes. The honest headline is narrower: **match on allocation, lead modestly on call overhead.**

## The path forward

"Java is slow," "off-heap requires `Unsafe`," "native integration is dangerous" — for this workload, none of it held. One library binds a C codec with no JNI and no `Unsafe`, cross-compiles to six platforms from a laptop, and trades blows with the incumbent while matching it on allocation. If you are wrapping native code from the JVM today, the old trade-off is not the one you are still paying.

---

[^ci]: Both projects run their native builds under [`mlugg/setup-zig`](https://github.com/mlugg/setup-zig) in GitHub Actions; the classifier matrix is a loop over `-target` strings inside one job, not one job per OS.

[^uber]: Uber Engineering, [*Bootstrapping Uber's Infrastructure on arm64 with Zig*](https://www.uber.com/us/en/blog/bootstrapping-ubers-infrastructure-on-arm64-with-zig/) (2023).

[^justwork]: Loris Cro, [*Zig Makes Go Cross Compilation Just Work*](https://dev.to/kristoff/zig-makes-go-cross-compilation-just-work-29ho) — the same claim, and title, shows up for Rust and other languages once people reach for `zig cc` as their C toolchain.

[^bench]: Golden-corpus run on an Apple M5 (32 GB), JDK 25, zstd-jni 1.5.7-11, JMH 1.37: 3 forks × 3 warmup × 5 measurement iterations with `-prof gc`, error bars are 99.9% confidence intervals. Inputs are real fixtures from zstd's own golden corpus. Full methodology and the wider table in the project's [`docs/benchmarks.md`](https://github.com/dfa1/zstd-java/blob/master/docs/benchmarks.md).
