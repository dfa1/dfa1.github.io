# Guard the Native Boundary Twice

*26 July 2026*

*The Foreign Function & Memory API doesn't pretend native calls are safe — it makes you ask permission for them. `--enable-native-access` is not a suggestion; the JVM refuses a downcall without it. FFM is the cornerstone that makes this question askable at all — JNI never had a grant to withhold, and `sun.misc.Unsafe` never had a type to check. On top of that cornerstone, [zstd-java](https://github.com/dfa1/zstd-java) still has to answer two more questions: permission for whom, to pass what? Module boundaries answer the first. Domain primitives answer the second. Neither is optional once your code is one `MethodHandle` away from a C library — and neither would have anything to stand on without FFM underneath it.*

---

## The cornerstone

Everything else in this article leans on one fact: FFM turned "calling into C" from an unchecked jump into a typed, permissioned operation. JNI had no equivalent — a native method was declared, compiled, and callable, and the JVM had no vocabulary for asking who was allowed to reach it. `sun.misc.Unsafe` was worse: no method boundary at all, just a raw address and a promise that you'd got the offset right.

`java.lang.foreign` replaces both with three concrete types: a `MethodHandle` in place of a JNI stub, a `MemorySegment` in place of a raw pointer, an `Arena` in place of "however long the pointer happens to stay valid." Binding `ZSTD_compress` is a `FunctionDescriptor`, not a `.c` file:

```java
// size_t ZSTD_compress(void* dst, size_t dstCap, const void* src, size_t srcSize, int level)
static final MethodHandle COMPRESS =
        NativeLibrary.lookup("ZSTD_compress",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));
```

That typed model is precisely what lets the JVM classify a downcall as a *restricted operation* in the first place[^ffm-jep] — there was no such category before, because there was nothing typed enough to restrict. It's also the reason the memory itself stays safe once you're across the boundary: a `MemorySegment` carries its own bounds and rejects an out-of-range access instead of corrupting the heap, and an `Arena` owns its segments' lifetime so a use-after-free throws instead of segfaulting. Those mechanics are the subject of a companion piece, *Breaking Java's Old Trade-off* — this article picks up where that one stops, at the two boundaries built on top of FFM's cornerstone: who gets to hold `COMPRESS`, and what they're allowed to hand it.

## Permission for whom

A `MethodHandle` bound to `ZSTD_compress` is not just a function call — it's a hole punched straight through the JVM's safety net. Whatever code holds that handle can hand libzstd a bad pointer, an oversized length, anything. FFM doesn't try to make that hole disappear; it makes you declare, module by module, who's allowed to use it[^ffm-jep].

zstd-java's answer is a two-line `module-info.java`:

```java
/// Java Foreign Function & Memory (FFM) bindings for Zstandard.
///
/// Exports the single public API package; the native `libzstd` is loaded at
/// runtime from the platform `zstd-native-<classifier>` artifact on the path.
/// Requires `--enable-native-access=io.github.dfa1.zstd` (or `ALL-UNNAMED` on
/// the classpath) since FFM downcalls are a restricted operation.
@SuppressWarnings("module") // dfa1 is my username in github
module io.github.dfa1.zstd {
    exports io.github.dfa1.zstd;
}
```

One package exported, nothing else. The `MethodHandle`s in `Bindings` — the ones that actually touch native memory — never leave the module; only the validated, record-based API in `io.github.dfa1.zstd` does. A caller on the module path has to write `--enable-native-access=io.github.dfa1.zstd` by name — not a blanket grant, a grant to *this* module, and the ADR that shipped it says so directly: "native-access requirement is explicit and scoped to this module."[^adr-0011]

That scoping is the whole point of putting a module boundary around a native binding. `ALL-UNNAMED` still exists for classpath users, and it's exactly as broad as it sounds — but on the module path, granting access to `io.github.dfa1.zstd` doesn't also hand it to whatever else is on the classpath. If something in your dependency tree turns out to be doing something it shouldn't with off-heap memory, the module system is where you'd see it named.

## Permission to pass what

Getting into the module answers *who* can reach the native call. It says nothing about *what* they hand it once they're in. That's the older, more familiar failure mode: two `long`s at a call site, and nothing stops them from arriving in the wrong order or the wrong unit — the exact bug [Your Compiler Is Already Part of Your Security Team](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team.html) opened with, except here the receiving end is C, not Java, so a mistake doesn't throw — it corrupts memory or reads past a buffer.

zstd-java's v0.12 changelog frames this as a deliberate, self-contained sweep — replacing every naked `int`/`long` at the public boundary with a validated type, and it cites that article by name as the reason[^changelog]:

