# Skip the Sysroot

*12 July 2026*

*[rocksdbffm](https://github.com/dfa1/rocksdbffm) and [zstd-java](https://github.com/dfa1/zstd-java)
both wrap a C library for Java over the [Foreign Function & Memory API](https://openjdk.org/jeps/454),
and both build that C library themselves as part of the Maven build. The part that surprised me
was how boring cross-compilation became once [Zig](https://ziglang.org/) was the compiler —
one `-target` string, no sysroot, no Docker, no matrix of OS-specific CI runners.*

---

## One compiler, every target

`zig cc` and `zig c++` are drop-in replacements for `cc`/`c++` that happen to bundle clang,
their own libc, and the headers for every target Zig supports — inside the Zig toolchain
download itself. Point `CC` at `zig cc -target aarch64-linux-gnu` from any host, macOS or
Linux, and it just works: no `apt install gcc-aarch64-linux-gnu`, no manually assembled
sysroot, no target-specific Docker image.

`rocksdbffm` uses it as a drop-in for RocksDB's own `Makefile`-driven build:

```bash
# scripts/build-rocksdb.sh
export CC="zig cc -target $ZIG_TARGET"
export CXX="zig c++ -target $ZIG_TARGET"
export PORTABLE=1
```

`zstd-java` skips the C build system entirely — `zstd` is vendored as plain source, and the
script globs the `.c` files and compiles each one directly:

```bash
# scripts/build-zstd.sh
SRCS=$(find "$ZSTD_LIB/common" "$ZSTD_LIB/compress" "$ZSTD_LIB/decompress" \
            "$ZSTD_LIB/dictBuilder" -name '*.c' | sort)
...
zig cc -target "$ZIG_TARGET" $CFLAGS -c "$src" -o "$out"
```

## Six targets, one host

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
that extra step here.

## Closing

The best compliment I can give a build tool is that it stopped being interesting to think
about. `-target x86_64-windows-gnu` from a MacBook, and out comes a `.dll` — no sysroot to
assemble, no container to maintain, no separate CI job to keep green. That's what "just works"
is supposed to feel like — and it's not just two small libraries: Uber has compiled every line
of C/C++ in its Go monorepo with `zig cc`, for both x86_64 and arm64, since January
2023[^uber]. The experience generalizes past C, too — swap the language and the same essay
gets written again[^justwork].

---

[^ci]: Both projects run their native builds under [`mlugg/setup-zig`](https://github.com/mlugg/setup-zig) in GitHub Actions; the classifier matrix is a loop over `-target` strings inside one job, not one job per OS.

[^uber]: Uber Engineering, [*Bootstrapping Uber's Infrastructure on arm64 with Zig*](https://www.uber.com/us/en/blog/bootstrapping-ubers-infrastructure-on-arm64-with-zig/) (2023).

[^justwork]: Loris Cro, [*Zig Makes Go Cross Compilation Just Work*](https://dev.to/kristoff/zig-makes-go-cross-compilation-just-work-29ho) — the same claim, and title, shows up for Rust and other languages once people reach for `zig cc` as their C toolchain.
