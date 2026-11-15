# FFM Design Patterns

{% comment %}
Editorial notes, stripped by Jekyll at build time and never shipped.
- Versioning: edit in place, one URL. Minor bump for new or split items, patch for wording, major only if
  item numbers or advice change in a way that invalidates earlier citations. Keep item numbers stable.
- Never reuse or renumber an item; the `{#item-N}` anchor is a permanent ID. Remove an item by tombstone: keep the
  heading and anchor, add one line saying why and linking the replacement. On a merge or split, the original
  anchor stays on the part that keeps the advice, and the other part links to its new number.
- On a minor or major bump: update the version line below, add a changelog line, bump <updated> in feed.xml.
- Open design notes: Item 8 refcount is a design, not code; Item 7 cursor issue is lmdb-ffm #4.
{% endcomment %}

*15 November 2026, version 0.9 (draft, living document)*

*Java's Foreign Function & Memory API, one of the outcomes of [Project Panama](https://openjdk.org/projects/panama/),
is well documented as an API. Wrapping a real C library with it is a different problem: who owns each pointer, who
frees it, and when, is left to you. These are the patterns I use.*

The format is borrowed from *Effective Java*: each item has a short title that states the rule, an opening that
says why, an example from real code, and the caveats where the cost lives. Most examples come with the C prototype
they bind, copied from `zstd.h`, `lmdb.h` or RocksDB's `c.h`. Half of FFM is reading a header correctly, and a Java
snippet without its prototype hides the half that goes wrong. Code that shows a mistake is marked `// WRONG:` and
followed by the fix, marked `// RIGHT:`.

