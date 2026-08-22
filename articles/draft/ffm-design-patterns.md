# Design FFM Bindings for Day Forty

*22 August 2026*

*There is a lot of writing about Java's Foreign Function & Memory API, and almost all of it stops at
the same place: look up `strlen`, allocate a string in a confined arena, invoke, print the length. The
API is final, the tutorials are correct, and none of them tell you what to do on day forty — when you
have three hundred native functions, a callback that outlives the call that registered it, and a C
library that mallocs on your behalf. This post is about that part.*

---

The material comes from two bindings I wrote: [rocksdbffm](https://github.com/dfa1/rocksdbffm), FFM
bindings for [RocksDB](https://rocksdb.org/), and [zstd-java](https://github.com/dfa1/zstd-java), FFM
bindings for [Zstandard](https://facebook.github.io/zstd/). They are useful together because their C
APIs disagree about almost everything — error reporting, buffer ownership, statefulness — and yet the
Java-side solutions converged. That convergence is what makes these items rather than anecdotes.

The format is borrowed from *Effective Java*: the title of each item is the rule, the body is why it
earns its place, and the caveats after it are where the cost actually lives. Thirteen items, three
chapters. Every example carries the C prototype it binds, copied from `zstd.h` or RocksDB's `c.h` —
half of FFM is reading a header correctly, and a Java snippet without its prototype hides exactly the
half that goes wrong.

I'll assume you know what `Arena`, `MemorySegment`, `Linker`, `FunctionDescriptor` and `MethodHandle`
are. If not, [dev.java's FFM tutorials](https://dev.java/learn/ffm/) are the right starting point, and
[JEP 454](https://openjdk.org/jeps/454) is unusually readable on the design rationale.

## Chapter 1: Memory and Lifetime

### Item 1: Prefer a confined arena per operation

The default, and the right answer most of the time. One arena, one thread, try-with-resources,
deterministic free at the closing brace. zstd-java's whole one-shot compression path is this and
nothing else:[^zstd-compress]

```java
// size_t ZSTD_compressBound(size_t srcSize);
// size_t ZSTD_compress(void* dst, size_t dstCapacity,
//                      const void* src, size_t srcSize, int compressionLevel);
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

Everything allocated inside the block dies at the end of it. There is no lifetime question to answer,
no ownership to document, no leak to find. Every other pattern in this post is a deviation from this
one, and each deviation should have to justify itself.

Two properties are worth stating explicitly, because they are the reasons to prefer confined over
shared:

- A confined arena is single-threaded by construction, so the access path only has to check that the
  accessing thread is the owner — a check the JIT can hoist out of a loop, unlike the shared arena's
  liveness handshake.
- `Arena.ofShared()` buys thread-crossing at the cost of a much more expensive close: it has to make
  the deallocation globally visible. Reach for it when a segment genuinely must outlive one thread,
  not to avoid deciding which thread owns what.

### Item 2: Allocate out-params in the calling arena

C's answer to multiple return values is pointer arguments. The Java-side answer is that those slots
are allocated in the same confined arena as everything else and never escape it.

```java
// char* rocksdb_get(rocksdb_t* db, const rocksdb_readoptions_t* options,
//                   const char* key, size_t keylen, size_t* vallen, char** errptr);
try (Arena arena = Arena.ofConfined()) {
    MemorySegment vallen = arena.allocate(JAVA_LONG);   // size_t*
    MemorySegment errptr = arena.allocate(ADDRESS);     // char**
    MemorySegment value  = (MemorySegment)
            rocksdb_get.invokeExact(db, readOptions, key, keyLen, vallen, errptr);
    // ...
}
```

Nothing interesting happens here, which is the point. The out-param slots are scratch space, they are
sized by a layout rather than by a hand-computed number of bytes, and they are gone at the closing
brace. What comes *out* of them is the interesting part.

### Item 3: Copy out and free before reaching for anything cleverer

When a C function returns a buffer it allocated, the conservative move is to copy it into a `byte[]`
and free the native memory immediately. Ownership begins and ends inside one method.

```java
// void rocksdb_free(void* ptr);
long len = vallen.get(JAVA_LONG, 0);
byte[] result = value.reinterpret(len).toArray(JAVA_BYTE);
free(value);
return result;
```

Note the `reinterpret(len)`. A pointer returned from native code arrives as a zero-length segment: FFM
knows the address but has no idea how much memory is behind it, so every read would fail the bounds
check. `reinterpret` is how you tell it, and it is a restricted method precisely because you are
asserting something the runtime cannot verify. Get the length wrong here and you have re-invented the
JNI failure mode.

This pattern is boring and correct and should be the default. The next one exists only because the
copy is sometimes the thing you were trying to avoid.

### Item 4: Give a borrowed native buffer a Java lifetime with `reinterpret`

RocksDB has a second read path that hands back values it owns rather than copies:

```c
rocksdb_pinnableslice_t* rocksdb_get_pinned(rocksdb_t* db, const rocksdb_readoptions_t* options,
                                            const char* key, size_t keylen, char** errptr);
const char* rocksdb_pinnableslice_value(const rocksdb_pinnableslice_t* t, size_t* vlen);
void rocksdb_pinnableslice_destroy(rocksdb_pinnableslice_t* v);
```

The slice keeps the value pinned in the block cache and `rocksdb_pinnableslice_value` returns a bare
`const char*` into it — valid until `rocksdb_pinnableslice_destroy`, and not yours to free. Copying it
into a `byte[]` doubles the memory traffic of a read that was supposed to be zero-copy. The
three-argument `reinterpret` is the expression that avoids the copy:[^pinnable-slice]

```java
// The `null` cleanup is deliberate: this view borrows from the slice, it does
// not own the memory, so closing `arena` must not attempt to free it.
MemorySegment view = data.reinterpret(len, arena, null).asReadOnly();
```

`reinterpret(long, Arena, Consumer<MemorySegment>)` does three separable things:

1. **Restores spatial bounds** — the segment now knows it is `len` bytes long.
2. **Grafts on temporal bounds** — the segment dies when `arena` closes, and accessing it afterwards
   throws instead of reading freed memory.
3. **Registers a deallocator** — the consumer runs at close, for the case where the buffer really is
   yours to free (`rocksdb_free` on a `rocksdb_get` result, say).

Steps 1 and 2 are what a zero-copy read tier is built on; step 3 is what makes "foreign allocator,
Java lifetime" expressible at all. Splitting them matters, because who frees the memory is a different
question from how long Java may look at it — pinned data needs the first two and a `null` for the
third.

The failure modes are worth naming, because they are not obvious:

- **A wrong length is unrecoverable.** You are overriding the safety property, so the usual
  `IndexOutOfBoundsException` won't save you. Read the length from the out-param, never infer it.
- **The cleanup runs on whoever closes the arena.** With a shared arena that can be an arbitrary
  thread. If the native `free` has thread affinity — some allocators do — this is a latent crash.
- **Binding to a long-lived arena converts a bounded cost into an unbounded one.** A confined arena
  per read frees at the closing brace; the same call against a global arena is a leak.
- **Ownership is not transitive.** If the buffer contains pointers to other native allocations,
  nothing frees those. Bind the whole graph or copy.

Zstandard never needs any of this: you size the destination yourself with `ZSTD_compressBound` and
pass it in. That asymmetry is a useful sanity check — if you find yourself reaching for a deallocator
in a library whose API is caller-allocates, you have probably misread the ownership contract.

### Item 5: Give every opaque handle its own arena

Between "lives for one call" and "lives forever" sits the most common real case: a native object that
is expensive to create, reused across many calls, and freed explicitly. `ZSTD_CCtx` is the canonical
example; `rocksdb_t` and `rocksdb_iterator_t` are the same shape.

The rule is one arena per native object, owned by the Java wrapper, closed in the wrapper's
`close()`:[^cctx-close]

```java
// ZSTD_CCtx* ZSTD_createCCtx(void);
// size_t     ZSTD_freeCCtx(ZSTD_CCtx* cctx);   /* compatible with NULL pointer */
@Override
protected void tryClose(MemorySegment ptr) throws Throwable {
    try {
        var _ = (long) Bindings.FREE_CCTX.invokeExact(ptr);
    } finally {
        arena.close();
    }
}
```

The ordering matters and is easy to get backwards: **free the native object first, then close the
arena.** The arena holds the buffers and stubs the native object may still touch during its own
teardown. Closing the arena first is a use-after-free that will not reproduce under light load. The
`finally` is not decoration either — if the native free throws, the arena still has to go.

A wrapper like this is also where you decide the object's thread policy, and you should decide it
rather than inherit it. `ZSTD_CCtx` is not thread-safe; a confined arena turns misuse into a Java-level
exception instead of silent corruption, which is a rare case of the safety property and the C contract
lining up for free.

### Item 6: Scope an upcall stub to the life of the object that uses it

Upcalls are where lifetime stops being about buffers.[^upcall-tutorial] A RocksDB custom comparator is
registered once and invoked much later, during compaction, on a thread you did not create. The stub
must remain valid for as long as RocksDB might call it — which is not "until the call returns" but
"until the comparator is destroyed".

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

The `arena` argument is the whole story: the stub is valid exactly as long as that arena is open. So
the arena has to be scoped to the *native object's* life, per Item 5, and its close has
to be ordered after RocksDB is done with the comparator.

Three rules I'd put in bold in any binding's contributing guide:

- **An exception escaping an upcall does not propagate — it takes down the VM.** Every upcall body is
  a `try`/`catch (Throwable)` that converts to a return code or a sentinel. There is no exception
  tunneling across the native frame; to surface the error, stash it in a field and rethrow after the
  downcall returns.
- **Reachability is not enough.** A strong Java reference to the stub segment does not keep it
  callable; the arena being open does. Forgetting this produces a crash far away from the cause.
- **Per-instance stubs are cheap to write and expensive in aggregate.** Binding context via
  `MethodHandles.insertArguments` gives you one stub per comparator instance, each with its own
  generated code. Once there are many, switch to the C idiom the API already offers: one stub, plus
  the `void* state` pointer RocksDB passes back, keyed into a registry on the Java side.

### Item 7: Make adopted native threads die before the VM does

Item 6 is about keeping the stub alive. This one is about the *thread* that runs it — a lifetime you
did not create, cannot enumerate, and are nevertheless responsible for.

When native code invokes an upcall stub from a thread the JVM has never seen — a RocksDB background
compaction thread, say — the JDK quietly attaches that thread to the VM so it can run Java code. It
stays attached. The detach happens in a thread-local destructor whenever the native thread eventually
dies, which for a pooled worker means "at process exit".

That is fine until something at process exit tries to join it. RocksDB's default `Env` registers a
static destructor that stops its thread pools and `pthread_join`s every background thread. Combine the
two and you get a deadlock that is not a race — it happens every single time:[^drain-source]

1. Something calls `System.exit()`. HotSpot brings the world to a safepoint, sets its global
   `_vm_exited` flag **while holding `Threads_lock`**, and calls libc `exit()` from the VM thread.
2. `exit()` runs static destructors, reaching RocksDB's `PosixEnv::JoinThreadsOnExit`, which
   `pthread_join`s the pool.
3. A joined thread that once ran an upcall is an *attached* JVM thread. As it unwinds, its
   thread-local destructor runs the JDK's `UpcallContext::~UpcallContext` → `DetachCurrentThread` →
   `VM_Exit::wait_if_vm_exited`, which blocks forever on the `Threads_lock` the exiting VM thread
   never releases. HotSpot does that deliberately: the process is about to die, so parking the thread
   is free.

Except it doesn't die. Step 2 waits on a thread that step 3 has parked permanently:

```
VM Thread  → VM_Exit::doit() → os::exit() → exit() → __cxa_finalize_ranges
           → PosixEnv::JoinThreadsOnExit::~JoinThreadsOnExit()
           → ThreadPoolImpl::Impl::JoinThreads() → std::thread::join()   [blocked]

BG thread  → _pthread_exit → _pthread_tsd_cleanup
           → UpcallContext::~UpcallContext() → jni_DetachCurrentThread
           → VM_Exit::wait_if_vm_exited() → Mutex::lock()                [blocked]
```

How that trace was obtained is worth a line of its own: `jstack` and `jcmd` cannot attach to a VM
already inside `VM_Exit`, so the only way to see it is an OS-level sampler — `sample` on macOS,
`gdb -p` or `perf` on Linux. A hang with no thread dump available is itself a hint that you are
looking at a VM in the middle of exiting.

The reason this gets misdiagnosed as flaky is that it depends on how the process ends, not on timing:

| | return from `main` | `System.exit()` |
|---|---|---|
| **no upcall ever fired** | clean | clean |
| **upcall fired** | clean | **hangs, every time** |

Returning from `main` goes through `DestroyJavaVM` rather than `VM_Exit`, so a hand-written reproducer
exits cleanly and the bug looks intermittent. It is not; it is exactly reproducible once you exit the
way test runners and application containers do.

The fix is to make those threads exit while the VM is still fully alive, so their detach completes
normally. A shutdown hook is the last point at which that is still possible:

```java
// void rocksdb_env_set_background_threads(rocksdb_env_t* env, int n);
// void rocksdb_env_set_high_priority_background_threads(rocksdb_env_t* env, int n);

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

Shrinking a pool to zero makes each excess thread detach itself from the pool and return, so by the
time the static destructor runs there is nothing left for it to join.

The details that make this work are all easy to get wrong:

- **Arm the hook at registration, not from the upcall.** Triggering class initialization and
  `addShutdownHook` from inside a native upcall means acquiring locks on a thread the VM has just
  adopted. Do it on the thread that registers the callback, where it is ordinary Java.
- **You have to know which threads to wait for.** There is no API that enumerates "threads the VM
  adopted via FFM", so each upcall dispatch records `Thread.currentThread()` in a set. The set stays
  small — one entry per pool thread, not one per callback.
- **Bound the wait.** If a thread is wedged for an unrelated reason, timing out leaves you exactly
  where you were without the hook. Blocking shutdown forever does not.
- **`addShutdownHook` throws if the VM is already shutting down.** Catch and ignore: a callback
  registered that late will not outlive the VM anyway.
- **This is not a RocksDB quirk.** Any native library that joins its own threads from an `atexit`
  handler or a static destructor — and many thread-pool-owning C libraries do — produces the same
  deadlock the moment one of those threads has run an upcall.

zstd-java never hits this, for the same structural reason it never needs a deallocator: it has no
callbacks invoked from library-owned threads, so no thread of zstd's ever becomes a JVM thread. The
hazard is specific to bindings whose upcalls are called back on threads the native library created and
owns.

## Chapter 2: The API Boundary

### Item 8: Keep the generated raw layer out of the public API

jextract generates a faithful mirror of the header. That mirror is not an API — it is an intermediate
representation, and shipping it is how `MemorySegment` ends up in application code.

Every binding worth using has two layers:

- **Raw layer**, package-private. One method per C symbol, signature mirrors the header exactly, no
  interpretation, no cleverness. Generated, ideally mechanically.
- **Idiomatic layer**, public. `byte[]`, `Optional`, records, exceptions, `Path`. No `MethodHandle`,
  no restricted methods, no arenas the caller has to know about, and no `MemorySegment` outside the
  tiers that declare one (Item 10).

The generated raw layer is a precondition, not a convenience. If it is hand-written, people will put
"just a little" interpretation into it — a null check here, a length fixup there — and within a year
nobody can tell which layer owns the semantics.

A small example of the boundary doing real work: rocksdbffm takes `java.nio.file.Path` everywhere the
C API takes `const char*` for a filesystem location. The C API cannot tell you whether a string is a
path; `Path` can, it composes with the rest of `java.nio.file`, and it makes "absolute or relative?"
someone else's already-solved problem.

### Item 9: Translate every C error idiom at a single boundary

This is the pattern the two libraries make legible, because the C idioms could hardly be more
different and the Java answer is identical.

**RocksDB** uses an out-param: the `char** errptr` in `rocksdb_get` above, set to a malloc'd message
on failure and left NULL on success. It is the last parameter of nearly every function in `c.h`.

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

```java
// unsigned    ZSTD_isError(size_t result);
// const char* ZSTD_getErrorName(size_t result);
static boolean isError(long code) {
    try {
        return ((int) Bindings.IS_ERROR.invokeExact(code)) != 0;
    } catch (Throwable t) {
        throw rethrow(t);
    }
}
```

Different mechanics, same shape: **one helper, called at the boundary, converting the C convention
into a typed Java exception, so that no caller ever sees the convention at all.** The idiom is the
variable; the boundary is the constant.

Related translations belong in the same place:

- **Sentinel returns.** `-1` and `NULL` become an exception when they mean failure and
  `Optional.empty()` when they mean "legitimately absent". Which one applies is a judgment call per
  function — `rocksdb_get` returning NULL is a missing key, not an error — and pretending there is a
  mechanical rule is how you end up throwing on empty results.
- **Multiple out-params collapse into one value.** `(value, length, found)` becomes `Optional<byte[]>`.
- **errno** is captured with `Linker.Option.captureCallState("errno")` on the raw side and translated
  on the idiomatic side. Reading `errno` after the downcall by any other means is a race — the JVM may
  have made syscalls of its own in between.

### Item 10: Expose zero-copy as a declared tier, never as a leak

"No `MemorySegment` in public signatures" is the right default and the wrong absolute rule. Some
callers *want* the segment — they are streaming to a socket, or feeding another native library, and a
`byte[]` copy is exactly the cost they came to avoid.

The three copying tiers below are `rocksdb_get`; the scoped one is `rocksdb_get_pinned` from Item 4.
rocksdbffm exposes them as tiers of the same operation:

| Tier | Signature | Copy | Caller obligation |
|---|---|---|---|
| `byte[]` | `byte[] get(byte[] key)` | one copy out | none |
| `ByteBuffer` | `CopyResult get(ByteBuffer key, ByteBuffer value)` | into the caller's buffer | provide it, size it |
| `MemorySegment` | `CopyResult get(MemorySegment key, MemorySegment value)` | into the caller's segment | provide it, own its arena |
| scoped | `<R> Optional<R> get(MemorySegment key, Mapper<R> fn)` | none | read inside the callback |

The interesting one is the last. The zero-copy tier does not hand a segment back; it passes a
read-only view *into* a callback, and the arena backing that view closes the moment the callback
returns.[^mapper] Retaining it is not undefined behavior, it is an `IllegalStateException` — or a
`WrongThreadException` if the segment is smuggled to another thread. The obligation is discharged by
the library, not documented for the caller.

The rule that survives is narrower and more useful than the one I started with:

> A `MemorySegment` may appear in a public signature only where the signature itself pins its
> lifetime: a buffer the caller already owns, or an argument to a callback that ends when the call
> does.

The same reasoning applies to zstd-java's zero-copy path, and to zstd's streaming API, where the
natural unit is a segment pair rather than an array.

### Item 11: Derive struct accessors from the layout, never from offsets

Zstd's streaming API is a good counterexample to "always copy out". It is driven by two structs whose
`pos` field the native side advances in place:

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

The whole protocol is that you call, read `pos`, and call again. The two differ only in the name and
constness of the first field, so one Java layout serves both — named `ptr` precisely because it is
`src` in one and `dst` in the other:[^stream-buffer]

```java
private static final StructLayout LAYOUT = MemoryLayout.structLayout(
        ADDRESS.withName("ptr"),
        JAVA_LONG.withName("size"),
        JAVA_LONG.withName("pos"));

private static final VarHandle POS_HANDLE = LAYOUT.varHandle(PathElement.groupElement("pos"));
```

Two things to take from this. First, the accessors come out of the layout, so padding and ABI
differences stay the library's problem instead of yours. Second, the
buffers live for the whole stream, not for one call, which puts them squarely in Item 5 — allocated
in the wrapper's arena, closed with it.

## Chapter 3: Performance and Packaging

### Item 12: Hold downcall handles in `static final` fields

```java
// size_t ZSTD_compress(void* dst, size_t dstCapacity,
//                      const void* src, size_t srcSize, int compressionLevel);
static final MethodHandle COMPRESS =
        NativeLibrary.lookup("ZSTD_compress",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));
```

Not style. When a `MethodHandle` is in a `static final` field, C2 treats it as a constant, sees
through it, and inlines the downcall stub — which is where FFM's performance story actually comes
from. rocksdbffm's iteration benchmarks put the FFM `byte[]` path 10–80% ahead of `rocksdbjni`
depending on value size, and the reason is structural: no JNI frame setup, no thread-state transition,
just a JIT-compiled stub.[^scale-bench] A handle read from an instance field or a map gives most of
that back.

The same applies to `VarHandle`s derived from layouts, and to `FunctionDescriptor`s if you are
building them at call time (don't).

The complementary point comes from zstd's own header, which warns that when the streaming interface is
driven through a foreign function interface, "it's not rare that performance ends being spent more
into the interface, rather than compression itself", and recommends buffers "as large as practical" to
cut the number of round trips.[^zstd-header] FFM makes each crossing cheaper; it does not make
crossings free. Batching at the API level is still the lever with the most travel.

### Item 13: Ship one Java artifact and cross-compile the native library

Half of what makes JNI bindings miserable to maintain is not the C glue — it is shipping a compiled
shim per platform, built against whatever Java version the users still run. Every target you did not
build for is an `UnsatisfiedLinkError` for somebody, and the matrix only grows.

FFM removes the per-platform *Java* artifact: the glue is ordinary Java, compiled once. All that
remains is the C library itself, and `zig cc` will cross-compile that for every target from one
machine — bundling clang and libc for each, without a sysroot or a system toolchain, including a
Windows `.dll` built on a Linux runner. Both rocksdbffm and zstd-java build their natives this way.

This is not a footnote to the FFM story. It is a large fraction of the practical benefit, and it gets
far less attention than the API itself.

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

[^zstd-compress]: [`Zstd.compress`](https://github.com/dfa1/zstd-java/blob/main/zstd/src/main/java/io/github/dfa1/zstd/Zstd.java), zstd-java.

[^pinnable-slice]: [`PinnableSlice.map`](https://github.com/dfa1/rocksdbffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/PinnableSlice.java), rocksdbffm.

[^cctx-close]: [`ZstdCompressStream.tryClose`](https://github.com/dfa1/zstd-java/blob/main/zstd/src/main/java/io/github/dfa1/zstd/ZstdCompressStream.java), zstd-java.

[^upcall-tutorial]: dev.java has a short, precise walkthrough of the mechanics: [Calling Java from native code](https://dev.java/learn/ffm/upcall/).

[^drain-source]: The full diagnosis and the fix live in [`BackgroundUpcallThreads`](https://github.com/dfa1/rocksdbffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/BackgroundUpcallThreads.java), rocksdbffm.

[^mapper]: [`Mapper`](https://github.com/dfa1/rocksdbffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/Mapper.java), rocksdbffm.

[^stream-buffer]: [`ZstdStreamBuffer`](https://github.com/dfa1/zstd-java/blob/main/zstd/src/main/java/io/github/dfa1/zstd/ZstdStreamBuffer.java), zstd-java.

[^scale-bench]: JMH `ScaleBenchmarkRunner`, `iterator.next()` + `value()`, two databases and two value sizes on an Apple M5 MacBook; numbers and caveats in [RocksDB Performance and Zero-Copy](https://dfa1.github.io/articles/rocksdb-performance-and-zero-copy.html).

[^zstd-header]: [`zstd.h`](https://github.com/facebook/zstd/blob/dev/lib/zstd.h), in the comment above `ZSTD_CStreamInSize`.
