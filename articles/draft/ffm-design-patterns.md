# FFM Design Patterns

*22 August 2026*

*There is a lot of writing about Java's Foreign Function & Memory API, and almost all of it stops at
the same place: look up `strlen`, allocate a string in a confined arena, invoke, print the length. The
API is final, the tutorials are correct, and none of them tell you what to do on day forty — when you
have three hundred native functions, a callback that outlives the call that registered it, and a C
library that mallocs on your behalf. This post is about that part.*

---

The material comes from three small projects: [rocksdbffm](https://github.com/dfa1/rocksdbffm), FFM
bindings for [RocksDB](https://rocksdb.org/), [zstd-java](https://github.com/dfa1/zstd-java), FFM
bindings for [Zstandard](https://facebook.github.io/zstd/), and [lmdb-ffm](https://github.com/dfa1/lmdb-ffm),
FFM bindings for [LMDB](https://www.lmdb.tech/). They are useful together because their C APIs disagree
about almost everything — error reporting, buffer ownership, statefulness, transactional structure —
and yet the Java-side solutions converged on most of the same rules. That convergence is what makes
these items rather than anecdotes.

The format is borrowed from *Effective Java*: the title of each item is the rule, the body is why it
earns its place, and the caveats after it are where the cost actually lives. Sixteen items. Every
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

### Item 14: Track child handles so a parent's terminal method can neutralize them

`mdb_env_close`, `mdb_txn_abort`, and `mdb_cursor_close` all return nothing:

```c
void mdb_env_close(MDB_env *env);
void mdb_txn_abort(MDB_txn *txn);
void mdb_cursor_close(MDB_cursor *cursor);
```

There is nothing in any of those signatures to tell you that calling the first while a
transaction from that environment is still open is undefined behavior, or that the
transaction must itself have no open cursors before *it* ends. That rule lives entirely in
LMDB's prose documentation. Nothing enforced it on the Java side either — every
`NativeObject` subclass managed its own pointer and nothing else, so a parent's terminal
method had no way to know a child was still alive. The failure is a two-step one, which
is what makes it easy to miss in review: the mistake (closing the parent early) and the
crash (the next, unrelated call on the child) are two different call sites, often two
different stack traces, sometimes two different threads.

```java
LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
LmdbDbi dbi = txn.openDatabase(Set.of());
txn.get(dbi, "key000042".getBytes(UTF_8));   // fine

env.close();

txn.get(dbi, "key000043".getBytes(UTF_8));   // SIGSEGV — mdb_page_search_root+0x2c
```

The fix is to make the parent actually track what it opened, and give its terminal method
something to check:

```java
// LmdbEnv
private final AtomicInteger openTransactions = new AtomicInteger();

void registerTransaction() {
    openTransactions.incrementAndGet();
}

void unregisterTransaction() {
    openTransactions.decrementAndGet();
}

@Override
protected void tryClose(MemorySegment ptr) throws Throwable {
    int open = openTransactions.get();
    if (open != 0) {
        // Do not call mdb_env_close — a still-open transaction may hold a
        // zero-copy view into the mapping it would free. Leak the native
        // handle instead of freeing memory a live transaction still depends
        // on; see Item 16 for why this throws rather than logs.
        throw new LmdbContractException(/* … */);
    }
    try {
        Bindings.ENV_CLOSE.invokeExact(ptr);
    } finally {
        arena.close();
    }
}
```

`LmdbTxn`'s constructor calls `registerTransaction()`; `commit()`, `abort()`, and the
transaction's own `tryClose()` each call `unregisterTransaction()` — exactly one of the
three runs per transaction, so the count never drifts. **The safe recovery here is refuse,
not repair.** An earlier version of this fix force-aborted every still-open transaction
before actually closing the environment, on the theory that a clean abort beats a leak.
That version was wrong: LMDB confines a transaction to the thread that began it, so
force-aborting one from whatever thread happens to be calling `close()` trades a
predictable crash for an unpredictable one — corrupting LMDB's reader-table bookkeeping,
or racing a call the owning thread has in flight right now, is strictly worse than the bug
being fixed. Once a resource can't be reached by a thread that's allowed to touch it,
*leaving it alone* is the only safe move; the parent still needs to know it happened, which
is Item 16.[^lmdb-env-close]

The same shape exists one ownership level down — a transaction whose cursor is still open
when it commits or aborts — and is still an open issue at the time of writing.[^lmdb-cursor-issue]
The fix there is the version of this pattern with no leak trade-off at all: neutralizing a
`LmdbCursor` needs no native call whatsoever (LMDB already frees the C-level cursor struct
as a side effect of ending the transaction), so it's safe to do from any thread — the
transaction just swaps the cursor's pointer to `NULL` directly, the same swap
`NativeObject#close()` already does to itself.

### Item 15: Re-derive an optimization's safety envelope for every capability added after it

Two different hot-path optimizations in lmdb-ffm each had a safety envelope that was
correct for the call sites that existed when the optimization was written, and wrong the
moment a new capability was added on top without anyone re-checking it.

The first is `Linker.Option.critical`, applied to `mdb_get` and `mdb_cursor_get` to skip
the JVM's thread-state transition on every downcall — a real win, 25–33% on
`benchmark/ReadBenchmark`'s cursor scans. `critical`'s contract is usually read as "keep
it short," but the JDK's own doc says more: a critical function "does not call back into
Java (e.g. using an upcall stub)." Nothing about a short-running native call *implies* that
constraint holds forever — it holds only until something makes that call re-enter Java.
lmdb-ffm has exactly that something: a custom `LmdbComparator`, installed via
`mdb_set_compare`, runs as an upcall from inside LMDB's own B+tree search — from inside
the very `mdb_get`/`mdb_cursor_get` call that was linked as critical. The result isn't a
segfault, it's a VM-level guarantee failure, because the JVM's own bookkeeping assumed
the thread never left native code:

```
# Internal Error (upcallLinker.cpp:77)
# guarantee(thread->thread_state() == _thread_in_native) failed:
#     wrong thread state for upcall
```

The fix links every affected symbol twice — once critical, once plain — and picks between
them at each call site on a flag that latches the first time a comparator is installed:

```java
static final MethodHandle GET =
        NativeLibrary.lookup("mdb_get", GET_DESCRIPTOR);
static final MethodHandle GET_CRITICAL =
        NativeLibrary.lookup("mdb_get", GET_DESCRIPTOR,
                new Linker.Option[] {Linker.Option.critical(false)});
```

```java
if (env.usesComparators()) {
    code = (int) Bindings.GET.invokeExact(ptr(), dbi.handle(), keyVal, dataVal);
} else {
    code = (int) Bindings.GET_CRITICAL.invokeExact(ptr(), dbi.handle(), keyVal, dataVal);
}
```

A database with no custom comparator — the default, and what the benchmark that
justified `critical` in the first place actually measures — keeps the fast path; installing a
comparator gives it up, once, for the rest of that environment's life.[^lmdb-critical-upcall]

The second case is a reused pair of `MDB_val` out-parameter structs, allocated once per
transaction and per cursor instead of once per call — a ~300x win over allocating a fresh
`Arena` on every read, per the same benchmark. That reuse is safe for every cursor
operation whose data parameter is purely an *out*-parameter — which is every operation
except `GET_BOTH`/`GET_BOTH_RANGE`, which need the caller's data as an *in*-parameter to
match against. Nothing in the API distinguishes that one operation from the rest, so it
silently reads whatever the previous call happened to leave in the reused slot — a
plausible-looking result built from stale state, not from anything the caller asked
for.[^lmdb-get-both-issue] The same confined `Arena` backing those structs also produces
an asymmetry nobody designed on purpose: a cross-thread `get()` throws
`WrongThreadException` before it can reach C, because reading the out-parameter touches
the confined arena — but a cross-thread `put()` allocates its own scratch `Arena` per call
and never touches the confined one, so it sails straight through to memory corruption
instead.[^lmdb-write-thread-issue] Same reused resource, two different call paths, and
only one of them happened to inherit the arena's protection.

**Neither of these is a bug in the optimization.** Both are correct for the shape of call
they were measured against. The lesson is that *shape* is exactly what a safety envelope
is drawn around, and nothing enforces re-checking it when a later change — a new op, a
new flag, a new call path through the same reused resource — falls outside the shape the
benchmark exercised. A `critical` binding's envelope is "no upcalls, ever, from this call or
anything it invokes"; a reused out-parameter's envelope is "every caller of this reused slot
treats it as pure output." Write down which capabilities were assumed when the
optimization was accepted, the same way you'd write down its measured cost — and treat
every new capability as a diff against that list, not as an unrelated addition.

### Item 16: Give a terminal method exactly one named escape hatch through its swallow

"Destructors must not throw" is the right default for a `close()` shared across every
`NativeObject` subclass — a caller unwinding through a `try`-with-resources block should
never have a cleanup failure mask the real exception, or blow up a path that was already
failing. lmdb-ffm's `NativeObject#close()` enforces exactly that:

```java
public final void close() {
    MemorySegment p = ptr.getAndSet(MemorySegment.NULL);
    if (!MemorySegment.NULL.equals(p)) {
        try {
            tryClose(p);
        } catch (Throwable _) {
            // destructors must not throw
        }
    }
}
```

Item 14's fix breaks that, on purpose, for exactly one case. Refusing to call
`mdb_env_close` while a transaction is still open (Item 14) is the right recovery — but if
the caller never finds out, the swallow has traded a crash for something arguably worse: a
`close()` that returns normally while the object it claims to have closed is still fully
alive underneath, silently able to mutate the file a caller now believes is safely closed.
A background log line doesn't fix that either — a caller who wraps `close()` in
try-with-resources, the idiom this whole API is built around, will never see it. The
difference that matters is *why* `tryClose` is throwing: an ordinary native-call failure is
routine and should stay swallowed, but a detected violation of the library's own
resource-lifetime contract is a bug in the *caller's* code that would otherwise vanish
without a trace. So `close()` gets one narrow, named carve-out — not a general "rethrow
on failure" flag, and not a checked exception threading a `throws` clause through every
`AutoCloseable#close()` in the codebase:

```java
public final void close() {
    MemorySegment p = ptr.getAndSet(MemorySegment.NULL);
    if (!MemorySegment.NULL.equals(p)) {
        try {
            tryClose(p);
        } catch (LmdbContractException e) {
            // The one Throwable this deliberately does not swallow.
            throw e;
        } catch (Throwable _) {
            // destructors must not throw
        }
    }
}
```

Every existing `tryClose` failure mode — an ordinary native-call error, a double-free
attempt, anything that isn't a detected contract violation — is swallowed exactly as
before; there's a regression test locking that in (`closeSwallowsTryCloseFailures`). Only
`LmdbContractException` escapes, and only after whatever recovery was possible already
ran, so the caller's own state is always left consistent by the time they catch it —
never mid-repair.[^lmdb-contract-exception]

The general shape: when a `close()`-style method's contract is "never throws," don't
relax that contract wholesale the first time you need to report something loud. Give the
one case that genuinely needs to escape its own type, and let the type itself carry the
justification for why "destructors must not throw" doesn't apply to it — the exception's
own javadoc becomes the place that argument lives, not a comment at each throw site.

## Item 17: Use Zig to cross-compile C/C++

FFM handles the calling side from Java. The other half of wrapping a C library is building that C library, and that is where cross-compilation usually turns into a matrix of OS-specific CI runners, Docker images, and hand-assembled sysroots. With Zig as the compiler, that whole problem mostly disappears.

`zig cc` is a drop-in replacement for `cc` that bundles clang, its own libc, and the headers for every target Zig supports — inside the toolchain download itself. `zstd-java` skips zstd's build system entirely: the sources are vendored as plain `.c`, and the script globs and compiles them directly.

```bash
# scripts/build-zstd.sh
export CC="zig cc -target $ZIG_TARGET"
SRCS=$(find "$ZSTD_LIB/common" "$ZSTD_LIB/compress" "$ZSTD_LIB/decompress" \
            "$ZSTD_LIB/dictBuilder" -name '*.c' | sort)
...
zig cc -target "$ZIG_TARGET" $CFLAGS -c "$src" -o "$out"
```

Once cross-compiling is just a `-target` string, adding a platform is a line in a `case` statement, not a new CI runner. For example: `zstd-java` maps six classifiers to six Zig target triples:

```bash
osx-aarch64)     ZIG_TARGET="aarch64-macos"
osx-x86_64)      ZIG_TARGET="x86_64-macos"
linux-x86_64)    ZIG_TARGET="x86_64-linux-gnu"
linux-aarch64)   ZIG_TARGET="aarch64-linux-gnu"
windows-x86_64)  ZIG_TARGET="x86_64-windows-gnu"
windows-aarch64) ZIG_TARGET="aarch64-windows-gnu"
```

That last pair is the one that stood out: a working Windows `.dll`, PE export table and all, produced from a Linux or macOS runner — no MinGW install, no Wine, no Windows box anywhere in the pipeline. Neither this project nor its C++ sibling [rocksdbffm](https://github.com/dfa1/rocksdbffm) runs one job per OS to get there[^ci]; one host builds every target, because nothing about the build depends on which OS it runs on.

One note: the Linux targets are `-gnu`, not `-musl`. The resulting `.so` still dynamically links glibc and expects a compatible one on the runtime host — Zig can target `-musl` for a fully static binary.

Uber has compiled every line of C/C++ in its Go monorepo with `zig cc`, for both x86_64 and arm64, since January 2023[^uber]. The experience generalizes past C, too — swap the language and the same essay gets written again[^justwork].

[^ci]: Both projects run their native builds under [`mlugg/setup-zig`](https://github.com/mlugg/setup-zig) in GitHub Actions; the classifier matrix is a loop over `-target` strings inside one job, not one job per OS.

[^uber]: Uber Engineering, [*Bootstrapping Uber's Infrastructure on arm64 with Zig*](https://www.uber.com/us/en/blog/bootstrapping-ubers-infrastructure-on-arm64-with-zig/) (2023).

[^justwork]: Loris Cro, [*Zig Makes Go Cross Compilation Just Work*](https://dev.to/kristoff/zig-makes-go-cross-compilation-just-work-29ho) — the same claim, and title, shows up for Rust and other languages once people reach for `zig cc` as their C toolchain.



## What carried across

Two libraries that agree on nothing at the C level converged on eleven of these items. The two they
did not share, 4 and 7, are the ones that cost me the most to learn, and zstd-java skips both for the
same structural reason: it never takes ownership of memory it did not size, and it is never called
back on a thread it did not create. That is a useful way to read the list — which items apply is
decided by the C library's ownership and threading model, not by taste.

lmdb-ffm, FFM bindings for [LMDB](https://www.lmdb.tech/), is a third data point, and it confirms all
eleven again, unchanged — plus three more (Items 14–16) that neither of the first two projects had
reason to hit. The difference isn't complexity so much as *shape*: rocksdbffm and zstd-java each wrap
a single opaque handle with one lifetime to manage, which is what Items 1–13 are mostly about managing
correctly. LMDB is transactional — an environment owns transactions, a transaction owns cursors — so
there are *relationships between* lifetimes as well, and a relationship has no natural place to live
unless one of the two sides is made to track it explicitly.

One idea runs under the rest. Make the obligation visible where it is incurred: an arena in a
try-with-resources, a deallocator passed to `reinterpret`, a `Mapper` parameter, a `static final`
field, a registration counter checked in a terminal method, an exception type carved through an
otherwise-total swallow. Each puts a lifetime, a cost, or a contract somewhere the compiler or the
next reader cannot miss it.

All three libraries are experimental and all three are open to contributions, particularly around
benchmarking the Java-to-native boundary — which is, in the end, the only part of this that is hard
to argue about.

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


[^lmdb-env-close]: [`LmdbEnv#tryClose`](https://github.com/dfa1/lmdb-ffm/pull/15), lmdb-ffm PR #15.

[^lmdb-cursor-issue]: [Cursor outlives its transaction](https://github.com/dfa1/lmdb-ffm/issues/4), lmdb-ffm issue #4, open at the time of writing.

[^lmdb-critical-upcall]: [Don't call the critical mdb_get/mdb_cursor_get through a comparator](https://github.com/dfa1/lmdb-ffm/pull/14), lmdb-ffm PR #14, merged.

[^lmdb-get-both-issue]: [GET_BOTH and GET_BOTH_RANGE have no way to pass the data they match on](https://github.com/dfa1/lmdb-ffm/issues/12), lmdb-ffm issue #12, open at the time of writing.

[^lmdb-write-thread-issue]: [Cross-thread put corrupts memory while cross-thread read throws](https://github.com/dfa1/lmdb-ffm/issues/8), lmdb-ffm issue #8, open at the time of writing.

[^lmdb-contract-exception]: [`LmdbContractException`](https://github.com/dfa1/lmdb-ffm/pull/15), lmdb-ffm PR #15.