> This cycle completes a sweep replacing naked primitives at the public API with validated **domain primitives** — `ZstdByteSize`, `ZstdCompressionLevel`, `ZstdWindowLog`, `ZstdMagicVariant`, `ZstdVersion`, and `ZstdFrameHeader`'s size fields. Each validates once, at construction, so a value is proof of its own validity and illegal states are unrepresentable rather than guarded by scattered runtime checks.

`ZstdByteSize` is the one that carries the most weight, because it's the type standing between untrusted input and an allocation:

```java
public record ZstdByteSize(long value) {

    public ZstdByteSize {
        if (value < 0) {
            throw new IllegalArgumentException("size " + value + " must not be negative");
        }
    }

    public int toArraySize() {
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException _) {
            throw new ZstdException("size " + value + " exceeds the maximum array length");
        }
    }

    // ofKiB, ofMiB, fromFrameContentSize, fromUnsignedFrameHeaderField ...
}
```

Two things happen in that compact constructor and its neighbor `toArraySize()` that a bare `long` would leave to the caller: negative sizes are rejected the moment a `ZstdByteSize` exists, and narrowing to an `int` for a `byte[]` allocation is a checked, named failure (`ZstdException`) rather than a truncation or a raw `ArithmeticException` leaking out of unrelated code. A zstd frame header is attacker-controlled data — the decompressed-size field a hostile frame declares becomes exactly the allocation `ZstdByteSize.fromFrameContentSize` is asked to trust. Wrapping it in a validating type at the boundary means the decompression bomb question gets answered once, in one constructor, instead of at every call site that happens to remember to check.

`ZstdCompressionLevel`, `ZstdWindowLog`, and `ZstdMagicVariant` follow the same shape: a raw `int` that used to be "whatever fits in 32 bits" becomes "whatever libzstd actually accepts," checked at construction against the linked library's own reported bounds. The changelog is explicit that this was a breaking change on purpose — `Zstd.maxCompressionLevel()` and friends stopped being public `int` queries; you get `ZstdCompressionLevel.MAX` as a value object instead, and reach for `.value()` only at the one line that hands it to native code.

## What's still an identity class

None of this reaches for Valhalla. `ZstdByteSize` and its siblings are ordinary `record`s — identity classes, one heap allocation and one header per value, exactly the shape [Rethink Domain Primitives with Valhalla](https://dfa1.github.io/articles/rethink-domain-primitives-with-valhalla.html) measured at ~4× the size of the bare primitive they wrap. zstd-java targets JDK 25, the first LTS with stable FFM; `value class` is still a JEP 401 preview feature on JDK 27 EA[^valhalla-jep], and shipping a public API against a preview feature isn't a trade a library on Maven Central gets to make yet.

It's a cost worth naming rather than hiding: a streaming loop that constructs a fresh `ZstdByteSize` per chunk is allocating a small object per chunk, on top of whatever the native call itself costs. At the API boundary — one call, one size, one level — that's noise. Inside a hot per-record loop, it's the same overhead the Valhalla article measured, and today there's no flag to turn it off.

What the migration would cost, though, is small precisely because the type is already shaped for it. `ZstdByteSize` is `final`, immutable, has no synchronization, no identity-sensitive comparison — nothing in its behavior depends on being a distinct object rather than a flattened value. The path the Valhalla article lays out — keep the fields and the validation, change the declaration from `record` to `value class`, write out the accessor the record used to generate for free — is mechanical, not a redesign. The domain-primitive sweep in v0.12 already did the hard part: it decided every size, level, and window log at the API boundary deserves a real type. Whether that type costs a heap allocation or nothing at all is a decision the JVM gets to make later, once it's allowed to.

## Three things, one library

FFM is the cornerstone: a typed, permissioned model that gives the JVM something to gate and gives a library something to wrap, where JNI and `Unsafe` gave it nothing. A module boundary decides which code is trusted to call into libzstd at all. A domain primitive decides what that trusted code is allowed to hand it once it's inside. Take any one away and the other two don't hold: without FFM, there's no restricted operation to scope a module around and no typed value to validate; with FFM alone, a perfectly safe `MemorySegment` still says nothing about who should be dereferencing it or whether the size behind it came from a hostile frame. Wrapping native code safely means building on the cornerstone, not stopping at it.

---

[^ffm-jep]: [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454) — final in JDK 22; JDK 25 is the first LTS to carry it as a stable, non-preview feature.

[^adr-0011]: [ADR 0011 — JPMS module descriptor](https://github.com/dfa1/zstd-java/blob/master/adr/0011-jpms-module-descriptor.md), zstd-java, accepted 2026-06-27.

[^changelog]: [CHANGELOG.md](https://github.com/dfa1/zstd-java/blob/master/CHANGELOG.md), zstd-java v0.12, 2026-07-26.

[^valhalla-jep]: [JEP 401: Value Classes and Objects](https://openjdk.org/jeps/401) — preview, current EA builds target JDK 27.
