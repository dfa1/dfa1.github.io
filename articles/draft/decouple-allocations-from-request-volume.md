# Decouple Allocations from Request Volume

*30 November 2023*

*A few thousand requests per day. [RocksDB](https://rocksdb.org/)
as the storage engine, returning variable-size values. Nothing about this load profile suggests
a performance problem.*
*The naive implementation has a structural flaw: allocation rate scales with
request rate, when it should scale with concurrency. Each request churns fresh
buffers and hands them to the GC. Pre-allocating a pool sized to concurrency
breaks that coupling — allocation cost is paid once at startup, regardless of
throughput.*

## The Starting Point

We started a POC to explore RocksDB for our business requirements. Having a lot to explore,
the design was kept deliberately simple on the data fetching:

```java
Key key = ...; // request from the caller
byte[] keyBytes = serializeKey(key);
byte[] valueBytes = db.get(keyBytes);
Value value = deserializeValue(valueBytes);
```

Both `serializeKey` and `deserializeValue` allocate extra objects to implement the serialization logic:
```java
ByteArrayOutputStream baos = new ByteArrayOutputStream();
ObjectOutputStream oos = new ObjectOutputStream(baos);
serializeKey(key, oos);
byte[] keyBytes = baos.toByteArray();
```

For quite some time, this was not a problem: request volume was low and response
times were acceptable.

### Why a custom binary format?

Our keys had specific ordering requirements (grouped by path but sorted by
timestamp, most recent first), so we implemented a custom binary encoding with
one non-negotiable constraint: **lexicographic order must equal semantic
order**. RocksDB sorts keys lexicographically for iteration and prefix scans. If
the binary encoding does not preserve the intended ordering, prefix scans
return wrong results.

The consequences are concrete:

- Integer fields must be encoded as **fixed-width big-endian**. An ASCII or
  decimal-string encoding of `2` and `10` sorts `10` before `2`
  lexicographically. Fixed-width big-endian sorts unsigned values correctly.
  Our keys only use non-negative integers; signed values would need a sign-bit
  flip (or a bias) so that negatives sort before positives.
- Hierarchy levels must have fixed-width components so that a prefix of one
  level cannot accidentally match the interior of another level's encoding.
- Separators between path segments become unnecessary when all components are
  fixed-width — the parser always knows where each field ends.

This is the constraint that rules out Java's default serialization, Protobuf,
and most off-the-shelf encodings. They are not designed to produce
lexicographically ordered keys. A custom encoding is not premature optimization — it
is a correctness requirement.

## The Signal

The signal was not a latency spike. It was cost. The service was small, the
load was modest, but the infrastructure bill was not proportional to what the
service was doing. GC pauses were frequent enough to require more headroom than
the workload justified.

The natural first response is to scale horizontally: add nodes. It works.
Throughput goes up. Cost goes up proportionally. The structural problem remains,
now running on more machines.

To understand what was actually happening, we ran
[JMH](https://github.com/openjdk/jmh) with
[`GCProfiler`](https://javadoc.io/doc/org.openjdk.jmh/jmh-core/latest/org/openjdk/jmh/profile/GCProfiler.html)[^rocksdb-jni-bench]:

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

~4 GB/s of heap allocations under benchmark saturation. Production traffic of a
few thousand requests per day does not produce anything close to this rate —
the benchmark validates the mechanism, not the production diagnosis. The
production signal was qualitatively different: GC headroom and infrastructure
cost, not throughput. We sized the fix for projected scale, not for the load
we already had. The benchmark gave us a reproducible target to measure against.

The structural point holds at any rate: allocation volume scales with request
rate, even though only a few requests are ever in flight at the same time.
Under varying value sizes, the heap sees a continuous stream of short-lived
objects of unpredictable size. Well-known problem, well-known solutions.

## The Design

A `DirectByteBuffer` lives off-heap. It is not managed by the GC. Allocation
is expensive (native memory call), which is exactly why we pool them: pay the
cost once at startup, reuse indefinitely.

Essentially the pool is:

```java
// auto-sized pool: concurrency level determine the number of buffers
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

`ConcurrentLinkedDeque` chosen because it's lock-free and `offerFirst`/`pollFirst` give you LIFO
with cache-warm buffers.
`clear()` only resets indices, not contents.
The borrow/release contract is the caller's responsibility. Double-release is possible and it could
be stopped by wrapping the `ByteBuffer` with a `Lease` object that avoid that (omitted for brevity here).
The pool is unbounded by design — it grows to the high-water mark of concurrent borrows
(the logic is interesting but deviates the point being made in this article).

The caller ended up like:
```java
ByteBuffer keyBuffer = pool.borrow();
ByteBuffer valueBuffer = pool.borrow();
try {
    serializeKey(key, keyBuffer);
    db.get(keyBuffer, valueBuffer);
    valueBuffer.flip();
    return deserialize(valueBuffer);
} finally {
    pool.release(keyBuffer);
    pool.release(valueBuffer);
}
```
that is much more complex than the naive approach.

Per-request cost becomes:
- borrow a buffer;
- read into it;
- use it for serialize/deserialize;
- return it to the pool.

Buffer allocations on the hot path are zero. Domain-object allocations (the
deserialized `Value`) still happen on the heap — the pool eliminates the
serialization scaffolding, not the result of deserialization.

Migrating from `DataOutputStream` to `ByteBuffer` for serialize/deserialize was trivial
(all corner cases were clearly documented and unit tested).

## The Result

With the pool in place, the same JMH benchmark:

```
Benchmark                              Mode  Cnt    Units
RocksDbReadBenchmark.pooledGet        thrpt   10   ops/s
gc.alloc.rate                                  ~10  MB/sec
gc.churn.G1_Eden_Space                         ~10  MB/sec
```

Heap allocations dropped from 4 GB/s to ~10 MB/s. The residual is the
deserialized `Value` plus a few iterator and wrapper objects — buffer
allocations are zero, domain-object allocations remain. The GC had almost
nothing to do on the read path. Throughput stabilized. Latency variance
dropped.


## The Insight

The allocation cost in the naive design is:

```
per_request_alloc = avg_key_size + avg_value_size + serialization wrappers
total_alloc_rate  = requests_per_second × per_request_alloc
```

With a pool of pre-allocated `DirectByteBuffer`s:

```
per_request_alloc = sizeof(Value)                # deserialized domain object
fixed_cost        = pool_size × buffer_capacity  # paid once, at startup
```

Buffers no longer scale with request rate; they scale with concurrency. The pool starts empty
and buffers are created lazily, following the concurrency level of the instance.

## The Tradeoff

The new design is more complex and has more moving parts (pool, monitoring, health checks, etc)
but it is operationally cheaper to scale.

With the naive design, scaling is simple: add nodes. Each node is stateless with
respect to allocation — it allocates what it needs, the GC cleans up. Horizontal
scaling is cheap to reason about..

With the pool, each node carries a fixed memory reservation. `LinkedBlockingDeque`
handles the concurrency itself; the real risk is the borrow/release lifecycle:
a borrow without a matching release leaks a buffer permanently, double-release
puts the same buffer in the pool twice (two callers will then mutate it
concurrently), and use-after-release corrupts whoever borrowed it next. We
mitigate this with a try-with-resources wrapper that owns the release call,
plus a borrow/release counter exposed as a metric — a steadily growing delta
flags a leak before the pool drains.
The payoff is that a single node can handle significantly more load efficiently.
Horizontal scaling remains available, but is no longer forced by allocation
pressure alone.

---

[^rocksdb-jni-bench]: See also [RocksDB Java JNI benchmarks](https://rocksdb.org/blog/2023/11/06/java-jni-benchmarks.html).
