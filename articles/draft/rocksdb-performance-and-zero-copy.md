# RocksDB Performance and Zero-Copy

*8 August 2026*

*[rocksdbffm](https://github.com/dfa1/rocksdbffm), the FFM-based RocksDB binding I wrote about
[last time](https://dfa1.github.io/articles/java-plus-rocksdb-minus-jni.html), just picked up a
genuinely zero-copy read path: RocksDB's C API grew a `rocksdb_pinnable_handle_t` that hands back
a pointer straight into the block cache, no intermediate copy at all. Getting that pointer into Java
safely turned out to be the easy part. Making it fast was where the surprises were — twice.*

---

## The new C API

RocksDB v10.9.1 added a family of zero-copy read functions to the C API[^c-header]:

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

So the public read overload wraps the handle in a scoped callback instead of returning the raw view:

```java
public <R> Optional<R> get(MemorySegment key, Mapper<R> fn) {
    return RocksDB.withPinned(ptr(), readOpts.ptr(), key, fn);
}
```

Internally, that call threaded *two* callbacks through one shared core method — one for the native
downcall, one for the caller's `fn`. That second callback is the interesting one, and it comes back
into the story later, when the GC profiler shows what it was quietly costing.

`Mapper<R>` is a single method, `R map(MemorySegment value)`. The `MemorySegment` handed to
`fn` is bound to a confined [`Arena`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Arena.html)
that closes the moment `fn` returns — before the native handle is destroyed via `rocksdb_pinnable_handle_destroy`.
Escaping the view therefore fails loudly rather than reading freed memory: `IllegalStateException` if
it is used after the call returns, `WrongThreadException` if another thread touches it.

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
constant `PinnableSlice` bookkeeping. `MemorySegment`'s stays flat regardless of value size, since the
destination buffer is preallocated once outside the benchmark loop and handed in by the caller — what
little is left to allocate is native-call bookkeeping, not the value, which is also why the 8 B row
dips to 96 B instead of the usual ~296 B: less bookkeeping for the smallest call. `withPinned`'s is
*also* flat — proof it never touches the value bytes — but flat at roughly 400 bytes, not zero.
"Zero-copy" describes what happens to the value. It says nothing about what happens to the call.

Two things were allocating on every single invocation, neither of them the value:

**A closure per call.** The two public overloads — plain key, and key plus column family — shared
one core method (`withPinned0`, `withPinnedCore` in the repo today) that owned the arena-then-destroy
lifetime logic, so a divergence between their `finally` blocks couldn't happen by construction. The
mechanism was a `PinnedGet` functional interface:

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
                                   MemorySegment key, Mapper<R> fn) {
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

So avoiding the lambda's capture and the one extra allocation in the hot path really helped.

One caveat about the right-hand end of that table, because it is easy to quote out of context: the
`withPinned` benchmark maps with `MemorySegment::byteSize`. It pins the value and reads its *length* —
it never touches the megabyte itself. That is a real usage pattern (checking a size, reading a header,
parsing a prefix), and it is the pattern the callback API exists to serve, but it is the best case.

The three columns actually decompose the cost. At 1 MB, `byte[]` both allocates a megabyte on the heap
and copies into it (22.0K ops/s); `MemorySegment` only copies, into a buffer the caller preallocated
once (33.6K ops/s); `withPinned` does neither (77.3K ops/s). So dropping the *allocation* alone buys
roughly 1.5x, and the remaining jump is the copy — which only disappears if you genuinely do not need
the bytes materialized. If your consumer has to read all 1 MB, the honest number is the middle column,
not the right one. The GC relief is arguably the better story there anyway: allocation per operation
falls from a megabyte to a flat ~289 bytes.

## What's next: `copy_into_buffer` and better return types

The same RocksDB release also grew `rocksdb_get_into_buffer`[^get-into-buffer]: one native call that
copies straight into a caller-provided buffer, returns `1`/`0` for fit-or-not, and always sets
`vallen` to the real value size — no pin, read pointer, destroy round trip:

```c
/* Direct get into caller-provided buffer.
   Returns 1 if value fits in buffer, 0 if buffer too small.
   Sets *vallen to actual value size.
   If buffer is too small, no data is copied but *vallen is set. */
extern ROCKSDB_LIBRARY_API unsigned char rocksdb_get_into_buffer(
    rocksdb_t* db, const rocksdb_readoptions_t* options, const char* key,
    size_t keylen, char* buffer, size_t buffer_size, size_t* vallen,
    unsigned char* found, char** errptr);

extern ROCKSDB_LIBRARY_API unsigned char rocksdb_get_into_buffer_cf(
    rocksdb_t* db, const rocksdb_readoptions_t* options,
    rocksdb_column_family_handle_t* column_family, const char* key,
    size_t keylen, char* buffer, size_t buffer_size, size_t* vallen,
    unsigned char* found, char** errptr);
```

The implementation is a thin wrapper over the same `PinnableSlice`-based `Get` used everywhere
else — a `memcpy` happens only if the caller's buffer is already big enough, otherwise the real size
comes back for free in `*vallen`[^get-into-buffer-impl]:

```c
unsigned char rocksdb_get_into_buffer(rocksdb_t* db,
                                      const rocksdb_readoptions_t* options,
                                      const char* key, size_t keylen,
                                      char* buffer, size_t buffer_size,
                                      size_t* vallen, unsigned char* found,
                                      char** errptr) {
  PinnableSlice pinnable_val;
  Status s = db->rep->Get(options->rep, db->rep->DefaultColumnFamily(),
                          Slice(key, keylen), &pinnable_val);
  if (s.ok()) {
    *found = 1;
    *vallen = pinnable_val.size();
    if (buffer_size >= pinnable_val.size()) {
      memcpy(buffer, pinnable_val.data(), pinnable_val.size());
      return 1;  // Success - data copied
    }
    return 0;  // Buffer too small
  } else {
    *found = 0;
    *vallen = 0;
    if (!s.IsNotFound()) {
      SaveError(errptr, s);
    }
    return 0;
  }
}
```

`rocksdb_get_into_buffer_cf` is the same body against a caller-supplied column family instead of
`DefaultColumnFamily()`.

Early benchmarking against the current `byte[]` path shows it ~13.6% faster at identical allocation
per op, purely from collapsing three native calls into one[^get-into-buffer-bench].

The catch is the return shape: a fit-or-too-small flag plus two out-params (`vallen`, `found`) doesn't
fit the `-1`-or-length `int` the rest of the read path still returns. The fix in progress is a sealed
`CopyResult` — `Copied`, `NotEnoughCapacity(long required)`, `NotFound` — so a `switch` over it is
exhaustive and no `int` is doing triple duty as length, sentinel, and error code anymore[^sealed-result].
`CopyResult` documents what happened right in the type instead of leaving it encoded in a number the
caller has to interpret correctly — the retry path can't be skipped silently, it has to be named as
its own case:

```java
ByteBuffer buffer = pool.acquire();
CopyResult result = rocksdb.get(key, buffer);
switch (result) {
    case Copied copied -> process(buffer);
    case NotEnoughCapacity(long required) -> { /* propagate error or retry with a buffer sized to required */ }
    case NotFound notFound -> { /* nothing to process */ }
}
```

No `default` branch — the compiler rejects the `switch` if a `CopyResult` variant is ever added and
left unhandled. Same idea as
[making illegal state unrepresentable](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team.html):
once "not enough capacity" is its own type instead of a magic number, forgetting to check it becomes
a compile error, not a runtime surprise.

## Conclusion

Designing zero-copy data access isn't easy — but it's worth it.

The FFM bindings for RocksDB now have a clean way to express zero-copy semantics that, on this
benchmark run, beat the alternatives at every size measured — by a little on small values, by a lot
on large ones, and most decisively on allocation. The Java layer hands back a `MemorySegment`, and
the rule is simple: don't store it, just read the data.

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

The same design is also applied to `RocksIterator`, and will likely extend to the rest of the read path.

If you work with RocksDB in Java, or want a concrete project to learn FFM with, [take a look](https://github.com/dfa1/rocksdbffm).

[^c-header]: [`rocksdb_get_pinned_v2` / `rocksdb_get_pinned_cf_v2` / `rocksdb_pinnable_handle_get_value` / `rocksdb_pinnable_handle_destroy`](https://github.com/facebook/rocksdb/blob/abeebd9630f11bd08c28b7bd43c7bdfc62050654/include/rocksdb/c.h#L4687-L4707), `rocksdb/include/rocksdb/c.h`, RocksDB v11.8.1 (the version rocksdbffm currently pins). The API was introduced by [facebook/rocksdb#13911](https://github.com/facebook/rocksdb/pull/13911), "optimize C API to reduce memory allocations and using PinnableSlice for zero-copy reads," first shipped in **v10.9.1**. rocksdbffm tracks binding it as [GitHub issue #55](https://github.com/dfa1/rocksdbffm/issues/55).

[^bench-before]: [`FfmBlobSizeBenchmark`](https://github.com/dfa1/rocksdbffm/blob/0cfd631/benchmarks/src/test/java/io/github/dfa1/rocksdbffm/benchmark/FfmBlobSizeBenchmark.java) `readsBlobViaByteArray`/`readsBlobViaMemorySegment`/`readsBlobViaPinned`, `@Param({"8","16","1024","4096","65536","1048576"})` on `blobValueSize`. Pre-optimization numbers from commit [0cfd631](https://github.com/dfa1/rocksdbffm/commit/0cfd631), 2 forks, 5 warmup + 5 measurement iterations at 1 s each, `-prof gc`, JDK 25 on an Apple M5. At that commit the benchmark seeded a single key and measured without flushing, so every read resolved from the memtable; absolute throughput is therefore higher than a populated database would give, though all three tiers hit the same memtable and the comparison between them holds. The benchmark has since been renamed `FfmValueSizeBenchmark` and given a populated, flushed and compacted dataset.

[^get-into-buffer]: [`rocksdb_get_into_buffer` / `rocksdb_get_into_buffer_cf`](https://github.com/facebook/rocksdb/blob/abeebd9630f11bd08c28b7bd43c7bdfc62050654/include/rocksdb/c.h#L4709-L4722), `rocksdb/include/rocksdb/c.h`, RocksDB v11.8.1.

[^get-into-buffer-impl]: [`rocksdb_get_into_buffer` / `rocksdb_get_into_buffer_cf`](https://github.com/facebook/rocksdb/blob/abeebd9630f11bd08c28b7bd43c7bdfc62050654/db/c.cc#L13618-L13669), `rocksdb/db/c.cc`, RocksDB v11.8.1.

[^get-into-buffer-bench]: [rocksdbffm issue #52](https://github.com/dfa1/rocksdbffm/issues/52): `getViaCopy` (one `rocksdb_get_into_buffer` call into a pre-sized `ByteBuffer`) at 7,454,324 ± 61,687 ops/s vs. `readsBytes` (today's `rocksdb_get_pinned` round trip) at 6,561,700 ± 78,596 ops/s — 5 forks, 10 measurement iterations, non-overlapping 99.9% confidence intervals, identical 192 B/op allocation on both paths.

[^sealed-result]: [rocksdbffm issue #47](https://github.com/dfa1/rocksdbffm/issues/47), "Replace int/long sentinel returns on the read path with a sealed result hierarchy." Proposed shape: `public sealed interface CopyResult permits Copied, NotEnoughCapacity, NotFound`.