The material comes from three projects: [rocksdb-ffm](https://github.com/dfa1/rocksdb-ffm), FFM
bindings for [RocksDB](https://rocksdb.org/), [zstd-ffm](https://github.com/dfa1/zstd-ffm), FFM
bindings for [Zstandard](https://facebook.github.io/zstd/), and [lmdb-ffm](https://github.com/dfa1/lmdb-ffm),
FFM bindings for [LMDB](https://www.lmdb.tech/). They are useful together because their C APIs disagree
about almost everything: how errors are reported, who owns a buffer, whether calls carry state, how transactions
work. Yet the Java side kept landing on the same handful of rules. That is why these read as patterns rather than
one-off fixes.

Prerequisites: familiarity with `Arena`, `MemorySegment`, `Linker`, `FunctionDescriptor` and
`MethodHandle`. If they are new to you, [dev.java's FFM tutorials](https://dev.java/learn/ffm/) are the right starting point,
[JEP 454](https://openjdk.org/jeps/454) is unusually readable on the design rationale, and Maurizio
Cimadamore's [Project Panama design notes](https://cr.openjdk.org/~mcimadamore/panama/) are where the
API's harder trade-offs (confinement, arenas, how segments address memory) were actually argued out.

Snippets assume this plumbing, the same in all three projects, and a static import of `ValueLayout.*`:

```java
public class NativeLibrary {

    static final MemoryLayout SIZE_T = Linker.nativeLinker().canonicalLayouts().get("size_t");

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = SymbolLookup.libraryLookup(resolveLibPath(), Arena.ofAuto());

    static MethodHandle lookup(String name, FunctionDescriptor fd, Linker.Option... options) {
        return LINKER.downcallHandle(
                LIB.find(name).orElseThrow(() ->
                        new UnsatisfiedLinkError("Symbol not found: " + name)),
                fd, options);
    }
}
```

`resolveLibPath()` is [Item 20](#item-20), the symbol lookup is [Item 21](#item-21), and `SIZE_T` is
[Item 2](#item-2).

---

## Contents

**[Memory and arenas](#memory)**

- [Item 1: Prefer a confined arena per operation](#item-1)
- [Item 2: Size out-params from the platform's own C types](#item-2)
- [Item 3: Give a borrowed native buffer a Java lifetime with `reinterpret`](#item-3)

**[Ownership](#ownership)**

- [Item 4: Free a native pointer exactly once, from any thread](#item-4)
- [Item 5: Drop the pointer when C takes ownership of it](#item-5)
- [Item 6: Close children before their parent](#item-6)
- [Item 7: Refuse to close what is still in use](#item-7)
- [Item 8: Share ownership when a borrower can outlive its owner](#item-8)
- [Item 9: Give a handle its own arena when it owns buffers or stubs](#item-9)
- [Item 10: Scope an upcall stub to the life of the object that uses it](#item-10)

**[API shape](#api-shape)**

- [Item 11: Keep the generated raw layer out of the public API](#item-11)
- [Item 12: Give a bitmask its own enum, and fold it back at one boundary](#item-12)
- [Item 13: Expose zero-copy as a declared tier, never as a leak](#item-13)
- [Item 14: Hold downcall handles in `static final` fields](#item-14)
- [Item 15: Link a downcall as `critical` only if it never blocks and never calls back](#item-15)

**[Errors](#errors)**

- [Item 16: Translate every C error convention at a single boundary](#item-16)
- [Item 17: Never throw from `close()`, except to report a broken contract](#item-17)
- [Item 18: Never let an exception escape an upcall](#item-18)

**[Build, loading, and testing](#build)**

- [Item 19: Let the caller grant native access](#item-19)
- [Item 20: Extract the bundled library once, into a directory only you can write](#item-20)
- [Item 21: Pick the symbol lookup per symbol](#item-21)
- [Item 22: Test the ownership rules, not just the happy path](#item-22)
- [Item 23: Ship one Java artifact and cross-compile the native library with Zig](#item-23)

## Memory and arenas {#memory}

### Item 1: Prefer a confined arena per operation {#item-1}

Most calls need a few bytes of native memory for the length of one call and nothing after. The default, and
usually the right answer: one arena, one thread, try-with-resources, memory freed at the closing brace. The
smallest example is reading a zstd frame header: one input buffer in, one number out.

```c
unsigned long long ZSTD_getFrameContentSize(const void* src, size_t srcSize);
```

```java
static long frameContentSize(byte[] compressed) throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment in = arena.allocate(compressed.length);
        MemorySegment.copy(compressed, 0, in, JAVA_BYTE, 0, compressed.length);
        return (long) Bindings.GET_FRAME_CONTENT_SIZE.invokeExact(in, (long) compressed.length);
    }
}
```

The copy of `compressed` lives exactly as long as the call. Nothing comes back but a `long`, so there is
nothing left to own when the block ends. Most of a binding is this simple or simpler. In rocksdb-ffm, roughly
455 of the 835 bound symbols are plain scalar getters and setters, mostly on the `*Options` classes, that take
or return an `int`, a `long` or a `boolean` and need no arena at all; the ones that do take a buffer follow the
shape above. That is a grep-based count, so treat it as approximate, but the proportion is the point: the bulk
of the mapping work is mechanical, and the rest of this article is about the other half.

The same shape with buffers in both directions is zstd-ffm's one-shot compression:

```c
size_t ZSTD_compressBound(size_t srcSize);
size_t ZSTD_compress(void* dst, size_t dstCapacity,
                     const void* src, size_t srcSize, int compressionLevel);
```

In C, the caller allocates the output buffer. `ZSTD_compressBound` returns the worst-case compressed size for
an input of that length, so one allocation is always enough:

```c
size_t bound = ZSTD_compressBound(srcSize);
void* dst = malloc(bound);
size_t written = ZSTD_compress(dst, bound, src, srcSize, level);
/* ... use the first `written` bytes of dst ... */
free(dst);
```

**Ownership:** entirely on the caller's side. Java allocates both `src` and `dst`; `ZSTD_compress` only reads
one and writes the other. Nothing here is allocated by C, so there is nothing to free. The arena takes the place
of the `malloc`/`free` pair:

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

`copyIn` and `copyOut` are plain `MemorySegment.copy` calls, one per direction, and the only two copies in
the path.

Everything allocated inside the block dies with it: no lifetime to track, no ownership to document,
no leak to find. Every other item in this part is a step away from this default, and each has to earn it.

Prefer confined over shared. `Arena.ofShared()` lets segments cross threads, but closing it is much more
expensive, because the deallocation has to become visible to every thread. Reach for it only when a segment
really must outlive one thread.

**Sources and examples**

- [`Zstd.frameContentSize`](https://github.com/dfa1/zstd-ffm/blob/main/zstd/src/main/java/io/github/dfa1/zstd/Zstd.java), zstd-ffm. Simplified above; the real method also checks the returned value for zstd's "unknown size" and error sentinels.
- [`Zstd.compress`](https://github.com/dfa1/zstd-ffm/blob/main/zstd/src/main/java/io/github/dfa1/zstd/Zstd.java), zstd-ffm. Simplified above; the real method wraps the call in `NativeCall.checkReturnValue` ([Item 16](#item-16)).

### Item 2: Size out-params from the platform's own C types {#item-2}

C returns multiple values through pointer arguments. On the Java side, allocate those slots in the same confined
arena as everything else and never let them escape it.

```c
char* rocksdb_get(rocksdb_t* db, const rocksdb_readoptions_t* options,
                  const char* key, size_t keylen, size_t* vallen, char** errptr);
```

**Ownership:** `vallen` and `errptr` are scratch allocated by the caller and freed with the arena. What RocksDB
writes *through* them, the value pointer and any error message, is a different story: both are allocated by C
and handed to the caller on return. The value is released with `rocksdb_free`; the error message is [Item 16](#item-16)'s.

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

`JAVA_LONG` for `size_t*` works because `size_t` is 8 bytes on every mainstream 64-bit platform, but nothing in
the code says so. The same guess for C's `long` breaks on Windows, where `long` is 32 bits even in a 64-bit
process, so a binding written on Linux fails there without warning. `Linker` already knows the answer, keyed by
the C type's own name:

```java
// RIGHT: ask the Linker for the platform's size_t
MemorySegment vallen = arena.allocate(SIZE_T);
```

`SIZE_T` is the plumbing's `Linker.nativeLinker().canonicalLayouts().get("size_t")`.

`canonicalLayouts()` returns the target platform's C types (`"size_t"`, `"long"`, `"wchar_t"`, and the rest)
as resolved by the same `Linker` that will make the call. Resolve it once per C type in the raw layer, and every
out-param sized from it is right by construction.

**Sources and examples**

- [`Linker.canonicalLayouts`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.html#canonicalLayouts()) javadoc, JDK 25.

### Item 3: Give a borrowed native buffer a Java lifetime with `reinterpret` {#item-3}

`rocksdb_get` ([Item 2](#item-2)) copies the value out, into memory the caller must free:

```c
size_t vallen;
char* err = NULL;
char* value = rocksdb_get(db, readoptions, key, keylen, &vallen, &err);
/* ... read vallen bytes from value ... */
rocksdb_free(value);
```

`rocksdb_get_pinned` skips the copy by handing back a pointer into memory RocksDB keeps owning:

```c
rocksdb_pinnableslice_t* rocksdb_get_pinned(rocksdb_t* db, const rocksdb_readoptions_t* options,
                                            const char* key, size_t keylen, char** errptr);
const char* rocksdb_pinnableslice_value(const rocksdb_pinnableslice_t* t, size_t* vlen);
void rocksdb_pinnableslice_destroy(rocksdb_pinnableslice_t* v);
```

```c
rocksdb_pinnableslice_t* slice = rocksdb_get_pinned(db, readoptions, key, keylen, &err);
size_t vlen;
const char* data = rocksdb_pinnableslice_value(slice, &vlen);
/* ... read vlen bytes from data; do not free it ... */
rocksdb_pinnableslice_destroy(slice);
```

The slice pins the value in the block cache, so `rocksdb_pinnableslice_value` returns a `const char*` that is
valid until `rocksdb_pinnableslice_destroy` and is not yours to free. Reading it without a copy takes the
three-argument `reinterpret`:

```java
// The `null` cleanup is deliberate: this view borrows from the slice, it does
// not own the memory, so closing `arena` must not attempt to free it.
MemorySegment view = data.reinterpret(len, arena, null).asReadOnly();
```

`reinterpret(long, Arena, Consumer<MemorySegment>)` does three separate things:

1. **Sets the size.** The segment now knows it is `len` bytes long.
2. **Ties the lifetime to `arena`.** The segment dies when the arena closes; reading it after that throws
   instead of touching freed memory.
3. **Registers a cleanup action.** The consumer runs when the arena closes. Use it only for a buffer that
   really is yours to free (`rocksdb_free` on a `rocksdb_get` result, say), and only with a short-lived arena:
   it runs on whichever thread closes the arena, and on an arena that lives forever it never runs.

Steps 1 and 2 are what a zero-copy read is built on. Who frees the memory is a different question from how
long Java may look at it, so pinned data takes the first two and a `null` for the third.

Two ways to get it wrong:

- **A wrong length cannot be caught.** You have told FFM the size, so `IndexOutOfBoundsException` won't save
  you. Read the length from the out-param (`vlen`), never guess it.
- **Closing the arena after destroying the slice.** The view dies with its arena. Destroy the slice first and
  the still-open arena lets Java read freed memory.

**Sources and examples**

- [`PinnableSlice.map`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/PinnableSlice.java), rocksdb-ffm.
- Inside.java, [*FFM vs. Unsafe. Safety (Sometimes) Has a Cost*](https://inside.java/2025/06/12/ffm-vs-unsafe/): builds a segment from a raw address with `MemorySegment.ofAddress(...).reinterpret(size)` and compares the cost of FFM's bounds checks with `Unsafe`.

## Ownership {#ownership}

Wrapping a native pointer in a Java object raises questions no C header answers: who frees it, and how many
times; can ownership move to someone else without a free; can this object be released while something else still
depends on it. The answers fall into three shapes:

| Shape | Class | Closing it | C++ analogue |
|---|---|---|---|
| unique | `NativeObject` ([Item 4](#item-4)) | frees the pointer exactly once | `std::unique_ptr` |
| parent | `NativeObjectWithChildren` ([Item 6](#item-6)) | closes every child first, then frees | none |
| shared | refcounted ([Item 8](#item-8)) | frees when the last owner lets go | `std::shared_ptr` |

The parent shape has no C++ analogue, and neither the STL nor Rust's standard library has one. Qt's `QObject`
parent/child ownership is the closest prior art.

### Item 4: Free a native pointer exactly once, from any thread {#item-4}

`close()` has to run its cleanup exactly once no matter how many times, or from how many threads, it is
called. All three projects arrived at the same small base class before any of them named it as a pattern.
zstd-ffm's version, with a constructor check added:

```java
public abstract class NativeObject implements AutoCloseable {

    private final AtomicReference<MemorySegment> ptr;

    protected NativeObject(MemorySegment owningPointer) {
        Objects.requireNonNull(owningPointer, "owningPointer");
        if (MemorySegment.NULL.equals(owningPointer)) {
            throw new IllegalArgumentException("owningPointer is NULL");
        }
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

A wrapper is then a constructor and a `tryClose`. zstd-ffm's digested compression dictionary:

```java
public final class ZstdCompressDictionary extends NativeObject {

    public ZstdCompressDictionary(ZstdDictionary dict, ZstdCompressionLevel level) {
        super(create(dict, level));                             // ZSTD_createCDict, checked for NULL
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        var _ = (long) Bindings.FREE_CDICT.invokeExact(ptr);   // ZSTD_freeCDict
    }
}
```

The constructor check is not in the projects' versions yet. Without it, a wrapper built around
`MemorySegment.NULL` is born closed, and the first `ptr()` call reports "native object is closed" for an object
nobody closed. Refusing at construction moves the error to the call site that produced the bad pointer.

Three things this buys that a hand-written `close()` per wrapper doesn't:

- **One atomic swap handles both "called twice" and "called from two threads at once".** A `boolean closed`
  flag that is checked and then set is a race. Here the pointer *is* the flag, so there is no second field
  to fall out of sync.
- **`tryClose` runs exactly once, with a pointer that is known to be non-NULL.** No null check repeated in
  every subclass.

`close()` is `final`; only `tryClose(MemorySegment)` is abstract.

The concurrency argument is made once, here, instead of audited again in every subclass that touches a native pointer.

Two smaller choices in that snippet:

- **`MemorySegment.NULL.equals(p)`, not `==`.** `equals` compares by address, so a NULL returned from a
  downcall, which is a different object at address zero, still matches. `==` compares object identity and
  misses it. Use `equals` for every NULL check in the binding.
- **No `Cleaner` safety net for a caller who forgets `close()`.** A cleaner thread finalizing a leaked object
  runs on an arbitrary thread, which is exactly where [Item 3](#item-3) warns a native `free` can go wrong. That trades a
  leak, which shows up in monitoring, for a background thread quietly making native calls nobody asked for.

**Sources and examples**

- [`NativeObject`](https://github.com/dfa1/zstd-ffm/blob/main/zstd/src/main/java/io/github/dfa1/zstd/NativeObject.java), zstd-ffm. The constructor check shown above is not in the project yet.
- [`ZstdCompressDictionary`](https://github.com/dfa1/zstd-ffm/blob/main/zstd/src/main/java/io/github/dfa1/zstd/ZstdCompressDictionary.java), zstd-ffm.

### Item 5: Drop the pointer when C takes ownership of it {#item-5}

Not every pointer a wrapper holds dies in that wrapper's `close()`. Sometimes a later native call takes
ownership instead:

```c
void rocksdb_block_based_options_set_filter_policy(
    rocksdb_block_based_table_options_t* options,
    rocksdb_filterpolicy_t* filter_policy);
void rocksdb_filterpolicy_destroy(rocksdb_filterpolicy_t*);
```

**Ownership:** `set_filter_policy` moves `filter_policy` into `options`. RocksDB's `c.cc` implements it as
`options->rep.filter_policy.reset(filter_policy)`, so from here on `options`'s own destructor frees it.
Calling `rocksdb_filterpolicy_destroy` on it afterward is a double free of memory the caller no longer owns.

rocksdb-ffm's `NativeObject` (same shape as [Item 4](#item-4)'s, field named `owningPointer` there) has a second,
package-private way to reach `MemorySegment.NULL` that skips `tryClose` entirely:

```java
public abstract class NativeObject implements AutoCloseable {
    // ... as in Item 4 ...

    void transferOwnership() {
        owningPointer.set(MemorySegment.NULL);
    }
}
```

The setter calls it right after the native call that took the pointer:

```java
public BlockBasedTableOptions setFilterPolicy(FilterPolicy policy) {
    try {
        MH_SET_FILTER_POLICY.invokeExact(ptr(), policy.ptr());
    } catch (Throwable t) {
        throw NativeCalls.wrapInvokeFailure("setFilterPolicy failed", t);
    }
    policy.transferOwnership();   // options frees it from now on; policy.close() becomes a no-op
    return this;
}
```

The hard part isn't the method, it's knowing when to call it: nothing in a C signature says "takes
ownership." The class's own javadoc lists where to find out. Read the C source and check whether the
destructor calls `delete` on what you handed it; check the API docs, on the rare occasion they say so; or copy
whatever the official JNI binding already worked out.

`close()` called after `transferOwnership()` is a no-op, since the pointer is already NULL. That is the point:
the object still gets closed, it just no longer owns anything by then. Reusing the same sentinel has one flaw:
`ptr()`'s exception afterward reads "native object is closed," which is wrong. The object gave its pointer away,
it wasn't closed. The message was written for the close path and never adjusted for this second one.

**Sources and examples**

- [`BlockBasedTableOptions.setFilterPolicy`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/BlockBasedTableOptions.java), rocksdb-ffm.
- [`NativeObject.transferOwnership`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/NativeObject.java), rocksdb-ffm. Its javadoc lists where to find out whether a C call takes ownership.

### Item 6: Close children before their parent {#item-6}

An object that borrows native memory from another object is that object's child, and children are released
before their parent. The rule sounds obvious; the failure it prevents is easy to miss in review, because the
mistake and the crash are two steps apart. `mdb_env_close`, `mdb_txn_abort`, and `mdb_cursor_close` all return
nothing:

```c
void mdb_env_close(MDB_env *env);
void mdb_txn_abort(MDB_txn *txn);
void mdb_cursor_close(MDB_cursor *cursor);
```

**Ownership:** nothing here looks like a handoff; the hazard is the *hierarchy*. An `MDB_env*` owns every
`MDB_txn*` opened against it, and each of those owns any `MDB_cursor*` opened against it. Closing the
environment while one of its transactions is still open is undefined behavior. Cursors follow their own rules:
one in a read-only transaction must be closed explicitly, one in a write transaction is closed with it. All of
this lives in LMDB's prose documentation, nowhere in these signatures.

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

The early close and the crash are two different call sites, often two different stack traces, sometimes two
different threads. A rule that lives only in documentation will be broken.

rocksdb-ffm enforces it in code. `NativeObjectWithChildren` overrides `tryClose` as `final`: it walks a
`ConcurrentHashMap`-backed set of registered children, releases each one, then calls the subclass's own
`tryCloseResource`. A child registers with its parent in its constructor and unregisters first thing in its own
release, so a long-lived parent doesn't pile up dead children. Cascading is safe here because releasing a
snapshot, an iterator or a transaction doesn't need the thread that created it, so it can run on whatever thread
is closing the `DB`. (LMDB's transactions don't allow that; [Item 7](#item-7) is the alternative.)

What hangs off that base is the one rule applied repeatedly:

```
Database
├── ColumnFamilyHandle    kept by the database; close() is a no-op (see below)
├── RocksIterator
├── Snapshot
└── Transaction           TransactionDB and OptimisticTransactionDB only
    ├── RocksIterator
    └── Snapshot
```

The two rows under `Transaction` are the ones worth a look, because they were missing. A transaction hands out
an iterator and a snapshot, and for a long time neither was registered with anything:

```c
const rocksdb_snapshot_t* rocksdb_transaction_get_snapshot(rocksdb_transaction_t* txn);
```

**Ownership:** `c.cc` implements it as a `malloc`'d wrapper whose `rep` is `txn->rep->GetSnapshot()`, a
snapshot the *transaction* owns, so the wrapper is valid exactly as long as the transaction is.
The Java `Snapshot` knew only to `rocksdb_free` its wrapper; close the transaction, call
`snapshot.sequenceNumber()`, and it reads freed memory. `Transaction.newIterator` had the same shape: an iterator
with no owner at all. The gap surfaced while making `Transaction` itself a child of its database: the database's
close would now destroy transactions, and with them any iterator or snapshot still alive. The fix is the same
registration one level down. The transaction became a `NativeObjectWithChildren` and its iterator and snapshot
became its children. **A borrowed object with no owner is the bug**, however innocent the method that creates it.

**Handed out, not handed over.** Most children are created by the caller and closed by the caller, with the
parent's close as the safety net. A rocksdb-ffm `ColumnFamilyHandle` is the other kind: the database opens every
handle itself, and `db.getColumnFamily(name)` hands the *same* object to every caller. If `close()` really closed
it, the idiomatic `try (var cf = db.getColumnFamily(name))`, which tooling nags you to write since it returns an
`AutoCloseable`, would destroy the handle for every other user of that database. So `close()` on it is a no-op,
and only the owner frees it, through a separate package-private `release()` that the parent's sweep calls. That
is a third state next to "owns its pointer" and "gave it away": `transferOwnership()` nulls the pointer,
`makeImmortal()` keeps a shared default alive for the whole process, and this child is freed exactly once, by its
parent.

**Sources and examples**

- [`NativeObjectWithChildren`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/NativeObjectWithChildren.java), rocksdb-ffm.
- [`rocksdb_transaction_get_snapshot`](https://github.com/facebook/rocksdb/blob/main/db/c.cc), RocksDB `db/c.cc`: the wrapper is `malloc`'d, but the `rep` it points at belongs to the transaction.
- [ADR 0009: the database owns its column family handles](https://github.com/dfa1/rocksdb-ffm/blob/main/docs/adr/0009-db-owned-column-family-handles.md), rocksdb-ffm.

### Item 7: Refuse to close what is still in use {#item-7}

[Item 6](#item-6) closes children on the parent's behalf. That works when the borrower is an object the parent
can release. It does not work when the borrower is a *call in progress*: a callback running inside a native call
that still uses the parent, with nothing to release, only a stack frame to return to.

```c
void rocksdb_close(rocksdb_t* db);
```

**Ownership:** `rocksdb_close` deletes the `DB`. Nothing in the signature says that every native call currently
inside it, including the one running this callback, is left holding freed memory.

```java
// WRONG: closes the database under the read that is running this callback
db.get(key, value -> {
    db.close();
    return value.get(ValueLayout.JAVA_BYTE, 0);
});   // SIGSEGV — CachableEntry<Block>::ReleaseCacheHandle
```

The pinned value points into the block cache, and when the callback returns the read releases it into a cache the
close already destroyed. Reproduced in rocksdb-ffm, in a forked JVM: the same SIGSEGV from a merge operator during
a `get` and from a logger during a `flush`. From a background thread (merge operator, compaction filter, event
listener) it is a hang instead: `rocksdb_close` waits for the job that is running the callback.

Cascading cannot help, so the database refuses:

```java
// RIGHT: NativeDB, the base of every database type
private static final Set<Class<?>> CALLBACK_HOSTS = Set.of(
        EventNotifierBridge.class, MergeOperator.Custom.class, CompactionFilter.class,
        CompactionFilterFactory.class, Logger.class,                  // the upcall dispatchers
        PinnableHandle.class, PinnableSlice.class, RocksIterator.class);   // the scoped zero-copy readers

private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

@Override
public void close() {
    if (inCallback()) {
        throw new IllegalStateException("a database cannot be closed from inside a callback: ...");
    }
    super.close();
}

private static boolean inCallback() {
    return STACK.walk(frames -> frames.anyMatch(frame -> CALLBACK_HOSTS.contains(frame.getDeclaringClass())));
}
```

- **It refuses before anything is released.** The check runs ahead of [Item 4](#item-4)'s pointer swap, so the
  database stays open: the read finishes and the caller closes it once the callback has returned.
- **It pays on the cold path.** A `ScopedValue` set around every callback costs about 6.5 ns in a rough loop, on
  every pinned get and every merge or filter call. A stack walk costs about 1 µs at 10 frames and 9 µs at 200, but
  only inside `close()`.
- **The list is maintained by hand.** Forgetting a new callback type brings the crash back for that callback only;
  one test per callback kind pins the list down.
- **It guards the database, not everything.** Closing the *iterator* a mapper is reading from, inside that mapper,
  still leaves its view pointing at freed memory. Another thread closing the database mid-callback is the ordinary
  close-versus-call race, which none of these libraries protect against.

**Refuse before you release, not after.** LMDB has the same problem one shape over: an `MDB_txn` belongs to the
thread that began it, so `env.close()` on another thread cannot abort it without corrupting the reader table or
racing a call in flight. lmdb-ffm's `LmdbEnv` counts open transactions and refuses, but in `tryClose`, after
[Item 4](#item-4)'s `close()` already swapped the pointer. The wrapper then considers itself closed whatever
`tryClose` decides: `mdb_env_close` can never be called, and the environment and its upcall-stub arena leak until the
process exits. The caller is told about their bug and handed a second one. Refusing before the swap fixes it:

```java
// RIGHT: the environment stays open and can be closed later
@Override
public void close() {
    if (openTransactions.get() != 0) {
        throw new LmdbContractException(/* … */);
    }
    super.close();
}
```

This needs a `close()` a subclass can wrap, where lmdb-ffm's is `final`, so it ships the first version at the time
of writing. Neither version is airtight: `mdb_txn_begin` returns before the transaction registers with the count,
so a `beginTxn()` racing `close()` can slip through.

**Sources and examples**

- [`NativeDB#close`](https://github.com/dfa1/rocksdb-ffm/pull/342), rocksdb-ffm PR #342; the first, listener-only version is [PR #341](https://github.com/dfa1/rocksdb-ffm/pull/341).
- [Closing from inside a callback](https://github.com/dfa1/rocksdb-ffm/blob/main/docs/explanation.md#closing-from-inside-a-callback), rocksdb-ffm `docs/explanation.md`.
- [`LmdbEnv#tryClose`](https://github.com/dfa1/lmdb-ffm/pull/15), lmdb-ffm PR #15.
- [Cursor outlives its transaction](https://github.com/dfa1/lmdb-ffm/issues/4), lmdb-ffm issue #4: the same shape one level down, open at the time of writing.

### Item 8: Share ownership when a borrower can outlive its owner {#item-8}

`NativeObject` answers "how does one wrapper free its own pointer exactly once." It doesn't answer what happens
when a *second* wrapper depends on that pointer without owning it.

zstd-ffm's `ZstdCompressContext#refDictionary` attaches a pre-digested dictionary by reference, and its javadoc
already says so:

```java
/// The reference is borrowed: `dict` must stay open for as long as this
/// context uses it. The reference is dropped by a parameter
/// [#reset(ZstdResetDirective)] or by passing `null`.
public ZstdCompressContext refDictionary(ZstdCompressDictionary dict) { /* ... */ }
```

Nothing enforces that sentence. Closing the dictionary under a live context is a two-line
reproduction, found while closing pitest-reported mutation gaps in the dictionary tests:
the round-trip suite passes even with `refDictionary` mutated to a no-op, which is exactly what mutation testing
exists to catch.

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

**Why FFM's own safety net doesn't catch this.** `Arena` and `MemorySegment` liveness checks protect the
segment passed directly as an argument to *this* downcall. `refDictionary` hands zstd a raw pointer that zstd
stores inside its own `ZSTD_CCtx` struct for later. Once the call returns, Java has no further say. The next
`compress()` dereferences whatever zstd remembers, freed or not. It is a use-after-free like any other native
library's, because that is what it is.

**Which fix applies.** Two shapes of "A depends on B's native pointer" show up across these bindings, and they
want opposite remedies:

- **The child can't exist without the parent.** rocksdb-ffm's `Snapshot` needs its owning `DB`'s pointer just
  to release itself via `rocksdb_release_snapshot`. Remedy: cascade-close, [Item 6](#item-6).
- **The borrower only uses one reference and is fine without it.** `ZstdCompressContext` borrows a
  `ZstdCompressDictionary`, and stays valid for everything else even if that reference breaks. Cascading here
  would kill a context that is still perfectly usable. Remedy: fix the reference, not the object.

The second shape is shared ownership: more than one object holds the pointer, and the last to let go frees it.
`refDictionary` needs exactly that and doesn't have it yet.

A liveness flag, "check that my dependency is still open right before use, throw if not", sounds like the
obvious fix and isn't one. It only works when the borrower's own thread is the only thing that can invalidate
the reference. `ZstdCompressDictionary` is documented immutable once built and safe to share across threads, so
a *different* thread can call `close()` between the check and the call. That turns a crash into a slightly more
readable race, not into no race.

Shared ownership removes the window instead of narrowing it. Implement it with a reference count: the borrower
stops borrowing and becomes a co-owner, so as long as it holds its reference the native memory cannot be freed
under it, and the last release frees it.

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

Compare-and-increment, not a blind `incrementAndGet()`: bringing a released count back from zero is the classic
refcounting bug. Public `close()` becomes "release the constructor's own reference," not an unconditional free.
`refDictionary` calls `retain()` when it attaches and must `release()` on replacement, on an explicit clear, on a
parameter `reset()` that drops the dictionary, and on the context's own close.

**The cost.** `close()` stops being deterministic from the caller's point of view: calling it while a context
still holds a reference no longer frees anything, it just lowers a count. That is a real change from "`close()`
frees now," which is otherwise this codebase's convention. And the bookkeeping doesn't disappear, it moves: every
path that acquires must release, or a crash turns into a leak. Safer, but still a bug class that depends on
discipline, the same shape as the child registration [Item 6](#item-6) leans on.

**One base, three opt-in shapes.** The refcount belongs on the class that needs shared ownership, the children
`Set` on the one that needs a parent. Neither goes into `NativeObject` itself, which every plain wrapper still
extends and shouldn't have to carry weight it never uses.

Status: the reproduction above is real; the refcounting is a design, not landed code. Two documents frame
it: Maurizio Cimadamore's design note on why FFM is lifetime-centric rather than pointer-centric, and `Arena`'s
javadoc guarantee that access after close is always a clean `IllegalStateException`.
That guarantee holds only for what the arena itself tracks, and a pointer copied into a C struct by
`refDictionary` is, by construction, not that.

**Sources and examples**

- [`ZstdCompressContext#refDictionary`](https://github.com/dfa1/zstd-ffm/blob/main/zstd/src/main/java/io/github/dfa1/zstd/ZstdCompressContext.java), zstd-ffm: the borrowed-reference contract, found unenforced while closing pitest-reported mutation gaps in the dictionary tests.
- Maurizio Cimadamore, [*Lifetimes in the Foreign Function & Memory API*](https://cr.openjdk.org/~mcimadamore/panama/why_lifetimes.html): a pre-`Arena` design note, written against the API's earlier `SegmentScope` name, arguing FFM should be lifetime-centric rather than pointer-centric: allocate related resources from one shared scope so they are freed together. Its worked examples (an array of C strings sharing its elements' scope, `dlopen`/`dlsym` symbols sharing their library handle's scope) are the JDK's own answer to the parent shape ([Item 6](#item-6)). The many-independent-borrowers shape isn't part of its argument.
- [`Arena` javadoc, JDK 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Arena.html): access after close is always a clean `IllegalStateException`, for a segment the arena itself tracks. A pointer copied into a C struct by `refDictionary` isn't one of those.

### Item 9: Give a handle its own arena when it owns buffers or stubs {#item-9}

Between "lives for one call" and "lives forever" sits the common case: a native object that is expensive to
create, reused across many calls, and freed explicitly. `ZSTD_CCtx` is the classic example; `rocksdb_t` and
`rocksdb_iterator_t` have the same shape. [Item 4](#item-4)'s `NativeObject` already owns the pointer, and for a handle
that owns nothing else that is enough. When the wrapper also owns Java-side buffers or upcall stubs, those need a
second resource next to the pointer, an arena, with its own lifetime to manage:

```c
ZSTD_CCtx* ZSTD_createCCtx(void);
size_t     ZSTD_freeCCtx(ZSTD_CCtx* cctx);   /* compatible with NULL pointer */
```

**Ownership:** `ZSTD_createCCtx` returns a pointer C no longer tracks; ownership passes to the caller at once.
`ZSTD_freeCCtx` is the other half of that contract: call it exactly once, which is what `NativeObject` ([Item 4](#item-4))
guarantees.

```java
public final class ZstdCompressStream extends NativeObject {

    private final Arena arena;
    private final ZstdStreamBuffer in;    // backed by `arena`
    private final ZstdStreamBuffer out;   // backed by `arena`

    public ZstdCompressStream(ZstdCompressionLevel level) {
        super(createCctx());              // NativeObject owns the CCtx pointer, freed by tryClose below
        this.arena = Arena.ofConfined();  // this wrapper's own resource, independent of the pointer above
        this.in = new ZstdStreamBuffer(arena);
        this.out = new ZstdStreamBuffer(arena);
    }

    private static MemorySegment createCctx() {
        MemorySegment p;
        try {
            p = (MemorySegment) Bindings.CREATE_CCTX.invokeExact();
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        if (MemorySegment.NULL.equals(p)) {
            throw new ZstdException("ZSTD_createCCtx returned NULL");   // allocation failure
        }
        return p;
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

`ZSTD_createCCtx` returns NULL when allocation fails, so `createCctx` checks before the pointer reaches
`NativeObject`. The real code keeps that check in a shared `NativeCall.createOrThrow` helper; it is inlined
above to show what it does.

`super(createCctx())` hands the pointer to `NativeObject`; `arena` is a second, independent resource this same
wrapper owns, backing the `in`/`out` buffers. `tryClose` is the one hook `NativeObject` calls, and everything
inside it, including what order to free things in, is this class's own business.

Order matters: **free the native object first, then close the arena.** For a plain `ZSTD_CCtx` neither resource
touches the other, so reversing the two lines wouldn't crash. But the moment a wrapper's arena backs an upcall
stub instead of a plain buffer ([Item 10](#item-10)'s comparator), that stops being true: the native side can still call
back into the stub during its own teardown, and closing the arena first pulls the stub out from under it
mid-call. Freeing in this order everywhere costs nothing when the hazard is absent, and saves working out the
answer case by case for the wrappers where it is present.

This is also where you decide the object's thread policy rather than inherit it. `ZSTD_CCtx` isn't thread-safe,
and a confined arena enforces that for the buffers it owns: touching them from another thread throws
`WrongThreadException`. It does not guard the `ZSTD_CCtx` pointer itself, and it constrains `close()`: closing
from another thread frees the native object, then fails in `arena.close()`, and `NativeObject.close()` swallows
that exception, leaking the arena. Close from the creating thread.

Two things about the shown constructor and `tryClose`:

- **The constructor leaks on failure.** If `Arena.ofConfined()` or either `new ZstdStreamBuffer(arena)` throws
  *after* `super(createCctx())` has run, the object never finishes constructing. Nobody holds a reference to
  it, so nobody can call `close()`, and the `ZSTD_CCtx` it already owns leaks. `NativeObject`'s constructor
  can't help; the failure happens later, in the subclass. The fix is a `try`/`catch` around everything after
  `super(...)` that closes `this` before rethrowing. Add it wherever a constructor does real work after taking
  ownership of a pointer, this one included.
- **The discarded `long` from `FREE_CCTX.invokeExact` is deliberate.** `ZSTD_freeCCtx` reports an error only
  for a static context, which this binding never creates. `var _ =` says "read, and ignored on purpose," which
  is a different claim than never having read it at all.

**Sources and examples**

- [`ZstdCompressStream.tryClose`](https://github.com/dfa1/zstd-ffm/blob/main/zstd/src/main/java/io/github/dfa1/zstd/ZstdCompressStream.java) and [`NativeCall.createOrThrow`](https://github.com/dfa1/zstd-ffm/blob/main/zstd/src/main/java/io/github/dfa1/zstd/NativeCall.java), zstd-ffm.

### Item 10: Scope an upcall stub to the life of the object that uses it {#item-10}

Upcalls are where lifetime stops being about buffers. dev.java's
[Calling Java from native code](https://dev.java/learn/ffm/upcall/) covers the mechanics; this item is about
how long the stub has to live. A RocksDB custom comparator is registered once and called later, during compaction, on a thread you didn't create. The stub must stay valid not
"until the call returns" but "until the comparator is destroyed".

RocksDB takes the comparator as three function pointers plus a state pointer:

```c
rocksdb_comparator_t* rocksdb_comparator_create(
    void* state, void (*destructor)(void*),
    int (*compare)(void*, const char* a, size_t alen, const char* b, size_t blen),
    const char* (*name)(void*));
```

**Ownership:** RocksDB owns the right to *call* `compare` and `name` for the comparator's lifetime, and calls
`destructor(state)` exactly once when it is done. `state` itself is opaque to RocksDB. Whatever it points to is
the caller's own memory, so releasing it is the caller's job, done inside that `destructor` callback.

Each of those function pointers is an upcall stub on the Java side:

```java
MethodHandle target = MethodHandles.lookup()
        .findStatic(MyComparator.class, "compare", COMPARE_TYPE);
MemorySegment stub = linker.upcallStub(target, COMPARE_DESC, arena);
```

The `arena` argument is the whole story: the stub is valid exactly as long as the arena is open. So the arena
has to be scoped to the *native object's* life, per [Item 9](#item-9), and closed only after the library is done with the
comparator. lmdb-ffm's `LmdbEnv` keeps one for exactly this, next to the environment pointer it already owns:

```java
public final class LmdbEnv extends NativeObject {

    // Stubs installed via mdb_set_compare stay callable for every later read on
    // this environment, so they live as long as the environment, not as long as
    // the call that installed them.
    private final Arena arena = Arena.ofConfined();

    MemorySegment upcallStub(LmdbComparator comparator) {
        return LmdbComparators.upcallStub(arena, comparator);
    }

    // tryClose: mdb_env_close(ptr), then arena.close(), in Item 9's order
}
```

Three rules for any binding's contributing guide:

- **An exception escaping an upcall takes down the VM.** Every stub body is a `try`/`catch (Throwable)`;
  [Item 18](#item-18) covers what to do with the failure. The complete stub builder from lmdb-ffm, with the catch in place:

  ```java
  final class LmdbComparators {

      // (const MDB_val* a, const MDB_val* b) -> int, with the comparator bound as first argument
      private static final MethodHandle TRAMPOLINE;
      static {
          try {
              TRAMPOLINE = MethodHandles.lookup().findStatic(LmdbComparators.class, "invoke",
                      MethodType.methodType(int.class, LmdbComparator.class, MemorySegment.class, MemorySegment.class));
          } catch (ReflectiveOperationException e) {
              throw new ExceptionInInitializerError(e);
          }
      }

      static MemorySegment upcallStub(Arena arena, LmdbComparator comparator) {
          MethodHandle bound = TRAMPOLINE.bindTo(comparator);
          return Linker.nativeLinker().upcallStub(bound, Bindings.CMP_FUNC_DESCRIPTOR, arena);
      }

      private static int invoke(LmdbComparator comparator, MemorySegment a, MemorySegment b) {
          try {
              return comparator.compare(asValue(a), asValue(b));
          } catch (Throwable t) {
              t.printStackTrace();   // never let it escape; Item 18 on what to return
              return 0;
          }
      }
  }
  ```
- **Reachability is not enough.** A strong reference to the stub segment doesn't keep it callable; the arena
  being open does. Forgetting this produces a crash far from the cause.
- **Per-instance stubs are cheap to write and expensive in bulk.** `bindTo` above gives one stub per
  comparator, each with its own generated code, which is fine for the handful of comparators a database has.
  For something created often, use the C idiom instead: one static stub for everyone, and the `void* state`
  pointer the library passes back to tell instances apart. rocksdb-ffm's merge operator does this with a
  registry whose keys are fake addresses:

  ```java
  final class UpcallRegistry<T> {

      private final ConcurrentHashMap<Long, T> registry = new ConcurrentHashMap<>();
      private final AtomicLong nextId = new AtomicLong(1);

      MemorySegment register(T value) {              // the "pointer" RocksDB stores as state
          long id = nextId.getAndIncrement();
          registry.put(id, value);
          return MemorySegment.ofAddress(id);
      }

      T get(MemorySegment state) {                   // called from inside the shared stub
          return registry.get(state.address());
      }

      T unregister(MemorySegment state) {            // from the destructor callback
          return registry.remove(state.address());
      }
  }
  ```

  `state` is never dereferenced by C, so it doesn't have to point anywhere; it only has to round-trip. The
  shared stubs are `static final`, built once at class init.

**Sources and examples**

- [`LmdbComparators`](https://github.com/dfa1/lmdb-ffm/blob/main/lmdb/src/main/java/io/github/dfa1/lmdb/LmdbComparators.java) and [`LmdbEnv`](https://github.com/dfa1/lmdb-ffm/blob/main/lmdb/src/main/java/io/github/dfa1/lmdb/LmdbEnv.java), lmdb-ffm.
- [`UpcallRegistry`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/UpcallRegistry.java) and [`MergeOperator`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/MergeOperator.java), rocksdb-ffm.
- dev.java, [Calling Java from native code](https://dev.java/learn/ffm/upcall/).

## API shape {#api-shape}

The public layer exists to hide FFM from callers, not to pile abstraction on top of it. The test for a helper
is whether it removes a repeated mistake or just a repeated line. zstd-ffm's `checkReturnValue` is the first
kind: every zstd call returns a `size_t` that may be an error code, and the lambda puts the check in one place.

```java
long written = NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS.invokeExact(
        out, bound, in, (long) src.length, level.value()));
```

The cost is one lambda and one extra frame per call. For a one-shot `compress` that is nothing. In a streaming
loop that crosses into C once per chunk it is worth a look at the profile ([Item 14](#item-14) has the caveat on inlining
through the helper), and the spelled-out form is the same check without the indirection:

```java
long written = (long) Bindings.COMPRESS.invokeExact(out, bound, in, (long) src.length, level.value());
if (NativeCall.isError(written)) {
    throw new ZstdException(/* name and code, as checkReturnValue does */);
}
```

Abstract the check, not the call. A helper that also chose buffers, levels, or arenas for the caller would be
the second kind, and would soon need options of its own.

### Item 11: Keep the generated raw layer out of the public API {#item-11}

jextract generates a faithful mirror of the header. That mirror isn't an API, it is a step on the way to one,
and shipping it is how `MemorySegment` ends up in application code.

Every binding worth using has two layers:

- **Raw layer**, package-private. One method per C symbol, signature mirrors the header exactly, no
  interpretation, no cleverness. Generated by jextract, or listed by hand in one `Bindings` class. Either way
  mechanical.
- **Public layer.** `byte[]`, `Optional`, records, exceptions, `Path`. No `MethodHandle`, no restricted methods,
  no arenas the caller has to know about, and no `MemorySegment` outside the tiers that declare one ([Item 13](#item-13)).

A mechanical raw layer is a precondition, not a convenience. Let interpretation creep in, a null check here, a
length fixup there, and within a year nobody can tell which layer owns the semantics.

A small example of the boundary doing real work: rocksdb-ffm takes `java.nio.file.Path` everywhere the C API
takes `const char*` for a filesystem location. `Path` composes with the rest of `java.nio.file` and makes
"absolute or relative?" someone else's already-solved problem.

More of the same from rocksdb-ffm, each one a C type replaced by the Java type that already carries the rule:

- `Duration` where `c.h` takes `int ttl_seconds` (`openTtl`): no unit to get wrong, and a negative or oversized
  value is rejected once, in the wrapper.
- `boolean` where `c.h` takes `unsigned char` for a flag (`setCreateIfMissing`).
- An enum where `c.h` takes an `int` from an anonymous enum (`CompressionType`, `CompactionStyle`); [Item 12](#item-12) is
  the bitmask version.
- `byte[]`, `null` for a missing key, where `c.h` returns `char*` plus a length out-param.

Each turns a convention the caller had to remember into a type the compiler checks, the same argument as
[Your Compiler Is Already Part of Your Security Team](https://dfa1.github.io/articles/your-compiler-is-already-part-of-your-security-team.html).

**Sources and examples**

- [`RocksDB.openTtl`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/RocksDB.java) and [`Options`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/Options.java), rocksdb-ffm.
- [jextract](https://github.com/openjdk/jextract), OpenJDK.

### Item 12: Give a bitmask its own enum, and fold it back at one boundary {#item-12}

[Item 11](#item-11) is about *where* interpretation lives. This one is about a translation that recurs often enough to earn
its own treatment: the C API takes a plain integer where the natural Java type is a set of named constants.
lmdb-ffm's environment flags are OR-able bits from `lmdb.h`:

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

The public layer takes a `Set<LmdbEnvFlag>`. `EnumSet.of(LmdbEnvFlag.RDONLY, LmdbEnvFlag.NOSUBDIR)` reads at
the call site the way the LMDB manual describes the flags, not as a `NOSUBDIR | RDONLY` expression whose
precedence the caller has to get right. One shared interface folds the set back into the single native
`unsigned int` the raw layer needs:

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

    static <E extends Enum<E> & LmdbFlag> Set<E> fromBits(int bits, Class<E> type) { /* the reverse, into an EnumSet */ }
}
```

`toBits` and `fromBits` are the only two places in the codebase that know these values are `int`s at all.
`LmdbDbiFlag` and `LmdbWriteFlag` implement the same interface and get both directions for free. rocksdb-ffm
has the simpler version of the same idea: `CompressionType` maps one-to-one onto `rocksdb_*_compression`, so
there is no bitmask to fold, just `getValue()` going out and `fromValue(int)` coming back.

At the call site the caller passes the set, and the wrapper folds it once, right before the downcall:

```java
try (LmdbEnv env = LmdbEnv.create()
        .mapSize(LmdbByteSize.ofMB(10))
        .open(Path.of("/tmp/mydb"), EnumSet.of(LmdbEnvFlag.NOSUBDIR))) {
    // ...
}
```

```java
public LmdbEnv open(Path path, Set<LmdbEnvFlag> flags, int mode) {
    int bits = LmdbFlag.toBits(flags);                               // the only conversion
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment pathPtr = arena.allocateFrom(path.toString());
        int code = (int) Bindings.ENV_OPEN.invokeExact(ptr(), pathPtr, bits, mode);
        NativeCall.check(code);                                      // Item 16
    }
    return this;
}
```

**Sources and examples**

- [`LmdbFlag`](https://github.com/dfa1/lmdb-ffm/blob/main/lmdb/src/main/java/io/github/dfa1/lmdb/LmdbFlag.java), [`LmdbEnvFlag`](https://github.com/dfa1/lmdb-ffm/blob/main/lmdb/src/main/java/io/github/dfa1/lmdb/LmdbEnvFlag.java) and [`LmdbEnv.open`](https://github.com/dfa1/lmdb-ffm/blob/main/lmdb/src/main/java/io/github/dfa1/lmdb/LmdbEnv.java), lmdb-ffm. `open` is simplified above; the real method also records that the environment is open.

### Item 13: Expose zero-copy as a declared tier, never as a leak {#item-13}

"No `MemorySegment` in public signatures" is the right default and the wrong absolute rule. Some callers *want*
the segment, to stream to a socket or feed another native library, and a `byte[]` copy is exactly the cost they
came to avoid.

The three copying tiers below are `rocksdb_get`; the scoped one is `rocksdb_get_pinned` from [Item 3](#item-3):

| Tier | Signature | Copy | Caller obligation |
|---|---|---|---|
| `byte[]` | `byte[] get(byte[] key)` | one copy out | none |
| `ByteBuffer` | `CopyResult get(ByteBuffer key, ByteBuffer value)` | into the caller's buffer | provide it, size it |
| `MemorySegment` | `CopyResult get(MemorySegment key, MemorySegment value)` | into the caller's segment | provide it, own its arena |
| scoped | `<R> R get(MemorySegment key, Mapper<R> fn)` | none | read inside the callback |

The interesting one is the last. The zero-copy tier doesn't hand a segment back; it passes a read-only view
*into* a callback, and the arena behind it closes the moment the callback returns. Keeping the view
isn't undefined behavior, it is an `IllegalStateException`, or `WrongThreadException` if smuggled to another
thread. The library enforces the rule; the caller doesn't have to remember it.

```java
try (var db = RocksDB.openReadOnly(dir); Arena arena = Arena.ofConfined()) {
    MemorySegment key = arena.allocateFrom("key").asSlice(0, 3);     // drop the NUL terminator
    byte[] value = db.get(key, view -> view.toArray(JAVA_BYTE));     // `view` is valid only inside the lambda
}
```

The lambda gets a read-only `MemorySegment` pointing straight into RocksDB's pinned slice. Copy out what you
need and return it; `get` returns that result, or `null` when the key is absent. Keep `view` for later and the
next access throws.

The workable rule is narrower:

> A `MemorySegment` may appear in a public signature only where the signature itself pins its lifetime: a
> buffer the caller already owns, or an argument to a callback that ends when the call does.

The same reasoning applies to zstd-ffm's zero-copy path and zstd's streaming API, where the natural unit is a
pair of segments rather than an array.

**Sources and examples**

- [`Mapper`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/Mapper.java) and [`ReadOnlyDBTest`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/test/java/io/github/dfa1/rocksdbffm/ReadOnlyDBTest.java), rocksdb-ffm.

### Item 14: Hold downcall handles in `static final` fields {#item-14}

```c
size_t ZSTD_compress(void* dst, size_t dstCapacity,
                     const void* src, size_t srcSize, int compressionLevel);
```

```java
static final MethodHandle COMPRESS =
        NativeLibrary.lookup("ZSTD_compress",
                FunctionDescriptor.of(SIZE_T, ADDRESS, SIZE_T, ADDRESS, SIZE_T, JAVA_INT));
```

Not style: a `static final` `MethodHandle` is a constant to C2, which sees through it and inlines the downcall
stub.

The same applies to `VarHandle`s derived from layouts, and to `FunctionDescriptor`s if you are building them at
call time (don't). One caveat: zstd-ffm wraps its calls in `NativeCall.checkReturnValue(() -> ... invokeExact ...)`
([Item 16](#item-16)), which funnels many call sites through one shared helper. Don't assume C2 inlines through that
wrapper. The two benchmarks above ran through it, so its cost is already inside those numbers. Measure it on its
own if you are chasing the last bit of overhead; restructure only when a profile names it.

zstd's own header makes the complementary point: through a foreign function interface, "it's not rare that
performance ends being spent more into the interface, rather than compression itself", and it recommends buffers
"as large as practical" to cut round trips. FFM makes each crossing cheaper, not free. Batching at
the API level is still the biggest lever.

**Sources and examples**

- [`zstd.h`](https://github.com/facebook/zstd/blob/dev/lib/zstd.h), in the comment above `ZSTD_CStreamInSize`.
- JMH numbers for the FFM path against `rocksdbjni`: [RocksDB Performance and Zero-Copy](https://dfa1.github.io/articles/rocksdb-performance-and-zero-copy.html).

### Item 15: Link a downcall as `critical` only if it never blocks and never calls back {#item-15}

`Linker.Option.critical` skips the JVM's thread-state transition around a downcall, on the assumption that the
native call is short and never re-enters Java. Both halves of that assumption can fail, and the two failures look
nothing alike.

**It must never call back into Java.** lmdb-ffm broke this: a custom `LmdbComparator`, installed via
`mdb_set_compare`, runs as an upcall from inside LMDB's own B+tree search, that is, from inside the very
`mdb_get`/`mdb_cursor_get` call linked as critical. The result isn't a segfault but a VM-level guarantee failure,
because HotSpot's own bookkeeping assumed the thread never left native code:

```
# Internal Error (upcallLinker.cpp:77)
# guarantee(thread->thread_state() == _thread_in_native) failed:
#     wrong thread state for upcall
```

**It must never block.** Skipping the transition also means the JVM cannot bring that thread to a safepoint
while the critical call runs. The upcall crash above is loud and immediate; this one is quiet and more likely. A
critical `mdb_get` that page-faults on a memory-mapped database bigger than RAM blocks in the kernel waiting on
disk, unable to safepoint, and every other thread waiting on a GC stalls until it returns. This is why `critical`
looks most tempting on exactly the calls it is least safe for: a hot read path into a large mmap'd file is both
the biggest performance win and the biggest blocking-I/O risk in the same call.

**Sources and examples**

- [Don't call the critical mdb_get/mdb_cursor_get through a comparator](https://github.com/dfa1/lmdb-ffm/pull/14), lmdb-ffm PR #14: the fix links the symbol twice, once critical and once plain, and switches to the plain handle for the rest of the environment's life once a comparator is installed.
- [`Linker.Option.critical`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.Option.html) javadoc, JDK 25.

## Errors {#errors}

Failures cross the Java/C boundary in three directions: C reports one to Java ([Item 16](#item-16)), Java fails while
cleaning up a native object ([Item 17](#item-17)), and Java code that C calls fails inside a native frame ([Item 18](#item-18)). Each
item has one rule, and each rule decides who gets to see the failure.

### Item 16: Translate every C error convention at a single boundary {#item-16}

The C conventions for reporting failure could hardly be more different, and the Java answer is the same for all
of them.

**RocksDB** uses an out-param: the `char** errptr` in `rocksdb_get` above, set to a malloc'd message on failure,
NULL on success, and the last parameter of nearly every function in `c.h`.

```java
public static void checkError(MemorySegment errHolder) {
    MemorySegment errPtr = errHolder.get(ValueLayout.ADDRESS, 0);
    if (!MemorySegment.NULL.equals(errPtr)) {
        throw new RocksDBException(toJavaString(errPtr));   // toJavaString frees it
    }
}
```

**Zstandard** encodes errors in the return value: a `size_t` whose error codes sit at the top of the range,
tested with `ZSTD_isError` and named with `ZSTD_getErrorName`.

```c
unsigned    ZSTD_isError(size_t result);
const char* ZSTD_getErrorName(size_t result);
```

**Ownership:** no pointers change hands at all. The string `ZSTD_getErrorName` returns is a static literal owned
by the library forever, never freed by either side.

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

Different mechanics, same shape: **one helper at the boundary converts the C convention into a typed Java
exception, so no caller ever sees the convention.** The convention varies; the boundary doesn't.

**The message string is a separate decision.** `mdb_strerror` and RocksDB's `errptr` message both arrive as a
`char*` to a NUL-terminated C string with no out-param telling Java how long it is. Unlike [Item 2](#item-2)'s `vallen`,
there is nothing to `reinterpret` against. The two bindings look almost identical; the ownership underneath them
is opposite:

```c
char* mdb_strerror(int err);
```

**Ownership:** none. For a non-negative `err`, LMDB delegates straight to the platform's `strerror(3)`;
otherwise it returns one of its own error strings. Either way the string is static, owned by the library or the
C runtime for the life of the process, and the caller must never free it.

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

`reinterpret(Long.MAX_VALUE)` is [Item 3](#item-3) without the arena or cleanup: `Long.MAX_VALUE` isn't a real
length, it only lets `getString` scan for the NUL instead of failing the bounds check on byte zero. It says "trust
me, there is a NUL in there", which is all a bare `const char*` promises. One trap: `strerror(3)`'s buffer is only as
thread-safe as the platform call, so a concurrent `mdb_strerror` can overwrite it mid-copy. Copying in the same
expression that reads the pointer narrows that window, not closes it.

RocksDB's version looks the same, but its pointer came out of an `errptr` slot ([Item 2](#item-2)), which only holds
a message RocksDB `malloc`'d for this one call:

```java
static String toJavaString(MemorySegment ptr) {
    String s = ptr.reinterpret(Long.MAX_VALUE).getString(0);
    free(ptr);
    return s;
}
```

**The ownership decision is made at the C API boundary, not from the shape of the string.** rocksdb-ffm keeps a
`toBorrowedJavaString`, identical minus `free(ptr)`, for strings that are *not* `errptr` output, such as a pointer
into an `std::string` owned by its parent object.

Related translations belong in the same place:

- **Sentinel returns.** `-1`/`NULL` become an exception when they mean failure, `null` or `Optional.empty()`
  when they mean "legitimately absent". That is a per-function judgment call (`rocksdb_get` returning NULL is a missing
  key, not an error), not a mechanical rule.
- **Multiple out-params collapse into one value.** `(value, length, found)` becomes one `byte[]`, or `null`.
- **errno** is captured with `Linker.Option.captureCallState("errno")` on the raw side and translated on the
  public side. Reading it any other way after the downcall is a race; the JVM may have made syscalls of its own
  in between.

**Sources and examples**

- [`NativeCall.errorMessage`](https://github.com/dfa1/lmdb-ffm/blob/main/lmdb/src/main/java/io/github/dfa1/lmdb/NativeCall.java), lmdb-ffm.
- [`NativeCalls.toJavaString`/`toBorrowedJavaString`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/NativeCalls.java), rocksdb-ffm.

### Item 17: Never throw from `close()`, except to report a broken contract {#item-17}

The shared `close()` from [Item 4](#item-4) calls `tryClose` and swallows anything it throws: a failed native free is not
something the caller can act on. One failure is the exception to that rule: the caller asked to close something
that is still in use ([Item 7](#item-7)), and the wrapper refuses.

```java
LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
env.close();   // the caller's bug: txn is still open
```

Closing the environment unmaps the database file, which would crash that transaction, so `LmdbEnv` refuses.
The refusal has to reach the caller. A log line doesn't help: `env.close()` returns normally, the caller's code
proceeds as if the environment were closed, and it is still alive. So it is thrown, as a dedicated unchecked
type that says whose bug it is:

```java
// RIGHT: checked first, thrown before anything is released
@Override
public void close() {
    if (openTransactions.get() != 0) {
        throw new LmdbContractException(/* … */);
    }
    super.close();   // Item 4: swap, tryClose, swallow
}
```

Everything after `super.close()` keeps the old rule: whatever `tryClose` throws is swallowed. Only the check in
front of it can throw, and when it does the object is exactly as it was before the call. rocksdb-ffm's database
does the same with a plain `IllegalStateException` when it is closed from inside a callback.

The tempting alternative is to throw from `tryClose` and let that one type through:

```java
// WRONG: the contract check lives in tryClose, and close() rethrows it
public final void close() {
    MemorySegment p = ptr.getAndSet(MemorySegment.NULL);   // the object is closed from here on
    if (!MemorySegment.NULL.equals(p)) {
        try {
            tryClose(p);                                    // throws LmdbContractException
        } catch (LmdbContractException e) {
            throw e;
        } catch (Throwable _) {
            // destructors must not throw
        }
    }
}
```

It does report the bug, and it follows `AutoCloseable`'s advice to mark the object closed before throwing. That
advice assumes the resource was released and only the reporting failed. Here nothing was released, so marking it
closed turns a refusal into a permanent leak: no later `close()` can reach `tryClose` again. lmdb-ffm ships this
version at the time of writing.

One limit applies to both: inside `try`-with-resources, the exception reaches the caller directly only if the
body completed normally. If the body already threw, it becomes a *suppressed* exception on the original, and a
bare `catch (LmdbContractException e)` around the block never sees it.

**Sources and examples**

- [`NativeDB#close`](https://github.com/dfa1/rocksdb-ffm/pull/342), rocksdb-ffm PR #342: refuses before releasing anything.
- [`LmdbContractException`](https://github.com/dfa1/lmdb-ffm/pull/15), lmdb-ffm PR #15: the version that refuses after the swap.

### Item 18: Never let an exception escape an upcall {#item-18}

[Item 10](#item-10) keeps an upcall stub alive; this item is about what runs inside it. The example is a custom comparator:
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

lmdb-ffm's shipped comparator ([Item 10](#item-10)) stops at the first half: it prints the stack trace and returns 0. The
per-thread stash and rethrow above is the next step, not landed code.

Two decisions hide in that catch block:

- **What to return.** The C side keeps running after the upcall, so return something it tolerates. `0` tells
  the B-tree the two keys are equal, which is wrong; that is why the stashed failure is checked as soon as the
  downcall returns, and the downcall's result is discarded.
- **Where the exception goes.** When the upcall runs on the thread that made the downcall, as LMDB's comparator
  does inside `mdb_get`, stash it per thread and rethrow once the downcall returns: [Item 16](#item-16)'s boundary, in the
  other direction. When it runs on a native-owned thread, as RocksDB's compaction does, no Java caller is
  waiting; the failure has to go to a logger or be recorded for the next call that can report it.

**Sources and examples**

- [`LmdbComparators.invoke`](https://github.com/dfa1/lmdb-ffm/blob/main/lmdb/src/main/java/io/github/dfa1/lmdb/LmdbComparators.java), lmdb-ffm: catches, logs, returns 0.
- [`Linker.upcallStub`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.html) javadoc, JDK 25: an exception that escapes an upcall terminates the JVM.

## Build, loading, and testing {#build}

### Item 19: Let the caller grant native access {#item-19}

`SymbolLookup.libraryLookup`, `reinterpret`, `Linker.upcallStub` and the downcall handle lookup are *restricted*
methods: misused, they can crash the JVM, so the JDK only lets a module call them once it has been granted native
access. Without the grant the JVM prints a warning today, and [JEP 472](https://openjdk.org/jeps/472) states the
intent to turn it into an error.

The grant can't come from the library. It is a launcher decision, made by whoever starts the JVM, so all three
READMEs say the same thing:

```
Run with --enable-native-access=ALL-UNNAMED
```

That flag is a blanket: every class on the classpath gets native access, including dependencies that never asked
for it. lmdb-ffm ships real named modules (`module-info.java`) so a modular application can grant it narrowly:

```
java --module-path <mods> --enable-native-access=io.github.dfa1.lmdb -m com.example.app/com.example.app.Main
```

A jar's manifest can carry `Enable-Native-Access: ALL-UNNAMED`, but the JDK honors it only for the jar launched
with `java -jar`, and a library on someone else's classpath can't edit that manifest. So a library has two
levers: document the flag, and ship named modules so the grant can be narrow.

**Sources and examples**

- [JEP 472: Prepare to Restrict the Use of JNI](https://openjdk.org/jeps/472).

### Item 20: Extract the bundled library once, into a directory only you can write {#item-20}

The dynamic loader opens files by path, and an entry inside a jar isn't one, so a library bundled in a jar has
to be copied to disk before `SymbolLookup.libraryLookup` can load it. None of the three projects lets a caller
point at an arbitrary path. A configurable `-Dlib.path` would mean loading whatever file a caller, or anyone who
can set a system property, names, which is native code execution inside the JVM process. All three extract their
own bundled library from a native-classifier jar on the classpath instead, once, at class-init time:

```java
private static final SymbolLookup LIB = SymbolLookup.libraryLookup(extractBundledLib(), Arena.ofAuto());
```

Use `Arena.ofAuto()` here. The library has to outlive every downcall handle bound against it for the life of the
process, and there is no natural point at which "close" would mean anything. So the real choice is between an
auto arena nobody closes and the global arena, which is never closed by design; the auto arena also lets the
library unload with its class loader.

What happens to the extracted *file* depends on the OS:

- **Linux and macOS: a fresh owner-only temp directory per run.** Extract into an `rwx------` directory and
  `deleteOnExit()` it. The owner-only permission is the point: on a shared machine, it closes the window
  between extraction and `dlopen` in which another local user could swap the file for their own. POSIX lets a
  process delete a library it has loaded, so nothing lingers.
- **Windows: a file named after its hash.** A loaded DLL stays locked for the life of the process, so
  `deleteOnExit()` silently fails, and a fresh temp-file name every run leaks a copy per run. Name the file
  after the SHA-256 of its bytes instead and move it into place atomically. A second run of the same version
  reuses the file the first run still holds open, so nothing new leaks; an upgrade gets a new hash, so it can't
  collide with an older process still running; and if another process's move wins the race, use whatever is
  already there.

**What protects the file.** Between extraction and `dlopen`, whoever can write to that file runs code inside
your JVM, so the defense is controlling who can write there. In order of strength:

1. **A private directory.** Create it yourself, owner-only (`rwx------`), per user: other users can't create,
   replace, or rename anything inside it. With a private directory the file's name hardly matters. On Windows
   use a per-user location, such as the user's local app-data directory, not the shared temp directory.
2. **Exclusive creation.** Create the file with `CREATE_NEW` and owner-only permissions, so nobody can plant a
   file at that path first.
3. **An unpredictable name.** A bonus, not a defense: a random name in a directory others can write to protects
   nothing without the two steps above.

The hash-named file can't have a random name, since a stable name is the point. It relies on step 1, and it
re-hashes an existing file before loading it. The hash check alone is not enough: in a writable directory the
file can be swapped between the check and `dlopen`. Treat the hash as a second layer on top of the private
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

Windows: a hash-named file in a per-user directory, re-hashed before reuse:

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

**Sources and examples**

- [`NativeLibrary`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/NativeLibrary.java), rocksdb-ffm.

### Item 21: Pick the symbol lookup per symbol {#item-21}

All three projects bind their own downcall handles through the library they just loaded:

```java
LINKER.downcallHandle(LIB.find(name).orElseThrow(...), fd);
```

That is `libraryLookup`: an explicit path, an explicit library. It isn't the only option. rocksdb-ffm reaches
for a different one where the C contract requires it. RocksDB's merge-operator callback hands back a buffer that
RocksDB itself later releases with a plain `free()`, so that buffer has to come from the allocator RocksDB's
`free()` expects: not from wherever `librocksdb`'s own internal allocator happens to be, and never from
JVM-managed memory. rocksdb-ffm binds libc's `malloc` directly for this one callback, via
`Linker.nativeLinker().defaultLookup()`, the process's own global symbols, resolved without loading
anything:

```java
private static final MethodHandle MH_MALLOC = Linker.nativeLinker().downcallHandle(
        Linker.nativeLinker().defaultLookup().find("malloc")
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: malloc")),
        FunctionDescriptor.of(ValueLayout.ADDRESS, SIZE_T));
```

The third option, `SymbolLookup.loaderLookup()`, resolves symbols from whatever some other component already
loaded via `System.load`/`loadLibrary`. It doesn't come up in any of the three projects, because none of them
share a load with anything else. The choice between the three is one question, asked per symbol: does it live in
the library this binding just loaded, somewhere already in the process, or in a library someone else is
responsible for loading? Get that answer right for each symbol, and the rest is mechanical.

**Sources and examples**

- [`MergeOperator.MH_MALLOC`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/main/java/io/github/dfa1/rocksdbffm/MergeOperator.java), rocksdb-ffm.

### Item 22: Test the ownership rules, not just the happy path {#item-22}

Three hundred native functions and a handful of ownership rules is also three hundred places a test suite has to
prove the rules hold, not just that the happy path returns the right bytes. The tests split cleanly by what they
need in order to run at all.

**Concurrency and ownership invariants need no native library.** [Item 4](#item-4)'s whole argument, that `close()`
releases its native resource exactly once no matter how many times or from how many threads it is called, is a
claim about `NativeObject`'s own Java code, not about LMDB or RocksDB. lmdb-ffm tests it that way: a fake
`NativeObject` subclass records how many times its `tryClose` ran, raced from 32 threads through a
`CountDownLatch` barrier so they all call `close()` at once:

```java
@Test
void concurrentCloseReleasesExactlyOnce() throws Exception {
    TestObject sut = new TestObject(POINTER);
    int threads = 32;
    // ... 32 threads wait on a latch, then all call sut.close() at once ...
    assertThat(sut.closeCount.get()).isEqualTo(1);
}
```

No `Arena`, no downcall, no real pointer; `POINTER` is a `MemorySegment` that is never dereferenced. That is
what makes this a *unit* test of the ownership machinery rather than an integration test of LMDB: it runs in
milliseconds, on every platform, with no native library to link against, and it would catch a regression in the
atomic-swap logic long before a flaky native crash would.

**An ownership tree needs one test per edge, against the real library.** The registration rule from [Item 6](#item-6)
lives at every call site that creates a child, not in `NativeObjectWithChildren`, so a unit test of the base
class can't see a child that forgot to register. rocksdb-ffm's `TransactionIsChildOfDbTest` runs the same
scenarios over both transaction types: parent closed first, child closed first, parent closed twice, fifty
children coming and going, a grandchild (an iterator or a snapshot) closed with its transaction, and the parent
closed with the whole chain still open. Each asserts `IllegalStateException` on the child afterward, not merely
"didn't crash". That assertion is what makes the test writable at all: a use-after-free is a nondeterministic
crash, but a registered child turns it into a deterministic exception you can put an `assertThat`
on.

**Round-trip correctness needs the real library, and needs to sweep the parameter space rather than sample
it.** zstd-ffm's compression tests are parameterized over every `ZstdCompressionLevel`, not just the default:

```java
@ParameterizedTest
@MethodSource("io.github.dfa1.zstd.ZstdTestSupport#levels")
void roundTripAtEveryLevel(ZstdCompressionLevel level) {
    byte[] original = "payload-data-".repeat(500).getBytes(StandardCharsets.UTF_8);
    byte[] restored = Zstd.decompress(Zstd.compress(original, level));
    assertThat(restored).as("level %s", level).isEqualTo(original);
}
```

One level passing doesn't say much about the binding. Every level failing the same way points at the binding;
a single level failing alone points at the library. The parameterization is what makes the failure diagnosable,
not just detectable.

**Fault injection has to happen at a boundary the C API exposes, not one you invent.** RocksDB's C API has no
callback-based custom `Env`, so there is no extension point to hand it a fake filesystem that fails on command.
Mocking one would test the mock, not the binding. rocksdb-ffm injects a real fault instead: it revokes write
permission on the database directory and lets the next flush hit a genuine `EACCES` from the
OS. The binding either turns that into a `RocksDBException` correctly or it doesn't; there is
no simulated layer in between to get subtly wrong.

**Sources and examples**

- [`NativeObjectTest.concurrentCloseReleasesExactlyOnce`](https://github.com/dfa1/lmdb-ffm/blob/main/lmdb/src/test/java/io/github/dfa1/lmdb/NativeObjectTest.java), lmdb-ffm.
- [`TransactionIsChildOfDbTest`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/test/java/io/github/dfa1/rocksdbffm/TransactionIsChildOfDbTest.java), rocksdb-ffm.
- [`FaultInjectionTest`](https://github.com/dfa1/rocksdb-ffm/blob/main/core/src/test/java/io/github/dfa1/rocksdbffm/FaultInjectionTest.java), rocksdb-ffm.

### Item 23: Ship one Java artifact and cross-compile the native library with Zig {#item-23}

Half of what makes JNI bindings hard to maintain isn't the C glue, it is shipping a compiled shim per platform.
Every target you didn't build for is an `UnsatisfiedLinkError` for somebody, and the matrix only grows.

FFM removes the per-platform *Java* artifact: the glue is ordinary Java, compiled once. What remains is the C
library, and that is where cross-compilation usually turns into a matrix of OS-specific CI runners, Docker
images, and hand-assembled sysroots. With Zig as the compiler, that problem shrinks to a `-target` flag, and
that is a large part of the practical benefit of an FFM binding.

`zig cc` is a drop-in replacement for `cc` that bundles clang, its own libc, and the headers for every target
Zig supports, inside the toolchain download itself. `zstd-ffm` skips zstd's build system entirely: the sources
are vendored as plain `.c`, and the script globs and compiles them directly.

```bash
# scripts/build-zstd.sh
export CC="zig cc -target $ZIG_TARGET"
SRCS=$(find "$ZSTD_LIB/common" "$ZSTD_LIB/compress" "$ZSTD_LIB/decompress" \
            "$ZSTD_LIB/dictBuilder" -name '*.c' | sort)
...
zig cc -target "$ZIG_TARGET" $CFLAGS -c "$src" -o "$out"
```

Once cross-compiling is just a `-target` string, adding a platform is a line in a `case` statement, not a new
CI runner. `zstd-ffm` maps six classifiers to six Zig target triples:

```bash
osx-aarch64)     ZIG_TARGET="aarch64-macos"
osx-x86_64)      ZIG_TARGET="x86_64-macos"
linux-x86_64)    ZIG_TARGET="x86_64-linux-gnu"
linux-aarch64)   ZIG_TARGET="aarch64-linux-gnu"
windows-x86_64)  ZIG_TARGET="x86_64-windows-gnu"
windows-aarch64) ZIG_TARGET="aarch64-windows-gnu"
```

That last pair is the one that stood out: a working Windows `.dll`, PE export table and all, produced from a
Linux or macOS runner. No MinGW install, no Wine, no Windows box anywhere in the pipeline. Neither this project
nor its C++ sibling [rocksdb-ffm](https://github.com/dfa1/rocksdb-ffm) runs one job per OS to get there;
one host builds every target, because nothing about the build depends on which OS it runs on.

One note: the Linux targets are `-gnu`, not `-musl`. The `.so` is loaded into a glibc-based JVM, so it
dynamically links glibc and expects a compatible one on the runtime host.

Two more things to pin down for a release pipeline, not just a local build:

- **The `zig cc` version itself.** A toolchain upgrade changing codegen or its bundled libc between one release
  and the next is exactly the kind of drift a build matrix is supposed to prevent. Pin the Zig version through
  the `version` input of `mlugg/setup-zig`, and pin the action itself to a commit SHA rather than a floating tag
  (both projects do the latter), for the same reason a `pom.xml` pins dependency versions instead of ranges.
- **Self-contained inputs.** Compiler, linker, and libc headers all come out of the one Zig download, so the
  build never reads the host's `/usr/include`, system `gcc`, or sysroot. The inputs are the vendored sources
  plus a pinned Zig version, on a laptop and on CI alike. That is what lets one job build all six targets, and
  it is also why the glibc floor above belongs to the toolchain rather than to whichever runner image happens to
  be current. Bit-for-bit identical output is a separate property, and not one this item claims.

Uber has compiled every line of C/C++ in its Go monorepo with `zig cc`, for both x86_64 and arm64, since
January 2023. The same case gets made for other languages too.

**Sources and examples**

- Both projects run their native builds under [`mlugg/setup-zig`](https://github.com/mlugg/setup-zig) in GitHub Actions; the classifier matrix is a loop over `-target` strings inside one job, not one job per OS.
- Uber Engineering, [*Bootstrapping Uber's Infrastructure on arm64 with Zig*](https://www.uber.com/us/en/blog/bootstrapping-ubers-infrastructure-on-arm64-with-zig/) (2023).
- Loris Cro, [*Zig Makes Go Cross Compilation Just Work*](https://dev.to/kristoff/zig-makes-go-cross-compilation-just-work-29ho): the same claim, and title, shows up for Rust and other languages once people reach for `zig cc` as their C toolchain.

---

## Changelog

- **1.0**, 22 August 2026: first edition.
