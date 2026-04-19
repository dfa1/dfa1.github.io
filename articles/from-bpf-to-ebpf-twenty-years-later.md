# From BPF to eBPF, Twenty Years Later

*April 2026*

In 2004, I wrote a packet sniffer called
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

Raw bytecode. No compiler, no abstraction. Instructions for a virtual
machine running inside the Linux kernel, written by hand.

Twenty years later, I compiled pangolin on kernel 6.17 on ARM64. One
type fix — `size_t` → `socklen_t` — and it ran. The bytecode in
`filters.c` was untouched (see [this commit](https://github.com/dfa1/pangolin/commit/5bee9a49a93ab4b72efb106e10ca70475692c904)).

That got me curious. The bytecode survived twenty years unchanged — what
did the tooling around it become? Brendan Gregg's [eBPF overview](https://www.brendangregg.com/ebpf.html)
was the map I used to find out.

---

## What cBPF actually is

BPF was introduced in the [1993 McCanne & Jacobson paper](https://www.tcpdump.org/papers/bpf-usenix93.pdf).
The [kernel documentation](https://www.kernel.org/doc/html/latest/networking/filter.html)
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

Load EtherType, check for IPv4, load protocol
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

Since [Linux 3.15](https://www.kernel.org/doc/html/latest/networking/filter.html#ebpf-extensions-to-the-socket-interface), the kernel translates cBPF to eBPF internally before
execution. When you call `setsockopt(SO_ATTACH_FILTER)` with your 2004
bytecode, the kernel:

1. Translates it to eBPF bytecode
2. Runs it through the eBPF verifier
3. JIT-compiles it to native machine code
4. Runs it

Early 2000s programs run unchanged on a 2026 kernel. The API held — removing it
would break `tcpdump`, `libpcap`, and everything built on top of them.

---

## What eBPF adds

eBPF (extended BPF, [introduced in Linux 3.18, 2014](https://lwn.net/Articles/599755/)) keeps the same core
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
no unbounded loops. If the program passes, the kernel considers it safe to
run in production — as infrastructure, not as a debugging aid.


---

## A toy DNS sensor

Here is a port 53 sensor rewritten as a modern eBPF tracepoint in Python.
Unlike the original cBPF socket filter — which ran in the network path and
matched on UDP protocol and port — this hooks the `sendto` syscall and checks
the destination port. Any `sendto` to port 53 matches, including TCP.

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

for (task, pid, cpu, flags, ts, msg) in b.trace_fields():
    msg = msg.decode(errors='replace') if isinstance(msg, bytes) else str(msg)
    if 'DNS' not in msg:
        continue
    data = dict(part.split('=') for part in msg.split() if '=' in part)
    ip_str = socket.inet_ntoa(struct.pack('<I', int(data['ip'], 16)))
    print(f"DNS query  pid={data['pid']}  dst={ip_str}")
```

The C code runs inside the kernel. Python runs in userspace. `bcc`
compiles the C string at runtime using LLVM/Clang and loads the bytecode
via the `bpf()` syscall. `setsockopt(SO_ATTACH_FILTER)` and `bpf()` are
separate syscall paths, but both feed the same eBPF subsystem — the same
verifier, the same JIT.

---

## BCC vs libbpf

[BCC](https://github.com/iovisor/bcc) is convenient for exploration. There is no separate build step: the C string compiles at runtime using LLVM/Clang embedded in the BCC library. The costs are real: a full LLVM installation as a runtime dependency, and a compilation delay on first load. Acceptable for a one-shot script; not for a daemon that restarts under load.

[libbpf](https://github.com/libbpf/libbpf) takes the opposite approach ([Brendan Gregg's overview](https://www.brendangregg.com/blog/2020-11-04/bpf-co-re-btf-libbpf.html) is a good introduction): compile ahead of time, ship a binary, load in milliseconds. You compile the BPF program ahead of time:

```sh
clang -O2 -target bpf -c dns_sensor.bpf.c -o dns_sensor.bpf.o
```

The resulting object file ships embedded in your binary. At startup, `bpf_object__open` / `bpf_object__load` loads it in milliseconds — no compiler on the target host.

BTF (BPF Type Format) and [CO-RE](https://nakryiko.com/posts/bpf-portability-and-co-re/) (Compile Once, Run Everywhere) solve the kernel version problem. BPF programs access kernel structs directly, and those structs change across versions. Without CO-RE, you compile once per kernel. With CO-RE, the loader rewrites field offsets at load time to match the running kernel's layout — the same binary runs on 5.15 and 6.9.

One concrete step: `bpftool` can dump the running kernel's BTF data as a C header:

```sh
bpftool btf dump file /sys/kernel/btf/vmlinux format c > vmlinux.h
```

`/sys/kernel/btf/vmlinux` exists on any kernel built with `CONFIG_DEBUG_INFO_BTF=y` — standard on most distributions since 5.8. The resulting `vmlinux.h` contains every struct, enum, and typedef the kernel exposes via BTF. A BPF program can include it once instead of pulling in dozens of per-subsystem headers. The file is large (~6 MB), but it is only needed at compile time — not bundled at runtime. CO-RE then rewrites field offsets at load time to match whatever kernel is actually running on the target host — the compiled object does not need to change.

For what I was doing — writing a sensor to understand the APIs — BCC was the right fit. But reading how production tools are built, the pattern is consistent: BCC for interactive exploration and one-off scripts, `libbpf` for anything that ships. A `DaemonSet` running on a thousand nodes, a security agent that must not pull LLVM into a production image — that is `libbpf` territory.

---

## Abstracting the BPF layer

[I kept following the thread](https://github.com/dfa1/ebpf-sensor): what would a production version of this sensor
look like? A sensor that prints to stdout is a debugging aid. A sensor
that ships events to a Kafka topic is something else — and the design problems
it raises are familiar ones in financial systems.

BPF only runs on Linux, and loading a program requires root. Testing
directly against it means no macOS, no CI without privileged containers,
and no fast iteration loop. The fix I reached for was the same one that
applies to any external I/O dependency: hide it behind an interface. If the BPF layer is
just another `EventSource`, the entire processing pipeline — filtering,
enrichment, correlation, routing — is testable with a `ReplayEventSource`
(this is critical to achieve good test coverage and fast CI builds).

Here are some basic classes:

```python
from typing import Iterator, Protocol
from dataclasses import dataclass

@dataclass(frozen=True)
class Event:
    timestamp: int    # nanoseconds since boot
    pid: int
    process: str
    payload: str      # event-specific data as a string

class EventSource(Protocol):
    def events(self) -> Iterator[Event]: ...

class EventSink(Protocol):
    def send(self, event: Event) -> None: ...
```

```
┌──────────────────────┐      ┌──────────────────────┐
│   BpfEventSource     │      │  ReplayEventSource   │
│  (Linux, root only)  │      │  (anywhere, no root) │
└──────────┬───────────┘      └──────────┬───────────┘
           └────────────────┬────────────┘
                            │ Iterator[Event]
                 ┌──────────▼──────────┐
                 │  pipeline           │
                 │  filter / enrich /  │
                 │  correlate          │
                 └──────────┬──────────┘
              ┌─────────────┴──────────────┐
   ┌──────────▼──────────┐  ┌──────────────▼──────┐
   │   KafkaEventSink    │  │   RecordEventSink   │
   │   (production)      │  │   (tests)           │
   └─────────────────────┘  └─────────────────────┘
```

`BpfEventSource` wraps the DNS tracepoint probe shown earlier:

```python
import socket
import struct
import time
from bcc import BPF

class BpfEventSource(EventSource):
    """Loads the DNS tracepoint probe via BCC. Requires Linux and root."""

    def __init__(self):
        self._bpf = BPF(text=prog)  # prog defined above

    def events(self) -> Iterator[Event]:
        for (task, pid, cpu, flags, ts, msg) in self._bpf.trace_fields():
            msg = msg.decode(errors='replace') if isinstance(msg, bytes) else str(msg)
            parts = dict(p.split('=') for p in msg.split() if '=' in p)
            ip_str = socket.inet_ntoa(struct.pack('<I', int(parts['ip'], 16)))
            yield Event(
                timestamp=time.time_ns(),
                pid=int(parts['pid']),
                process='',
                payload=f"dst={ip_str}",
            )
```

```python
import json
from dataclasses import asdict
from kafka import KafkaProducer

class KafkaEventSink(EventSink):
    def __init__(self, bootstrap_servers: str, topic: str):
        self._producer = KafkaProducer(
            bootstrap_servers=bootstrap_servers,
            value_serializer=lambda v: json.dumps(v).encode(),
        )
        self._topic = topic

    def send(self, event: Event) -> None:
        self._producer.send(self._topic, asdict(event))


class ReplayEventSource(EventSource):
    """Replays events from an NDJSON file. Runs anywhere, no root required."""

    def __init__(self, path: str):
        self._path = path

    def events(self) -> Iterator[Event]:
        with open(self._path) as f:
            for line in f:
                yield Event(**json.loads(line))


class RecordEventSink(EventSink):
    """Collects events in memory. Used in tests for assertion."""

    def __init__(self):
        self.events: list[Event] = []

    def send(self, event: Event) -> None:
        self.events.append(event)
```

`KafkaEventSink` ships to Kafka. `RecordEventSink` collects events in a
list for assertion.

`ReplayEventSource` reads [NDJSON](https://ndjson.com/) recorded
from a previous run — runs on macOS, no kernel access needed.

To record a session for later replay, pipe any `EventSource` through a
`RecordEventSink` that writes one JSON object per line to a file. The same
file feeds `ReplayEventSource` in tests.

The BPF layer never appears in a test: it is just another I/O boundary.
This is the [sans I/O pattern](https://sans-io.readthedocs.io/): implement
protocol logic independently of I/O so it can be composed and tested in
isolation.

Processing logic is testable by feeding events from `ReplayEventSource`
into any `EventSink` — no Kafka, no kernel access needed. The BPF code
itself still requires a privileged test, but that can be isolated from
the rest of the pipeline.

---

## From printk to ring buffer

The DNS sensor uses `bpf_trace_printk`. This writes a formatted string to
`/sys/kernel/debug/tracing/trace_pipe` — a global pipe shared across every
BPF program and the kernel's own ftrace subsystem. Three problems make it
unsuitable for production:

1. **Global mutex.** Only one program can write at a time. Under load, BPF
   programs on multiple CPUs spin waiting for the lock.
2. **Limited arguments.** [`bpf_trace_printk` accepted at most three format
   arguments](https://man7.org/linux/man-pages/man7/bpf-helpers.7.html) before Linux 5.13; the verifier rejects programs that pass more on
   older kernels.
3. **Silent drops.** If the pipe fills faster than userspace reads, events
   disappear with no indication.

The intermediate step was `BPF_MAP_TYPE_PERF_EVENT_ARRAY` (perf buffer) —
per-CPU ring buffers that avoid the global lock. Events arrive out of order
across CPUs, and userspace must poll each CPU's buffer separately —
more complex to consume than a single shared buffer.

[`BPF_MAP_TYPE_RINGBUF`](https://nakryiko.com/posts/bpf-ringbuf/) (Linux 5.8) replaces both. Single buffer, shared
across CPUs, memory-mapped between kernel and userspace. When the BPF
program calls `bpf_ringbuf_reserve()`, it gets a pointer into that shared
region and writes the struct directly. Userspace reads the same bytes via
`ring_buffer__poll()` — no copy, no serialization, no syscall per event.

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

In a libbpf-based implementation, `BpfEventSource.events()` wraps this:
`ring_buffer__poll()` calls `handle_event` for each record, which translates
the binary struct into an `Event` and yields it upstream.

Reading how real tools handle this, I found three refinements that come up consistently:

- **In-kernel filtering**: check a BPF map (pid allowlist, path prefix)
  before calling `bpf_ringbuf_reserve`. Events that do not match never
  cross the kernel/userspace boundary.
- **Back-pressure**: if `bpf_ringbuf_reserve` returns NULL the buffer is
  full. The BPF program increments a counter in a separate map; userspace
  reads it to distinguish "quiet" from "dropping".
- **Aggregation**: instead of emitting every event, accumulate counts or
  histograms in a `BPF_MAP_TYPE_PERCPU_HASH` and read periodic snapshots.
  This is how profilers like Parca avoid per-event overhead entirely.

The pattern I kept seeing: push decisions into the BPF program to reduce
the volume crossing the boundary, then handle only what matters in
userspace.

---

## Observability without overhead

That aggregation idea extends further than I first expected. BPF can build
performance profiles and latency distributions entirely in the kernel,
surfacing only summaries to userspace.

**CPU profiling.** Attach a BPF program to a `perf_event` firing at fixed
frequency ([99 Hz is conventional](https://www.brendangregg.com/perf.html#CPU) — avoids lockstep with 100 Hz timers).
On each sample, `bpf_get_stackid()` hashes the current call stack and
stores it as a key in a map; the value is a hit counter. Userspace reads
the map periodically, resolves stack IDs to symbols, and builds a flame
graph. No per-sample ring buffer traffic — just a snapshot of accumulated
state. Sound familiar? That is roughly how continuous profilers like
[Parca](https://www.parca.dev/) and [Pyroscope](https://grafana.com/oss/pyroscope/) are built on top of eBPF.

**Latency histograms.** Record a timestamp on syscall entry, compute the
delta on exit, and increment a histogram bucket:

```c
// sys_enter_read: stash timestamp by pid
__u64 ts = bpf_ktime_get_ns();
bpf_map_update_elem(&start, &pid, &ts, BPF_ANY);

// sys_exit_read: compute delta, increment log2 bucket
__u64 *tsp = bpf_map_lookup_elem(&start, &pid);
if (!tsp) return 0;  // missed sys_enter (e.g. process started before probe)
__u64  delta_us = (bpf_ktime_get_ns() - *tsp) / 1000;
__u32  bucket = delta_us ? (63 - __builtin_clzll(delta_us)) : 0;
__sync_fetch_and_add(&hist[bucket], 1);
```

Userspace reads the array and prints a latency distribution — `p50`, `p99`,
`p999` — without ever seeing individual events. The BCC toolkit ships
[`biolatency`](https://github.com/iovisor/bcc/blob/master/tools/biolatency.py) (disk I/O) and [`runqlat`](https://github.com/iovisor/bcc/blob/master/tools/runqlat.py) (scheduler queue wait) built on this
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

## eBPF in production

The same architecture appears across the cloud-native ecosystem, at
different points on the observability-to-enforcement spectrum.

**[Cilium](https://cilium.io)** replaces iptables as the Kubernetes CNI.
Rather than maintaining netfilter rules that scale poorly with pod count, it
programs eBPF maps directly in the kernel network path. XDP (eXpress Data
Path) — the lowest eBPF hook, running before the kernel's network stack
processes the packet — handles load balancing without leaving the driver,
avoiding most per-packet kernel overhead.

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

Following the thread to its logical end: a BPF sensor on one machine is
a debugging tool. The same sensor deployed as a Kubernetes `DaemonSet` —
one pod per node — becomes an observability platform. I have not built
this myself, but tools like Falco and Tetragon follow exactly this
architecture, and the pieces fit together clearly enough to sketch.

```
┌──────────────────────────────────────────────────────────────┐
│ Kubernetes cluster                                           │
│                                                              │
│  node 0  [eBPF sensor] ──┐                                   │
│  node 1  [eBPF sensor] ──┼──> Kafka ──> SIEM / alerts        │
│  node 2  [eBPF sensor] ──┤                                   │
│    ...                   │                                   │
│  node N  [eBPF sensor] ──┘                                   │
└──────────────────────────────────────────────────────────────┘
```

`SIEM` (Security Information and Event Management) is the platform at the
end of the pipeline: it ingests the event stream, correlates events against
threat-intelligence rules, and surfaces alerts for analysts. The eBPF layer
feeds it raw, high-fidelity data — process lineage, file access, network
connections — that would be impractical to collect at this cost any other way.

Each sensor runs as a privileged pod with [`CAP_BPF` and `CAP_PERFMON`](https://man7.org/linux/man-pages/man7/capabilities.7.html) (split from `CAP_SYS_ADMIN` in Linux 5.8). It
loads the BPF program via `libbpf`, polls the ring buffer, serializes each
event to Avro or Protobuf, and produces to a Kafka topic partitioned by
node. BPF-level filtering keeps the event rate manageable — only events
matching policy cross the kernel boundary, regardless of syscall volume on
the host.

---

## What I took away

What struck me reading through this was how closely the architecture mirrors
what high-throughput financial systems do with market data. A feed handler runs co-located with the exchange, applies
symbol filters at the wire level, and forwards only the instruments that
matter downstream — because every unnecessary message costs latency and
bandwidth. An eBPF sensor does the same thing at the syscall level: in-kernel
maps act as the subscription filter, the ring buffer is the zero-copy
transport (the same role the [LMAX Disruptor](https://lmax-exchange.github.io/disruptor/) plays in Java trading systems),
and Kafka carries the normalized event stream to wherever analysis happens.
The principle is the same in both worlds: push the filter as close to the
source as possible, and only pay for what crosses the boundary.

### First surprise

What surprised me most was not how much had changed, but how much had
not. The bytecode I wrote in 2004 still runs, unmodified, on a 2026 kernel.
The `sock_filter` struct is still in the UAPI headers. The API guarantee
held for two decades — a level of stability you rarely see anywhere in
systems software.

The bytecode VM is the same. What changed:

| 2004 | 2026 |
|------|------|
| Hand-written opcodes | C compiled by Clang |
| `SO_ATTACH_FILTER` | `bpf()` syscall |
| Socket filter only | kprobes, tracepoints, network, perf |
| No shared state | BPF maps |
| Trust the programmer | Verifier proves safety |
| Silent drops under load | Ring buffers, drops detectable |

### Second surprise

The second surprise was the verifier. I expected a filter VM; I did not
expect a static analyzer that makes kernel extensions safe enough to ship
as production infrastructure. The verifier is not a side feature — it is
what makes all of eBPF possible. Without it there is no safe enforcement,
no CO-RE, no Cilium, no Tetragon. Everything else is built on the guarantee
that the kernel can reject an unsafe program before it runs.

Going back to [pangolin](https://github.com/dfa1/pangolin) and following the thread forward felt like
archaeology. The original cBPF filter is still there, buried underneath
the eBPF verifier and the JIT compiler — [unchanged](https://github.com/dfa1/pangolin/blob/master/src/filters.c).

That arc — from hand-written socket-filter bytecode on one machine to a
verified, JIT-compiled program deployed across a fleet — is what twenty
years of kernel engineering bought.
