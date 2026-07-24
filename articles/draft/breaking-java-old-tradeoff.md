# Breaking Java's Old Trade-off

*24 July 2026*

*For years, getting native-level performance out of Java meant reaching for `sun.misc.Unsafe` or writing JNI glue — trading safety and a sane build for speed. The Foreign Function & Memory API removes that trade-off, and once [Zig](https://ziglang.org/) is the C compiler, even the native build stops being a problem. This is what I learned building [rocksdbffm](https://github.com/dfa1/rocksdbffm), [zstd-java](https://github.com/dfa1/zstd-java), and a couple of smaller experiments.*

---

## Beyond `Unsafe` and JNI

Historically, extreme performance in Java meant picking your poison:

* `sun.misc.Unsafe` gave you raw off-heap pointers with zero bounds checking, silent memory corruption on a mistake, and a hard dependency on an unsupported internal API.
* JNI meant a C/C++ bridge layer, a cross-compilation toolchain, and blind spots where a single mismanaged pointer crashed the whole JVM.

The [Foreign Function & Memory (FFM) API](https://openjdk.org/jeps/454) replaces both with one standard model. `rocksdbffm` (C++) and `zstd-java` (C) use it to drop JNI and `Unsafe` entirely: they bind directly to the native headers and get off-heap access with explicit spatial bounds, temporal lifecycles, and thread confinement enforced by the JVM.

## Skip the sysroot

FFM handles the calling side. The other half of wrapping a C library is building that C library, and that is where cross-compilation usually turns into a matrix of OS-specific CI runners, Docker images, and hand-assembled sysroots. With Zig as the compiler, that whole problem mostly disappears.

`zig cc` and `zig c++` are drop-in replacements for `cc`/`c++` that bundle clang, their own libc, and the headers for every target Zig supports — inside the Zig toolchain download itself. Point `CC` at `zig cc -target aarch64-linux-gnu` from any host, macOS or Linux, and it just works: no `apt install gcc-aarch64-linux-gnu`, no manually assembled sysroot, no target-specific Docker image.

`rocksdbffm` uses it as a drop-in for RocksDB's own `Makefile`-driven build:

```bash
# scripts/build-rocksdb.sh
export CC="zig cc -target $ZIG_TARGET"
export CXX="zig c++ -target $ZIG_TARGET"
export PORTABLE=1
```

`zstd-java` skips the C build system entirely — `zstd` is vendored as plain source, and the script globs the `.c` files and compiles each one directly:

```bash
# scripts/build-zstd.sh
SRCS=$(find "$ZSTD_LIB/common" "$ZSTD_LIB/compress" "$ZSTD_LIB/decompress" \
            "$ZSTD_LIB/dictBuilder" -name '*.c' | sort)
...
zig cc -target "$ZIG_TARGET" $CFLAGS -c "$src" -o "$out"
```

Once cross-compiling is just a `-target` string, adding a platform is a line in a `case`
statement, not a new CI runner. `zstd-java` maps six classifiers to six Zig target triples:

```bash
osx-aarch64)     ZIG_TARGET="aarch64-macos"
osx-x86_64)      ZIG_TARGET="x86_64-macos"
linux-x86_64)    ZIG_TARGET="x86_64-linux-gnu"
linux-aarch64)   ZIG_TARGET="aarch64-linux-gnu"
windows-x86_64)  ZIG_TARGET="x86_64-windows-gnu"
windows-aarch64) ZIG_TARGET="aarch64-windows-gnu"
```

That last pair is the one that stood out: a working Windows `.dll`, correctly populated PE
export table included, produced from a Linux or macOS runner — no MinGW install, no Wine, no
Windows box anywhere in the pipeline. `rocksdbffm` covers the four Unix classifiers the same
way. Neither project runs one job per OS to get there[^ci]; one host builds every target,
because nothing about the build depends on which OS it's running on.

One caveat: both target `x86_64-linux-gnu`/`aarch64-linux-gnu`, not `-musl`. The resulting
`.so` still dynamically links glibc and expects a compatible one on the runtime host — Zig can
target `-musl` for a fully static, distro-independent Linux binary, but neither project takes
that step here.

The best compliment I can give a build tool is that it stopped being interesting to think
about. And it is not just two small libraries: Uber has compiled every line of C/C++ in its Go
monorepo with `zig cc`, for both x86_64 and arm64, since January 2023[^uber]. The experience
generalizes past C, too — swap the language and the same essay gets written again[^justwork].

## Compile-time guarantees over runtime failures

Replacing the low-level mechanics is only half the point; the other half is using modern Java idioms to make bad states impossible to represent. In a traditional native wrapper, calling a write on a read-only handle compiles fine and fails at runtime. Domain primitives, records, and tight class hierarchies lift those checks to compile time.

That is the idea behind [refined-type](https://github.com/dfa1/refined-type), an experiment built on Project Valhalla previews that pushes domain constraints into the type system. Instead of passing an arbitrary `long` for a block size and hoping, validation happens at instantiation and illegal states stop compiling.

## Native throughput without native code

Aligning Java abstractions with hardware realities — mechanical sympathy — sometimes means you do not need native code at all. [vortex-java](https://github.com/dfa1/vortex-java) is a pure-Java implementation with no native bindings, no JNI, and no `Unsafe`. It relies on `MemorySegment` and zero-copy semantics to move data.

The point of all this is stewardship. Scaling horizontally by throwing cloud nodes at inefficient code buys time while inflating the bill. Rewriting hot paths around lean, zero-copy layouts cuts the compute footprint and keeps the code safe, readable, and idiomatic for everyone else on the team.

## The path forward

"Java is slow," "off-heap requires `Unsafe`," "native integration is dangerous" — all of it belongs in the past. Modern Java lets you write bare-metal, low-overhead systems code behind safe, strongly typed interfaces, and cross-compile it from a laptop. If you are building data pipelines, storage layers, or high-frequency systems, look at your hot paths: you do not need unsupported internal APIs, and you do not need to accept GC overhead as inevitable.

---

[^ci]: Both projects run their native builds under [`mlugg/setup-zig`](https://github.com/mlugg/setup-zig) in GitHub Actions; the classifier matrix is a loop over `-target` strings inside one job, not one job per OS.

[^uber]: Uber Engineering, [*Bootstrapping Uber's Infrastructure on arm64 with Zig*](https://www.uber.com/us/en/blog/bootstrapping-ubers-infrastructure-on-arm64-with-zig/) (2023).

[^justwork]: Loris Cro, [*Zig Makes Go Cross Compilation Just Work*](https://dev.to/kristoff/zig-makes-go-cross-compilation-just-work-29ho) — the same claim, and title, shows up for Rust and other languages once people reach for `zig cc` as their C toolchain.
