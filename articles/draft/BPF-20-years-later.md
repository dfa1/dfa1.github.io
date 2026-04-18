# From Raw Bytecode to eBPF: the Same Idea, Twenty Years Apart

*April 2026*

In 2004 I wrote a packet sniffer called
[pangolin](https://github.com/dfa1/pangolin). It filtered network traffic
using BPF — Berkeley Packet Filter. To select only UDP packets I wrote this:

```c
struct sock_filter UDP_code[] = {
    {0x28, 0, 0, 0x0000000c},
    {0x15, 0, 3, 0x00000800},
    {0x30, 0, 0, 0x00000017},
    {0x15, 0, 1, 0x00000011},
    {0x6,  0, 0, 0x00000044},
    {0x6,  0, 0, 0x00000000}
};
```

Raw bytecode. No compiler, no abstraction. I wrote the instructions for a
virtual machine running inside the Linux kernel by hand.

Twenty years later, I compiled pangolin on kernel 6.17 on ARM64. One
type fix — `size_t` → `socklen_t` — and it ran. The bytecode in
`filters.c` was untouched.

That is the thesis of this post: **cBPF and eBPF are the same idea at
different abstraction levels**. The mental model has not changed. The
tooling around it has changed enormously.

---

## What cBPF actually is

BPF was introduced in 1992. The [kernel documentation](https://www.kernel.org/doc/Documentation/networking/filter.txt)
describes a simple virtual machine with two registers, a small set of
opcodes, and a verifiable property: every program terminates. The guarantee
comes from a structural constraint: the jump offsets `jt` and `jf` are
unsigned, so jumps can only go forward. No backward branches means no loops,
and with a fixed instruction count the program must halt in bounded time.
The kernel can run it safely.

Each instruction is four fields:

```c
struct sock_filter {
    __u16 code;   /* opcode */
    __u8  jt;     /* jump offset if true */
    __u8  jf;     /* jump offset if false */
    __u32 k;      /* constant */
};
```

So `{0x28, 0, 0, 0x0000000c}` means: load a halfword (`0x28`) from offset
12 (`0x0000000c`) of the packet. Offset 12 is the EtherType field in an
Ethernet frame.

The magic numbers become readable if you name them:

```c
#define ETHERTYPE_OFFSET  0x0000000c
#define ETHERTYPE_IPV4    0x00000800
#define IP_PROTOCOL_OFFSET 0x00000017
#define PROTO_UDP         0x00000011
#define ACCEPT            0x00000044
#define REJECT            0x00000000

#define BPF_LD_H  0x28   /* load halfword */
#define BPF_JEQ   0x15   /* jump if equal */
#define BPF_LD_B  0x30   /* load byte */
#define BPF_RET   0x6    /* return */

struct sock_filter UDP_code[] = {
    {BPF_LD_H, 0, 0, ETHERTYPE_OFFSET},
    {BPF_JEQ,  0, 3, ETHERTYPE_IPV4},
    {BPF_LD_B, 0, 0, IP_PROTOCOL_OFFSET},
    {BPF_JEQ,  0, 1, PROTO_UDP},
    {BPF_RET,  0, 0, ACCEPT},
    {BPF_RET,  0, 0, REJECT}
};
```

Now it reads like prose. Load EtherType, check for IPv4, load protocol
byte, check for UDP, accept or reject. The instructions did not change —
only the names did. The same insight as naming a magic `int` `MarketId`
instead of leaving it as a raw number.

You attach this program to a socket with:

```c
struct sock_fprog prog = {
    .len    = sizeof(UDP_code) / sizeof(UDP_code[0]),
    .filter = UDP_code,
};
setsockopt(fd, SOL_SOCKET, SO_ATTACH_FILTER, &prog, sizeof(prog));
```

The kernel loads the bytecode, verifies it terminates, and runs it for
every packet before copying anything to userspace. Packets that fail the
filter are dropped in the kernel — your application never sees them.

---

## What the kernel does with cBPF today

Since Linux 3.15, the kernel translates cBPF to eBPF internally before
execution. When you call `setsockopt(SO_ATTACH_FILTER)` with your 1992
bytecode, the kernel:

1. Translates it to eBPF bytecode
2. Runs it through the eBPF verifier
3. JIT-compiles it to native machine code
4. Runs it

Your 2004 program runs on a 2025 kernel. The API is stable — removing it
would break `tcpdump`, `libpcap`, and everything built on top of them.

---

## What eBPF adds

eBPF (extended BPF, introduced in Linux 3.18, 2014) keeps the same core
idea — bytecode in a kernel sandbox — and adds:

- **64-bit registers** (cBPF had two; eBPF has eleven)
- **BPF maps** — shared memory between kernel and userspace
- **Multiple hook points** — not just sockets; kprobes, tracepoints,
  network hooks, perf events
- **A real verifier** — static analysis that proves memory safety, bounds,
  and termination
- **JIT compilation** on all major architectures

The verifier is what makes the rest possible. It checks every path through
the program: no out-of-bounds memory access, no null pointer dereference,
no unbounded loops. If the program passes verification, the kernel
guarantees it cannot crash or compromise the system.

You no longer write bytecode by hand. You write C, and Clang compiles it
to BPF bytecode.

---

## A modern DNS sensor

Here is the UDP port 53 filter from pangolin, rewritten as a modern eBPF
tracepoint in Python:

```python
from bcc import BPF
import socket
import struct

prog = """
#include <uapi/linux/ptrace.h>
#include <uapi/linux/in.h>

TRACEPOINT_PROBE(syscalls, sys_enter_sendto) {
    if (args->addr == NULL) return 0;

    struct sockaddr_in addr = {};
    bpf_probe_read_user(&addr, sizeof(addr), args->addr);

    if (addr.sin_family != AF_INET) return 0;
    if (addr.sin_port != bpf_htons(53)) return 0;

    u32 pid = bpf_get_current_pid_tgid() >> 32;
    u32 ip  = addr.sin_addr.s_addr;
    bpf_trace_printk("DNS pid=%d ip=%x\\n", pid, ip);
    return 0;
}
"""

b = BPF(text=prog)

for fields in b.trace_fields():
    msg = fields.decode(errors='replace') if isinstance(fields, bytes) else str(fields)
    if 'DNS' not in msg:
        continue
    data = dict(part.split('=') for part in msg.split() if '=' in part)
    ip_str = socket.inet_ntoa(struct.pack('<I', int(data['ip'], 16)))
    print(f"DNS query  pid={data['pid']}  dst={ip_str}")
```

The C code runs inside the kernel. Python runs in userspace. `bcc`
compiles the C string at runtime using LLVM/Clang and loads the bytecode
via the `bpf()` syscall. The same syscall your `setsockopt` ultimately
reaches, just with a much richer API on top.

Note `bpf_htons(53)` — network byte order conversion. In 2004 I wrote
`__builtin_bswap16(53)` which is subtly wrong on little-endian
architectures. The BPF helper function is the right call.

---

## The pipeline

A sensor that prints to stdout is a toy. A sensor that ships events to a
pipeline is useful. The same design principle that applies to financial
market data pipelines applies here: separate the I/O boundaries, make the
logic testable, inject dependencies.

```python
from abc import ABC, abstractmethod
from typing import Iterator
from dataclasses import dataclass

@dataclass(frozen=True)
class KernelEvent:
    timestamp: int    # nanoseconds since boot
    pid: int
    process: str
    payload: str

class EventSource(ABC):
    @abstractmethod
    def events(self) -> Iterator[KernelEvent]:
        pass

class EventSink(ABC):
    @abstractmethod
    def send(self, event: dict) -> None:
        pass
```

`BpfEventSource` runs on Linux. `ReplayEventSource` replays recorded
events from a file — runs on macOS, useful for debugging. `GeneratorEventSource`
yields synthetic events for unit tests. `KafkaEventSink` ships to Kafka.
`InMemoryEventSink` collects events in a list for testing.

The BPF layer never appears in a test. It is just another I/O boundary.

---

## From printk to ring buffer

The DNS sensor uses `bpf_trace_printk` — a formatted string written to
`/sys/kernel/debug/tracing/trace_pipe`. That is fine for a toy. For
production you want structured binary events, and that means a ring buffer.

`BPF_MAP_TYPE_RINGBUF` (Linux 5.8) is shared memory between kernel and
userspace. When the BPF program calls `bpf_ringbuf_reserve()`, it gets a
pointer into that shared region and writes the struct directly. Userspace
reads the same bytes — no serialization, no copy, just a cast.

Each hook point gets its own struct, but all share a common header:

```c
struct event_header {
    __u64 timestamp_ns;
    __u32 pid;
    __u32 tid;
    __u32 uid;
    __u32 gid;
    char  comm[16];
    __u32 type;        /* EVENT_OPENAT, EVENT_CONNECT, ... */
};

struct openat_event {
    struct event_header hdr;
    int  dirfd;
    int  flags;
    int  ret;
    char filename[256];
};
```

Userspace reads the type field and dispatches:

```c
static int handle_event(void *ctx, void *data, size_t sz)
{
    struct event_header *hdr = data;
    switch (hdr->type) {
    case EVENT_OPENAT:  handle_openat((struct openat_event *)data);  break;
    case EVENT_CONNECT: handle_connect((struct connect_event *)data); break;
    }
    return 0;
}
```

This is what `BpfEventSource.events()` wraps: `ring_buffer__poll()` calls
`handle_event` for each record, which translates the binary struct into a
`KernelEvent` and yields it upstream.

Production systems add three refinements on top of this skeleton:

- **In-kernel filtering**: check a BPF map (pid allowlist, path prefix)
  before calling `bpf_ringbuf_reserve`. Events that do not match never
  cross the kernel/userspace boundary.
- **Back-pressure**: if `bpf_ringbuf_reserve` returns NULL the buffer is
  full. The BPF program increments a counter in a separate map; userspace
  reads it to distinguish "quiet" from "dropping".
- **Aggregation**: instead of emitting every event, accumulate counts or
  histograms in a `BPF_MAP_TYPE_PERCPU_HASH` and read periodic snapshots.
  This is how profilers like Parca avoid per-event overhead entirely.

The pattern is always the same: push decisions into the BPF program to
reduce the volume crossing the boundary, then handle only what matters in
userspace.

---

## Observability without overhead

The aggregation point above extends further: BPF can build performance
profiles and latency distributions entirely in the kernel, surfacing only
summaries to userspace.

**CPU profiling.** Attach a BPF program to a `perf_event` firing at fixed
frequency (99 Hz is conventional — avoids lockstep with 100 Hz timers).
On each sample, `bpf_get_stackid()` hashes the current call stack and
stores it as a key in a map; the value is a hit counter. Userspace reads
the map periodically, resolves stack IDs to symbols, and builds a flame
graph. No per-sample ring buffer traffic — just a snapshot of accumulated
state. This is how Parca and Pyroscope work.

**Latency histograms.** Record a timestamp on syscall entry, compute the
delta on exit, and increment a histogram bucket:

```c
// sys_enter_read: stash timestamp by pid
__u64 ts = bpf_ktime_get_ns();
bpf_map_update_elem(&start, &pid, &ts, BPF_ANY);

// sys_exit_read: compute delta, increment log2 bucket
__u64 *tsp = bpf_map_lookup_elem(&start, &pid);
__u64  delta_us = (bpf_ktime_get_ns() - *tsp) / 1000;
__u32  bucket = log2l(delta_us);
__sync_fetch_and_add(&hist[bucket], 1);
```

Userspace reads the array and prints a latency distribution — `p50`, `p99`,
`p999` — without ever seeing individual events. The BCC toolkit ships
`biolatency` (disk I/O) and `runqlat` (scheduler queue wait) built on this
pattern.

**Scheduling latency.** The `sched_wakeup` and `sched_switch` tracepoints
let you measure how long a process waited in the run queue before the
scheduler gave it CPU time. Average CPU utilization can look fine while
tail latency is high; run queue wait catches that.

Memory is the weakest area: there is no helper to read RSS or heap size
directly. Hooking `kmalloc`/`kfree` or the `mm_page_alloc` tracepoint
works but building a complete picture is involved. Production memory
profilers tend to rely on language-level hooks or `/proc` instead.

The common thread: BPF aggregates in the kernel. Histograms and counters
live in BPF maps; userspace reads the final numbers. O(1) traffic across
the boundary regardless of event volume.

---

## What changed in twenty years

The bytecode VM is the same. What changed:

| 2004 | 2025 |
|------|------|
| Hand-written opcodes | C compiled by Clang |
| `SO_ATTACH_FILTER` | `bpf()` syscall |
| Socket filter only | kprobes, tracepoints, network, perf |
| No shared state | BPF maps |
| Trust the programmer | Verifier proves safety |
| Packets drop under load | Ring buffers, no drops |

The mental model — a verified bytecode program running in a kernel
sandbox — has not changed. The kernel just got smarter about what it does
with the bytecode underneath.

---
The analogy for java  world:
- eBPFJavabpf_trace_printk   System.out.println
-perf buffer LinkedBlockingQueue per thread
- ring buffer   Disruptor — single ring, multiple producers, ordered

You'd never build a financial market data pipeline on System.out.println. Same reasoning applies here.

---

## eBPF in production

The same architecture appears across the cloud-native ecosystem, at
different points on the observability-to-enforcement spectrum.

**[Cilium](https://cilium.io)** replaces iptables as the Kubernetes CNI.
Rather than maintaining netfilter rules that scale poorly with pod count, it
programs eBPF maps directly in the kernel network path. XDP (eXpress Data
Path) — the lowest eBPF hook, running before the kernel's network stack
processes the packet — handles load balancing at line rate.

**[Falco](https://falco.org)** (CNCF) captures system calls via an eBPF
driver and matches them against rules. It focuses on auditability: emit an
alert when a container opens a sensitive file or spawns a shell. The hook is
the same as Tetragon — syscall entry — but Falco predates Tetragon's
enforcement model and stops at detection.

**[Tetragon](https://tetragon.io)**, built on Cilium, goes further: it can
`SIGKILL` a process or drop a connection from inside the eBPF program,
before the syscall returns to userspace. Operators declare policy in a
`TracingPolicy` custom resource; enforcement happens at the kernel hook, not
in a userspace daemon that races against the event.

**[bpftrace](https://github.com/bpftrace/bpftrace)** sits at the opposite
end of the abstraction spectrum — a high-level tracing language that
compiles to eBPF. The visibility Tetragon exposes as a platform, bpftrace
exposes as a one-liner:

```
bpftrace -e 'tracepoint:syscalls:sys_enter_openat { printf("%s %s\n", comm, str(args->filename)); }'
```

No C, no loading bytecode manually. The same idea condensed to a single
expression.

---

## Appendix: fixing a 20-year-old codebase

Compiling pangolin on a modern kernel required one real fix and several
cleanups. In rough order of severity:

**`socklen_t` vs `size_t`** — the only fix required to compile:
```c
// before
size_t errlen = sizeof(err);
// after
socklen_t errlen = sizeof(err);
```
`socklen_t` is `unsigned int` (32-bit); `size_t` is `unsigned long`
(64-bit) on 64-bit platforms. The ABI difference did not exist in 2004.

**`memcpy` buffer overread** — a real bug:
```c
// before: copies 8 bytes from a 4-byte struct
long t;
memcpy(&t, &in, sizeof(long));
args->host = TOHOST32(t);

// after
args->host = ntohl(in.s_addr);
```

**`inet_aton` → `inet_pton`** — `inet_aton` is BSD-specific and never
made POSIX. `inet_pton` handles both IPv4 and IPv6 and has a clearer
return value contract.

**Designated initializers** — C99 syntax that silences missing-field
warnings and makes struct initialization self-documenting:
```c
// before
static struct argp argp = { options, parse_opt, NULL, program_doc };

// after
static struct argp argp = {
    .options  = options,
    .parser   = parse_opt,
    .args_doc = NULL,
    .doc      = program_doc
};
```

**Implicit fallthrough** — add `__attribute__((fallthrough))` where
fallthrough is intentional, or restructure to eliminate it.

The filters themselves — the BPF bytecode in `filters.c` — required no
changes at all.
