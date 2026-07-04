# Decouple Allocations from Request Volume

*30 November 2023*

*Millions of requests per day. [RocksDB](https://rocksdb.org/)
as the storage engine, returning variable-size values. Nothing about this load profile suggests
a performance problem.*
*The implementation has a structural flaw: allocation rate scales with
request rate, when it should scale with concurrency.*

## The Starting Point

Years ago, a POC explored RocksDB as the storage engine for the project. With
a lot of ground to cover, the data-fetching path was kept deliberately simple:

```java
Key key = ...; // request from the caller
byte[] keyBytes = serializeKey(key);
byte[] valueBytes = db.get(keyBytes);
if (valueBytes == null) {
    return null; // key not found
}
Value value = deserializeValue(valueBytes);
```

Both `serializeKey` and `deserializeValue` allocate extra objects to implement the serialization logic:
```java
ByteArrayOutputStream baos = new ByteArrayOutputStream();
DataOutputStream dos = new DataOutputStream(baos);
serializeKey(key, dos);
byte[] keyBytes = baos.toByteArray();
```

### Why a custom binary format?

The keys had specific ordering requirements (grouped by path but sorted by
timestamp, most recent first), so the schema used a custom binary encoding with
one non-negotiable constraint: **lexicographic order must equal semantic
order**. RocksDB sorts keys lexicographically for iteration and prefix scans. If
the binary encoding does not preserve the intended ordering, prefix scans
return wrong results.

## The Signal

Initially the service was receiving a few hundred requests per day,
then a few thousand, and then, in a few more months, several million.
The signal that something was not quite right was that throughput was never enough.
The natural first response is to scale horizontally: add nodes. It works.
Throughput goes up. Cost goes up proportionally. The structural problem remains,
now running on more machines.

To understand what was actually happening, a
[JMH](https://github.com/openjdk/jmh) benchmark with
[`GCProfiler`](https://javadoc.io/doc/org.openjdk.jmh/jmh-core/latest/org/openjdk/jmh/profile/GCProfiler.html)
isolated the read path[^rocksdb-jni-bench]:

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class RocksDbReadBenchmark {

    private RocksDB db;

    @Setup
    public void setup() throws RocksDBException {
        // setup omitted
    }

    @Benchmark
    public byte[] naiveGet() throws RocksDBException {
        return db.get(encodedKey()); // encodes a path key into a new byte[]
    }
}
```

The output was unambiguous:

```
Benchmark                              Mode  Cnt    Units
RocksDbReadBenchmark.naiveGet         thrpt   10   ops/s
gc.alloc.rate                               ~4096  MB/sec
gc.churn.G1_Eden_Space                      ~4096  MB/sec
```

~4 GB/s of heap allocations at benchmark saturation — far beyond production
traffic at the time, and deliberately so: the benchmark isolates the mechanism,
not the production load. It explains the symptom: every request pays an
allocation tax, the GC burns CPU collecting it, and adding nodes buys headroom
without removing the tax. It also gives the fix a reproducible target, sized
for projected scale rather than for the load already in production.

## The Pooled Design

A `DirectByteBuffer` backs its bytes with off-heap native memory. The wrapper
object itself is still a heap object — reclaimed by the GC, which then triggers
a `Cleaner` to free the native region — but the payload bytes do not pressure
the eden/survivor spaces. Allocation is expensive (a native `malloc`-style
call), which is exactly why pooling helps: pay the cost once per buffer, reuse
indefinitely.

The pool only pays off in combination with the direct-buffer overload of
RocksDB's Java API, [`RocksDB.get(ByteBuffer key, ByteBuffer value)`](https://javadoc.io/doc/org.rocksdb/rocksdbjni/latest/org/rocksdb/RocksDB.html#get(org.rocksdb.ReadOptions,java.nio.ByteBuffer,java.nio.ByteBuffer)).
The default `byte[]` overload copies across the JNI boundary on every call; the
direct-buffer overload reads straight into the pooled native memory. Without
that variant, pooling Java-side wrappers around `byte[]` would not eliminate
the per-request copy.

```
byte[] overload:

   JVM heap              │  native (RocksDB)
                         │
   new byte[] key   ──copy──►  db.get()
   new byte[] value ◄──copy──  result
                         │
   2 allocations + 2 copies per request

direct-buffer overload:

   JVM heap              │  native memory
                         │
   pool.borrow()   ────────►  pooled DirectByteBuffer
   pool.release()  ◄────────  RocksDB reads/writes it in place
                         │
   0 allocations, 0 copies per request
```

Essentially the pool is:

```java
// unbounded pool: grows lazily to the number of concurrent borrows
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class DirectByteBufferPool {

    private final Deque<ByteBuffer> pool;
    private final int bufferCapacity;

    public DirectByteBufferPool(int bufferCapacity) {
        if (bufferCapacity <= 0) {
            throw new IllegalArgumentException("bufferCapacity must be > 0");
        }
        this.pool = new ConcurrentLinkedDeque<>();
        this.bufferCapacity = bufferCapacity;
    }

    public ByteBuffer borrow() {
        ByteBuffer b = pool.pollFirst();
        if (b == null) {
            b = ByteBuffer.allocateDirect(bufferCapacity);
        }
        b.clear();
        return b;
    }

    public void release(ByteBuffer b) {
        pool.offerFirst(b);
    }
}
```

`ConcurrentLinkedDeque` was chosen because it is non-blocking, and
`offerFirst`/`pollFirst` give LIFO ordering — recently released buffers stay
cache-warm. `clear()` only resets indices, not contents. A
`ThreadLocal<ByteBuffer>` would avoid the shared deque entirely and works well
with a bounded platform-thread pool, but it couples buffer count to thread
count — a dead end with virtual threads, where that number is unbounded.

The borrow/release contract is the caller's responsibility: double-release is
possible, and a `Lease` wrapper around the `ByteBuffer` would prevent it
(omitted for brevity).

The pool is unbounded by design — it grows to the high-water mark of concurrent
borrows and never shrinks: a single latency spike that drives concurrency up
pins that peak native memory for the lifetime of the JVM. A soft cap or
idle-buffer eviction would bound it; in production, extra instrumentation
tracks the high-water mark instead. That reservation counts against
[`-XX:MaxDirectMemorySize`](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html#extra-options-for-java),
which must be sized for the peak — otherwise `allocateDirect` fails with
`OutOfMemoryError: Direct buffer memory`. The sizing matters more than usual
here: with heap churn gone, the GC runs rarely, so any direct buffer left to
the `Cleaner` would be freed late — the pool sidesteps this by never releasing
buffers at all.

The caller ended up like:
```java
ByteBuffer keyBuffer = pool.borrow();
ByteBuffer valueBuffer = pool.borrow();
try {
    serializeKey(key, keyBuffer);
    keyBuffer.flip();
    int size = db.get(keyBuffer, valueBuffer);
    if (size == RocksDB.NOT_FOUND) {
        return null;
    }
    if (size > valueBuffer.capacity()) {
        // partial result: retry with a dedicated buffer of `size` bytes
        return getWithLargerBuffer(key, size);
    }
    return deserialize(valueBuffer);
} finally {
    pool.release(keyBuffer);
    pool.release(valueBuffer);
}
```

The extra ceremony follows the contract of the direct-buffer `get`: the key is
read between `position` and `limit` (hence the `flip()` after serializing), and
the return value is the size of the actual value — `RocksDB.NOT_FOUND` when the
key is missing, larger than the buffer's capacity when the pooled buffer was
too small and the result was truncated. The value buffer needs no flip: `get()`
sets its limit to the value size before returning. The `byte[]` overload has no
truncation case at all — it returns the whole value in a freshly allocated,
exactly-sized array, which is precisely the allocation being eliminated here.

This is noticeably more complex than the naive approach.

Per-request cost becomes:
- borrow a buffer;
- read into it;
- use it for serialize/deserialize;
- return it to the pool.

Buffer allocations on the hot path are zero. Domain-object allocations (the
deserialized `Value`) still happen on the heap — the pool eliminates the
serialization scaffolding, not the result of deserialization.

Migrating from `DataOutputStream` to `ByteBuffer` for serialize/deserialize was
trivial — all corner cases were documented and unit-tested.

## The Result

With the pool in place, the same JMH benchmark:

```
Benchmark                              Mode  Cnt    Units
RocksDbReadBenchmark.pooledGet        thrpt   10   ops/s
gc.alloc.rate                                  ~10  MB/sec
gc.churn.G1_Eden_Space                         ~10  MB/sec
```

Heap allocations dropped from 4 GB/s to ~10 MB/s. The leftover ~10 MB/s is the
deserialized `Value` and a few iterator and wrapper objects — the buffers are
gone from the profile. With almost nothing left for the GC to do on the read
path, throughput stabilized.

## The Insight

```
alloc rate                        alloc rate
    │        ╱  naive                 │         pooled
    │      ╱                          │
    │    ╱                            │    ┌───────────────
    │  ╱                              │   ╱  bounded by
    │╱                                │  ╱   concurrency
    └───────────► requests/s          └───────────► requests/s
```

In the naive design, allocation rate scales with request rate:

```
per_request_alloc = avg_key_size + avg_value_size + serialization wrappers
total_alloc_rate  = requests_per_second × per_request_alloc
```

With a pool of reusable `DirectByteBuffer`s, allocation rate scales with
concurrency, and concurrency is bounded by the thread pool:

```
per_request_alloc = sizeof(Value)                # deserialized domain object
fixed_cost        = pool_size × buffer_capacity  # paid once, grows to peak concurrency
```

Request rate can grow without bound; allocation rate cannot.

## The Tradeoff

With the naive design, scaling is simple: add nodes. Each node is stateless with
respect to allocation — it allocates what it needs, the GC cleans up. Horizontal
scaling is cheap to reason about.

With the pool, each node carries a fixed memory reservation. The design is more
complex and has more moving parts — the pool itself,
plus monitoring of borrow/release counters and pool-size health checks — but it
is operationally cheaper to scale.

The payoff is that a single node can handle significantly more load efficiently.
Horizontal scaling remains available, but is no longer forced by allocation
pressure alone.

The pattern is familiar from database connection pools: pay a fixed cost once,
then amortize it across every request. What changes here is the resource being
pooled — native memory instead of sockets — and the property being decoupled —
allocation rate from request rate.

[^rocksdb-jni-bench]: See also [RocksDB Java JNI benchmarks](https://rocksdb.org/blog/2023/11/06/java-jni-benchmarks.html).
