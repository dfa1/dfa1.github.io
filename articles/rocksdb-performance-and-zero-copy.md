# RocksDB Performance and Zero-Copy

*8 August 2026*

*[rocksdbffm](https://github.com/dfa1/rocksdbffm), the [FFM-based](https://openjdk.org/jeps/454) [RocksDB](https://rocksdb.org/) binding I wrote about
[last time](https://dfa1.github.io/articles/java-plus-rocksdb-minus-jni.html). This is my little journey into zero-copy performance.*

---

## Context

The library is still a proposal as I want to explore some new trade-off between proper modeling,
performance, and security by using Java 25.

One central idea to explore is how to express data contracts with types:
- a read-only RocksDB class has no put/delete methods — unlike `rocksdbjni`, where a read-only instance still exposes `delete()`/`put()` that fail at runtime;
- avoid passing pointers as `long`;
- avoid using `int` to deliver errors/domain values;
- get good performance without `Unsafe` (see [here](https://openjdk.org/jeps/471)).

Like `rocksdbjni`, this library exposes every operation in different ways, with and without `ColumnFamily` and with and without `ReadOptions/WriteOptions`:
- `byte[] operation(byte[] key)`, return value is allocated on the Java heap;
- `result operation(ByteBuffer key, ByteBuffer value)`, value is output param that must be big enough to hold the value;
- `result operation(MemorySegment key, MemorySegment value)`, value is output param that must be big enough.

The first one allocates memory proportionally to the request rate, so it is not terribly efficient.
The last two are interesting because they let the user provide the pointer to the memory,
which can be handled by a pool.

Is it possible to do better?

## The new C API for PinnableSlice

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

## Zero-copy mapping callback

How to map this in Java? The most natural design is a callback running inside a well-defined scope,
where the data stays "pinned" for the callback's duration:

```java
public <R> Optional<R> get(MemorySegment key, Mapper<R> fn) {
    return RocksDB.withPinned(ptr(), readOpts.ptr(), key, fn);
}
```

`Mapper<R>` is a single method, `R map(MemorySegment value)`. The `MemorySegment` handed to
`fn` is bound to a confined [`Arena`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Arena.html)
owned by the caller: `fn` returns, the handle is destroyed in a `finally`, and the arena closes
immediately after, on the way out. Escaping the view therefore fails loudly rather than silently
reading freed memory: `IllegalStateException` once the arena closes, `WrongThreadException` if
another thread touches it.

The pin is what keeps the bytes alive, and it lasts exactly as long as the callback:

```
withPinned(key, fn) — who holds the bytes, and for how long

   ┌ rocksdb_get_pinned_v2         block-cache page is pinned here
   │
   │   fn(view)                    the only window where the pointer
   │                               is live — reads land in the block
   │                               cache itself, nothing is copied
   │
   ├ rocksdb_pinnable_handle_destroy
   │                               page unpinned, bytes may be evicted
   │
   └ arena.close()                 an escaped view now throws:
                                   IllegalStateException on this thread,
                                   WrongThreadException from any other
```

The narrow gap between `destroy` and `arena.close()` is real, but no caller code runs inside it —
it is a single native call followed immediately by the resource close, with nothing in between.

## The benchmark I expected to be boring

So let's try to validate the idea with JMH for the various "styles" of operation.
The idea is to isolate and measure only the *overhead of the library*, not RocksDB itself.
Drawn out, the difference is where the value bytes end up:

```
get(byte[] key) — convenience tier

   JVM heap                 │  native (RocksDB)
                            │
   new byte[valueSize] ◄──copy──  pinned block-cache page
                            │
   1 value-sized allocation + 1 copy — both grow with the value


get(ByteBuffer key, ByteBuffer dst) — buffer tier
get(MemorySegment key, MemorySegment dst) — buffer tier

   JVM heap                 │  native (RocksDB)
                            │
   caller's dst        ◄──copy──  pinned block-cache page
   (allocated once)         │
                            │
   0 allocations + 1 copy — the copy still grows with the value


get(MemorySegment key, Mapper<R> fn) — pinned tier

   JVM heap                 │  native (RocksDB)
                            │
   fn(view) ──reads through────►  pinned block-cache page
                            │
   0 allocations + 0 copies — flat, whatever the value size
```

Only the third one is zero-copy in the strict sense: the `Mapper` receives a "safe" pointer
to the memory holding the value and it can be used to deserialize the bytes back to Java objects.

A JMH benchmark with a value-size sweep from 8 bytes to 1 MB on an Apple M5 MacBook, GC profiler attached, said otherwise:

| Value size | `byte[]` | `MemorySegment` | zero-copy `Mapper` | zero-copy `Mapper` alloc/op | vs `byte[]` | vs `MemorySegment` | zero-copy `Mapper`/`byte[]` |
|---|---|---|---|---|---|---|---|
| 8 B | 6.87M ops/s | 7.53M ops/s | 6.29M ops/s | 392 B | -8.4% | -16.4% | 0.92x |
| 16 B | 7.05M ops/s | 6.79M ops/s | 6.52M ops/s | 392 B | -7.5% | -4.0% | 0.92x |
| 1 KB | 4.81M ops/s | 4.89M ops/s | 5.09M ops/s | 288 B | +5.9% | +4.1% | 1.06x |
| 4 KB | 3.09M ops/s | 4.04M ops/s | 4.39M ops/s | 416 B | +42.0% | +8.8% | 1.42x |
| 64 KB | 336K ops/s | 452K ops/s | 700K ops/s | 416 B | +108.1% | +54.9% | 2.08x |
| 1 MB | 26.2K ops/s | 33.3K ops/s | 76.4K ops/s | 417 B | +191.4% | +129.5% | 2.91x |

At 8 and 16 bytes, the zero-copy path was the *slowest* of the three — 0.92x `byte[]`. Not by a
rounding error, by 8 to 16 percent.

How many allocations per operation?

| Value size | `byte[]` alloc/op | `MemorySegment` alloc/op | zero-copy `Mapper` alloc/op |
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
little is left to allocate is native-call bookkeeping, not the value.

It is already good, but two things were allocating on every single invocation, neither of them the value:

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
static <R> Optional<R> map(MemorySegment db, MemorySegment readOpts,
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

Same benchmark, same machine, same six sizes, GC profiler still attached:

| Value size | `byte[]` | `MemorySegment` | zero-copy `Mapper` | zero-copy `Mapper` alloc/op | vs `byte[]` | vs `MemorySegment` | `withPinned`/`byte[]` |
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
not the right one. The GC relief is the better story there anyway: allocation per operation
falls from a megabyte to a flat ~289 bytes.

## The final real-world benchmark

Both tables above come from a benchmark that seeded one key and measured without flushing, so every
read resolved from the memtable. All three tiers hit the same memtable, so the comparison between
them survives — but the absolute throughput is higher than a real database gives, and one fork is not
enough to separate tiers that land within a few percent of each other.

Rebuilt against a populated database (few thousand keys, flushed and compacted before measuring)
with GC profiler attached:

| Value size | `byte[]` (ops/s) | `MemorySegment` (ops/s) | zero-copy `Mapper` (ops/s) | zero-copy `Mapper` vs `byte[]` |
|---|---|---|---|---|
| 8 B | 2,268,074 ± 8,587 | 2,231,895 ± 22,794 | 2,236,311 ± 16,273 | **−1.4%** |
| 16 B | 1,848,519 ± 7,001 | 1,824,674 ± 24,329 | 1,800,191 ± 9,366 | **−2.6%** |
| 1 KB | 2,415,181 ± 31,935 | 2,524,256 ± 16,807 | 2,553,068 ± 15,267 | +5.7% |
| 4 KB | 1,913,045 ± 70,820 | 2,317,311 ± 24,793 | 2,534,819 ± 17,341 | +32.5% |
| 64 KB | 524,415 ± 1,985 | 820,967 ± 25,206 | 2,665,982 ± 22,768 | +408% |
| 1 MB | 39,784 ± 139 | 74,513 ± 836 | 2,716,631 ± 36,222 | +6728% |

Zero-copy is *not* free at the small end. At 8 and 16 bytes it loses to the copying path by 1.4% and
2.6%, and those confidence intervals do not overlap — it is a real effect, not noise. The per-call
machinery — chiefly a confined `Arena` — costs more than copying eight bytes does. The crossover
sits somewhere between 16 bytes and 1 KB, and past it the gap opens fast: 32% at 4 KB, 4x at 64 KB,
68x at 1 MB.

That is the honest shape of the feature. Zero-copy reads are not a blanket win to switch on
everywhere; they are a large win above roughly a kilobyte, a wash in the hundreds of bytes, and a
small loss on tiny values. Allocation tells the same story more starkly, and with far less
measurement noise — it is flat at 224–248 B/op across the whole range, while `byte[]` climbs to just
over a megabyte per operation:

| Value size | `byte[]` alloc/op | `MemorySegment` alloc/op | `withPinned` alloc/op |
|---|---|---|---|
| 8 B | 224 B | 160 B | 224 B |
| 16 B | 232 B | 160 B | 224 B |
| 1 KB | 1,240 B | 160 B | 248 B |
| 4 KB | 4,312 B | 160 B | 248 B |
| 64 KB | 65,752 B | 160 B | 248 B |
| 1 MB | 1,048,793 B | 161 B | 248 B |

`byte[]` is exactly `value_size + 216 B`, linear to within a byte across four orders of magnitude.
If you only remember one row, make it the last one: a megabyte of copy per read, versus 248 bytes.

## Example

Take a value stored as a raw 8-byte epoch-millis long and read back as a `java.time.Instant`. The
`byte[]` path allocates an array just to hand it straight to `getLong()` and throw it away:

```java
byte[] raw = rocksdb.get(key);
Instant createdAt = Instant.ofEpochMilli(ByteBuffer.wrap(raw).getLong());
```

The `get(key, fn)` overload parses the long straight out of the pinned view — no array ever exists:

```java
Optional<Instant> createdAt = rocksdb.get(key, memorySegment -> Instant.ofEpochMilli(memorySegment.get(JAVA_LONG, 0)));
```

The same design also applies to `RocksIterator`, which can iterate over keys and values
without ever allocating a `byte[]` per item.

## Better return type when data must be copied

Zero-copy is not always needed: often data needs to be copied somewhere.
Initially, the library used the same pin/unpin + copy pattern:
- pin
- copy the data into the specified buffer
- unpin

Those are three native calls per operation — can we do better?

The same RocksDB release also delivered `rocksdb_get_into_buffer`: one native call that
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

The C++ implementation performs the same logical steps — pin, copy, unpin — that the earlier code
did by hand, just collapsed into a single native call:
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

An early benchmark against the current `byte[]` path shows it ~15% faster at identical allocation
per op, purely from collapsing three native calls into one.

The catch is the return shape: a fit-or-too-small flag plus two out-params (`vallen`, `found`) doesn't
fit the `-1`-or-length `int` the rest of the read path still returns. The fix in progress is a sealed
`CopyResult` — `Copied`, `NotEnoughCapacity(long required)`, `NotFound` — so a `switch` over it is
exhaustive and no `int` is doing triple duty as length, sentinel, and error code anymore.
`CopyResult` documents what happened right in the type instead of leaving it encoded in a number the
caller has to interpret correctly — the retry path can't be skipped silently, it has to be named as
its own case:

```java
ByteBuffer buffer = pool.acquire();
try {
    CopyResult result = rocksdb.get(key, buffer);
    switch (result) {
        case Copied copied -> process(buffer);
        case NotEnoughCapacity(long required) -> { /* propagate error or retry with a buffer sized to required */ }
        case NotFound notFound -> { /* nothing to process */ }
   }
} finally {
    pool.release(buffer);
}
```

No `default` branch because `CopyResult` is a sealed interface, so the compiler rejects the `switch` if a variant is ever added and
left unhandled. Same idea as
[making illegal state unrepresentable](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team.html):
once "not enough capacity" is its own type instead of a magic number, forgetting to check it becomes
a compile error, not a runtime surprise.

## Conclusion

Designing zero-copy data access isn't easy, but it's worth it — it makes systems that use it
more scalable and cheaper, as discussed
[here](https://dfa1.github.io/articles/decouple-allocations-from-request-volume.html).

These bindings for RocksDB now have a clean way to express zero-copy semantics — one that costs a
couple of percent on tiny values, pays for itself somewhere under a kilobyte, and wins by orders of
magnitude on large ones. The Java layer hands back a read-only `MemorySegment`, and the rule is simple: don't store it, just read the data. At the same time, when a copy is needed, the library can express it more precisely.

If you work with RocksDB in Java, or want a concrete project to learn FFM, [take a look](https://github.com/dfa1/rocksdbffm).

[^c-header]: [`rocksdb_get_pinned_v2` / `rocksdb_get_pinned_cf_v2` / `rocksdb_pinnable_handle_get_value` / `rocksdb_pinnable_handle_destroy`](https://github.com/facebook/rocksdb/blob/abeebd9630f11bd08c28b7bd43c7bdfc62050654/include/rocksdb/c.h#L4687-L4707), `rocksdb/include/rocksdb/c.h`, RocksDB v11.8.1 (the version rocksdbffm currently pins). The API was introduced by [facebook/rocksdb#13911](https://github.com/facebook/rocksdb/pull/13911), "optimize C API to reduce memory allocations and using PinnableSlice for zero-copy reads," first shipped in **v10.9.1**. rocksdbffm tracks binding it as [GitHub issue #55](https://github.com/dfa1/rocksdbffm/issues/55).
