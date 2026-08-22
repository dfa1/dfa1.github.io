# FFM Design Patterns

*22 August 2026*

*There is a lot of writing about Java's Foreign Function & Memory API, and almost all of it stops at
the same place: look up `strlen`, allocate a string in a confined arena, invoke, print the length. The
API is final, the tutorials are correct, and none of them tell you what to do on day forty — when you
have three hundred native functions, a callback that outlives the call that registered it, and a C
library that mallocs on your behalf. This post is about that part.*

---

The material comes from two small projects: [rocksdbffm](https://github.com/dfa1/rocksdbffm), FFM
bindings for [RocksDB](https://rocksdb.org/), and [zstd-java](https://github.com/dfa1/zstd-java), FFM
bindings for [Zstandard](https://facebook.github.io/zstd/). They are useful together because their C
APIs disagree about almost everything — error reporting, buffer ownership, statefulness — and yet the
Java-side solutions converged. That convergence is what makes these items rather than anecdotes.

The format is borrowed from *Effective Java*: the title of each item is the rule, the body is why it
earns its place, and the caveats after it are where the cost actually lives. Thirteen items. Every
example carries the C prototype it binds, copied from `zstd.h` or RocksDB's `c.h` —
half of FFM is reading a header correctly, and a Java snippet without its prototype hides exactly the
half that goes wrong.

I'll assume you know what `Arena`, `MemorySegment`, `Linker`, `FunctionDescriptor` and `MethodHandle`
are. If not, [dev.java's FFM tutorials](https://dev.java/learn/ffm/) are the right starting point, and
[JEP 454](https://openjdk.org/jeps/454) is unusually readable on the design rationale.

### Item 1: Prefer a confined arena per operation

The default, and usually the right answer: one arena, one thread, try-with-resources, deterministic
free at the closing brace. zstd-java's one-shot compression path is exactly this:[^zstd-compress]

```c
size_t ZSTD_compressBound(size_t srcSize);
size_t ZSTD_compress(void* dst, size_t dstCapacity,
                     const void* src, size_t srcSize, int compressionLevel);
```

```java
public static byte[] compress(byte[] src, ZstdCompressionLevel level) {
    Objects.requireNonNull(src, "src");
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment in = copyIn(arena, src);
        ZstdByteSize bound = compressBound(new ZstdByteSize(src.length));
        MemorySegment out = arena.allocate(bound.value());
        long written = NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS.invokeExact(
                out, bound.value(), in, (long) src.length, level.value()));
        return copyOut(out, written);
    }
}
```

Everything allocated inside the block dies with it: no lifetime to track, no ownership to document,
no leak to find. Every other pattern here is a deviation that has to justify itself.

Two reasons to prefer confined over shared:

- A confined arena is single-threaded by construction — the JIT can hoist the owner check out of a
  loop, unlike the shared arena's liveness handshake.
- `Arena.ofShared()` buys thread-crossing at the cost of a far more expensive close, since deallocation
  has to become globally visible. Reach for it only when a segment genuinely must outlive one thread.

### Item 2: Allocate out-params in the calling arena

C's answer to multiple return values is pointer arguments; the Java-side answer allocates those slots
in the same confined arena as everything else and never lets them escape it.

```c
char* rocksdb_get(rocksdb_t* db, const rocksdb_readoptions_t* options,
                  const char* key, size_t keylen, size_t* vallen, char** errptr);
```

```java
try (Arena arena = Arena.ofConfined()) {
    MemorySegment vallen = arena.allocate(JAVA_LONG);   // size_t*
    MemorySegment errptr = arena.allocate(ADDRESS);     // char**
    MemorySegment value  = (MemorySegment)
            rocksdb_get.invokeExact(db, readOptions, key, keyLen, vallen, errptr);
    // ...
}
```

Nothing interesting happens here, which is the point: the slots are scratch space, sized by a layout
rather than a hand-computed byte count, gone at the closing brace. What comes *out* of them is the
interesting part.

### Item 3: Copy out and free before reaching for anything cleverer

When a C function returns a buffer it allocated, the conservative move is to copy it into a `byte[]`
and free the native memory immediately — ownership begins and ends inside one method.

```c
void rocksdb_free(void* ptr);
```

```java
long len = vallen.get(JAVA_LONG, 0);
byte[] result = value.reinterpret(len).toArray(JAVA_BYTE);
free(value);
return result;
```

A pointer from native code arrives as a zero-length segment — FFM knows the address but not how much
memory is behind it, so every read fails the bounds check until `reinterpret(len)` tells it. It's a
restricted method because you're asserting something the runtime can't verify; get the length wrong
and you've re-invented the JNI failure mode.

Boring and correct, and the default. The next item exists only because the copy is sometimes the cost
you were trying to avoid.

### Item 4: Give a borrowed native buffer a Java lifetime with `reinterpret`

RocksDB has a second read path that hands back values it owns rather than copies:

```c
rocksdb_pinnableslice_t* rocksdb_get_pinned(rocksdb_t* db, const rocksdb_readoptions_t* options,
                                            const char* key, size_t keylen, char** errptr);
const char* rocksdb_pinnableslice_value(const rocksdb_pinnableslice_t* t, size_t* vlen);
void rocksdb_pinnableslice_destroy(rocksdb_pinnableslice_t* v);
```

The slice pins the value in the block cache; `rocksdb_pinnableslice_value` returns a bare `const char*`
into it — valid until `rocksdb_pinnableslice_destroy`, not yours to free. Copying it into a `byte[]`
doubles the memory traffic of what was supposed to be a zero-copy read. The three-argument
`reinterpret` avoids the copy:[^pinnable-slice]

```java
// The `null` cleanup is deliberate: this view borrows from the slice, it does
// not own the memory, so closing `arena` must not attempt to free it.
MemorySegment view = data.reinterpret(len, arena, null).asReadOnly();
```

`reinterpret(long, Arena, Consumer<MemorySegment>)` does three separable things:

1. **Restores spatial bounds** — the segment now knows it is `len` bytes long.
2. **Grafts on temporal bounds** — the segment dies when `arena` closes; accessing it after that throws
   instead of reading freed memory.
3. **Registers a deallocator** — the consumer runs at close, for when the buffer really is yours to
   free (`rocksdb_free` on a `rocksdb_get` result, say).

Steps 1 and 2 are what a zero-copy read tier is built on; step 3 is what makes "foreign allocator,
Java lifetime" expressible at all. Who frees the memory is a different question from how long Java may
look at it — pinned data needs the first two and a `null` for the third.

The failure modes, none of them obvious:

- **A wrong length is unrecoverable.** You've overridden the safety property, so
  `IndexOutOfBoundsException` won't save you — read the length from the out-param, never infer it.
- **The cleanup runs on whoever closes the arena** — an arbitrary thread, with a shared arena. A latent
  crash if the native `free` has thread affinity, as some allocators do.
- **Binding to a long-lived arena converts a bounded cost into an unbounded one** — fine per-read with
  a confined arena, a leak against a global one.
- **Ownership is not transitive.** Pointers inside the buffer to other native allocations go unfreed —
  bind the whole graph or copy.

Zstandard never needs any of this — you size the destination yourself with `ZSTD_compressBound`.
Reaching for a deallocator in a caller-allocates API is a sign you've misread the ownership contract.

### Item 5: Give every opaque handle its own arena

Between "lives for one call" and "lives forever" sits the common case: a native object expensive to
create, reused across many calls, freed explicitly. `ZSTD_CCtx` is the canonical example; `rocksdb_t`
and `rocksdb_iterator_t` are the same shape.

The rule is one arena per native object, owned by the Java wrapper, closed in the wrapper's
`close()`:[^cctx-close]

```c
ZSTD_CCtx* ZSTD_createCCtx(void);
size_t     ZSTD_freeCCtx(ZSTD_CCtx* cctx);   /* compatible with NULL pointer */
```

```java
@Override
protected void tryClose(MemorySegment ptr) throws Throwable {
    try {
        var _ = (long) Bindings.FREE_CCTX.invokeExact(ptr);
    } finally {
        arena.close();
    }
}
```

The ordering is easy to get backwards: **free the native object first, then close the arena.** The
arena holds buffers and stubs the object may still touch during teardown; closing it first is a
use-after-free that won't reproduce under light load. The `finally` isn't decoration — if the native
free throws, the arena still has to go.

This is also where you decide the object's thread policy rather than inherit it. `ZSTD_CCtx` isn't
thread-safe; a confined arena turns misuse into a Java-level exception instead of silent corruption —
the safety property and the C contract lining up for free.

### Item 6: Scope an upcall stub to the life of the object that uses it

Upcalls are where lifetime stops being about buffers.[^upcall-tutorial] A RocksDB custom comparator is
registered once and invoked later, during compaction, on a thread you didn't create — the stub must
stay valid not "until the call returns" but "until the comparator is destroyed".

RocksDB takes the comparator as three function pointers plus a state pointer:

```c
rocksdb_comparator_t* rocksdb_comparator_create(
    void* state, void (*destructor)(void*),
    int (*compare)(void*, const char* a, size_t alen, const char* b, size_t blen),
    const char* (*name)(void*));
```

Each of those function pointers is an upcall stub on the Java side:

```java
MethodHandle target = MethodHandles.lookup()
        .findStatic(MyComparator.class, "compare", COMPARE_TYPE);
MemorySegment stub = linker.upcallStub(target, COMPARE_DESC, arena);
```

The `arena` argument is the whole story — the stub is valid exactly as long as it's open. So the arena
has to be scoped to the *native object's* life, per Item 5, and closed only after RocksDB is done with
the comparator.

Three rules worth bolding in any binding's contributing guide:

- **An exception escaping an upcall does not propagate — it takes down the VM.** Every upcall body is
  a `try`/`catch (Throwable)` converting to a return code or sentinel; to surface the error, stash it
  in a field and rethrow after the downcall returns.
- **Reachability is not enough.** A strong reference to the stub segment doesn't keep it callable — the
  arena being open does. Forgetting this produces a crash far from the cause.
- **Per-instance stubs are cheap to write and expensive in aggregate.** Binding context via
  `MethodHandles.insertArguments` gives one stub per instance, each with its own generated code. Once
  there are many, switch to the C idiom: one stub, plus the `void* state` pointer RocksDB passes back,
  keyed into a registry.

### Item 7: Make adopted native threads die before the VM does

Item 6 keeps the stub alive; this one is about the *thread* that runs it — a lifetime you didn't
create, can't enumerate, and are responsible for anyway.

When native code invokes an upcall from a thread the JVM has never seen — a RocksDB background
compaction thread, say — the JDK quietly attaches it so it can run Java code, and it stays attached.
The detach happens in a thread-local destructor when the native thread eventually dies, which for a
pooled worker means at process exit.

That's fine until something at process exit tries to join it. RocksDB's default `Env` registers a
static destructor that stops its thread pools and `pthread_join`s every background thread. Combine the
two and you get a deadlock that is not a race — it happens every time:[^drain-source]

1. Something calls `System.exit()`. HotSpot brings the world to a safepoint, sets its global
   `_vm_exited` flag **while holding `Threads_lock`**, and calls libc `exit()` from the VM thread.
2. `exit()` runs static destructors, reaching RocksDB's `PosixEnv::JoinThreadsOnExit`, which
   `pthread_join`s the pool.
3. A joined thread that once ran an upcall is an *attached* JVM thread. As it unwinds, its
   thread-local destructor runs `UpcallContext::~UpcallContext` → `DetachCurrentThread` →
   `VM_Exit::wait_if_vm_exited`, which blocks forever on the `Threads_lock` the exiting VM thread never
   releases — deliberately: the process is about to die, so parking the thread is free.

Except it doesn't die. Step 2 waits on a thread that step 3 has parked permanently:

```
VM Thread  → VM_Exit::doit() → os::exit() → exit() → __cxa_finalize_ranges
           → PosixEnv::JoinThreadsOnExit::~JoinThreadsOnExit()
           → ThreadPoolImpl::Impl::JoinThreads() → std::thread::join()   [blocked]

BG thread  → _pthread_exit → _pthread_tsd_cleanup
           → UpcallContext::~UpcallContext() → jni_DetachCurrentThread
           → VM_Exit::wait_if_vm_exited() → Mutex::lock()                [blocked]
```

`jstack` and `jcmd` can't attach to a VM already inside `VM_Exit`, so the only way to see this trace
is an OS-level sampler — `sample` on macOS, `gdb -p` or `perf` on Linux. A hang with no thread dump
available is itself a hint you're looking at a VM mid-exit.

Step 3 is load-bearing but unspecified. Neither the `Linker` javadoc nor dev.java's upcall tutorial
mentions threads — the javadoc's only documented upcall hazards are an escaping exception and a
function-pointer type mismatch. Thread attachment on first upcall, detached from a thread-local
destructor at death, is HotSpot implementation behavior (`UpcallLinker::on_entry`), observed in a
native stack trace, not a contract. Build the fix on that basis and it survives a future JDK that
changes the behavior — the shutdown hook becomes a no-op instead of a bug. Assume attachment is
guaranteed instead, and the day it isn't, this section becomes folklore instead of a diagnosis.

The reason this gets misdiagnosed as flaky is that it depends on how the process ends, not on timing:

| | return from `main` | `System.exit()` |
|---|---|---|
| **no upcall ever fired** | clean | clean |
| **upcall fired** | clean | **hangs, every time** |

Returning from `main` goes through `DestroyJavaVM` rather than `VM_Exit`, so a hand-written reproducer
exits cleanly and the bug looks intermittent. It isn't — it's exactly reproducible once you exit the
way test runners and containers do.

The fix: make those threads exit while the VM is still fully alive, so their detach completes
normally. A shutdown hook is the last point that's still possible:

```c
void rocksdb_env_set_background_threads(rocksdb_env_t* env, int n);
void rocksdb_env_set_high_priority_background_threads(rocksdb_env_t* env, int n);
```

```java
// armed at callback-registration time, from an ordinary Java thread
Runtime.getRuntime().addShutdownHook(
        new Thread(BackgroundUpcallThreads::drain, "rocksdbffm-background-thread-drain"));

private static void drain() {
    try (Env env = Env.defaultEnv()) {
        env.setBackgroundThreads(0);              // shrink the pools to zero
        env.setHighPriorityBackgroundThreads(0);
    }
    awaitTermination(THREADS);                    // recorded by each upcall dispatch
}
```

Shrinking a pool to zero makes each excess thread detach and return, so by the time the static
destructor runs there's nothing left to join.

Details that are easy to get wrong:

- **Arm the hook at registration, not from the upcall.** Triggering class initialization and
  `addShutdownHook` from inside a native upcall means acquiring locks on a thread the VM just adopted —
  do it on the thread that registers the callback instead, where it's ordinary Java.
- **You have to know which threads to wait for.** No API enumerates "threads the VM adopted via FFM",
  so each upcall dispatch records `Thread.currentThread()` in a set — one entry per pool thread, not
  per callback.
- **Bound the wait.** If a thread is wedged for an unrelated reason, timing out leaves you where you'd
  be without the hook; blocking shutdown forever does not.
- **`addShutdownHook` throws if the VM is already shutting down.** Catch and ignore — a callback
  registered that late won't outlive the VM anyway.
- **Not a RocksDB quirk.** Any native library that joins its own threads from `atexit` or a static
  destructor — and many thread-pool-owning C libraries do — produces the same deadlock once one of
  those threads has run an upcall.

zstd-java never hits this, for the same reason it never needs a deallocator: no callbacks from
library-owned threads, so no zstd thread ever becomes a JVM thread. The hazard is specific to bindings
whose upcalls run on threads the native library created and owns.

### Item 8: Keep the generated raw layer out of the public API

jextract generates a faithful mirror of the header. That mirror isn't an API — it's an intermediate
representation, and shipping it is how `MemorySegment` ends up in application code.

Every binding worth using has two layers:

- **Raw layer**, package-private. One method per C symbol, signature mirrors the header exactly, no
  interpretation, no cleverness. Generated, ideally mechanically.
- **Idiomatic layer**, public. `byte[]`, `Optional`, records, exceptions, `Path`. No `MethodHandle`,
  no restricted methods, no arenas the caller has to know about, and no `MemorySegment` outside the
  tiers that declare one (Item 10).

The generated raw layer is a precondition, not a convenience. Hand-write it and people put "just a
little" interpretation in — a null check here, a length fixup there — and within a year nobody can
tell which layer owns the semantics.

A small example of the boundary doing real work: rocksdbffm takes `java.nio.file.Path` everywhere the
C API takes `const char*` for a filesystem location. `Path` composes with the rest of `java.nio.file`
and makes "absolute or relative?" someone else's already-solved problem.

### Item 9: Translate every C error idiom at a single boundary

The pattern the two libraries make legible: the C idioms could hardly be more different, and the Java
answer is identical.

**RocksDB** uses an out-param — the `char** errptr` in `rocksdb_get` above, set to a malloc'd message
on failure, NULL on success, and the last parameter of nearly every function in `c.h`.

```java
public static void checkError(MemorySegment errHolder) {
    MemorySegment errPtr = errHolder.get(ValueLayout.ADDRESS, 0);
    if (!MemorySegment.NULL.equals(errPtr)) {
        throw new RocksDBException(toJavaString(errPtr));   // toJavaString frees it
    }
}
```

**Zstandard** encodes errors in the return value: a `size_t` whose error space sits at the top of the
range, tested with `ZSTD_isError` and named with `ZSTD_getErrorName`.

```c
unsigned    ZSTD_isError(size_t result);
const char* ZSTD_getErrorName(size_t result);
```

```java
static boolean isError(long code) {
    try {
        return ((int) Bindings.IS_ERROR.invokeExact(code)) != 0;
    } catch (Throwable t) {
        throw rethrow(t);
    }
}
```

Different mechanics, same shape: **one helper at the boundary converts the C convention into a typed
Java exception, so no caller ever sees the convention.** The idiom is the variable; the boundary is the
constant.

Related translations belong in the same place:

- **Sentinel returns.** `-1`/`NULL` become an exception when they mean failure, `Optional.empty()`
  when they mean "legitimately absent" — a per-function judgment call (`rocksdb_get` returning NULL is
  a missing key, not an error), not a mechanical rule.
- **Multiple out-params collapse into one value.** `(value, length, found)` becomes `Optional<byte[]>`.
- **errno** is captured with `Linker.Option.captureCallState("errno")` on the raw side, translated on
  the idiomatic side. Reading it any other way after the downcall is a race — the JVM may have made
  syscalls of its own in between.

### Item 10: Expose zero-copy as a declared tier, never as a leak

"No `MemorySegment` in public signatures" is the right default and the wrong absolute rule. Some
callers *want* the segment — streaming to a socket, feeding another native library — and a `byte[]`
copy is exactly the cost they came to avoid.

The three copying tiers below are `rocksdb_get`; the scoped one is `rocksdb_get_pinned` from Item 4:

| Tier | Signature | Copy | Caller obligation |
|---|---|---|---|
| `byte[]` | `byte[] get(byte[] key)` | one copy out | none |
| `ByteBuffer` | `CopyResult get(ByteBuffer key, ByteBuffer value)` | into the caller's buffer | provide it, size it |
| `MemorySegment` | `CopyResult get(MemorySegment key, MemorySegment value)` | into the caller's segment | provide it, own its arena |
| scoped | `<R> Optional<R> get(MemorySegment key, Mapper<R> fn)` | none | read inside the callback |

The interesting one is the last: the zero-copy tier doesn't hand a segment back, it passes a read-only
view *into* a callback, and the arena backing it closes the moment the callback returns.[^mapper]
Retaining it isn't undefined behavior — it's an `IllegalStateException`, or `WrongThreadException` if
smuggled to another thread. The obligation is discharged by the library, not documented for the
caller.

The rule that survives is narrower and more useful than the one I started with:

> A `MemorySegment` may appear in a public signature only where the signature itself pins its
> lifetime: a buffer the caller already owns, or an argument to a callback that ends when the call
> does.

Same reasoning applies to zstd-java's zero-copy path and zstd's streaming API, where the natural unit
is a segment pair rather than an array.

### Item 11: Derive struct accessors from the layout, never from offsets

Zstd's streaming API is a good counterexample to "always copy out" — driven by two structs whose `pos`
field the native side advances in place:

```c
typedef struct ZSTD_inBuffer_s {
  const void* src;    /**< start of input buffer */
  size_t size;        /**< size of input buffer */
  size_t pos;         /**< position where reading stopped. Will be updated. */
} ZSTD_inBuffer;

typedef struct ZSTD_outBuffer_s {
  void*  dst;         /**< start of output buffer */
  size_t size;        /**< size of output buffer */
  size_t pos;         /**< position where writing stopped. Will be updated. */
} ZSTD_outBuffer;
```

The protocol is call, read `pos`, call again. The two structs differ only in the name and constness of
the first field, so one Java layout serves both — named `ptr` because it's `src` in one and `dst` in
the other:[^stream-buffer]

```java
private static final StructLayout LAYOUT = MemoryLayout.structLayout(
        ADDRESS.withName("ptr"),
        JAVA_LONG.withName("size"),
        JAVA_LONG.withName("pos"));

private static final VarHandle POS_HANDLE = LAYOUT.varHandle(PathElement.groupElement("pos"));
```

Two things to take from this: the accessors come out of the layout, so padding and ABI differences
stay the library's problem, not yours; and the buffers live for the whole stream, not one call, which
puts them squarely in Item 5 — allocated in the wrapper's arena, closed with it.

### Item 12: Hold downcall handles in `static final` fields

```c
size_t ZSTD_compress(void* dst, size_t dstCapacity,
                     const void* src, size_t srcSize, int compressionLevel);
```

```java
static final MethodHandle COMPRESS =
        NativeLibrary.lookup("ZSTD_compress",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));
```

Not style: a `static final` `MethodHandle` is a constant to C2, which sees through it and inlines the
downcall stub — where FFM's performance story actually comes from. rocksdbffm's iteration benchmarks
put the FFM `byte[]` path 10–80% ahead of `rocksdbjni` depending on value size, for a structural
reason: no JNI frame setup, no thread-state transition, just a JIT-compiled stub.[^scale-bench] A
handle read from an instance field or a map gives most of that back.

The same applies to `VarHandle`s derived from layouts, and to `FunctionDescriptor`s if you are
building them at call time (don't).

zstd's own header makes the complementary point: driven through a foreign function interface, "it's
not rare that performance ends being spent more into the interface, rather than compression itself",
and recommends buffers "as large as practical" to cut round trips.[^zstd-header] FFM makes each
crossing cheaper, not free — batching at the API level is still the lever with the most travel.

### Item 13: Ship one Java artifact and cross-compile the native library

Half of what makes JNI bindings miserable to maintain isn't the C glue — it's shipping a compiled
shim per platform, built against whatever Java version users still run. Every target you didn't build
for is an `UnsatisfiedLinkError` for somebody, and the matrix only grows.

FFM removes the per-platform *Java* artifact — the glue is ordinary Java, compiled once. What remains
is the C library, and `zig cc` cross-compiles it for every target from one machine — bundling clang
and libc, no sysroot or system toolchain, including a Windows `.dll` built on a Linux runner. Both
projects build their natives this way.

Not a footnote to the FFM story — a large fraction of the practical benefit, and it gets far less
attention than the API itself.

## What carried across

Two libraries that agree on nothing at the C level converged on eleven of these items. The two they
did not share, 4 and 7, are the ones that cost me the most to learn, and zstd-java skips both for the
same structural reason: it never takes ownership of memory it did not size, and it is never called
back on a thread it did not create. That is a useful way to read the list — which items apply is
decided by the C library's ownership and threading model, not by taste.

One idea runs under the rest. Make the obligation visible where it is incurred: an arena in a
try-with-resources, a deallocator passed to `reinterpret`, a `Mapper` parameter, a `static final`
field. Each puts a lifetime or a cost somewhere the compiler or the next reader cannot miss it.

Both libraries are experimental and both are open to contributions, particularly around benchmarking
the Java-to-native boundary — which is, in the end, the only part of this that is hard to argue about.

---

[^zstd-compress]: [`Zstd.compress`](https://github.com/dfa1/zstd-java/blob/main/zstd/src/main/java/io/github/dfa1/zstd/Zstd.java), zstd-java.

[^pinnable-slice]: [`PinnableSlice.map`](https://github.com/dfa1/rocksdbffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/PinnableSlice.java), rocksdbffm.

[^cctx-close]: [`ZstdCompressStream.tryClose`](https://github.com/dfa1/zstd-java/blob/main/zstd/src/main/java/io/github/dfa1/zstd/ZstdCompressStream.java), zstd-java.

[^upcall-tutorial]: dev.java has a short, precise walkthrough of the mechanics: [Calling Java from native code](https://dev.java/learn/ffm/upcall/).

[^drain-source]: The full diagnosis and the fix live in [`BackgroundUpcallThreads`](https://github.com/dfa1/rocksdbffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/BackgroundUpcallThreads.java), rocksdbffm.

[^mapper]: [`Mapper`](https://github.com/dfa1/rocksdbffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/Mapper.java), rocksdbffm.

[^stream-buffer]: [`ZstdStreamBuffer`](https://github.com/dfa1/zstd-java/blob/main/zstd/src/main/java/io/github/dfa1/zstd/ZstdStreamBuffer.java), zstd-java.

[^scale-bench]: JMH `ScaleBenchmarkRunner`, `iterator.next()` + `value()`, two databases and two value sizes on an Apple M5 MacBook; numbers and caveats in [RocksDB Performance and Zero-Copy](https://dfa1.github.io/articles/rocksdb-performance-and-zero-copy.html).

[^zstd-header]: [`zstd.h`](https://github.com/facebook/zstd/blob/dev/lib/zstd.h), in the comment above `ZSTD_CStreamInSize`.
