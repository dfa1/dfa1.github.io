# RocksDB Performance and Zero-Copy

*8 August 2026*

*[rocksdbffm](https://github.com/dfa1/rocksdbffm), the [FFM-based](https://openjdk.org/jeps/454) [RocksDB](https://rocksdb.org/) binding I wrote about
[last time](https://dfa1.github.io/articles/java-plus-rocksdb-minus-jni.html). Here's what is next: trying to achieve zero-copy between C++ and Java.*

---

## Context

The library is still a proposal as I want to explore new trade-offs between proper modeling,
performance, and security by using Java 25.

One central idea to explore is how to express data contracts with types:
- a read-only RocksDB class has no put/delete methods;
- avoid using `int` to deliver errors/domain values, embracing domain primitives;
- get good performance without `Unsafe` (see [here](https://openjdk.org/jeps/471)).

Like `rocksdbjni`, this library exposes every operation in different ways, with and without `ColumnFamily` and with and without `ReadOptions/WriteOptions`. These are the supported types:
- `byte[] operation(byte[] key)`, return value is allocated on the Java heap;
- `result operation(ByteBuffer key, ByteBuffer value)`, value is output param that must be big enough to hold the value;
- `result operation(MemorySegment key, MemorySegment value)`, value is output param that must be big enough.

The first one allocates memory proportionally to the request rate, so it is not terribly efficient.
The last two are interesting because they let the user provide the pointer to the memory,
which can be handled by a pool. This has been discussed [here](https://dfa1.github.io/articles/decouple-allocations-from-request-volume.html) and there is a clear trade-off: adding more complexity
in exchange for better performance.

Is it possible to do better?

## The new C API for PinnableSlice

RocksDB v10.9.1 added a family of zero-copy read functions to the C API[^c-header] (rocksdbffm currently pins v11.8.1):

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
owned by the caller: when `fn` returns, the handle is destroyed in a `finally`, and the arena closes
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

The flip side of that guarantee is that `fn` should be fast. The block-cache page stays pinned for
as long as `fn` runs, so doing I/O or anything slow inside it holds that page.

## The benchmark I expected to be boring

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

Only the third one is zero-copy in the strict sense: the `Mapper` receives a pointer
to the memory holding the value and it must be used to deserialize the bytes back to Java objects.

A JMH benchmark with a value-size sweep from 8 bytes to 1 MB on an Apple M5 MacBook (I need to repeat those on a desktop machine eventually), GC profiler attached, said otherwise:[^blob-size-bench]

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

It is already good, but two things still allocate on every single invocation. Here is how to avoid
that, with a few details on the Java side.

This is roughly the code without any extra helper:
```java
static <R> Optional<R> map(MemorySegment db, MemorySegment readOpts,
                            MemorySegment key, Mapper<R> fn) {
    try (Arena arena = Arena.ofConfined()) {
        // allocate a pointer (like char*)
        MemorySegment holder = arena.allocate(ValueLayout.ADDRESS);
        holder.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
        MemorySegment handle;
        try {
            handle = (MemorySegment) MH_GET_PINNED_V2.invokeExact(db, readOpts, key, key.byteSize(), holder);
        } catch (Throwable t) {
            throw RocksDBException.wrap("get_pinned failed", t);
        }
        RocksDB.checkError(holder);
        // second allocation
        MemorySegment lenSeg = arena.allocate(ValueLayout.JAVA_LONG);
        MemorySegment data;
        try {
            data = (MemorySegment) MH_VALUE.invokeExact(ptr(), lenSeg);
        } catch (Throwable t) {
            throw RocksDBException.wrap("pinnableslice value failed", t);
        }
        long len = lenSeg.get(ValueLayout.JAVA_LONG, 0);
        MemorySegment view = data.reinterpret(len, arena, null).asReadOnly();
        R result = fn.map(view);
        Objects.requireNonNull(result, "Mapper.map(MemorySegment) must not return null");
        return result;
    } finally {
        try { MH_PINNABLESLICE_DESTROY.invokeExact(ptr); } catch (Throwable t) { /* ignored */ }
    }
}
```

The final code is something like:
```java
public <R> Optional<R> get(MemorySegment key, Mapper<R> fn) {
    return RocksDB.withPinned(ptr(), readOpts.ptr(), key, fn);
}

// internal plumbing
static <R> Optional<R> withPinned(MemorySegment db, MemorySegment readOpts, MemorySegment key, Mapper<R> fn) {
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment err = errHolder(arena);
        MemorySegment handle = (MemorySegment) MH_GET_PINNED_V2.invokeExact(db, readOpts, key, key.byteSize(), err);
        checkError(err);
        if (MemorySegment.NULL.equals(handle)) {
            return Optional.empty();
        }
        try (PinnableHandle ph = PinnableHandle.wrap(handle)) {
            return Optional.of(ph.map(arena, fn, err));
        }
    } catch (Throwable t) {
        throw RocksDBException.wrap("get_pinned failed", t);
    }
}
```

`PinnableHandle` takes ownership of the C pointer and also implements `map`, like this:
```java
<R> R map(Arena arena, Mapper<R> fn, MemorySegment vallenOut) {
    MemorySegment data = value(vallenOut);
    MemorySegment view = data.reinterpret(vallenOut.get(ValueLayout.JAVA_LONG, 0), arena, null).asReadOnly();
    R result = fn.map(view);
    Objects.requireNonNull(result, "Mapper.map(MemorySegment) must not return null");
    return result;
}
```

## The final real-world benchmark

Both tables above come from a benchmark that seeded one key and measured without flushing, so every
read resolved from the memtable.

Rebuilt against a populated database (a few thousand keys, flushed and compacted before measuring)
with GC profiler attached:[^value-size-bench]

| Value size | `byte[]` (ops/s) | `MemorySegment` (ops/s) | zero-copy `Mapper` (ops/s) | zero-copy `Mapper` vs `byte[]` |
|---|---|---|---|---|
| 8 B | 2,399,580 ± 53,833 | 2,257,043 ± 97,185 | 2,151,024 ± 71,377 | **−10.4%** |
| 16 B | 1,934,265 ± 67,442 | 1,861,753 ± 102,912 | 1,755,841 ± 111,220 | **−9.2%** |
| 64 B | 2,036,742 ± 140,437 | 2,006,506 ± 94,092 | 1,931,452 ± 132,726 | **−5.2%** |
| 128 B | 2,237,487 ± 102,502 | 2,195,471 ± 48,027 | 2,139,850 ± 53,850 | **−4.4%** |
| 1 KB | 2,495,873 ± 154,139 | 2,515,132 ± 106,323 | 2,566,045 ± 40,883 | +2.8% |
| 4 KB | 2,038,732 ± 67,401 | 2,298,188 ± 103,866 | 2,571,069 ± 35,577 | +26.1% |
| 64 KB | 532,291 ± 5,009 | 777,629 ± 107,427 | 2,865,001 ± 32,836 | +438% |
| 1 MB | 39,636 ± 124 | 70,351 ± 1,981 | 2,775,178 ± 37,737 | +6902% |

Zero-copy is *not* free at the small end. The per-call machinery costs more than copying
a few hundred bytes does, consistently. The crossover sits somewhere between 128 bytes and 1 KB,
so it is ideal for small values like a UUID plus a hash plus a few other details. A load based on
blobs could benefit a lot from this zero-copy mapper, as the library overhead is minimal.

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
without ever allocating a `byte[]` per item:

```java
try (RocksIterator it = db.newIterator()) {
     for (it.seekToFirst(); it.isValid(); it.next()) {
         var key   = it.key(this::keyMapper);
         var value = it.value(this::valueMapper);
         // use key and value
     }
}
```

`key` and `value` are domain objects that are mapped without any intermediate copy.

## Better return type when data must be copied

Zero-copy is not always needed: often data needs to be copied somewhere.
Initially, the library used the same pin, copy, unpin pattern:
- pin
- copy the data into the specified buffer
- unpin

Those are three native calls per operation: can we do better?

The same RocksDB release also delivered `rocksdb_get_into_buffer`[^get-into-buffer]: one native call that
copies straight into a caller-provided buffer, returns `1`/`0` for fit-or-not, and always sets
`vallen` to the real value size — no pin, read-pointer, destroy round trip:

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

## Results

As of **rocksdbffm v0.7 plus the fixes above**, the zero-copy read path extends across every DB type
and `RocksIterator`, and a dedicated benchmark puts it up against `rocksdbjni` directly, rather than
only against itself. Two databases (10,000 and 100,000 keys), two value sizes (8 B and 1 KB), flushed
and compacted before measuring:[^scale-bench]

**`iterator.next()` + `value()`: throughput (ops/s)**

| Keys | Value size | FFM `byte[]` | FFM zero-copy | JNI `byte[]` | zero-copy vs FFM `byte[]` | FFM `byte[]` vs JNI |
|---|---|---|---|---|---|---|
| 10,000 | 8 B | 13,930,461 ± 263,642 | 14,998,627 ± 251,180 | 7,724,452 ± 20,241 | +7.7% | +80.3% |
| 10,000 | 1 KB | 7,812,718 ± 65,430 | 11,490,152 ± 85,996 | 5,516,739 ± 6,069 | +47.1% | +41.6% |
| 100,000 | 8 B | 13,564,538 ± 171,143 | 14,825,954 ± 185,709 | 7,566,237 ± 39,757 | +9.3% | +79.3% |
| 100,000 | 1 KB | 2,497,809 ± 40,901 | 3,075,472 ± 15,642 | 2,260,483 ± 7,695 | +23.1% | +10.5% |

**`iterator.next()` + `value()`: allocation (bytes/op)**

| Keys | Value size | FFM `byte[]` | FFM zero-copy | JNI `byte[]` |
|---|---|---|---|---|
| 10,000 | 8 B | 24.0 | 0.0 | 24.0 |
| 10,000 | 1 KB | 1,040.0 | 0.0 | 1,040.0 |
| 100,000 | 8 B | 24.0 | 0.0 | 24.0 |
| 100,000 | 1 KB | 1,040.0 | 0.0 | 1,040.0 |

Iteration is where zero-copy is unambiguous: allocation-free at every scale, 8–47% faster than FFM's
own `byte[]` tier, and 10–80% faster than the JNI binding.

**One honest caveat**: every number in this article, including the v0.7 scale table above, comes from a
single machine (an Apple M5 MacBook, macOS arm64) against a database that's small by production
standards — 10,000 to 100,000 keys, not the millions a real deployment runs against. I'd welcome
feedback on the benchmark methodology itself (JMH fork/warmup counts, whether flushing and compacting
before measuring is representative enough, what else should be controlled for), and help running
`scripts/benchmark.sh ScaleBenchmarkRunner` on Linux and Windows, or against a larger dataset, to see
whether the shape holds. Open an issue on the repo if you try it.

## Conclusion

`rocksdb-ffm` now has a clean way to express zero-copy semantics with good overall performance. The Java layer hands back a read-only `MemorySegment`, and the rule is simple: don't store it, just read the data. At the same time, when a copy is needed, the library can express it explicitly and with more precise return type (`sealed CopyResult hierarchy`).

If you work with RocksDB in Java, or want a concrete project to learn FFM, [take a look](https://github.com/dfa1/rocksdbffm).

---

[^blob-size-bench]: [`FfmBlobSizeBenchmark`](https://github.com/dfa1/rocksdbffm/blob/8377f4c/benchmarks/src/test/java/io/github/dfa1/rocksdbffm/benchmark/FfmBlobSizeBenchmark.java), removed in [`38b32e6`](https://github.com/dfa1/rocksdbffm/commit/38b32e6).

[^value-size-bench]: [`FfmValueSizeBenchmark`](https://github.com/dfa1/rocksdbffm/blob/06641f2/benchmarks/src/test/java/io/github/dfa1/rocksdbffm/benchmark/FfmValueSizeBenchmark.java).

[^scale-bench]: [`ScaleBenchmarkRunner`](https://github.com/dfa1/rocksdbffm/blob/06641f2/benchmarks/src/test/java/io/github/dfa1/rocksdbffm/benchmark/ScaleBenchmarkRunner.java).

[^c-header]: [`rocksdb_get_pinned_v2` / `rocksdb_get_pinned_cf_v2` / `rocksdb_pinnable_handle_get_value` / `rocksdb_pinnable_handle_destroy`](https://github.com/facebook/rocksdb/blob/abeebd9630f11bd08c28b7bd43c7bdfc62050654/include/rocksdb/c.h#L4687-L4707), `rocksdb/include/rocksdb/c.h`, RocksDB v11.8.1 (the version rocksdbffm currently pins). The API was introduced by [facebook/rocksdb#13911](https://github.com/facebook/rocksdb/pull/13911), "optimize C API to reduce memory allocations and using PinnableSlice for zero-copy reads," first shipped in **v10.9.1**. rocksdbffm tracks binding it as [GitHub issue #55](https://github.com/dfa1/rocksdbffm/issues/55).

[^get-into-buffer]: [`rocksdb_get_into_buffer` / `rocksdb_get_into_buffer_cf`](https://github.com/facebook/rocksdb/blob/abeebd9630f11bd08c28b7bd43c7bdfc62050654/include/rocksdb/c.h#L4708-L4721), same file, same commit, same PR ([facebook/rocksdb#13911](https://github.com/facebook/rocksdb/pull/13911)) as the pinned-handle functions above.
