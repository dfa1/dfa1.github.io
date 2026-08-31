# FFM Design Patterns

*22 August 2026*

*There is a lot of writing about Java's Foreign Function & Memory API — the result of [Project
Panama](https://openjdk.org/projects/panama/) — and almost all of it stops at the same place: look up
`strlen`, allocate a string in a confined arena, invoke, print the length. The API is final, the
tutorials are correct, and none of them tell you what to do on day forty — when you have three hundred
native functions, a callback that outlives the call that registered it, and a C library that mallocs
on your behalf. This post is my running notes on that part.*

---

These are evolving notes, not a finished catalog — the patterns I currently reach for to keep ownership
sane, cleanup deterministic, and the public API type-safe when wrapping a C library with FFM. They
change as the bindings they're drawn from do: an item gets added, revised, or dropped once a fourth
project proves it wrong. Treat this as where I've landed so far, not a final word.

The material comes from three small projects: [rocksdbffm](https://github.com/dfa1/rocksdbffm), FFM
bindings for [RocksDB](https://rocksdb.org/), [zstd-ffm](https://github.com/dfa1/zstd-ffm), FFM
bindings for [Zstandard](https://facebook.github.io/zstd/), and [lmdb-ffm](https://github.com/dfa1/lmdb-ffm),
FFM bindings for [LMDB](https://www.lmdb.tech/). They are useful together because their C APIs disagree
about almost everything — error reporting, buffer ownership, statefulness, transactional structure —
and yet the Java-side solutions kept converging on the same handful of rules. That convergence is why
these read as patterns rather than one-off fixes.

The format is borrowed from *Effective Java*: a short title states the pattern, the body explains why
it earns its place, and the caveats after it are where the cost actually lives. Every example carries
the C prototype it binds, copied from `zstd.h` or RocksDB's `c.h` — half of FFM is reading a header
correctly, and a Java snippet without its prototype hides exactly the half that goes wrong.

I'll assume you know what `Arena`, `MemorySegment`, `Linker`, `FunctionDescriptor` and `MethodHandle`
are. If not, [dev.java's FFM tutorials](https://dev.java/learn/ffm/) are the right starting point,
[JEP 454](https://openjdk.org/jeps/454) is unusually readable on the design rationale, and Maurizio
Cimadamore's [Project Panama design notes](https://cr.openjdk.org/~mcimadamore/panama/) are where the
API's harder tradeoffs — confinement, arenas, memory-segment addressing — were actually argued out.

## Contents

- [Item 1: Prefer a confined arena per operation](#item-1)
- [Item 2: Size scratch out-params from the platform's own C type](#item-2)
- [Item 3: Copy out and free before reaching for anything cleverer](#item-3)
- [Item 4: Give a borrowed native buffer a Java lifetime with `reinterpret`](#item-4)
- [Item 5: Wrap native pointers to provide ownership](#item-5)
- [Item 6: Name the ownership shape — unique, parent, or shared](#item-6)
- [Item 7: Give every opaque handle its own arena](#item-7)
- [Item 8: Scope an upcall stub to the life of the object that uses it](#item-8)
- [Item 9: Make adopted native threads die before the VM does](#item-9)
- [Item 10: Keep the generated raw layer out of the public API](#item-10)
- [Item 11: Give a bitmask its own enum, and fold it back at one boundary](#item-11)
- [Item 12: Translate every C error idiom at a single boundary](#item-12)
- [Item 13: Bind NUL-terminated strings by who allocated them, not by their shape](#item-13)
- [Item 14: Expose zero-copy as a declared tier, never as a leak](#item-14)
- [Item 15: Derive struct accessors from the layout, never from offsets](#item-15)
- [Item 16: Hold downcall handles in `static final` fields](#item-16)
- [Item 17: Ship one Java artifact and cross-compile the native library with Zig](#item-17)
- [Item 18: Native access is a permission your caller grants, not one you can grant yourself](#item-18)
- [Item 19: Load the library once, and know exactly where each symbol comes from](#item-19)
- [Item 20: Test what the type system can't check — ownership, concurrency, and the C library's real failure modes](#item-20)

### Item 1: Prefer a confined arena per operation {#item-1}

The default, and usually the right answer: one arena, one thread, try-with-resources, deterministic
free at the closing brace. zstd-ffm's one-shot compression path is exactly this:[^zstd-compress]

```c
size_t ZSTD_compressBound(size_t srcSize);
size_t ZSTD_compress(void* dst, size_t dstCapacity,
                     const void* src, size_t srcSize, int compressionLevel);
```

**Ownership:** entirely caller-side. Java allocates both `src` and `dst`; `ZSTD_compress` only reads
one and writes the other. Nothing here is allocated by C, so there's nothing to free.

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

### Item 2: Size scratch out-params from the platform's own C type {#item-2}

C's answer to multiple return values is pointer arguments; the Java-side answer allocates those slots
in the same confined arena as everything else and never lets them escape it.

```c
char* rocksdb_get(rocksdb_t* db, const rocksdb_readoptions_t* options,
                  const char* key, size_t keylen, size_t* vallen, char** errptr);
```

**Ownership:** `vallen` and `errptr` are caller-allocated scratch, freed with the arena. What RocksDB
writes *through* them — the returned value pointer and any error message — is a different story: both
are C-allocated and handed off to the caller on return. Freeing the first is Item 3's subject; the
second, Item 12's.

The obvious way to size that scratch is also the wrong one:

```java
try (Arena arena = Arena.ofConfined()) {
    MemorySegment vallen = arena.allocate(JAVA_LONG);   // size_t*
    MemorySegment errptr = arena.allocate(ADDRESS);     // char**
    MemorySegment value  = (MemorySegment)
            rocksdb_get.invokeExact(db, readOptions, key, keyLen, vallen, errptr);
    // ...
}
```

`JAVA_LONG` for `size_t*` is a guess wearing a layout's clothes. It happens to be right today because
every platform FFM currently targets is 64-bit — but the same style of guess for C's `long` is exactly
how a binding written on Linux quietly breaks on Windows, where `long` is 32 bits even in a 64-bit
process (LLP64, not LP64). `Linker` already knows the answer, keyed by the C type's own name instead of
by whatever a Java programmer assumes it maps to:

```java
MemoryLayout SIZE_T = Linker.nativeLinker().canonicalLayouts().get("size_t");
MemorySegment vallen = arena.allocate(SIZE_T);
```

`canonicalLayouts()` returns the target platform's own C dialect — `"size_t"`, `"long"`, `"wchar_t"`,
and the rest — resolved by the same `Linker` that will make the call, not asserted by whoever wrote the
binding. Do this once per C type in the raw layer, and every out-param sized from it inherits the
correct answer instead of every call site repeating the same guess — Item 15's struct layout is the same
lesson applied to a whole field instead of one out-param.

### Item 3: Copy out and free before reaching for anything cleverer {#item-3}

When a C function returns a buffer it allocated, the conservative move is to copy it into a `byte[]`
and free the native memory immediately — ownership begins and ends inside one method.

```c
void rocksdb_free(void* ptr);
```

```java
checkError(errptr);                     // throws RocksDBException on failure — Item 12
if (MemorySegment.NULL.equals(value)) {
    return null;                        // missing key, not an error — Item 12 again
}
long len = vallen.get(JAVA_LONG, 0);
byte[] result = value.reinterpret(len).toArray(JAVA_BYTE);
free(value);
return result;
```

Both checks run before the copy, not after: `errptr` is the two out-param slots' whole reason for being
(Item 2), and skipping it here is how `value.reinterpret(len)` ends up reading from whatever address
`errptr` happened to leave in `value` on a failed call — including, for a NULL return, address zero. A
pointer from native code arrives as a zero-length segment either way — FFM knows the address but not how
much memory is behind it, so every read fails the bounds check until `reinterpret(len)` tells it. It's a
restricted method because you're asserting something the runtime can't verify; get the length wrong and
you've re-invented the JNI failure mode.

Boring and correct, and the default. The next item exists only because the copy is sometimes the cost
you were trying to avoid.

### Item 4: Give a borrowed native buffer a Java lifetime with `reinterpret` {#item-4}

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

### Item 5: Wrap native pointers to provide ownership {#item-5}

Wrapping a native pointer in a Java object raises three questions no C header answers: who frees it, and
how many times; can ownership move to someone else without a free; and can this object's own resource be
released while something downstream still depends on it. All three projects answer the first two with the
same base class, `NativeObject`, holding the pointer in an `AtomicReference<MemorySegment>`; rocksdbffm
generalizes the third.

**Free exactly once, from any thread.** `close()` has to run its cleanup exactly once no matter how many
times, or from how many threads, it's called. All three projects independently arrived at the same twelve
lines before any of them named it as a pattern — zstd-ffm's version:

```java
public abstract class NativeObject implements AutoCloseable {

    private final AtomicReference<MemorySegment> ptr;

    protected NativeObject(MemorySegment owningPointer) {
        this.ptr = new AtomicReference<>(owningPointer);
    }

    protected final MemorySegment ptr() {
        MemorySegment p = ptr.get();
        if (MemorySegment.NULL.equals(p)) {
            throw new IllegalStateException("native object is closed");
        }
        return p;
    }

    @Override
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

    protected abstract void tryClose(MemorySegment ptr) throws Throwable;
}
```

Three things this buys that a hand-written `close()` per wrapper doesn't:

- **`getAndSet` makes "called twice" and "called from two threads at once" the same problem, solved by
  one atomic swap.** A `boolean closed` flag checked then set is a race; here the pointer *is* the flag,
  so there's no second field to fall out of sync with it.
- **`tryClose` runs exactly once, with a pointer the type already knows is non-NULL** — no defensive
  null check duplicated in every subclass, no double-free even if a caller closes the same object
  concurrently from two threads.
- **The swallow-or-not decision is made once, not re-litigated per subclass.** rocksdbffm's version logs
  before swallowing; zstd-ffm's swallows silently — the LMDB carve-out later in this item is a narrow,
  deliberate exception to that default, not a rebuild of it.

`close()` is `final`; only `tryClose(MemorySegment)` is abstract, so a `ZstdCompressStream.tryClose` and
an `LmdbEnv.tryClose` (below) can be entirely different one-off bodies plugged into identical,
already-correct plumbing — the concurrency argument gets made once, here, instead of re-audited in every
subclass that touches a native pointer.

Two smaller choices in that snippet are worth a second look rather than a second thought:

- **`MemorySegment.NULL.equals(p)`, not `==`.** Both work here — `ptr` only ever holds
  `MemorySegment.NULL` or a pointer this class produced itself — but they're not the same check:
  `equals` compares address and byte size, `==` compares object identity. Reference identity would be
  cheaper and closer to what's actually being asked ("is this the sentinel"), and leaning on `equals`'
  notion of "same location" here is a habit worth noticing rather than a deliberate choice.
- **No `Cleaner` safety net for a caller who forgets `close()`.** A cleaner thread finalizing a leaked
  object would run on precisely the kind of arbitrary thread Item 4 warns a native `free` can be
  sensitive to — trading a leak, which at least shows up in whatever monitoring already watches for
  leaks, for a background thread quietly making native calls nobody asked it to make.

**Give ownership away without freeing it.** Not every pointer a wrapper holds dies in that wrapper's
`close()` — sometimes a later native call takes ownership instead:

```c
void rocksdb_block_based_options_set_filter_policy(
    rocksdb_block_based_table_options_t* options,
    rocksdb_filterpolicy_t* filter_policy);
void rocksdb_filterpolicy_destroy(rocksdb_filterpolicy_t*);
```

**Ownership:** `set_filter_policy` transfers ownership of `filter_policy` into `options` — RocksDB's
`c.cc` implements it as `options->rep.filter_policy.reset(filter_policy)`, so `options`'s own destructor
frees it from here on. Calling `rocksdb_filterpolicy_destroy` on it afterward is a double-free of memory
the caller no longer owns. rocksdbffm's `NativeObject` — same shape, field named `owningPointer` there —
has a second, package-private way to reach `MemorySegment.NULL` that skips `tryClose` entirely:

```java
void transferOwnership() {
    owningPointer.set(MemorySegment.NULL);
}
```

The hard part isn't the method, it's knowing when to call it — nothing in a C signature says "takes
ownership." The class's own javadoc admits as much and lists where to actually find out: read the C
source and check whether the destructor calls `delete` on what you handed it, check the API docs on the
rare occasion they say so explicitly, or fall back to whatever the official JNI binding already worked
out. `close()` called after `transferOwnership()` is a no-op — the pointer is already NULL — which is the
point: the object still gets closed, it just no longer owns anything by then. One rough edge that comes
along with reusing the same sentinel: `ptr()`'s exception afterward still reads "native object is
closed," which isn't quite what happened — the object gave its pointer away, it wasn't closed. The
message was written for the CAS-close path above and never adjusted for this second one.

**Refuse to free while a child depends on you.** `mdb_env_close`, `mdb_txn_abort`, and `mdb_cursor_close`
all return nothing:

```c
void mdb_env_close(MDB_env *env);
void mdb_txn_abort(MDB_txn *txn);
void mdb_cursor_close(MDB_cursor *cursor);
```

**Ownership:** none of these take or return a pointer, so nothing here looks like a transfer — the
hazard is *hierarchy*, not a handoff. An `MDB_env*` owns every `MDB_txn*` opened against it, and each of
those owns any `MDB_cursor*` opened against it; calling the first while a transaction from that
environment is still open is undefined behavior, and a transaction must itself have no open cursors
before *it* ends — but that rule lives entirely in LMDB's prose documentation, nowhere in these
signatures. Left unenforced, the failure is a two-step one, which is what makes it easy to miss in
review: the mistake (closing the parent early) and the crash (the next, unrelated call on the child) are
two different call sites, often two different stack traces, sometimes two different threads.

```java
LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
LmdbDbi dbi = txn.openDatabase(Set.of());
txn.get(dbi, "key000042".getBytes(UTF_8));   // fine

env.close();

txn.get(dbi, "key000043".getBytes(UTF_8));   // SIGSEGV — mdb_page_search_root+0x2c
```

rocksdbffm already has a generic answer to "a parent must outlive its children": `NativeObjectWithChildren`
overrides `tryClose` as `final`, walks a `ConcurrentHashMap`-backed set of registered children, closes
each one, then delegates to the subclass's own `tryCloseResource`. A `Snapshot` registers with its `DB`
this way, and cascading is safe there because releasing a RocksDB snapshot is safe to do from whatever
thread happens to be closing the `DB`.

LMDB can't reuse that shape, because the constraint is different: a transaction is confined to the thread
that began it, so forcing one closed from whatever thread is calling `env.close()` trades a predictable
crash for an unpredictable one — corrupting LMDB's reader-table bookkeeping, or racing a call the owning
thread has in flight right now. Cascading is unsafe here, so lmdb-ffm's `LmdbEnv` tracks a count instead
of a set, and refuses to close rather than closing on the child's behalf:

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
        // on; see below for why this throws rather than logs.
        throw new LmdbContractException(/* … */);
    }
    try {
        Bindings.ENV_CLOSE.invokeExact(ptr);
    } finally {
        arena.close();
    }
}
```

`LmdbTxn`'s constructor calls `registerTransaction()`; `commit()`, `abort()`, and the transaction's own
`tryClose()` each call `unregisterTransaction()` — exactly one of the three runs per transaction, so the
count itself never drifts. The read inside `tryClose` is still a plain `get()`-then-branch, though, not
one atomic step: `LmdbTxn.begin()` calls the native `mdb_txn_begin` before its constructor reaches
`registerTransaction()`, so a `beginTxn()` in flight on another thread can fall through that narrow gap —
`tryClose` reads zero, proceeds, and the transaction finishes registering itself against an environment
whose native handle is already gone. The counter narrows "don't call `close()` while another thread might
still be starting a transaction" to a much smaller window; it doesn't turn that discipline into something
enforced. Worth knowing if `beginTxn()` and `close()` can genuinely race in your usage, rather than
trusting the counter as a hard barrier by itself.

**The safe recovery here is refuse, not repair.** An earlier version of this fix force-aborted every
still-open transaction before actually closing the environment, on the theory that a clean abort beats a
leak — and that version was wrong, for the thread-confinement reason above. Once a resource can't be
reached by a thread that's allowed to touch it, *leaving it alone* is the only safe move.

And "leaving it alone" here means permanently, not "until the transaction handling gets fixed and closing
is retried." `NativeObject#close()` already swaps this environment's own pointer field to
`MemorySegment.NULL` *before* calling `tryClose` — that CAS is what makes `close()` idempotent, and it
runs unconditionally, regardless of what `tryClose` goes on to decide. So by the time `tryClose` refuses,
this object already considers itself closed: a second `close()` is a silent no-op, and every other method
now fails fast with `IllegalStateException`. There is no path back to a state where closing succeeds,
even once every dangling transaction has since ended — restoring the pointer so a later `close()` could
retry would mean deciding, from whatever thread happens to make that second call, that it's *now* safe to
free memory some transaction might still hold a zero-copy view into. That is exactly the judgment call
this design refuses to make from an arbitrary thread, so the native `mdb_env` and its upcall-stub arena
are accepted as a permanent leak, not a retryable one, the moment the refusal happens.[^lmdb-env-close]

The same shape exists one ownership level down — a transaction whose cursor is still open when it commits
or aborts — and is still an open issue at the time of writing.[^lmdb-cursor-issue] The fix there is the
version of this pattern with no leak trade-off at all: neutralizing a `LmdbCursor` needs no native call
whatsoever (LMDB already frees the C-level cursor struct as a side effect of ending the transaction), so
it's safe to do from any thread — the transaction just swaps the cursor's pointer to `NULL` directly, the
same swap `NativeObject#close()` already does to itself.

"Destructors must not throw" is otherwise the right default for the shared `close()` above — a caller
unwinding through a `try`-with-resources block should never have a cleanup failure mask the real
exception, or blow up a path that was already failing. The refuse-not-repair fix breaks that default, on
purpose, for exactly one case: if the caller never finds out that `mdb_env_close` was refused, the swallow
has traded a crash for something arguably worse — a `close()` that returns normally while the object it
claims to have closed is still fully alive underneath, silently able to mutate a file the caller now
believes is safely closed. A background log line doesn't fix that either — a caller relying on
try-with-resources, the idiom this whole API is built around, will never see it. The difference that
matters is *why* `tryClose` is throwing: an ordinary native-call failure is routine and should stay
swallowed, but a detected violation of the library's own resource-lifetime contract is a bug in the
*caller's* code that would otherwise vanish without a trace. So `close()` gets one narrow, named
carve-out — not a general "rethrow on failure" flag, and not a checked exception threading a `throws`
clause through every `AutoCloseable#close()` in the codebase:

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

Every existing `tryClose` failure mode — an ordinary native-call error, a double-free attempt, anything
that isn't a detected contract violation — is swallowed exactly as before; there's a regression test
locking that in (`closeSwallowsTryCloseFailures`). Only `LmdbContractException` escapes — unchecked,
since `close()` declares no `throws` for it to widen — and only after whatever recovery was possible
already ran, so the *wrapper's own bookkeeping* is always consistent by the time a caller catches it: the
pointer field is NULL, every method fails fast, nothing is left half-updated. That is narrower than "the
native resource ended up in a good state" — here it explicitly didn't, which is the entire reason to
throw. It's also narrower in a second way worth knowing before leaning on it: inside a `try`-with-resources
block, this exception only reaches a caller directly if the body itself completed normally. If the body
already threw, `LmdbContractException` from `close()` becomes a *suppressed* exception attached to that
original one instead (`Throwable#getSuppressed()`) — not what a bare `catch (LmdbContractException e)`
around the whole block will see fire.[^lmdb-contract-exception]

The general shape: when a `close()`-style method's contract is "never throws," don't relax that contract
wholesale the first time you need to report something loud. Give the one case that genuinely needs to
escape its own type, and let the type itself carry the justification for why "destructors must not throw"
doesn't apply to it — the exception's own javadoc becomes the place that argument lives, not a comment at
each throw site.

### Item 6: Name the ownership shape — unique, parent, or shared {#item-6}

Item 5's `NativeObject` answers "how does one wrapper free its own pointer exactly once." It doesn't
answer what happens when a *second* wrapper depends on that pointer without owning it — and the two
shapes that dependency can take call for opposite fixes.

zstd-ffm's `ZstdCompressContext#refDictionary` attaches a pre-digested dictionary by reference, and its
javadoc already says so:

```java
/// The reference is borrowed: `dict` must stay open for as long as this
/// context uses it. The reference is dropped by a parameter
/// [#reset(ZstdResetDirective)] or by passing `null`.
public ZstdCompressContext refDictionary(ZstdCompressDictionary dict) { /* ... */ }
```

Nothing enforces that sentence. Closing the dictionary out from under a live context is a two-line
reproduction, surfaced while closing pitest-reported mutation gaps in the dictionary tests[^refdictionary-javadoc] —
the round-trip suite passes even with `refDictionary` mutated to a no-op, which is exactly what mutation
testing exists to catch:

```java
ZstdCompressDictionary cdict = new ZstdCompressDictionary(dict);
ZstdCompressContext cctx = new ZstdCompressContext();
cctx.refDictionary(cdict);

cdict.close();          // frees the native cdict — cctx now holds a dangling pointer
cctx.compress(payload); // SIGSEGV, ZSTD_buildSeqStore+0x280
```

**Why FFM's own safety net doesn't catch this:** `Arena`/`MemorySegment` liveness checks protect the
segment passed directly as an argument to *this* downcall. `refDictionary` hands zstd a raw pointer that
gets stashed inside zstd's own `ZSTD_CCtx` struct for later use — once the call returns, Java has no
further say. The next `compress()` just dereferences whatever zstd remembers, freed or not.
Indistinguishable from any other native library's use-after-free, because that's exactly what it is.

**The decision criterion.** Two shapes of "A depends on B's native pointer" show up across these
bindings, and they want opposite remedies:

- **Composition** — the dependent cannot exist without the parent (rocksdbffm's `Snapshot` needs its
  owning `DB`'s pointer just to release itself via `rocksdb_release_snapshot`, Item 5). Correct remedy:
  cascade-close, covered there.
- **Aggregation** — the dependent *borrows* a resource it doesn't own and stays valid for everything else
  even if that one reference breaks (`ZstdCompressContext` borrowing a `ZstdCompressDictionary`).
  Cascading here would be wrong — it kills a context that's still perfectly usable for every purpose
  except that one dictionary. Correct remedy: poison just the reference, not the object.

Same underlying hazard — a native pointer outliving the Java object that owns it — opposite correct fix,
decided entirely by whether it's the dependent's *existence* or one *reference* that's actually invalid.

**Three shapes, one minimal base.** Naming borrowed from C++ smart pointers as a mental-model hook; the
actual class names stay Java-idiomatic.

1. `unique_ptr` — plain `NativeObject` (Item 5). Single exclusive owner, deterministic `close()`, no
   dependents. The default; most wrappers are this and nothing more.
2. `parent_ptr` — `NativeObjectWithChildren` (Item 5). Composition: closing the parent cascades to every
   still-open child first. No canonical name to collide with — Qt's `QObject` parent/child ownership is
   the closest real-world prior art, and neither the C++ STL nor Rust's stdlib has a smart pointer for
   cascading composition; it's not unique ownership and it's not shared ownership either.
3. `shared_ptr` — refcounted. Aggregation, where more than one borrower can outlive any single one of
   them. This is the shape `refDictionary` actually needs and doesn't have yet.

A liveness flag — check "is my dependency still open" right before use, throw if not — sounds like the
obvious fix and isn't one: it only works when the borrower's own thread is the only thing that can
invalidate the reference. `ZstdCompressDictionary` is documented immutable once built and safe to share
across threads, so a *different* thread can call `close()` between the check and the call. That's a
TOCTOU gap, not a fix — it turns a crash into a slightly more readable race, not into no race.

Refcounting removes the window instead of narrowing it: as long as a borrower holds its reference for the
duration of use, the native memory cannot be freed out from under it.

```java
private final AtomicInteger refCount = new AtomicInteger(1);   // the constructor's own reference

void retain() {
    if (refCount.getAndUpdate(c -> c == 0 ? 0 : c + 1) == 0) {
        throw new IllegalStateException("dictionary is closed");
    }
}

void release() {
    if (refCount.decrementAndGet() == 0) {
        freeNative();
    }
}
```

Compare-and-increment, not a blind `incrementAndGet()` — resurrecting a released count back from zero is
the classic refcounting bug. Public `close()` becomes "release the constructor's own reference," not an
unconditional free; `refDictionary` calls `retain()` when it attaches and must reliably `release()` on
replacement, on an explicit clear, on a parameter `reset()` that drops the dictionary, and on the
context's own close.

**Cost worth being honest about:** `close()` stops being deterministic from the caller's point of view —
calling it while a context still holds a reference no longer frees anything, just lowers a count, a real
behavior change from "`close()` frees now," which is otherwise this codebase's convention. And the
bookkeeping doesn't disappear, it moves: every acquisition path has to reliably release or a crash turns
into a leak instead. Strictly safer, but still a discipline-dependent bug class — the same shape as the
child-registration discipline `NativeObjectWithChildren` already leans on in Item 5.

**One base, three opt-in shapes, not one base accreting fields.** The refcount belongs on the class that
needs `shared_ptr` semantics, the children `Set` on the one that needs `parent_ptr` — neither folded into
`NativeObject` itself, which every plain wrapper still extends and shouldn't have to carry weight it
never uses.

This item is a proposal, not yet a merged fix: the reproduction above is what surfaced the gap, the
refcounting sketch is the direction, not landed code. Two documents worth reading alongside it: Maurizio
Cimadamore's own design note on why FFM is lifetime-centric rather than pointer-centric,[^why-lifetimes]
and `Arena`'s javadoc guarantee that access-after-close is always a clean `IllegalStateException`[^arena-javadoc]
— a guarantee that holds only for what the arena itself tracks, and a pointer copied into a C struct by
`refDictionary`, by construction, is not that.

### Item 7: Give every opaque handle its own arena {#item-7}

Between "lives for one call" and "lives forever" sits the common case: a native object expensive to
create, reused across many calls, freed explicitly. `ZSTD_CCtx` is the canonical example; `rocksdb_t`
and `rocksdb_iterator_t` are the same shape. Item 5's `NativeObject` already owns the pointer; what's
new here is a second resource the wrapper owns alongside it — an arena for its own buffers and
stubs — with its own lifetime to manage:[^cctx-close]

```c
ZSTD_CCtx* ZSTD_createCCtx(void);
size_t     ZSTD_freeCCtx(ZSTD_CCtx* cctx);   /* compatible with NULL pointer */
```

**Ownership:** `ZSTD_createCCtx` allocates and returns a pointer C no longer tracks — ownership passes
to the caller immediately. `ZSTD_freeCCtx` is the other half of that contract: call it exactly once,
which is exactly what `NativeObject` (Item 5) guarantees.

```java
public final class ZstdCompressStream extends NativeObject {

    private final Arena arena;
    private final ZstdStreamBuffer in;    // backed by `arena`, holds the source segment + position
    private final ZstdStreamBuffer out;   // backed by `arena`, holds the destination segment + position

    public ZstdCompressStream(ZstdCompressionLevel level) {
        super(createCctx());              // NativeObject now owns the CCtx pointer, freed by tryClose below
        this.arena = Arena.ofConfined();  // this wrapper's own resource, independent of the pointer above
        this.in = new ZstdStreamBuffer(arena);
        this.out = new ZstdStreamBuffer(arena);
    }

    private static MemorySegment createCctx() {
        return NativeCall.createOrThrow("ZSTD_createCCtx", () -> (MemorySegment) Bindings.CREATE_CCTX.invokeExact());
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        try {
            var _ = (long) Bindings.FREE_CCTX.invokeExact(ptr);
        } finally {
            arena.close();
        }
    }
}
```

`super(createCctx())` hands the pointer to `NativeObject`; `arena` is a second, independent resource
this same wrapper owns, backing the `in`/`out` buffers Item 15 covers. `tryClose(MemorySegment)` is the
one hook `NativeObject` calls, per Item 5 — everything inside it, including what order to free things
in, is this class's own business.

That ordering is still worth getting right: **free the native object first, then close the arena.** For
a plain `ZSTD_CCtx` neither resource touches the other, so reversing the two lines above wouldn't
actually crash — but the moment a wrapper's arena backs an upcall stub instead of a plain buffer (Item
7's comparator, next), that stops being true: the native side can still call back into the stub while
its own teardown runs, and closing the arena first pulls the stub out from under it mid-call. Freeing in
this order everywhere costs nothing when the hazard isn't present, and means never having to re-derive
the answer, case by case, for the wrappers where it is.

This is also where you decide the object's thread policy rather than inherit it. `ZSTD_CCtx` isn't
thread-safe; a confined arena turns misuse into a Java-level exception instead of silent corruption —
the safety property and the C contract lining up for free.

Two things worth noting about the shown constructor and `tryClose`, neither of them changing what to do
so much as what to watch for:

- **A leak this constructor doesn't guard against.** If `Arena.ofConfined()` or either
  `new ZstdStreamBuffer(arena)` throws *after* `super(createCctx())` has already run, the object never
  finishes constructing — nothing is ever assigned a reference to it, so nobody can call `close()`, and
  the `ZSTD_CCtx` it already owns leaks. `NativeObject`'s constructor can't protect against this; the
  failure happens later, in the subclass's own constructor. The fix would be a `try`/`catch` around
  everything after `super(...)` that closes `this` before rethrowing — worth adding wherever a
  constructor does real work after taking ownership of a pointer, this one included.
- **The discarded `long` from `FREE_CCTX.invokeExact` is deliberate, not an oversight.** `zstd.h`
  documents every `ZSTD_free*` function as always returning `0` — kept only for signature symmetry with
  the rest of the API, not because failure is possible. `var _ =` says "read, and intentionally ignored,"
  which is a different claim than never having read it at all.

### Item 8: Scope an upcall stub to the life of the object that uses it {#item-8}

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

**Ownership:** RocksDB takes ownership of *calling* `compare`/`name` for the comparator's lifetime, and
calls `destructor(state)` exactly once when it's done. `state` itself is opaque to RocksDB — whatever it
points to is the caller's own memory, so releasing it is the caller's job, done inside that
`destructor` callback.

Each of those function pointers is an upcall stub on the Java side:

```java
MethodHandle target = MethodHandles.lookup()
        .findStatic(MyComparator.class, "compare", COMPARE_TYPE);
MemorySegment stub = linker.upcallStub(target, COMPARE_DESC, arena);
```

The `arena` argument is the whole story — the stub is valid exactly as long as it's open. So the arena
has to be scoped to the *native object's* life, per Item 7, and closed only after RocksDB is done with
the comparator.

Five rules worth bolding in any binding's contributing guide:

- **An exception escaping an upcall does not propagate — it takes down the VM.** Every upcall body is
  a `try`/`catch (Throwable)` converting to a return code or sentinel; to surface the error, stash it
  in a field and rethrow after the downcall returns.
- **Reachability is not enough.** A strong reference to the stub segment doesn't keep it callable — the
  arena being open does. Forgetting this produces a crash far from the cause.
- **Per-instance stubs are cheap to write and expensive in aggregate.** Binding context via
  `MethodHandles.insertArguments` gives one stub per instance, each with its own generated code. Once
  there are many, switch to the C idiom: one stub, plus the `void* state` pointer RocksDB passes back,
  keyed into a registry.
- **A downcall linked `Linker.Option.critical` must never trigger an upcall.** The option skips the
  JVM's thread-state transition on the assumption the native call never re-enters Java. lmdb-ffm breaks
  that assumption: a custom `LmdbComparator`, installed via `mdb_set_compare`, runs as an upcall from
  inside LMDB's own B+tree search — from inside the very `mdb_get`/`mdb_cursor_get` call linked as
  critical. The result isn't a segfault, it's a VM-level guarantee failure, because HotSpot's own
  bookkeeping assumed the thread never left native code:
  ```
  # Internal Error (upcallLinker.cpp:77)
  # guarantee(thread->thread_state() == _thread_in_native) failed:
  #     wrong thread state for upcall
  ```
  The fix links the symbol twice — once critical, once plain — and switches to the plain handle for the
  rest of that environment's life the moment a comparator is installed.[^lmdb-critical-upcall]
- **`critical` has a more mundane sibling hazard, and it's the one more likely to actually bite.** The
  option's whole point is to skip the thread-state transition that marks a thread "in native code" —
  which also means the JVM cannot bring that thread to a safepoint while the critical call is running.
  An upcall re-entry is a VM-guarantee crash, loud and immediate; a critical `mdb_get` that page-faults on
  a memory-mapped database bigger than RAM is quiet and much more likely: the thread blocks in the kernel
  waiting on disk I/O, unable to safepoint, and every other thread waiting on a GC stalls until it
  returns. This is exactly why `critical` looks most tempting on the calls it's least safe for — a hot
  read path into a large mmap'd file is both the biggest performance win and the biggest blocking-I/O
  risk in the same call.

### Item 9: Make adopted native threads die before the VM does {#item-9}

Item 8 keeps the stub alive; this one is about the *thread* that runs it — a lifetime you didn't
create, can't enumerate, and are responsible for anyway.

When native code invokes an upcall from a thread the JVM has never seen — a RocksDB background
compaction thread, say — the JDK quietly attaches it so it can run Java code, and it stays attached.
The detach happens in a thread-local destructor when the native thread eventually dies, which for a
pooled worker means at process exit.

That's fine until something at process exit tries to join it. RocksDB's default `Env` registers a
static destructor that stops its thread pools and `pthread_join`s every background thread. Combine the
two and you get a deadlock that is not a race — it happens every time: `System.exit()` brings the VM to
`VM_Exit` while holding a lock it never releases before calling libc `exit()`; that reaches RocksDB's
static destructor, which tries to `pthread_join` a background thread; that thread, unwinding from having
once run an upcall, tries to detach itself and blocks forever on the very lock the VM thread is still
holding. Neither side ever lets go.[^drain-source]

Thread attachment on first upcall, detached from a thread-local destructor at death, is HotSpot
implementation behavior (`UpcallLinker::on_entry`), observed in a native stack trace, not a documented
contract — neither the `Linker` javadoc nor dev.java's upcall tutorial mentions threads at all; the
javadoc's only documented upcall hazards are an escaping exception and a function-pointer type mismatch.
Build the fix on that basis and it survives a future JDK that changes the behavior — the shutdown hook
becomes a no-op instead of a bug. Assume attachment is guaranteed instead, and the day it isn't, this
section becomes folklore instead of a diagnosis.

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

zstd-ffm never hits this, for the same reason it never needs a deallocator: no callbacks from
library-owned threads, so no zstd thread ever becomes a JVM thread. The hazard is specific to bindings
whose upcalls run on threads the native library created and owns.

### Item 10: Keep the generated raw layer out of the public API {#item-10}

jextract generates a faithful mirror of the header. That mirror isn't an API — it's an intermediate
representation, and shipping it is how `MemorySegment` ends up in application code.

Every binding worth using has two layers:

- **Raw layer**, package-private. One method per C symbol, signature mirrors the header exactly, no
  interpretation, no cleverness. Generated, ideally mechanically.
- **Idiomatic layer**, public. `byte[]`, `Optional`, records, exceptions, `Path`. No `MethodHandle`,
  no restricted methods, no arenas the caller has to know about, and no `MemorySegment` outside the
  tiers that declare one (Item 14).

The generated raw layer is a precondition, not a convenience. Hand-write it and people put "just a
little" interpretation in — a null check here, a length fixup there — and within a year nobody can
tell which layer owns the semantics.

A small example of the boundary doing real work: rocksdbffm takes `java.nio.file.Path` everywhere the
C API takes `const char*` for a filesystem location. `Path` composes with the rest of `java.nio.file`
and makes "absolute or relative?" someone else's already-solved problem.

### Item 11: Give a bitmask its own enum, and fold it back at one boundary {#item-11}

The layering argument in Item 10 is about *where* interpretation lives; this one is about a specific,
recurring translation worth its own treatment — the common case where the C API takes a plain integer
where the natural Java type is a set of named constants. lmdb-ffm's environment flags are OR-able bits
from `lmdb.h`:

```java
public enum LmdbEnvFlag implements LmdbFlag {
    FIXEDMAP(0x01),
    NOSUBDIR(0x4000),
    NOSYNC(0x10000),
    RDONLY(0x20000),
    // ...
    NOMEMINIT(0x1000000);

    private final int bits;

    LmdbEnvFlag(int bits) {
        this.bits = bits;
    }

    @Override
    public int bits() {
        return bits;
    }
}
```

The idiomatic layer takes a `Set<LmdbEnvFlag>` — `EnumSet.of(LmdbEnvFlag.RDONLY, LmdbEnvFlag.NOSUBDIR)`
reads at the call site the way the LMDB manual itself describes the flags, not as a `NOSUBDIR | RDONLY`
expression the caller has to get the precedence of `|` right on. One shared interface folds the set back
into the single native `unsigned int` the raw layer needs, and splits it back apart for calls that report
flags back:

```java
interface LmdbFlag {
    int bits();

    static int toBits(Set<? extends LmdbFlag> flags) {
        int bits = 0;
        for (LmdbFlag flag : flags) {
            bits |= flag.bits();
        }
        return bits;
    }

    static <E extends Enum<E> & LmdbFlag> Set<E> fromBits(int bits, Class<E> type) {
        Set<E> flags = EnumSet.noneOf(type);
        for (E flag : type.getEnumConstants()) {
            if ((bits & flag.bits()) == flag.bits()) {
                flags.add(flag);
            }
        }
        return flags;
    }
}
```

`toBits` and `fromBits` are the only two places in the codebase that know these values are `int`s at all
— `LmdbDbiFlag` and `LmdbWriteFlag` implement the same interface and get both directions for free.
rocksdbffm has the simpler version of the same idea: `CompressionType` maps one-to-one onto
`rocksdb_*_compression`, so there's no bitmask to fold, just `getValue()` going out and `fromValue(int)`
coming back — the same principle, without the OR because the C side never combines these values.

### Item 12: Translate every C error idiom at a single boundary {#item-12}

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

**Ownership:** no pointers change hands at all. `ZSTD_isError`/`ZSTD_getErrorName` classify and name a
plain `size_t` return code; the string `ZSTD_getErrorName` returns is a static literal owned by the
library forever, never freed by either side.

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

### Item 13: Bind NUL-terminated strings by who allocated them, not by their shape {#item-13}

`mdb_strerror` and RocksDB's error message both arrive as a `char*` to a NUL-terminated C string with no
out-param telling Java how long it is — unlike Item 2's `vallen`, there's nothing to `reinterpret`
against. The two bindings look almost identical; the ownership underneath them is opposite:

```c
char* mdb_strerror(int err);
```

**Ownership:** none. For a non-negative `err`, LMDB delegates straight to the platform's `strerror(3)`;
otherwise it returns one of its own error strings. Either way the string is static, owned by the library
or the C runtime for the life of the process, and the caller must never free it.[^lmdb-strerror]

```java
@SuppressWarnings("restricted") // reinterpret needed to read a C string of unknown length
private static String errorMessage(int code) {
    try {
        MemorySegment p = (MemorySegment) Bindings.STRERROR.invokeExact(code);
        return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.US_ASCII);
    } catch (Throwable t) {
        throw rethrow(t);
    }
}
```

`reinterpret(Long.MAX_VALUE)` is doing something narrower than Item 4's three-argument form: no arena,
no deallocator, just enough of a spatial-bounds lie to let `getString` scan forward for the NUL
terminator instead of failing the bounds check on byte zero. `Long.MAX_VALUE` isn't a real length — it's
"trust me, there's a NUL in there somewhere," which is exactly what a bare `const char*` promises and
nothing more. One footgun that comes with delegating to `strerror(3)`: its buffer is exactly as
thread-safe as the platform call itself — which is to say, not. A concurrent `mdb_strerror` on another
thread can overwrite it before the copy above finishes; doing the copy in the same expression that reads
the pointer narrows that window, but doesn't close it.

RocksDB's version of the same call looks almost identical, because the mechanical problem — an
unknown-length, NUL-terminated string — is the same one. What differs is what RocksDB has already told
you by the time you get here: this pointer came out of an `errptr` slot (Item 2), which only ever holds
a message RocksDB `malloc`'d for this one call and handed off to the caller:[^rocksdb-tojavastring]

```java
static String toJavaString(MemorySegment ptr) {
    String s = ptr.reinterpret(Long.MAX_VALUE).getString(0);
    free(ptr);
    return s;
}
```

Same `reinterpret(Long.MAX_VALUE).getString(0)`, one extra line. **The ownership decision was made
before this method runs — at the C API boundary, not in how the string happens to be shaped.**
rocksdbffm even keeps a second version, `toBorrowedJavaString`, identical except for the missing
`free(ptr)`, for RocksDB strings that are *not* `errptr` output — a pointer into an internal
`std::string` that stays alive only as long as its parent object, say. Three near-identical methods, one
line of difference apiece, because "does this string get freed" is a fact about the specific C call that
produced the pointer, not about `char*` as a type.

### Item 14: Expose zero-copy as a declared tier, never as a leak {#item-14}

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

Same reasoning applies to zstd-ffm's zero-copy path and zstd's streaming API, where the natural unit
is a segment pair rather than an array.

### Item 15: Derive struct accessors from the layout, never from offsets {#item-15}

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

**Ownership:** C never allocates either struct — the caller creates both (here, once per stream, in the
wrapper's own arena per Item 7) and passes a pointer in. `ZSTD_compressStream2` only reads `src`/`dst`/
`size` and writes `pos` back in place; there's nothing here for either side to free beyond the arena
itself.

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

One calling-convention detail worth stating explicitly: `POS_HANDLE` isn't invoked as
`POS_HANDLE.get(segment)` — a plain field access still carries a base-offset coordinate from the layout
path, so the call is `POS_HANDLE.get(segment, 0L)`. Leaving off that `0L` is a `WrongMethodTypeException`
at the call site, not a wrong answer, but it's the kind of mismatch people copy from an example that
skips it.

Two things to take from this: the accessors come out of the layout, so *padding* stays the library's
problem, not yours — but that claim stops at the struct's internal shape. `JAVA_LONG` for `size_t` here
is the same guessed width Item 2 flags for `size_t*`; it holds on every FFM target today, but the layout
itself, not just the offsets it produces, is where a real ABI difference would actually live. And the
buffers live for the whole stream, not one call, which puts them squarely in Item 7 — allocated in the
wrapper's arena, closed with it.

lmdb-ffm pushes the same reuse further, and shows where it stops paying off. A pair of `MDB_val`
out-parameter structs, allocated once per transaction and per cursor instead of once per call, is a
~300x win over a fresh `Arena` on every read[^lmdb-arena-bench]. That's safe for every cursor operation whose data field
is purely an *out*-parameter — every operation except `GET_BOTH`/`GET_BOTH_RANGE`, which need the
caller's data as an *in*-parameter to match against. Nothing in the API distinguishes that one
operation from the rest, so it silently reads whatever the previous call left in the reused
slot.[^lmdb-get-both-issue] The same confined arena backing those structs also produces an asymmetry
nobody designed on purpose: a cross-thread `get()` throws `WrongThreadException` before it can reach C,
because reading the output touches the confined arena — but a cross-thread `put()` allocates its own
scratch arena per call and sails straight through to memory corruption instead.[^lmdb-write-thread-issue]
A reused struct's safety only holds for as long as every caller treats it the way the optimization
assumed; check that assumption again each time a new operation is added on top.

### Item 16: Hold downcall handles in `static final` fields {#item-16}

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
downcall stub — where FFM's performance story actually comes from. rocksdbffm's iteration benchmarks put
the FFM `byte[]` path 10–80% ahead of `rocksdbjni` depending on value size; the project's own headline
number for plain point reads is a rounder ~2×[^scale-bench] — a different operation on the same
underlying win, not a contradiction, but worth reconciling if you go looking for one number and find two.
Either way, the reason is structural: no JNI frame setup, no thread-state transition, just a JIT-compiled
stub. A handle read from an instance field or a map gives most of that back.

The same applies to `VarHandle`s derived from layouts, and to `FunctionDescriptor`s if you are
building them at call time (don't). One tension worth naming rather than papering over: Item 1's
`NativeCall.checkReturnValue(() -> ... invokeExact ...)` — the same `Supplier`-wrapped shape recurs at
similar call sites across the raw layer — funnels hundreds of distinct call sites through one shared
helper. A `Supplier.get()` called from that many unrelated lambda shapes is exactly the kind of call site
C2 gives up on inlining, the opposite of the constant-folded `invokeExact` this item is arguing for. In
practice the `invokeExact` itself still gets the direct, monomorphic call this item describes — it's the
lambda wrapping it that's megamorphic, and the two benchmarks above were run through that same wrapper,
so whatever cost it adds is already inside those numbers, not hidden from them. Worth measuring on its
own if you're chasing the last bit of overhead; not worth restructuring around until a profile actually
names it.

zstd's own header makes the complementary point: driven through a foreign function interface, "it's
not rare that performance ends being spent more into the interface, rather than compression itself",
and recommends buffers "as large as practical" to cut round trips.[^zstd-header] FFM makes each
crossing cheaper, not free — batching at the API level is still the lever with the most travel.

### Item 17: Ship one Java artifact and cross-compile the native library with Zig {#item-17}

Half of what makes JNI bindings miserable to maintain isn't the C glue — it's shipping a compiled
shim per platform, built against whatever Java version users still run. Every target you didn't build
for is an `UnsatisfiedLinkError` for somebody, and the matrix only grows.

FFM removes the per-platform *Java* artifact — the glue is ordinary Java, compiled once. What remains
is the C library, and that's where cross-compilation usually turns into a matrix of OS-specific CI
runners, Docker images, and hand-assembled sysroots. With Zig as the compiler, that whole problem
mostly disappears — not a footnote to the FFM story, but a large fraction of the practical benefit,
and one that gets far less attention than the API itself.

`zig cc` is a drop-in replacement for `cc` that bundles clang, its own libc, and the headers for every
target Zig supports — inside the toolchain download itself. `zstd-ffm` skips zstd's build system
entirely: the sources are vendored as plain `.c`, and the script globs and compiles them directly.

```bash
# scripts/build-zstd.sh
export CC="zig cc -target $ZIG_TARGET"
SRCS=$(find "$ZSTD_LIB/common" "$ZSTD_LIB/compress" "$ZSTD_LIB/decompress" \
            "$ZSTD_LIB/dictBuilder" -name '*.c' | sort)
...
zig cc -target "$ZIG_TARGET" $CFLAGS -c "$src" -o "$out"
```

Once cross-compiling is just a `-target` string, adding a platform is a line in a `case` statement, not
a new CI runner. For example: `zstd-ffm` maps six classifiers to six Zig target triples:

```bash
osx-aarch64)     ZIG_TARGET="aarch64-macos"
osx-x86_64)      ZIG_TARGET="x86_64-macos"
linux-x86_64)    ZIG_TARGET="x86_64-linux-gnu"
linux-aarch64)   ZIG_TARGET="aarch64-linux-gnu"
windows-x86_64)  ZIG_TARGET="x86_64-windows-gnu"
windows-aarch64) ZIG_TARGET="aarch64-windows-gnu"
```

That last pair is the one that stood out: a working Windows `.dll`, PE export table and all, produced
from a Linux or macOS runner — no MinGW install, no Wine, no Windows box anywhere in the pipeline.
Neither this project nor its C++ sibling [rocksdbffm](https://github.com/dfa1/rocksdbffm) runs one job
per OS to get there[^ci]; one host builds every target, because nothing about the build depends on
which OS it runs on.

One note: the Linux targets are `-gnu`, not `-musl`. The resulting `.so` still dynamically links glibc
and expects a compatible one on the runtime host — Zig can target `-musl` for a fully static binary.

Two more things worth pinning down for a release pipeline, not just a local build:

- **The `zig cc` version itself.** A toolchain upgrade changing codegen or its bundled libc between one
  release and the next is exactly the kind of drift a build matrix is supposed to prevent — both
  projects pin `mlugg/setup-zig` to a specific commit SHA in CI rather than a floating tag, for the same
  reason a `pom.xml` pins dependency versions instead of ranges.
- **The glibc floor, which the plain `-gnu` target gets right by default rather than by declaration.**
  Zig's `-linux-gnu` targets record versioned `DT_NEEDED` symbols old enough to run on RHEL 8, Ubuntu
  20.04, and Amazon Linux 2 without asking — this project's build script notes the exact symbol versions
  (`GLIBC_2.2.5`/`2.3.2`) that show up in the linked `.so`, verified by inspecting it rather than assumed.
  That's the same guarantee `manylinux` exists to give Python wheels, arrived at from the other
  direction: nobody chose a floor, the toolchain's default happened to be conservative enough, and it was
  checked rather than trusted. Zig can also target a specific minimum explicitly (`x86_64-linux-gnu.2.17`,
  say) if you'd rather assert the floor than rely on where the default lands.

Uber has compiled every line of C/C++ in its Go monorepo with `zig cc`, for both x86_64 and arm64,
since January 2023[^uber]. The experience generalizes past C, too — swap the language and the same
essay gets written again[^justwork].

### Item 18: Native access is a permission your caller grants, not one you can grant yourself {#item-18}

Every restricted call this piece has shown — `SymbolLookup.libraryLookup`, `reinterpret`,
`Linker.upcallStub`, the downcall handle lookup itself — fails at the call site on a current JDK unless
the calling module has been granted native access. That grant can't come from inside the library: it's a
decision made by whoever launches the JVM, not by whoever wrote the code doing the restricted call. All
three projects' READMEs tell users the same thing, because there's no other way to say it:

```
Run with --enable-native-access=ALL-UNNAMED
```

On the unnamed module — the classpath, not the module path — that flag is a blanket grant: every class
loaded from the classpath gets native access, not just this library's. lmdb-ffm ships real named modules
instead of automatic ones specifically so a modularized application isn't forced into that:

```
java --module-path <mods> --enable-native-access=io.github.dfa1.lmdb -m com.example.app/com.example.app.Main
```

Naming the module scopes the grant to exactly the code that needs it — some other dependency three levels
deep doing its own restricted call no longer rides along on a flag that was meant for this one.

There's a second mechanism worth knowing even though none of these three projects can use it: a jar's own
`META-INF/MANIFEST.MF` can carry an `Enable-Native-Access: ALL-UNNAMED` attribute, honored specifically
when that jar is the one launched directly via `java -jar`. It grants native access to that jar's own
unnamed module — the executable application, not whatever it depends on. That is exactly why a *library*
can't use it to spare its users the command-line flag: the attribute belongs to whoever controls the
launched jar, and a dependency sitting on the classpath doesn't get to add a line to its consumer's
manifest. A library's own two levers are documenting the flag for whoever runs the app, and — like
lmdb-ffm — shipping real modules so whoever eventually grants access can grant it narrowly instead of
everywhere.

### Item 19: Load the library once, and know exactly where each symbol comes from {#item-19}

Item 17 gets the shared library built for every target; getting it into the JVM process, and resolving
each symbol from the right place once it's there, are separate, also-unglamorous problems of their own.

**Extraction.** None of these three projects lets a caller point at an arbitrary path — a configurable
`-Dlib.path` would mean loading whatever file a caller (or anyone who can set a system property) names,
which is arbitrary native code execution inside the JVM process. All three extract their own bundled
library from a native-classifier jar on the classpath instead, once, at class-init time:

```java
private static final SymbolLookup LIB = SymbolLookup.libraryLookup(extractBundledLib(), Arena.ofAuto());
```

`Arena.ofAuto()` here is deliberate, not a default reached for out of habit: the library has to outlive
every downcall handle bound against it for the entire life of the process, and there is no natural point
at which "close" would mean anything — so the real choice is between an auto arena nobody ever closes and
a shared arena deliberately never closed either. Where the three diverge is what happens to the
extracted *file*:

- **zstd-ffm and lmdb-ffm** extract into a fresh, owner-only (`rwx------`) temp *directory* on every JVM
  start, `deleteOnExit()`'d. The owner-only permission is the point: on a shared machine, it closes the
  window between extraction and `dlopen` in which another local user could swap the file for their own
  before this process loads it.
- **rocksdbffm** extracts into a content-addressed temp *file* instead — the SHA-256 of the bytes is the
  filename — reused across runs via an atomic move, falling back to whatever's already there if a
  concurrent process's move won the race. The reason is a Windows-specific leak the other two don't yet
  handle: a loaded DLL stays locked for the life of the process there, so `deleteOnExit()` silently fails,
  and a fresh temp-file name every run means every run leaks a copy. Content-addressing means a second run
  of the same version reuses the exact file the first run is still holding open — nothing new to leak —
  while an upgrade gets a genuinely new path instead of colliding with an older process's copy still
  running.

Neither approach is strictly better; they solve different problems — a hostile shared machine versus a
captive one accumulating temp files across restarts — and a project shipping to both would want both.

**Symbol resolution.** All three bind their own downcall handles through the library they just loaded:

```java
LINKER.downcallHandle(LIB.find(name).orElseThrow(...), fd);
```

That's `libraryLookup` — an explicit path, an explicit library — and it isn't the only option. rocksdbffm
reaches for a different one where the C contract actually requires it: RocksDB's merge-operator callback
protocol hands back a buffer that RocksDB itself later releases with a plain `free()`, which means that
buffer has to come from the same allocator RocksDB's `free()` expects — not from wherever `librocksdb`'s
own internal allocator happens to be, and never from JVM-managed memory. rocksdbffm binds libc's `malloc`
directly for exactly this one callback, via `Linker.nativeLinker().defaultLookup()` — the process's own
global symbols, resolved without loading anything:[^rocksdb-malloc]

```java
private static final MethodHandle MH_MALLOC = Linker.nativeLinker().downcallHandle(
        Linker.nativeLinker().defaultLookup().find("malloc")
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: malloc")),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
```

The third option, `SymbolLookup.loaderLookup()` — symbols from whatever some other component already
loaded via `System.load`/`loadLibrary` — doesn't come up in any of the three, because none of them share a
load with anything else. The choice between all three is really one question, asked per symbol: does it
live in the library this binding just loaded, somewhere already resident in the process, or in a library
someone else is responsible for loading? Get that answer right for each symbol, and the rest is
mechanical.

### Item 20: Test what the type system can't check — ownership, concurrency, and the C library's real failure modes {#item-20}

Three hundred native functions and a handful of ownership rules is also three hundred places a test suite
has to prove the rules actually hold, not just that the happy path returns the right bytes. The tests
worth having split cleanly by what they need in order to run at all.

**Concurrency and ownership invariants need no native library.** Item 5's whole argument — that
`close()` releases its native resource exactly once, no matter how many times or from how many threads
it's called — is a claim about `NativeObject`'s own Java code, not about LMDB or RocksDB. lmdb-ffm tests
it that way: a fake `NativeObject` subclass records how many times its `tryClose` ran, raced from 32
threads through a `CountDownLatch` barrier so they all call `close()` at once:[^native-object-test]

```java
@Test
void concurrentCloseReleasesExactlyOnce() throws Exception {
    TestObject sut = new TestObject(POINTER);
    int threads = 32;
    // ... 32 threads wait on a latch, then all call sut.close() at once ...
    assertThat(sut.closeCount.get()).isEqualTo(1);
}
```

No `Arena`, no downcall, no real pointer — `POINTER` is a `MemorySegment` that's never dereferenced, only
compared by identity. That's what makes this a *unit* test of the ownership machinery rather than an
integration test of LMDB: it runs in milliseconds, on every platform, with no native library to link
against, and it would catch a regression in the CAS logic itself long before a flaky native crash ever
would.

**Round-trip correctness needs the real library, and needs to sweep the parameter space rather than
sample it.** zstd-ffm's compression tests are parameterized over every `ZstdCompressionLevel`, not just
the default:

```java
@ParameterizedTest
@MethodSource("io.github.dfa1.zstd.ZstdTestSupport#levels")
void roundTripAtEveryLevel(ZstdCompressionLevel level) {
    byte[] original = "payload-data-".repeat(500).getBytes(StandardCharsets.UTF_8);
    byte[] restored = Zstd.decompress(Zstd.compress(original, level));
    assertThat(restored).as("level %s", level).isEqualTo(original);
}
```

One level passing doesn't say much about the binding; every level failing the same way would point at the
binding rather than the library, and a single level failing alone would point the other way — the
parameterization is what makes the failure diagnosable at all, not just detectable.

**Fault injection has to happen at a boundary the C API actually exposes, not one you invent.** RocksDB's
C API has no callback-based custom `Env`, so there's no extension point to hand it a fake filesystem that
fails on command — mocking one would test the mock, not the binding. rocksdbffm injects a real fault
instead: it revokes write permission on the database directory and lets the next flush hit a genuine
`EACCES` from the OS.[^fault-injection] The binding either turns that into a `RocksDBException` correctly
or it doesn't; there's no simulated layer in between to get subtly wrong.

None of the three projects currently runs its suite against an ASan-instrumented build of the C library,
or fuzzes the binding layer — worth naming as a real gap rather than implying otherwise. An ASan build
would catch exactly the class of error this whole piece is about — a `reinterpret` with the wrong length,
a use-after-free through a dangling zero-copy view — at the moment it happens, in the native stack,
instead of as a JVM crash three call sites later with no memory tool attached to explain it.

[^ci]: Both projects run their native builds under [`mlugg/setup-zig`](https://github.com/mlugg/setup-zig) in GitHub Actions; the classifier matrix is a loop over `-target` strings inside one job, not one job per OS.

[^uber]: Uber Engineering, [*Bootstrapping Uber's Infrastructure on arm64 with Zig*](https://www.uber.com/us/en/blog/bootstrapping-ubers-infrastructure-on-arm64-with-zig/) (2023).

[^justwork]: Loris Cro, [*Zig Makes Go Cross Compilation Just Work*](https://dev.to/kristoff/zig-makes-go-cross-compilation-just-work-29ho) — the same claim, and title, shows up for Rust and other languages once people reach for `zig cc` as their C toolchain.


---

[^zstd-compress]: [`Zstd.compress`](https://github.com/dfa1/zstd-ffm/blob/main/zstd/src/main/java/io/github/dfa1/zstd/Zstd.java), zstd-ffm.

[^pinnable-slice]: [`PinnableSlice.map`](https://github.com/dfa1/rocksdbffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/PinnableSlice.java), rocksdbffm.

[^cctx-close]: [`ZstdCompressStream.tryClose`](https://github.com/dfa1/zstd-ffm/blob/main/zstd/src/main/java/io/github/dfa1/zstd/ZstdCompressStream.java), zstd-ffm.

[^refdictionary-javadoc]: [`ZstdCompressContext#refDictionary`](https://github.com/dfa1/zstd-ffm/blob/main/zstd/src/main/java/io/github/dfa1/zstd/ZstdCompressContext.java), zstd-ffm — the borrowed-reference contract, found unenforced while closing pitest-reported mutation gaps in the dictionary tests.

[^why-lifetimes]: Maurizio Cimadamore, [*Lifetimes in the Foreign Function & Memory API*](https://cr.openjdk.org/~mcimadamore/panama/why_lifetimes.html) — a pre-`Arena` design note, written against the API's earlier `SegmentScope` name, arguing FFM should be lifetime-centric rather than pointer-centric: allocate related resources from one shared scope so they're deallocated atomically together. Worked examples: an array of native C strings sharing its elements' scope; `dlopen`/`dlsym` symbols sharing their library handle's scope — the JDK's own answer to the sibling/parent-owns-children shape (`parent_ptr` above). The many-independent-borrowers shape (`shared_ptr`) isn't part of its argument.

[^arena-javadoc]: [`Arena` javadoc, JDK 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Arena.html) — access after close is always a clean `IllegalStateException`, for a segment the arena itself tracks. A pointer copied into a C struct by `refDictionary` isn't one of those; the arena's own guarantee never reaches it.

[^upcall-tutorial]: dev.java has a short, precise walkthrough of the mechanics: [Calling Java from native code](https://dev.java/learn/ffm/upcall/).

[^drain-source]: Traced through both stacks: `System.exit()` brings HotSpot to a safepoint, sets its global `_vm_exited` flag **while holding `Threads_lock`**, and calls libc `exit()` from the VM thread; `exit()` runs static destructors, reaching RocksDB's `PosixEnv::JoinThreadsOnExit`, which `pthread_join`s the pool. A joined thread that once ran an upcall is an *attached* JVM thread — as it unwinds, its thread-local destructor runs `UpcallContext::~UpcallContext` → `DetachCurrentThread` → `VM_Exit::wait_if_vm_exited`, which blocks forever on the `Threads_lock` the exiting VM thread never releases (deliberately: the process is about to die, so parking the thread is free). The VM thread, meanwhile, is blocked waiting for exactly that thread to finish joining:
    ```
    VM Thread  → VM_Exit::doit() → os::exit() → exit() → __cxa_finalize_ranges
               → PosixEnv::JoinThreadsOnExit::~JoinThreadsOnExit()
               → ThreadPoolImpl::Impl::JoinThreads() → std::thread::join()   [blocked]

    BG thread  → _pthread_exit → _pthread_tsd_cleanup
               → UpcallContext::~UpcallContext() → jni_DetachCurrentThread
               → VM_Exit::wait_if_vm_exited() → Mutex::lock()                [blocked]
    ```
    `jstack`/`jcmd` can't attach to a VM already inside `VM_Exit`, so the only way to see this trace is an OS-level sampler (`sample` on macOS, `gdb -p`/`perf` on Linux) — a hang with no thread dump available is itself a hint you're looking at a VM mid-exit. Full diagnosis and fix in [`BackgroundUpcallThreads`](https://github.com/dfa1/rocksdbffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/BackgroundUpcallThreads.java), rocksdbffm.

[^mapper]: [`Mapper`](https://github.com/dfa1/rocksdbffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/Mapper.java), rocksdbffm.

[^stream-buffer]: [`ZstdStreamBuffer`](https://github.com/dfa1/zstd-ffm/blob/main/zstd/src/main/java/io/github/dfa1/zstd/ZstdStreamBuffer.java), zstd-ffm.

[^scale-bench]: JMH `ScaleBenchmarkRunner`, `iterator.next()` + `value()`, two databases and two value sizes on an Apple M5 MacBook; numbers and caveats in [RocksDB Performance and Zero-Copy](https://dfa1.github.io/articles/rocksdb-performance-and-zero-copy.html). The project's README leads with a rounder "~2×" for plain point `get()` reads — a different JMH benchmark (`docs/benchmarks.md`), not the iteration one cited above.

[^zstd-header]: [`zstd.h`](https://github.com/facebook/zstd/blob/dev/lib/zstd.h), in the comment above `ZSTD_CStreamInSize`.


[^lmdb-env-close]: [`LmdbEnv#tryClose`](https://github.com/dfa1/lmdb-ffm/pull/15), lmdb-ffm PR #15.

[^lmdb-cursor-issue]: [Cursor outlives its transaction](https://github.com/dfa1/lmdb-ffm/issues/4), lmdb-ffm issue #4, open at the time of writing.

[^lmdb-critical-upcall]: [Don't call the critical mdb_get/mdb_cursor_get through a comparator](https://github.com/dfa1/lmdb-ffm/pull/14), lmdb-ffm PR #14, merged.

[^lmdb-get-both-issue]: [GET_BOTH and GET_BOTH_RANGE have no way to pass the data they match on](https://github.com/dfa1/lmdb-ffm/issues/12), lmdb-ffm issue #12, open at the time of writing.

[^lmdb-arena-bench]: `benchmark/ReadBenchmark`'s `readSeq`, a fresh `Arena.ofConfined()` allocating the two 16-byte `MDB_val` structs on every call versus lmdb-ffm's current reused-slot approach, both against the same database lmdbjava's equivalent scan reads — see `CLAUDE.md`'s Code conventions section, lmdb-ffm.

[^lmdb-write-thread-issue]: [Cross-thread put corrupts memory while cross-thread read throws](https://github.com/dfa1/lmdb-ffm/issues/8), lmdb-ffm issue #8, open at the time of writing.

[^lmdb-contract-exception]: [`LmdbContractException`](https://github.com/dfa1/lmdb-ffm/pull/15), lmdb-ffm PR #15.

[^lmdb-strerror]: [`NativeCall.errorMessage`](https://github.com/dfa1/lmdb-ffm/blob/main/lmdb/src/main/java/io/github/dfa1/lmdb/NativeCall.java), lmdb-ffm.

[^rocksdb-tojavastring]: [`RocksDB.toJavaString`/`toBorrowedJavaString`](https://github.com/dfa1/rocksdbffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/RocksDB.java), rocksdbffm.

[^rocksdb-malloc]: [`MergeOperator.MH_MALLOC`](https://github.com/dfa1/rocksdbffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/MergeOperator.java), rocksdbffm.

[^native-object-test]: [`NativeObjectTest.concurrentCloseReleasesExactlyOnce`](https://github.com/dfa1/lmdb-ffm/blob/main/lmdb/src/test/java/io/github/dfa1/lmdb/NativeObjectTest.java), lmdb-ffm.

[^fault-injection]: [`FaultInjectionTest`](https://github.com/dfa1/rocksdbffm/blob/main/core/src/test/java/io/github/dfa1/rocksdbffm/FaultInjectionTest.java), rocksdbffm.
