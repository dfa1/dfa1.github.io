# Decouple Allocations from Request Volume

*6 January 2023*

*A few thousand requests per day. [RocksDB](https://rocksdb.org/)
as the storage engine, returning variable-size values. Nothing about this load profile suggests
a performance problem.*

And yet the naive implementation has a structural flaw: allocation cost scales
with data volume. More requests means more allocations. It is O(n) where it
should be O(1). You will not see it immediately. You will see it later, when
the cost of operating the service is higher than it should be.

This is the story of how we found it, named it, and fixed it.

## The Starting Point

The first version was a POC. Simple by design: call `RocksDB.get()`, get a
`byte[]`, deserialize, return the result.

```java
byte[] keyBytes = serializeKey(key);
byte[] valueBytes = db.get(keyBytes);
Value value = deserialize(valueBytes);
```

One allocation per request. Reasonable for a prototype. The problem is that
"one allocation per request" is not a constant — it is proportional to the
number of requests, and each allocation is proportional to the size of the
value. Under a workload of varying size, the heap sees a
continuous stream of short-lived objects of unpredictable size.

Key encoding had the same shape. The path structure we needed — hierarchical
identifiers like:

```
parent(parentId=1).child(childId=1).status = 2
parent(parentId=1).child(childId=2).status = 1
parent(parentId=1).child(childId=3).status = 1
```

— was encoded using an `ObjectOutputStream` per operation:

```java
ByteArrayOutputStream baos = new ByteArrayOutputStream();
ObjectOutputStream oos = new ObjectOutputStream(baos);
oos.writeObject(path);
byte[] key = baos.toByteArray();
```

Again: several allocations per encode. Reasonable for a POC. Wrong for production.

For some time, neither was a problem.

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
[`GCProfiler`](https://javadoc.io/doc/org.openjdk.jmh/jmh-core/latest/org/openjdk/jmh/profile/GCProfiler.html):

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
    public byte[] naiveGet(Blackhole bh) throws RocksDBException {
        return db.get(encodedKey()); // encodes a path key into a new byte[]
    }
}
```

The output was unambiguous:

```
Benchmark                              Mode  Cnt    Units
RocksDbReadBenchmark.naiveGet         thrpt   10   ops/s
·gc.alloc.rate                               ~4096  MB/sec
·gc.churn.G1_Eden_Space                      ~4096  MB/sec
```

~4 GB/s of heap allocations. For a service handling a few thousand requests
per day. The allocation rate was driven not by request count but by value size —
and value size was outside our control. That is the O(n) problem made visible.

## The Insight

The allocation cost in the naive design is:

```
allocation_cost = requests_per_second × avg_value_size
```

Both terms can grow independently. Value size is determined by upstream data,
not by us. Request rate is what we are trying to scale. The product of the two
grows unboundedly.

The fix is to break that relationship. Allocation cost should be a constant,
fixed at startup, independent of how much data flows through the system.

With a pool of pre-allocated `DirectByteBuffer`s:

```
allocation_cost = pool_size × buffer_capacity   (paid once, at startup)
```

Per-request cost becomes: borrow a buffer, read into it, process, return it.
Zero allocations on the hot path.

This is O(1) in allocation — constant regardless of request rate or value size.

## The Design

A `DirectByteBuffer` lives off-heap. It is not managed by the GC. Allocation
is expensive (native memory call), which is exactly why you pool them: pay the
cost once at startup, reuse indefinitely.

The pool is simple:

```java
public final class DirectByteBufferPool {

    private final BlockingDeque<ByteBuffer> pool;
    private final int bufferCapacity;

    public DirectByteBufferPool(int poolSize, int bufferCapacity) {
        this.bufferCapacity = bufferCapacity;
        this.pool = new LinkedBlockingDeque<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            pool.push(ByteBuffer.allocateDirect(bufferCapacity));
        }
    }

    public ByteBuffer borrow() {
        ByteBuffer buffer = pool.poll();
        if (buffer == null) {
            // pool exhausted: allocate a temporary unpooled buffer
            // rather than blocking the caller
            return ByteBuffer.allocateDirect(bufferCapacity);
        }
        buffer.clear();
        return buffer;
    }

    public void release(ByteBuffer buffer) {
        if (buffer.capacity() == bufferCapacity) {
            pool.push(buffer);
        }
        // oversized temporary buffers are discarded
    }
}
```

Pool exhaustion deserves a deliberate decision. Three options:

- **Block** — the caller waits until a buffer is available. Simple, backpressure
  for free, but can cause latency spikes under burst load.
- **Allocate temporarily** — as above, create an unpooled buffer and discard it
  after use. Allocation pressure returns briefly, but the system never blocks.
- **Fail fast** — throw immediately. Only appropriate if the pool size is tuned
  to handle peak load with margin.

We chose temporary allocation. Under normal load the pool is never exhausted;
under burst load a few extra allocations are acceptable.

### Key Encoding

The same pool handles key encoding. The path:

```
parent(parentId=1).child(childId=1).status
```

is encoded directly into a borrowed buffer:

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

The encoding is custom binary with one non-negotiable constraint: **lexicographic
order must equal semantic order**. RocksDB sorts keys lexicographically for
iteration and prefix scans. If the binary encoding does not preserve the
intended ordering, prefix scans return wrong results.

The consequences are concrete:

- Integer fields must be encoded as **fixed-width big-endian**. A variable-length
  encoding of `2` and `10` will sort `10` before `2` lexicographically.
  Fixed-width big-endian sorts them correctly.
- Hierarchy levels must have fixed-width components so that a prefix of one
  level cannot accidentally match the interior of another level's encoding.
- Separators between path segments become unnecessary when all components are
  fixed-width — the parser always knows where each field ends.

This is the constraint that rules out Java's default serialization, Protobuf,
and most off-the-shelf encodings. They are not designed to be
lexicographically ordered. A custom encoding is not premature optimization — it
is a correctness requirement.

## The Result

After the pool was in place, the same JMH benchmark:

```
Benchmark                              Mode  Cnt    Units
RocksDbReadBenchmark.pooledGet        thrpt   10   ops/s
·gc.alloc.rate                                  ~0  MB/sec
·gc.churn.G1_Eden_Space                         ~0  MB/sec
```

~0 GB/s of heap allocations. The GC has nothing to do on the read path.
Throughput is stable. Latency variance drops.

## The Tradeoff

The new design is more complex. This is not a small cost.

With the naive design, scaling is simple: add nodes. Each node is stateless with
respect to allocation — it allocates what it needs, the GC cleans up. Horizontal
scaling is cheap to reason about.

With the pool, each node carries a fixed memory reservation. Pool size and buffer
capacity must be tuned to the workload — too small and you fall back to temporary
allocation under load; too large and you waste native memory. The pool is a
shared resource that must be managed carefully in a concurrent context.
Diagnostics are harder: a buffer that is borrowed and never returned is a
silent leak.

The payoff is that a single node can handle significantly more load efficiently.
Horizontal scaling remains available, but is no longer forced by allocation
pressure alone. You scale because you need throughput, not because you are
fighting the GC.

---

See also [RocksDB Java JNI benchmarks](https://rocksdb.org/blog/2023/11/06/java-jni-benchmarks.html)
