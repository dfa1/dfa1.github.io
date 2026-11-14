# FFM Design Patterns

*22 August 2026*

Java's Foreign Function & Memory API, one of the outcomes of [Project Panama](https://openjdk.org/projects/panama/),
is well documented as an API. Wrapping a real C library with it is a different problem: ownership, cleanup, and
type safety are left to you. These are the patterns I use to solve it.

The format is borrowed from *Effective Java*: a short title states the pattern, the body explains why
it earns its place, and the caveats after it are where the cost actually lives. Most examples carry
the C prototype they bind, copied from `zstd.h`, `lmdb.h` or RocksDB's `c.h` — half of FFM is reading a header
correctly, and a Java snippet without its prototype hides exactly the half that goes wrong. Code that shows a
mistake is marked `// WRONG:` and followed by the fix, marked `// RIGHT:`.

The material comes from three small projects: [rocksdb-ffm](https://github.com/dfa1/rocksdb-ffm), FFM
bindings for [RocksDB](https://rocksdb.org/), [zstd-ffm](https://github.com/dfa1/zstd-ffm), FFM
bindings for [Zstandard](https://facebook.github.io/zstd/), and [lmdb-ffm](https://github.com/dfa1/lmdb-ffm),
FFM bindings for [LMDB](https://www.lmdb.tech/). They are useful together because their C APIs disagree
about almost everything — error reporting, buffer ownership, statefulness, transactional structure —
and yet the Java-side solutions kept converging on the same handful of rules. That convergence is why
these read as patterns rather than one-off fixes.

Prerequisites: familiarity with `Arena`, `MemorySegment`, `Linker`, `FunctionDescriptor` and
`MethodHandle`. If they are new to you, [dev.java's FFM tutorials](https://dev.java/learn/ffm/) are the right starting point,
[JEP 454](https://openjdk.org/jeps/454) is unusually readable on the design rationale, and Maurizio
Cimadamore's [Project Panama design notes](https://cr.openjdk.org/~mcimadamore/panama/) are where the
API's harder trade-offs — confinement, arenas, memory-segment addressing — were actually argued out.

---

## Contents

**[Ownership and lifetimes](#ownership)**

- [Item 1: Prefer a confined arena per operation](#item-1)
- [Item 2: Size out-params from the platform's own C types](#item-2)
- [Item 3: Give a borrowed native buffer a Java lifetime with `reinterpret`](#item-3)
- [Item 4: Wrap native pointers to provide ownership](#item-4)
- [Item 5: Give a handle its own arena when it owns buffers or stubs](#item-5)
- [Item 6: Scope an upcall stub to the life of the object that uses it](#item-6)

**[API shape](#api-shape)**

- [Item 7: Keep the generated raw layer out of the public API](#item-7)
- [Item 8: Give a bitmask its own enum, and fold it back at one boundary](#item-8)
- [Item 9: Expose zero-copy as a declared tier, never as a leak](#item-9)
- [Item 10: Hold downcall handles in `static final` fields](#item-10)

**[Errors](#errors)**

- [Item 11: Translate every C error idiom at a single boundary](#item-11)
- [Item 12: Never throw from `close()`, except to report a broken contract](#item-12)
- [Item 13: Never let an exception escape an upcall](#item-13)

**[Build, loading, and testing](#build)**

- [Item 14: Native access is a permission your caller grants, not one you can grant yourself](#item-14)
- [Item 15: Load the library once, and know exactly where each symbol comes from](#item-15)
- [Item 16: Test what the type system can't check — ownership, concurrency, and the C library's real failure modes](#item-16)
- [Item 17: Ship one Java artifact and cross-compile the native library with Zig](#item-17)

## Ownership and lifetimes {#ownership}

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
public static byte[] compress(byte[] src, ZstdCompressionLevel level) throws Throwable {
    Objects.requireNonNull(src, "src");
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment in = copyIn(arena, src);
        MemorySegment out = arena.allocate(compressBound(src.length));
        long written = (long) Bindings.COMPRESS.invokeExact(
                out, out.byteSize(), in, (long) src.length, level.value());
        // handle error if written < 0
        return copyOut(out, written);
    }
}
```

`copyIn` and `copyOut` are the only two copies in the path, one per direction:

```java
static MemorySegment copyIn(Arena arena, byte[] src) {
    MemorySegment seg = arena.allocate(Math.max(src.length, 1));
    MemorySegment.copy(src, 0, seg, JAVA_BYTE, 0, src.length);
    return seg;
}

static byte[] copyOut(MemorySegment seg, long len) {
    byte[] out = new byte[Math.toIntExact(len)];
    MemorySegment.copy(seg, JAVA_BYTE, 0, out, 0, out.length);
    return out;
}
```

Everything allocated inside the block dies with it: no lifetime to track, no ownership to document,
no leak to find. Every other pattern here is a deviation that has to justify itself.

Prefer confined over shared: `Arena.ofShared()` buys thread-crossing at the cost of a far more expensive
close, since deallocation has to become globally visible. Reach for it only when a segment genuinely must
outlive one thread.

### Item 2: Size out-params from the platform's own C types {#item-2}

C's answer to multiple return values is pointer arguments; the Java-side answer allocates those slots
in the same confined arena as everything else and never lets them escape it.

```c
char* rocksdb_get(rocksdb_t* db, const rocksdb_readoptions_t* options,
                  const char* key, size_t keylen, size_t* vallen, char** errptr);
```

**Ownership:** `vallen` and `errptr` are caller-allocated scratch, freed with the arena. What RocksDB
writes *through* them — the returned value pointer and any error message — is a different story: both
are C-allocated and handed off to the caller on return. The value is released with `rocksdb_free`; the
error message is Item 11's.

The obvious way to size that scratch is also the wrong one:

```java
// WRONG: JAVA_LONG is a guess for size_t
try (Arena arena = Arena.ofConfined()) {
    MemorySegment vallen = arena.allocate(JAVA_LONG);   // size_t*
    MemorySegment errptr = arena.allocate(ADDRESS);     // char**
    MemorySegment value  = (MemorySegment)
            rocksdb_get.invokeExact(db, readOptions, key, keyLen, vallen, errptr);
    // ...
}
```

`JAVA_LONG` for `size_t*` is a hardcoded assumption: it works because `size_t` is 8 bytes on every mainstream
64-bit platform, but nothing in the code says so. The same assumption about C's `long` breaks on Windows,
where `long` is 32 bits even in a 64-bit process (LLP64, not LP64), so a binding written on Linux fails there
without warning. `Linker` already knows the answer, keyed by the C type's own name instead of
by whatever a Java programmer assumes it maps to:

```java
// RIGHT: ask the Linker for the platform's size_t
MemoryLayout SIZE_T = Linker.nativeLinker().canonicalLayouts().get("size_t");
MemorySegment vallen = arena.allocate(SIZE_T);
```

`canonicalLayouts()` returns the target platform's own C dialect — `"size_t"`, `"long"`, `"wchar_t"`,
and the rest — resolved by the same `Linker` that will make the call, not asserted by whoever wrote the
binding. Do this once per C type in the raw layer, and every out-param sized from it inherits the
correct answer instead of every call site repeating the same guess.

### Item 3: Give a borrowed native buffer a Java lifetime with `reinterpret` {#item-3}

`rocksdb_get` copies the value out. `rocksdb_get_pinned` skips the copy by handing back a pointer into memory
RocksDB keeps owning:

```c
rocksdb_pinnableslice_t* rocksdb_get_pinned(rocksdb_t* db, const rocksdb_readoptions_t* options,
                                            const char* key, size_t keylen, char** errptr);
const char* rocksdb_pinnableslice_value(const rocksdb_pinnableslice_t* t, size_t* vlen);
void rocksdb_pinnableslice_destroy(rocksdb_pinnableslice_t* v);
```

The slice pins the value in the block cache, so `rocksdb_pinnableslice_value` returns a `const char*` that is
valid until `rocksdb_pinnableslice_destroy` and not yours to free. Reading it without a copy takes the
three-argument `reinterpret`:[^pinnable-slice]

```java
// The `null` cleanup is deliberate: this view borrows from the slice, it does
// not own the memory, so closing `arena` must not attempt to free it.
MemorySegment view = data.reinterpret(len, arena, null).asReadOnly();
```

`reinterpret(long, Arena, Consumer<MemorySegment>)` does three separable things:

1. **Restores spatial bounds** — the segment now knows it is `len` bytes long.[^ffm-vs-unsafe]
2. **Grafts on temporal bounds** — the segment dies when `arena` closes; accessing it after that throws
   instead of reading freed memory.
3. **Registers a deallocator** — the consumer runs when the arena closes, for a buffer that really is yours
   to free (`rocksdb_free` on a `rocksdb_get` result, say). Use it only then, and with a short-lived
   arena: it runs on whichever thread closes the arena, and on an arena that lives forever it never runs.

Steps 1 and 2 are what a zero-copy read is built on. Who frees the memory is a different question from how
long Java may look at it, so pinned data takes the first two and a `null` for the third.

Two ways to get it wrong:

- **A wrong length is unrecoverable.** You've overridden the safety property, so
  `IndexOutOfBoundsException` won't save you. Read the length from the out-param (`vlen`), never infer it.
- **Close the arena before destroying the slice.** The view dies with its arena. Destroy the slice first and
  the still-open arena lets Java read freed memory.

### Item 4: Wrap native pointers to provide ownership {#item-4}

Wrapping a native pointer in a Java object raises three questions no C header answers: who frees it, and
how many times; can ownership move to someone else without a free; and can this object's own resource be
released while something downstream still depends on it. All three projects answer the first two with the
same base class, `NativeObject`, holding the pointer in an `AtomicReference<MemorySegment>`.

The answers fall into three ownership shapes:

| Shape | Class | Closing it | C++ analogue |
|---|---|---|---|
| unique | `NativeObject` | frees the pointer exactly once | `std::unique_ptr` |
| parent | `NativeObjectWithChildren` | closes every child first, then frees | none |
| shared | refcounted, proposed at the end of this item | frees when the last owner lets go | `std::shared_ptr` |

Cascading parent-to-child ownership has no C++ analogue, and neither the STL nor Rust's stdlib has one. Qt's
`QObject` parent/child ownership is the closest prior art.

**Free exactly once, from any thread.** `close()` has to run its cleanup exactly once no matter how many
times, or from how many threads, it's called. All three projects independently arrived at the same small class
before any of them named it as a pattern — zstd-ffm's version:

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
  null check duplicated in every subclass.
- **The swallow-or-not decision is made once, not re-litigated per subclass.** rocksdb-ffm's version logs
  before swallowing; zstd-ffm's swallows silently — the LMDB carve-out in Item 12 is a narrow,
  deliberate exception to that default, not a rebuild of it.

`close()` is `final`; only `tryClose(MemorySegment)` is abstract, so a `ZstdCompressStream.tryClose` and
an `LmdbEnv.tryClose` (below) can be entirely different one-off bodies plugged into identical,
already-correct plumbing — the concurrency argument gets made once, here, instead of re-audited in every
subclass that touches a native pointer.

Two smaller choices in that snippet:

- **`MemorySegment.NULL.equals(p)`, not `==`.** `equals` compares by location, so a NULL returned from a
  downcall — a different object at address zero — still matches the sentinel. `==` compares object identity
  and misses it. Use `equals` for every NULL check in the binding, this one included.
- **No `Cleaner` safety net for a caller who forgets `close()`.** A cleaner thread finalizing a leaked
  object would run on precisely the kind of arbitrary thread Item 3 warns a native `free` can be
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
the caller no longer owns. rocksdb-ffm's `NativeObject` — same shape, field named `owningPointer` there —
has a second, package-private way to reach `MemorySegment.NULL` that skips `tryClose` entirely:

```java
void transferOwnership() {
    owningPointer.set(MemorySegment.NULL);
}
```

The hard part isn't the method, it's knowing when to call it — nothing in a C signature says "takes
ownership." The class's own javadoc says so and lists where to find out: read the C source and check
whether the destructor calls `delete` on what you handed it, check the API docs on the rare occasion they
say so explicitly, or copy whatever the official JNI binding already worked out. `close()` called after `transferOwnership()` is a no-op — the pointer is already NULL — which is the
point: the object still gets closed, it just no longer owns anything by then. Reusing the same sentinel
has one flaw: `ptr()`'s exception afterward reads "native object is closed," which is wrong — the object
gave its pointer away, it wasn't closed. The message was written for the swap-close path above and never
adjusted for this second one.

**Refuse to free while a child depends on you.** `mdb_env_close`, `mdb_txn_abort`, and `mdb_cursor_close`
all return nothing:

```c
void mdb_env_close(MDB_env *env);
void mdb_txn_abort(MDB_txn *txn);
void mdb_cursor_close(MDB_cursor *cursor);
```

**Ownership:** none of these returns a value, so nothing here looks like a transfer — the
hazard is *hierarchy*, not a handoff. An `MDB_env*` owns every `MDB_txn*` opened against it, and each of
those owns any `MDB_cursor*` opened against it; calling the first while a transaction from that
environment is still open is undefined behavior. Cursors follow their own rules — one in a read-only transaction must be
closed explicitly, one in a write transaction is closed with it — and all of it lives entirely in LMDB's
prose documentation, nowhere in these signatures. Left unenforced, the failure is a two-step one, which is what makes it easy to miss in
review: the mistake (closing the parent early) and the crash (the next, unrelated call on the child) are
two different call sites, often two different stack traces, sometimes two different threads.

```java
// WRONG: closes the environment while a transaction is still open
LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
LmdbDbi dbi = txn.openDatabase(Set.of());
txn.get(dbi, "key000042".getBytes(UTF_8));   // fine

env.close();

txn.get(dbi, "key000043".getBytes(UTF_8));   // SIGSEGV — mdb_page_search_root+0x2c
```

```java
// RIGHT: children first, parent last
try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
    LmdbDbi dbi = txn.openDatabase(Set.of());
    txn.get(dbi, "key000042".getBytes(UTF_8));
}
env.close();
```

rocksdb-ffm already has a generic answer to "a parent must outlive its children": `NativeObjectWithChildren`
overrides `tryClose` as `final`, walks a `ConcurrentHashMap`-backed set of registered children, releases
each one, then delegates to the subclass's own `tryCloseResource`. A child registers with its parent in its
constructor and unregisters first thing in its own release, so a long-lived parent doesn't accumulate dead
children. Cascading is safe here — unlike with LMDB's transactions, below — because releasing a snapshot, an
iterator or a transaction doesn't need the thread that created it, so it can run on whatever thread happens to be
closing the `DB`.

What hangs off that base is one rule applied repeatedly — *an object that borrows native memory from another
object is that object's child, and children are released before their parent*:

```
Database
├── ColumnFamilyHandle    kept by the database; close() is a no-op (see below)
├── RocksIterator
├── Snapshot
└── Transaction           TransactionDB and OptimisticTransactionDB only
    ├── RocksIterator
    └── Snapshot
```

The two rows under `Transaction` are the instructive ones, because they were missing. A transaction hands out
an iterator and a snapshot, and for a long time neither was registered with anything:

```c
const rocksdb_snapshot_t* rocksdb_transaction_get_snapshot(rocksdb_transaction_t* txn);
```

**Ownership:** `c.cc` implements it as a `malloc`'d wrapper whose `rep` is `txn->rep->GetSnapshot()` — a
snapshot the *transaction* owns, so the wrapper is valid exactly as long as the transaction is.[^rocksdb-txn-snapshot]
The Java `Snapshot` knew only to `rocksdb_free` its wrapper; close the transaction, call
`snapshot.sequenceNumber()`, and it reads freed memory. `Transaction.newIterator` had the same shape: an iterator
created with no owner at all. Like every bug in this item it takes two steps — the early close, then a later call
on the child — so nothing in the method that creates the child looks wrong. The gap surfaced while making
`Transaction` itself a child of its database: the database's close would now destroy transactions, and with them
any iterator or snapshot still alive. The fix is the same registration one level down — the transaction became a
`NativeObjectWithChildren` and its iterator and snapshot became its children. **A borrowed object with no owner is
the bug**, however innocent the method that creates it.

**Handed out, not handed over.** Most children are created by the caller and closed by
the caller — an iterator, a snapshot, a transaction — with the parent's close as the safety net. A rocksdb-ffm
`ColumnFamilyHandle` is the other kind: the database opens every handle itself, and `db.getColumnFamily(name)` hands
the *same* object to every caller. If `close()` really closed it, the idiomatic
`try (var cf = db.getColumnFamily(name))` — which tooling nags you to write, since it returns an `AutoCloseable` —
would destroy the handle for every other user of that database. So `close()` on it is a no-op, and only the owner frees
it, through a separate package-private `release()` that the parent's sweep calls. That is a third state next to "owns
its pointer" and "gave it away": `transferOwnership()` nulls the pointer, `makeImmortal()` keeps a shared default alive
for the whole process, and this child is freed exactly once, by its parent.[^adr-0009]

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
        // on; Item 12 covers why this throws rather than logs.
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
count itself never drifts. The read inside `tryClose` is a plain `get()`-then-branch, not
one atomic step: `LmdbTxn.begin()` calls the native `mdb_txn_begin` before its constructor reaches
`registerTransaction()`, so a `beginTxn()` in flight on another thread can fall through that narrow gap —
`tryClose` reads zero, proceeds, and the transaction finishes registering itself against an environment
whose native handle is already gone. The counter narrows "don't call `close()` while another thread might
still be starting a transaction" to a much smaller window; it doesn't enforce it. Treat it as a mitigation,
not a barrier: if `beginTxn()` and `close()` can race in your application, serialize them yourself.

**The safe recovery here is refuse, not repair.** An earlier version of this fix force-aborted every
still-open transaction before actually closing the environment, on the theory that a clean abort beats a
leak — and that version was wrong, for the thread-confinement reason above. Once a resource can't be
reached by a thread that's allowed to touch it, *leaving it alone* is the only safe move.

And "leaving it alone" here means permanently, not "until the transaction handling gets fixed and closing
is retried." `NativeObject#close()` already swaps this environment's own pointer field to
`MemorySegment.NULL` *before* calling `tryClose` — that atomic swap is what makes `close()` idempotent, and it
runs unconditionally, regardless of what `tryClose` goes on to decide. So by the time `tryClose` refuses,
this object already considers itself closed: a second `close()` is a silent no-op, and every other method
now fails fast with `IllegalStateException`. There is no path back to a state where closing succeeds,
even once every dangling transaction has since ended — restoring the pointer so a later `close()` could
retry would mean deciding, from whatever thread happens to make that second call, that it's *now* safe to
free memory some transaction might still hold a zero-copy view into. That is exactly the judgment call
this design refuses to make from an arbitrary thread, so the native `mdb_env` and its upcall-stub arena
are accepted as a permanent leak, not a retryable one, the moment the refusal happens.[^lmdb-env-close]

The same shape exists one ownership level down — a transaction whose cursor is still open when it commits
or aborts — and is still an open issue at the time of writing.[^lmdb-cursor-issue] For a write transaction the
fix is the version of this pattern with no leak trade-off: LMDB frees the C-level cursor as a side effect of
ending the transaction, so neutralizing a `LmdbCursor` needs no native call and is safe from any thread —
the transaction just swaps the cursor's pointer to `NULL`, the same swap `NativeObject#close()` already does
to itself. A cursor in a read-only transaction must still be closed explicitly, so there the swap would leak it.

**Share ownership when a borrower outlives its owner.** `NativeObject` answers "how does one wrapper free
its own pointer exactly once." It doesn't answer what happens when a *second* wrapper depends on that
pointer without owning it — and the two shapes that dependency can take call for opposite fixes.

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
// WRONG: closes the dictionary while the context still references it
ZstdCompressDictionary cdict = new ZstdCompressDictionary(dict);
ZstdCompressContext cctx = new ZstdCompressContext();
cctx.refDictionary(cdict);

cdict.close();          // frees the native cdict — cctx now holds a dangling pointer
cctx.compress(payload); // SIGSEGV, ZSTD_buildSeqStore+0x280
```

```java
// RIGHT: the dictionary outlives the context that borrows it
try (ZstdCompressDictionary cdict = new ZstdCompressDictionary(dict);
     ZstdCompressContext cctx = new ZstdCompressContext()) {
    cctx.refDictionary(cdict);
    cctx.compress(payload);
}
```

**Why FFM's own safety net doesn't catch this:** `Arena`/`MemorySegment` liveness checks protect the
segment passed directly as an argument to *this* downcall. `refDictionary` hands zstd a raw pointer that
gets stashed inside zstd's own `ZSTD_CCtx` struct for later use — once the call returns, Java has no
further say. The next `compress()` just dereferences whatever zstd remembers, freed or not.
Indistinguishable from any other native library's use-after-free, because that's exactly what it is.

**The decision criterion.** Two shapes of "A depends on B's native pointer" show up across these
bindings, and they want opposite remedies:

- **Composition** — the dependent cannot exist without the parent (rocksdb-ffm's `Snapshot` needs its
  owning `DB`'s pointer just to release itself via `rocksdb_release_snapshot`). Correct remedy:
  cascade-close, covered above.
- **Aggregation** — the dependent *borrows* a resource it doesn't own and stays valid for everything else
  even if that one reference breaks (`ZstdCompressContext` borrowing a `ZstdCompressDictionary`).
  Cascading here would be wrong — it kills a context that's still perfectly usable for every purpose
  except that one dictionary. Correct remedy: poison just the reference, not the object.

Same underlying hazard — a native pointer outliving the Java object that owns it — opposite correct fix,
decided entirely by whether it's the dependent's *existence* or one *reference* that's actually invalid.

The aggregation case is shared ownership: more than one object holds the pointer, and the last to let go
frees it. `refDictionary` needs exactly that and doesn't have it yet.

A liveness flag — check "is my dependency still open" right before use, throw if not — sounds like the
obvious fix and isn't one: it only works when the borrower's own thread is the only thing that can
invalidate the reference. `ZstdCompressDictionary` is documented immutable once built and safe to share
across threads, so a *different* thread can call `close()` between the check and the call. That's a
TOCTOU gap, not a fix — it turns a crash into a slightly more readable race, not into no race.

Shared ownership removes the window instead of narrowing it. Implement it with a refcount: the borrower
stops borrowing and becomes a co-owner, so as long as it holds its reference the native memory cannot be
freed out from under it, and the last release frees it.

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

**The cost:** `close()` stops being deterministic from the caller's point of view —
calling it while a context still holds a reference no longer frees anything, just lowers a count, a real
behavior change from "`close()` frees now," which is otherwise this codebase's convention. And the
bookkeeping doesn't disappear, it moves: every acquisition path has to reliably release or a crash turns
into a leak instead. Safer, but still a discipline-dependent bug class — the same shape as the
child-registration discipline `NativeObjectWithChildren` already leans on above.

**One base, three opt-in shapes, not one base accreting fields.** The refcount belongs on the class that
needs shared ownership, the children `Set` on the one that needs a parent — neither folded into
`NativeObject` itself, which every plain wrapper still extends and shouldn't have to carry weight it
never uses.

Status: the reproduction above is real; the refcounting is a design, not landed code. Two documents frame
it: Maurizio Cimadamore's design note on why FFM is lifetime-centric rather than pointer-centric,[^why-lifetimes]
and `Arena`'s javadoc guarantee that access-after-close is always a clean `IllegalStateException`[^arena-javadoc]
— a guarantee that holds only for what the arena itself tracks, and a pointer copied into a C struct by
`refDictionary`, by construction, is not that.

### Item 5: Give a handle its own arena when it owns buffers or stubs {#item-5}

Between "lives for one call" and "lives forever" sits the common case: a native object expensive to
create, reused across many calls, freed explicitly. `ZSTD_CCtx` is the canonical example; `rocksdb_t`
and `rocksdb_iterator_t` are the same shape. Item 4's `NativeObject` already owns the pointer, and for a
handle that owns nothing else that is enough. When the wrapper also owns Java-side buffers or upcall stubs,
those need a second resource alongside the pointer — an arena — with its own lifetime to manage:[^cctx-close]

```c
ZSTD_CCtx* ZSTD_createCCtx(void);
size_t     ZSTD_freeCCtx(ZSTD_CCtx* cctx);   /* compatible with NULL pointer */
```

**Ownership:** `ZSTD_createCCtx` allocates and returns a pointer C no longer tracks — ownership passes
to the caller immediately. `ZSTD_freeCCtx` is the other half of that contract: call it exactly once,
which is exactly what `NativeObject` (Item 4) guarantees.

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
this same wrapper owns, backing the `in`/`out` stream buffers. `tryClose(MemorySegment)` is the
one hook `NativeObject` calls, per Item 4 — everything inside it, including what order to free things
in, is this class's own business.

Order matters: **free the native object first, then close the arena.** For
a plain `ZSTD_CCtx` neither resource touches the other, so reversing the two lines above wouldn't
actually crash — but the moment a wrapper's arena backs an upcall stub instead of a plain buffer (Item
6's comparator, next), that stops being true: the native side can still call back into the stub while
its own teardown runs, and closing the arena first pulls the stub out from under it mid-call. Freeing in
this order everywhere costs nothing when the hazard isn't present, and means never having to re-derive
the answer, case by case, for the wrappers where it is.

This is also where you decide the object's thread policy rather than inherit it. `ZSTD_CCtx` isn't
thread-safe, and a confined arena enforces that for the buffers it owns: touching them from another thread
throws `WrongThreadException`. It does not guard the `ZSTD_CCtx` pointer itself, and it constrains `close()`:
closing from another thread frees the native object, then fails in `arena.close()`, and
`NativeObject.close()` swallows that exception, leaking the arena. Close from the creating thread.

Two things about the shown constructor and `tryClose`:

- **The constructor leaks on failure.** If `Arena.ofConfined()` or either
  `new ZstdStreamBuffer(arena)` throws *after* `super(createCctx())` has already run, the object never
  finishes constructing — nothing is ever assigned a reference to it, so nobody can call `close()`, and
  the `ZSTD_CCtx` it already owns leaks. `NativeObject`'s constructor can't protect against this; the
  failure happens later, in the subclass's own constructor. The fix is a `try`/`catch` around
  everything after `super(...)` that closes `this` before rethrowing. Add it wherever a constructor does
  real work after taking ownership of a pointer, this one included.
- **The discarded `long` from `FREE_CCTX.invokeExact` is deliberate.** `ZSTD_freeCCtx`
  reports an error only for a static context, which this binding never creates. `var _ =` says "read, and intentionally ignored,"
  which is a different claim than never having read it at all.

### Item 6: Scope an upcall stub to the life of the object that uses it {#item-6}

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
has to be scoped to the *native object's* life, per Item 5, and closed only after RocksDB is done with
the comparator.

Five rules for any binding's contributing guide:

- **An exception escaping an upcall takes down the VM.** Item 13 has the catch-and-stash shape.
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

## API shape {#api-shape}

### Item 7: Keep the generated raw layer out of the public API {#item-7}

jextract generates a faithful mirror of the header. That mirror isn't an API — it's an intermediate
representation, and shipping it is how `MemorySegment` ends up in application code.

Every binding worth using has two layers:

- **Raw layer**, package-private. One method per C symbol, signature mirrors the header exactly, no
  interpretation, no cleverness. Generated by jextract, or listed by hand in one `Bindings` class — either
  way mechanical.
- **Idiomatic layer**, public. `byte[]`, `Optional`, records, exceptions, `Path`. No `MethodHandle`,
  no restricted methods, no arenas the caller has to know about, and no `MemorySegment` outside the
  tiers that declare one (Item 9).

A mechanical raw layer is a precondition, not a convenience. Let interpretation creep in and people put "just a
little" in — a null check here, a length fixup there — and within a year nobody can
tell which layer owns the semantics.

A small example of the boundary doing real work: rocksdb-ffm takes `java.nio.file.Path` everywhere the
C API takes `const char*` for a filesystem location. `Path` composes with the rest of `java.nio.file`
and makes "absolute or relative?" someone else's already-solved problem.

### Item 8: Give a bitmask its own enum, and fold it back at one boundary {#item-8}

The layering argument in Item 7 is about *where* interpretation lives; this one is about a specific,
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
rocksdb-ffm has the simpler version of the same idea: `CompressionType` maps one-to-one onto
`rocksdb_*_compression`, so there's no bitmask to fold, just `getValue()` going out and `fromValue(int)`
coming back — the same principle, without the OR because the C side never combines these values.

### Item 9: Expose zero-copy as a declared tier, never as a leak {#item-9}

"No `MemorySegment` in public signatures" is the right default and the wrong absolute rule. Some
callers *want* the segment — streaming to a socket, feeding another native library — and a `byte[]`
copy is exactly the cost they came to avoid.

The three copying tiers below are `rocksdb_get`; the scoped one is `rocksdb_get_pinned` from Item 3:

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

The workable rule is narrower:

> A `MemorySegment` may appear in a public signature only where the signature itself pins its
> lifetime: a buffer the caller already owns, or an argument to a callback that ends when the call
> does.

Same reasoning applies to zstd-ffm's zero-copy path and zstd's streaming API, where the natural unit
is a segment pair rather than an array.

### Item 10: Hold downcall handles in `static final` fields {#item-10}

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
downcall stub — where FFM's performance story actually comes from. rocksdb-ffm's iteration benchmarks put
the FFM `byte[]` path 10–80% ahead of `rocksdbjni` depending on value size; the project's own headline
number for plain point reads is a rounder ~2×[^scale-bench] — different operations, same underlying win.
The reason is structural: no `JNIEnv` handle juggling, no array pinning, just a JIT-compiled
stub. A handle read from an instance field or a map gives most of that back.

The same applies to `VarHandle`s derived from layouts, and to `FunctionDescriptor`s if you are
building them at call time (don't). One caveat: zstd-ffm wraps its calls in `NativeCall.checkReturnValue(() -> ... invokeExact ...)` (Item 11),
which funnels many call sites through one shared helper. Don't assume C2 inlines through that wrapper. The two
benchmarks above ran through it, so its cost is already inside those numbers. Measure it on its own if you're
chasing the last bit of overhead; restructure only when a profile names it.

zstd's own header makes the complementary point: driven through a foreign function interface, "it's
not rare that performance ends being spent more into the interface, rather than compression itself",
and recommends buffers "as large as practical" to cut round trips.[^zstd-header] FFM makes each
crossing cheaper, not free — batching at the API level is still the lever with the most travel.

## Errors {#errors}

Failures cross the Java/C boundary in three directions: C reports one to Java (Item 11), Java fails while
cleaning up a native object (Item 12), and Java code that C calls fails inside a native frame (Item 13). Each
item has one rule, and each rule decides who gets to see the failure.

### Item 11: Translate every C error idiom at a single boundary {#item-11}

The C idioms for reporting failure could hardly be more different, and the Java answer is identical.

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

**LMDB** returns an `int` from most calls: zero on success, an error code otherwise, with `mdb_strerror`
turning the code into text.

Different mechanics, same shape: **one helper at the boundary converts the C convention into a typed
Java exception, so no caller ever sees the convention.** The idiom is the variable; the boundary is the
constant.

**The message string is a separate decision.** `mdb_strerror` and RocksDB's `errptr` message both arrive as a
`char*` to a NUL-terminated C string with no out-param telling Java how long it is — unlike Item 2's `vallen`,
there's nothing to `reinterpret` against. The two bindings look almost identical; the ownership underneath
them is opposite:

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

`reinterpret(Long.MAX_VALUE)` is doing something narrower than Item 3's three-argument form: no arena,
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
rocksdb-ffm even keeps a second version, `toBorrowedJavaString`, identical except for the missing
`free(ptr)`, for RocksDB strings that are *not* `errptr` output — a pointer into an internal
`std::string` that stays alive only as long as its parent object, say. Three near-identical methods, one
line of difference apiece, because "does this string get freed" is a fact about the specific C call that
produced the pointer, not about `char*` as a type.

Related translations belong in the same place:

- **Sentinel returns.** `-1`/`NULL` become an exception when they mean failure, `Optional.empty()`
  when they mean "legitimately absent" — a per-function judgment call (`rocksdb_get` returning NULL is
  a missing key, not an error), not a mechanical rule.
- **Multiple out-params collapse into one value.** `(value, length, found)` becomes `Optional<byte[]>`.
- **errno** is captured with `Linker.Option.captureCallState("errno")` on the raw side, translated on
  the idiomatic side. Reading it any other way after the downcall is a race — the JVM may have made
  syscalls of its own in between.

### Item 12: Never throw from `close()`, except to report a broken contract {#item-12}

The shared `close()` from Item 4 calls `tryClose` and swallows anything it throws: a failed native free is not
something the caller can act on, and `AutoCloseable`'s javadoc advises marking the object closed before
throwing, which is what the `getAndSet` there does. One failure is the exception to that rule.

Take lmdb-ffm, where `LmdbEnv` counts its open transactions. A caller closes the environment while a
transaction is still open:

```java
LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
env.close();   // the caller's bug: txn is still open
```

Closing the environment unmaps the database file, which would crash that transaction, so `LmdbEnv` refuses to
free it. The question is how `close()` reports the refusal. `tryClose` throws:

```java
protected void tryClose(MemorySegment ptr) throws Throwable {
    if (openTransactions.get() != 0) {
        throw new LmdbContractException(/* … */);
    }
    Bindings.ENV_CLOSE.invokeExact(ptr);   // simplified: Item 4's version also closes the arena
}
```

But the default `close()` swallows that exception like any other, so `env.close()` above returns normally:

```java
// WRONG for LmdbEnv: Item 4's default also swallows the LmdbContractException
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

The caller believes the environment is closed, but it is still alive, and nothing told them otherwise. A log
line doesn't help: the caller's code proceeds as if the environment were closed. Rethrowing every failure is
wrong too, because a failed native free is not something the caller can act on. Only the broken-contract case should escape:

```java
// RIGHT: swallow everything except a broken contract
public final void close() {
    MemorySegment p = ptr.getAndSet(MemorySegment.NULL);
    if (!MemorySegment.NULL.equals(p)) {
        try {
            tryClose(p);
        } catch (LmdbContractException e) {   // the only difference from the version above
            throw e;
        } catch (Throwable _) {
            // destructors must not throw
        }
    }
}
```

`LmdbContractException` is unchecked, so `close()` needs no `throws` clause. It escapes after the wrapper's
own bookkeeping is done: the pointer field is NULL and every method fails fast, even though the native
environment was not freed.

One limit: inside `try`-with-resources, the exception reaches the caller directly only if the body completed
normally. If the body already threw, it becomes a *suppressed* exception on the original, and a bare
`catch (LmdbContractException e)` around the block never sees it.[^lmdb-contract-exception]

### Item 13: Never let an exception escape an upcall {#item-13}

Item 6 keeps an upcall stub alive; this item is about what runs inside it. The example is a custom comparator:
LMDB and RocksDB call back into Java to order keys, in the middle of `mdb_get` or a compaction.

An upcall is Java code called by a native frame, and there is nowhere for an exception to go: it can't unwind
through C frames, so the VM exits instead. `Linker`'s javadoc names it, along with a function-pointer type
mismatch, as the documented upcall hazards.

Without a guard, any exception from the delegate takes down the VM:

```java
// WRONG: an exception from delegate escapes into native frames
int compare(MemorySegment a, MemorySegment b) {
    return delegate.compare(a, b);
}
```

Every upcall body is therefore a `try`/`catch (Throwable)` that converts the failure into something the C side
understands:

```java
// RIGHT: catch everything, stash the failure, return a value the C side tolerates
private final ThreadLocal<Throwable> failure = new ThreadLocal<>();

int compare(MemorySegment a, MemorySegment b) {
    try {
        return delegate.compare(a, b);
    } catch (Throwable t) {
        failure.set(t);
        return 0;
    }
}
```

The stash is per thread: many readers can call `mdb_get` through the same comparator, and a shared field would
let one thread rethrow another's failure. It is read back at the call site, once the downcall that triggered
the upcall has returned:

```java
// after the downcall returns
Throwable t = failure.get();
if (t != null) {
    failure.remove();
    throw rethrow(t);
}
```

Two decisions hide in that catch block:

- **What to return.** The C side keeps running after the upcall, so return something it tolerates. `0` tells
  the B-tree the two keys are equal, which is wrong; that is why the stashed failure is checked as soon as the
  downcall returns, and the downcall's result is discarded.
- **Where the exception goes.** When the upcall runs on the thread that made the downcall, as LMDB's comparator
  does inside `mdb_get`, stash it per thread and rethrow once the downcall returns — Item 11's boundary, in the
  other direction. When it runs on a native-owned thread, as RocksDB's compaction does, no Java caller is waiting;
  the failure has to go to a logger or be recorded for the next call that can report it.

## Build, loading, and testing {#build}

### Item 14: Native access is a permission your caller grants, not one you can grant yourself {#item-14}

`SymbolLookup.libraryLookup`, `reinterpret`, `Linker.upcallStub` and the downcall handle lookup are *restricted*
methods: misused, they can crash the JVM, so the JDK only lets a module call them once it has been granted native
access. Without the grant the JVM prints a warning today, and [JEP 472](https://openjdk.org/jeps/472) states the
intent to turn it into an error.

The grant can't come from the library. It is a launcher decision, made by whoever starts the JVM, so all three
READMEs say the same thing:

```
Run with --enable-native-access=ALL-UNNAMED
```

That flag is a blanket: every class on the classpath gets native access, including dependencies that never asked for
it. lmdb-ffm ships real named modules (`module-info.java`) so a modularized application can grant it narrowly:

```
java --module-path <mods> --enable-native-access=io.github.dfa1.lmdb -m com.example.app/com.example.app.Main
```

A jar's manifest can carry `Enable-Native-Access: ALL-UNNAMED`, but the JDK honors it only for the jar launched with
`java -jar`, and a library on someone else's classpath can't edit that manifest. So a library has two levers:
document the flag, and ship named modules so the grant can be narrow.

### Item 15: Load the library once, and know exactly where each symbol comes from {#item-15}

Item 17 gets the shared library built for every target; getting it into the JVM process, and resolving
each symbol from the right place once it's there, are two separate problems.

**Extraction.** The dynamic loader opens files by path, and an entry inside a jar isn't one, so a library
bundled in a jar has to be copied to disk before `SymbolLookup.libraryLookup` can load it. None of these
three projects lets a caller point at an arbitrary path — a configurable
`-Dlib.path` would mean loading whatever file a caller (or anyone who can set a system property) names,
which is native code execution inside the JVM process for whoever controls that property. All three extract their own bundled
library from a native-classifier jar on the classpath instead, once, at class-init time:

```java
private static final SymbolLookup LIB = SymbolLookup.libraryLookup(extractBundledLib(), Arena.ofAuto());
```

Use `Arena.ofAuto()` here: the library has to outlive
every downcall handle bound against it for the entire life of the process, and there is no natural point
at which "close" would mean anything — so the real choice is between an auto arena nobody closes and the
global arena, which is never closed by design; the auto arena also lets the library unload with its class loader. What happens to the extracted *file* depends on the OS:

- **Linux and macOS: a fresh owner-only temp directory per run.** Extract into an `rwx------` directory and
  `deleteOnExit()` it. The owner-only permission is the point: on a shared machine, it closes the window
  between extraction and `dlopen` in which another local user could swap the file for their own before this
  process loads it. POSIX lets a process delete a library it has loaded, so nothing lingers.
- **Windows: a content-addressed file.** A loaded DLL stays locked for the life of the process, so
  `deleteOnExit()` silently fails, and a fresh temp-file name every run leaks a copy per run. Name the file
  after the SHA-256 of its bytes instead and move it into place atomically. A second run of the same version
  reuses the file the first run is still holding open, so nothing new leaks; an upgrade gets a new hash, so
  it can't collide with an older process still running; and if a concurrent process's move wins the race,
  use whatever is already there.

**What protects the file.** Between extraction and `dlopen`, whoever can write to that file runs code inside
your JVM, so the defense is controlling who can write there. In order of strength:

1. **A private directory.** Create it yourself, owner-only (`rwx------`), per user: other users can't create,
   replace, or rename anything inside it. With a private directory the file's name hardly matters. On Windows
   use a per-user location, such as the user's local app-data directory, not the shared temp directory.
2. **Exclusive creation.** Create the file with `CREATE_NEW` and owner-only permissions, so nobody can
   pre-plant a file at that path.
3. **An unpredictable name.** A bonus, not a defense: a random name in a directory others can write to
   protects nothing without the two steps above.

The content-addressed file can't have a random name, since a stable name is the point. It relies on step 1,
and it re-hashes an existing file before loading it. The hash check alone is not enough: in a writable
directory the file can be swapped between the check and `dlopen`. Treat the hash as a second layer on top of the private
directory, never a replacement for it.

Linux and macOS: a private directory, exclusive creation, nothing left behind:

```java
static Path extractPosix(byte[] lib, String name) throws IOException {
    FileAttribute<?> ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    Path dir = Files.createTempDirectory("native-", ownerOnly);
    dir.toFile().deleteOnExit();                    // registered first, so it is deleted last
    Path file = Files.createFile(dir.resolve(name), ownerOnly);   // fails if the path already exists
    file.toFile().deleteOnExit();
    return Files.write(file, lib);
}
```

Windows: a content-addressed file in a per-user directory, re-hashed before reuse:

```java
static Path extractWindows(byte[] lib, Path userDir) throws IOException {   // e.g. %LOCALAPPDATA%\mylib
    String sha = sha256(lib);
    Path target = userDir.resolve(sha + ".dll");
    if (Files.exists(target) && sha.equals(sha256(Files.readAllBytes(target)))) {
        return target;                              // an existing file is reused only if it verifies
    }
    Path tmp = Files.createTempFile(userDir, "native-", ".tmp");
    Files.write(tmp, lib);
    try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException raced) {                   // another process's move won, or the file is locked
        Files.deleteIfExists(tmp);
        if (!sha.equals(sha256(Files.readAllBytes(target)))) {
            throw raced;
        }
    }
    return target;
}
```

`sha256` is `HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))`. The two snippets
are a sketch of the approach, not code copied from the projects.


**Symbol resolution.** All three bind their own downcall handles through the library they just loaded:

```java
LINKER.downcallHandle(LIB.find(name).orElseThrow(...), fd);
```

That's `libraryLookup` — an explicit path, an explicit library — and it isn't the only option. rocksdb-ffm
reaches for a different one where the C contract actually requires it: RocksDB's merge-operator callback
protocol hands back a buffer that RocksDB itself later releases with a plain `free()`, which means that
buffer has to come from the same allocator RocksDB's `free()` expects — not from wherever `librocksdb`'s
own internal allocator happens to be, and never from JVM-managed memory. rocksdb-ffm binds libc's `malloc`
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

### Item 16: Test what the type system can't check — ownership, concurrency, and the C library's real failure modes {#item-16}

Three hundred native functions and a handful of ownership rules is also three hundred places a test suite
has to prove the rules actually hold, not just that the happy path returns the right bytes. The tests
split cleanly by what they need in order to run at all.

**Concurrency and ownership invariants need no native library.** Item 4's whole argument — that
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

No `Arena`, no downcall, no real pointer — `POINTER` is a `MemorySegment` that's never dereferenced. That's what makes this a *unit* test of the ownership machinery rather than an
integration test of LMDB: it runs in milliseconds, on every platform, with no native library to link
against, and it would catch a regression in the atomic-swap logic itself long before a flaky native crash ever
would.

**An ownership tree needs one test per edge, against the real library.** The registration discipline from
Item 4 lives at every call site that creates a child, not in `NativeObjectWithChildren`, so a unit test of
the base class can't see a child that forgot to register. rocksdb-ffm's `TransactionIsChildOfDbTest` runs the same
scenarios over both transaction types — parent closed first, child closed first, parent closed twice, fifty children
coming and going, a grandchild (an iterator or a snapshot) closed with its transaction, and the parent closed with the
whole chain still open — and asserts `IllegalStateException` on the child afterward, not merely "didn't crash". That
assertion is what makes the test writable at all: a use-after-free is a nondeterministic crash, but a registered child
turns it into a deterministic exception you can put an `assertThat` on.[^txn-child-test]

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
fails on command — mocking one would test the mock, not the binding. rocksdb-ffm injects a real fault
instead: it revokes write permission on the database directory and lets the next flush hit a genuine
`EACCES` from the OS.[^fault-injection] The binding either turns that into a `RocksDBException` correctly
or it doesn't; there's no simulated layer in between to get subtly wrong.

### Item 17: Ship one Java artifact and cross-compile the native library with Zig {#item-17}

Half of what makes JNI bindings hard to maintain isn't the C glue — it's shipping a compiled
shim per platform. Every target you didn't build
for is an `UnsatisfiedLinkError` for somebody, and the matrix only grows.

FFM removes the per-platform *Java* artifact — the glue is ordinary Java, compiled once. What remains
is the C library, and that's where cross-compilation usually turns into a matrix of OS-specific CI
runners, Docker images, and hand-assembled sysroots. With Zig as the compiler, that problem
shrinks to a `-target` flag, and that is a large part of the practical benefit of an FFM binding.

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
Neither this project nor its C++ sibling [rocksdb-ffm](https://github.com/dfa1/rocksdb-ffm) runs one job
per OS to get there[^ci]; one host builds every target, because nothing about the build depends on
which OS it runs on.

One note: the Linux targets are `-gnu`, not `-musl`: the `.so` is loaded into a glibc-based JVM, so it
dynamically links glibc and expects a compatible one on the runtime host.

Three more things to pin down for a release pipeline, not just a local build:

- **The `zig cc` version itself.** A toolchain upgrade changing codegen or its bundled libc between one
  release and the next is exactly the kind of drift a build matrix is supposed to prevent — pin the Zig
  version through the `version` input of `mlugg/setup-zig`, and pin the action itself to a commit SHA rather
  than a floating tag (both projects do the latter), the same reason a `pom.xml` pins dependency versions
  instead of ranges.
- **Hermetic inputs.** Compiler, linker, and libc headers all come out of the one Zig download, so the build
  never reads the host's `/usr/include`, system `gcc`, or sysroot — the inputs are the vendored sources plus a
  pinned Zig version, on a laptop and on CI alike. That is what lets one job build all six targets, and it is
  also why the glibc floor below belongs to the toolchain rather than to whichever runner image happens to be
  current. Bit-for-bit identical output is a separate property from hermetic inputs, and not one this item
  claims.

Uber has compiled every line of C/C++ in its Go monorepo with `zig cc`, for both x86_64 and arm64,
since January 2023[^uber]. The same case gets made for other languages too[^justwork].

---

[^ci]: Both projects run their native builds under [`mlugg/setup-zig`](https://github.com/mlugg/setup-zig) in GitHub Actions; the classifier matrix is a loop over `-target` strings inside one job, not one job per OS.

[^uber]: Uber Engineering, [*Bootstrapping Uber's Infrastructure on arm64 with Zig*](https://www.uber.com/us/en/blog/bootstrapping-ubers-infrastructure-on-arm64-with-zig/) (2023).

[^justwork]: Loris Cro, [*Zig Makes Go Cross Compilation Just Work*](https://dev.to/kristoff/zig-makes-go-cross-compilation-just-work-29ho) — the same claim, and title, shows up for Rust and other languages once people reach for `zig cc` as their C toolchain.

[^zstd-compress]: [`Zstd.compress`](https://github.com/dfa1/zstd-ffm/blob/main/zstd/src/main/java/io/github/dfa1/zstd/Zstd.java), zstd-ffm, simplified: the real method wraps the call in `NativeCall.checkReturnValue` (Item 11).

[^pinnable-slice]: [`PinnableSlice.map`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/PinnableSlice.java), rocksdb-ffm.

[^cctx-close]: [`ZstdCompressStream.tryClose`](https://github.com/dfa1/zstd-ffm/blob/main/zstd/src/main/java/io/github/dfa1/zstd/ZstdCompressStream.java), zstd-ffm.

[^refdictionary-javadoc]: [`ZstdCompressContext#refDictionary`](https://github.com/dfa1/zstd-ffm/blob/main/zstd/src/main/java/io/github/dfa1/zstd/ZstdCompressContext.java), zstd-ffm — the borrowed-reference contract, found unenforced while closing pitest-reported mutation gaps in the dictionary tests.

[^why-lifetimes]: Maurizio Cimadamore, [*Lifetimes in the Foreign Function & Memory API*](https://cr.openjdk.org/~mcimadamore/panama/why_lifetimes.html) — a pre-`Arena` design note, written against the API's earlier `SegmentScope` name, arguing FFM should be lifetime-centric rather than pointer-centric: allocate related resources from one shared scope so they're deallocated atomically together. Worked examples: an array of native C strings sharing its elements' scope; `dlopen`/`dlsym` symbols sharing their library handle's scope — the JDK's own answer to the sibling/parent-owns-children shape (the parent shape above). The many-independent-borrowers shape (shared ownership) isn't part of its argument.

[^arena-javadoc]: [`Arena` javadoc, JDK 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Arena.html) — access after close is always a clean `IllegalStateException`, for a segment the arena itself tracks. A pointer copied into a C struct by `refDictionary` isn't one of those; the arena's own guarantee never reaches it.

[^upcall-tutorial]: dev.java has a short, precise walkthrough of the mechanics: [Calling Java from native code](https://dev.java/learn/ffm/upcall/).

[^mapper]: [`Mapper`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/Mapper.java), rocksdb-ffm.

[^scale-bench]: JMH `ScaleBenchmarkRunner`, `iterator.next()` + `value()`, two databases and two value sizes on an Apple M5 MacBook; numbers and caveats in [RocksDB Performance and Zero-Copy](https://dfa1.github.io/articles/rocksdb-performance-and-zero-copy.html). The project's README leads with a rounder "~2×" for plain point `get()` reads — a different JMH benchmark (`docs/benchmarks.md`), not the iteration one cited above.

[^zstd-header]: [`zstd.h`](https://github.com/facebook/zstd/blob/dev/lib/zstd.h), in the comment above `ZSTD_CStreamInSize`.

[^lmdb-env-close]: [`LmdbEnv#tryClose`](https://github.com/dfa1/lmdb-ffm/pull/15), lmdb-ffm PR #15.

[^lmdb-cursor-issue]: [Cursor outlives its transaction](https://github.com/dfa1/lmdb-ffm/issues/4), lmdb-ffm issue #4, open at the time of writing.

[^lmdb-critical-upcall]: [Don't call the critical mdb_get/mdb_cursor_get through a comparator](https://github.com/dfa1/lmdb-ffm/pull/14), lmdb-ffm PR #14, merged.

[^lmdb-contract-exception]: [`LmdbContractException`](https://github.com/dfa1/lmdb-ffm/pull/15), lmdb-ffm PR #15.

[^lmdb-strerror]: [`NativeCall.errorMessage`](https://github.com/dfa1/lmdb-ffm/blob/main/lmdb/src/main/java/io/github/dfa1/lmdb/NativeCall.java), lmdb-ffm.

[^rocksdb-tojavastring]: [`NativeCalls.toJavaString`/`toBorrowedJavaString`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/NativeCalls.java), rocksdb-ffm.

[^rocksdb-malloc]: [`MergeOperator.MH_MALLOC`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/MergeOperator.java), rocksdb-ffm.

[^native-object-test]: [`NativeObjectTest.concurrentCloseReleasesExactlyOnce`](https://github.com/dfa1/lmdb-ffm/blob/main/lmdb/src/test/java/io/github/dfa1/lmdb/NativeObjectTest.java), lmdb-ffm.

[^fault-injection]: [`FaultInjectionTest`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/test/java/io/github/dfa1/rocksdbffm/FaultInjectionTest.java), rocksdb-ffm.

[^rocksdb-txn-snapshot]: [`rocksdb_transaction_get_snapshot`](https://github.com/facebook/rocksdb/blob/main/db/c.cc), RocksDB `db/c.cc` — the wrapper is `malloc`'d, but the `rep` it points at belongs to the transaction.

[^adr-0009]: [ADR 0009: the database owns its column family handles](https://github.com/dfa1/rocksdb-ffm/blob/main/docs/adr/0009-db-owned-column-family-handles.md), rocksdb-ffm.

[^txn-child-test]: [`TransactionIsChildOfDbTest`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/test/java/io/github/dfa1/rocksdbffm/TransactionIsChildOfDbTest.java), rocksdb-ffm.

[^ffm-vs-unsafe]: Inside.java, [*FFM vs. Unsafe. Safety (Sometimes) Has a Cost*](https://inside.java/2025/06/12/ffm-vs-unsafe/) — builds a segment from a raw address with `MemorySegment.ofAddress(...).reinterpret(size)` and compares the cost of FFM's bounds checks with `Unsafe`.
