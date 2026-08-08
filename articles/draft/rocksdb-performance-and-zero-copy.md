# RocksDB Performance and Zero-Copy

*8 August 2026*

*[rocksdbffm](https://github.com/dfa1/rocksdbffm), the FFM-based RocksDB binding I wrote about
[last time](https://dfa1.github.io/articles/java-plus-rocksdb-minus-jni.html), just picked up a
genuinely zero-copy read path: RocksDB's C API grew a `rocksdb_pinnable_handle_t` that hands back
a pointer straight into the block cache, no intermediate copy at all. The idea is to try to build
an high-performance API with Java and FFM.

---

## The new C API

Recently the RocksDB C API added some new functions for zero-copy reads[^c-header]:

```c
/* High-performance zero-copy Get variants
   These functions avoid unnecessary memory allocations and copies.
   The returned buffer is valid until the handle is destroyed.
   Bindings should migrate to these for better performance. */

/* Zero-copy get that returns a handle to pinned data.
   The data remains valid until rocksdb_pinnable_handle_destroy is called.
   Returns NULL on error or not found. Check errptr to distinguish. */
typedef struct rocksdb_pinnable_handle_t rocksdb_pinnable_handle_t;

rocksdb_pinnable_handle_t* rocksdb_get_pinned_v2(
    rocksdb_t* db, const rocksdb_readoptions_t* options,
    const char* key, size_t keylen, char** errptr);

const char* rocksdb_pinnable_handle_get_value(
    const rocksdb_pinnable_handle_t* handle, size_t* vallen);

void rocksdb_pinnable_handle_destroy(rocksdb_pinnable_handle_t* handle);
```

Semantics aren't fully visible from the signatures: a `NULL` return means either
"not found" or "error," distinguished only by `errptr`, so it has to be checked first. And the
pointer `get_value` hands back is only valid until `destroy` runs — `destroy` drops the block-cache
reference, and the bytes can be evicted immediately after.

That second point is the whole design problem. In Java, "valid until you call this other function"
is exactly the kind of contract that turns into a use-after-free the first time a caller stores the
pointer somewhere and reads it later. The API needed to make that mistake impossible, not just
document it.

## Scoping the pointer to a callback

The most natural design is a callback running inside a well-defined scope, where the data stays
"pinned" for the callback's duration. Other bindings already commit to that shape: `rust-rocksdb`'s
`get_pinned` returns a `DBPinnableSlice` implementing `Deref<Target = [u8]>`, length intrinsic to
the borrow, with no caller-supplied-buffer API at all; `grocksdb` does the same. `rocksdbjni` didn't,
and upstream regrets it — its own source carries a TODO admitting as much: "we should improve the
`#get()` API, returning -1 (`RocksDB.NOT_FOUND`) is not very nice."

`withPinned` wraps the handle in a scoped callback instead of returning the raw view:

```java
public <R> Optional<R> withPinned(MemorySegment key, PinnedReader<R> fn) throws Exception {
    return RocksDB.withPinned(ptr(), readOpts.ptr(), key, fn);
}
```

Internally, that call threads two callbacks through one core method — the native downcall and `fn`
itself:

```java
private interface PinnedGet {
    MemorySegment invoke(MemorySegment errptr) throws Throwable;
}
```

Called as `withPinned0(err -> (MemorySegment) MH_GET_PINNED_V2.invokeExact(db, readOpts, key, key.byteSize(), err), fn)`
— one callback for the downcall, one for the caller. That double callback comes back into the story
later, when the GC profiler shows what it actually costs.

`PinnedReader<R>` is `R read(MemorySegment value) throws Exception`. The `MemorySegment` handed to
`fn` is bound to a confined [`Arena`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Arena.html)
that closes the moment `fn` returns — before the native handle is destroyed via `rocksdb_pinnable_handle_destroy`.


## The benchmark I expected to be boring

Three read paths do the same thing with different cost profiles: `get(byte[])` allocates and
returns a new array on every call; `get(MemorySegment, MemorySegment)` copies into a buffer the
caller preallocated once; `withPinned` copies nothing at all. I expected `withPinned` to win, and
to win more as values got bigger — more bytes not copied should mean more time saved.

A JMH size sweep from 8 bytes to 1 MB on an Apple M5 MacBook, GC profiler attached, said
otherwise[^bench-before]:

| Value size | `byte[]` | `MemorySegment` | `withPinned` | `withPinned` alloc/op | vs `byte[]` | vs `MemorySegment` | `withPinned`/`byte[]` |
|---|---|---|---|---|---|---|---|
| 8 B | 6.87M ops/s | 7.53M ops/s | 6.29M ops/s | 392 B | -8.4% | -16.4% | 0.92x |
| 16 B | 7.05M ops/s | 6.79M ops/s | 6.52M ops/s | 392 B | -7.5% | -4.0% | 0.92x |
| 1 KB | 4.81M ops/s | 4.89M ops/s | 5.09M ops/s | 288 B | +5.9% | +4.1% | 1.06x |
| 4 KB | 3.09M ops/s | 4.04M ops/s | 4.39M ops/s | 416 B | +42.0% | +8.8% | 1.42x |
| 64 KB | 336K ops/s | 452K ops/s | 700K ops/s | 416 B | +108.1% | +54.9% | 2.08x |
| 1 MB | 26.2K ops/s | 33.3K ops/s | 76.4K ops/s | 417 B | +191.4% | +129.5% | 2.91x |

At 8 and 16 bytes, the zero-copy path was the *slowest* of the three — 0.92x `byte[]`. Not by a
rounding error, by 8 to 16 percent.

## What the GC profiler actually measured

Throughput alone doesn't say why. `gc.alloc.rate.norm` — bytes allocated per operation — filled in
the rest of the picture. Restated alongside `byte[]` and `MemorySegment`'s own allocation curves:

| Value size | `byte[]` alloc/op | `MemorySegment` alloc/op | `withPinned` alloc/op |
|---|---|---|---|
| 8 B | 168 B | 96 B | 392 B |
| 16 B | 176 B | 296 B | 392 B |
| 1 KB | 1,184 B | 296 B | 288 B |
| 4 KB | 4,256 B | 296 B | 416 B |
| 64 KB | 65,696 B | 296 B | 416 B |
| 1 MB | 1,048,738 B | 297 B | 417 B |

`byte[]`'s curve is exactly `value_size + ~160 B` — a heap array that grows with the payload, plus
constant `PinnableSlice` bookkeeping. `MemorySegment`'s is flat, since the buffer is preallocated
once outside the benchmark loop and reused. `withPinned`'s is *also* flat — proof it never touches
the value bytes — but flat at roughly 400 bytes, not zero. "Zero-copy" describes what happens to
the value. It says nothing about what happens to the call.

Two things were allocating on every single invocation, neither of them the value:

**A closure per call.** The two public overloads — plain key, and key plus column family — shared
one core method that owned the arena-then-destroy lifetime logic, so a divergence between their
`finally` blocks couldn't happen by construction. The mechanism was a
`PinnedGet` functional interface:

```java
private interface PinnedGet {
    MemorySegment invoke(MemorySegment errptr) throws Throwable;
}
```

Called as `withPinned0(err -> (MemorySegment) MH_GET_PINNED_V2.invokeExact(db, readOpts, key, key.byteSize(), err), fn)`.
That lambda captures `db`, `readOpts`, and `key` — three references, non-static, a fresh closure
object on every call. The mechanism built to guarantee the *safety* property was quietly taxing
the *performance* property it wasn't supposed to touch at all.

**Two native scratch allocations where one would do.** `errptr` needs an 8-byte native slot; so
does `get_value`'s `size_t* vallen` out-param. The original code allocated both, separately, every
call — but by the time `get_value` runs, `checkError` has already consumed and discarded the
`errptr` slot. Nothing was using it anymore.

## The benchmark that surprised me (again!)

Split the "which native symbol" part from the "how the lifetime works" part, without losing the
guarantee that made the split necessary in the first place:

```java
static <R> Optional<R> withPinned(MemorySegment db, MemorySegment readOpts,
                                   MemorySegment key, PinnedReader<R> fn) throws Exception {
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment scratch = errHolder(arena);
        MemorySegment handle;
        try {
            handle = (MemorySegment) MH_GET_PINNED_V2.invokeExact(db, readOpts, key, key.byteSize(), scratch);
        } catch (Throwable t) {
            throw RocksDBException.wrap("get_pinned failed", t);
        }
        return withPinned0(arena, scratch, handle, fn);
    }
}
```

The two overloads now do their own downcall directly — a few duplicated lines — and hand the
already-obtained `arena`, `scratch`, and `handle` to `withPinned0` as plain arguments. The
finally-block ordering that arena-close-before-destroy depends on still lives in exactly one
place, unchanged. What moved outside the shared method was never the risk; the code review comment
that mandated the shared core was specifically about divergent cleanup, not about which symbol
gets called. And `scratch` — the same slot already holding the (by-now-checked, dead)
`errptr` — gets reused as `vallen`'s out-param instead of a second `arena.allocate`.

Same benchmark, same machine, same six sizes, GC profiler still attached:

| Value size | `byte[]` | `MemorySegment` | `withPinned` | `withPinned` alloc/op | vs `byte[]` | vs `MemorySegment` | `withPinned`/`byte[]` |
|---|---|---|---|---|---|---|---|
| 8 B | 6.53M ops/s | 6.54M ops/s | 8.18M ops/s | 264 B (was 392) | +25.3% | +25.0% | 1.25x |
| 16 B | 6.26M ops/s | 5.47M ops/s | 7.50M ops/s | 264 B (was 392) | +19.7% | +37.2% | 1.20x |
| 1 KB | 4.78M ops/s | 5.65M ops/s | 6.22M ops/s | 288 B (was 288) | +30.3% | +10.1% | 1.30x |
| 4 KB | 3.14M ops/s | 3.97M ops/s | 5.37M ops/s | 288 B (was 416) | +70.9% | +35.3% | 1.71x |
| 64 KB | 327K ops/s | 436K ops/s | 643K ops/s | 288 B (was 416) | +96.4% | +47.5% | 1.96x |
| 1 MB | 22.0K ops/s | 33.6K ops/s | 77.3K ops/s | 289 B (was 417) | +251.7% | +130.3% | 3.52x |

So avoiding lambda with capture  and one extra allocation in the hot-path really helped.

## Conclusion

Design zero-copy data access is not easy but highly rewarding activity.

The FFM bindings for RocksDB now have a clean way to express zero-copy semantics that, on this
benchmark run, beat the alternatives at every size — especially the large values: the Java layer
hands back a `MemorySegment`, and the rule is simple: don't store it, just read the data.

Take a value stored as a raw 8-byte epoch-millis long and read back as a `java.time.Instant`. The
`byte[]` path allocates an array just to hand it straight to `getLong()` and throw it away:

```java
byte[] raw = rocksdb.get(key);
Instant createdAt = Instant.ofEpochMilli(ByteBuffer.wrap(raw).getLong());
```

The `get(key, fn)` overload parses the long straight out of the pinned view — no array ever exists:

```java
Instant createdAt = rocksdb.get(key, memorySegment -> Instant.ofEpochMilli(memorySegment.get(JAVA_LONG, 0)))
    .orElseThrow();
```

Same design is also applied to the `RocksIterator` and most likely will be applied everywhere else.

If you work with RocksDB in Java and/or you want a concrete project to learn FFM with, [take a look](https://github.com/dfa1/rocksdbffm).


[^c-header]: [`rocksdb_get_pinned_v2` / `rocksdb_get_pinned_cf_v2` / `rocksdb_pinnable_handle_get_value` / `rocksdb_pinnable_handle_destroy`](https://github.com/facebook/rocksdb/blob/abeebd9630f11bd08c28b7bd43c7bdfc62050654/include/rocksdb/c.h#L4687-L4707), `rocksdb/include/rocksdb/c.h`, RocksDB v11.8.1 (the version rocksdbffm currently pins). The API was introduced by [facebook/rocksdb#13911](https://github.com/facebook/rocksdb/pull/13911), "optimize C API to reduce memory allocations and using PinnableSlice for zero-copy reads," first shipped in **v10.9.1**. rocksdbffm tracks binding it as [GitHub issue #55](https://github.com/dfa1/rocksdbffm/issues/55).

