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

BPF was introduced in 1992. The original paper described a simple virtual
machine with two registers, a small set of opcodes, and a verifiable
property: every program terminates. No loops, no dynamic jumps. The kernel
can run it safely.

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

A detection platform for security telemetry and a detection platform for
financial market data have the same architecture: high-throughput event
stream, windowed aggregation, threshold matching, auditable output. The
schema is different. The engineering is the same.

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
