# Compression Dictionary Transport (RFC 9842): does it earn its keep off the browser?

*14 September 2026*

*[RFC 9842](https://www.rfc-editor.org/rfc/rfc9842.html) — Compression Dictionary Transport, published September 2025 —
defines a way for HTTP clients and servers to negotiate a shared compression dictionary, then compress responses against
it (`dcb` for Brotli, `dcz` for Zstandard). It was written with browsers in mind: the two motivating use cases in the
spec are shipping a delta-compressed JS bundle against the previous version, and sharing a dictionary of common
HTML/template boilerplate across pages on a site. `Sec-Fetch-Site` checks, CORS-aware mitigations, cookie-like tracking
protections — the security section reads like it was written for a browser vendor, because it was.*

*None of that is browser-specific in the way it first looks, though. The negotiation headers themselves —
`Use-As-Dictionary`, `Available-Dictionary`, `Dictionary-ID` — are plain HTTP, usable by any client that speaks
`Accept-Encoding`/`Content-Encoding`. The negotiation is useful when two ends are decoupled: a public API with
third-party integrators writing their own clients — SDKs, curl, whatever — that you can't push config to, or two
microservices owned by different teams. `Use-As-Dictionary` on the response is how they discover "there's a
dictionary, here's its ID, it applies to `/orders/*`" without an out-of-band contract, and start sending it back on
later requests for smaller responses. It also buys dictionary rotation for free: bump the dictionary server-side, and
clients pick up the new ID and freshness off `Cache-Control` on their own, no coordinated redeploy.*

*If it's two services you control end-to-end — an internal service mesh, a client SDK you also ship — you already know
at deploy time which dictionary applies to which endpoint. There's nothing to discover, so hardcode
`Available-Dictionary`/`Dictionary-ID` on the request and skip parsing `Use-As-Dictionary` on responses entirely. That's
the RFC's "Common Content" use case, applied to a B2B API instead of a website.*

*The question I actually wanted answered wasn't "is this applicable?" but "is it worth it?" zstd-ffm already treats
this as a first-class citizen rather than a demo-only sketch — a framework-agnostic model layer
([#91](https://github.com/dfa1/zstd-ffm/issues/91) for the `dcz` codec,
[#92](https://github.com/dfa1/zstd-ffm/issues/92) for the header parsing/building) that follows
[sans-io](https://sans-io.readthedocs.io)'s split: the library provides only the protocol, and the I/O is supplied by
whatever framework the caller is already using.*


## The setup

The [demo](https://github.com/dfa1/zstd-ffm/tree/main/rfc9842/src/test/java/io/github/dfa1/zstd/rfc9842) runs on
embedded Jetty (`jetty-server` + `jetty-http2-server`, test-scoped) rather than the JDK's own `com.sun.net.httpserver`,
which only speaks HTTP/1.1 — one server exposing real HTTP/1.1 *and* real HTTP/2 (h2c, no TLS needed:
`java.net.http.HttpClient` does the RFC 7540 §3.2 cleartext upgrade) on the same port. That's what turns the HTTP/2
numbers below into measurements instead of HPACK arithmetic.

- **`ServerDemo`** negotiates the same four-rung ladder as before, best first: `dcz` if the client offers a matching
  dictionary, plain `zstd` if accepted, `gzip` if accepted, otherwise an uncompressed body — over either protocol. Its
  dictionary is trained (`ZstdDictionary.train`) on 300 synthetic NDJSON analytics events built from a seeded `Random`
  for reproducibility — the same shape of data it actually serves, since training on the wrong shape is its own way
  back to Finding 1.
- **`NaiveClientDemo`** is what nearly every HTTP client does today — sends `Accept-Encoding: gzip` and nothing else,
  landing on the `gzip` tier.
- **`Rfc9842ClientDemo`** fetches the dictionary once, offers it via `Available-Dictionary`/`Dictionary-ID` on matching
  requests; `--http2` picks the connector.
- **`PerfTestDemo`** exercises all four tiers and reports throughput, latency percentiles, and bytes transferred;
  `--http2` re-runs the same sweep over h2c.

Everything below is measured on one laptop (Apple M5, 10 cores, JDK 25), server and client as separate `exec:java`
processes talking over loopback TCP — not in-process — with no JVM tuning beyond the `--enable-native-access` flag FFM
requires: 2,000 warmup requests discarded, then 10,000 measured per cell. Not a rigorous benchmark, but real numbers
instead of intuition.

## What the negotiation looks like on the wire

A client with no dictionary yet just asks for what it always asks for:

```
GET /orders/12345 HTTP/1.1
Accept-Encoding: gzip, zstd, dcz
```

The server compresses normally and uses the response to point at a dictionary the client can pick up for next time:

```
HTTP/1.1 200 OK
Content-Encoding: zstd
Use-As-Dictionary: match="/orders/*", id="orders-v3"
Vary: accept-encoding, available-dictionary
```

The client fetches that dictionary once — a plain GET, cached like any other resource — and from then on offers it on
every request matching the pattern:

```
GET /orders/67890 HTTP/1.1
Accept-Encoding: gzip, zstd, dcz
Available-Dictionary: :pZGm1Av0IEBKARczz7exkNYsZb8LzaMrV7J32a2fFG4=:
Dictionary-ID: "orders-v3"
```

`Available-Dictionary`'s value isn't the ID — it's the base64url-encoded SHA-256 hash of the dictionary bytes the client
is holding, wrapped in colons because it's an RFC 8941 structured-field byte sequence. That's what lets the server
confirm the client has the exact dictionary it's about to compress against, not just a dictionary with a matching name.
Get a hash mismatch and the server falls back to a lower rung of the ladder rather than send something the client can't
decode.

With a match, the server switches encodings and compresses against the shared dictionary instead of from scratch:

```
HTTP/1.1 200 OK
Content-Encoding: dcz
Vary: accept-encoding, available-dictionary
```

That's the whole negotiation — one extra GET to fetch the dictionary, then two headers on every request after that.
`NaiveClientDemo` never sends `Available-Dictionary`, so it never sees anything but the `zstd`/`gzip` rungs;
`Rfc9842ClientDemo` is what runs the exchange above.

## Finding 1: the dictionary has to be sized to the payload, not "small"

The instinct is to keep dictionaries small. That's wrong. Against the same payload at zstd's default level 3:

| payload | plain zstd | `--dict 1`        | `--dict 4`       | `--dict 16`  |
|---------|------------|-------------------|------------------|--------------|
| 512 B   | 208 B      | 108 B (−48%)      | 108 B (−48%)     | 111 B (−47%) |
| 2 KB    | 334 B      | 274 B (−18%)      | 214 B (**−36%**) | 220 B (−34%) |
| 8 KB    | 691 B      | 811 B (**+17%**)  | 621 B (−10%)     | 634 B (−8%)  |
| 32 KB   | 2061 B     | 2936 B (**+42%**) | 2997 B (+45%)    | 1982 B (−4%) |

An undersized dictionary doesn't just fail to help — it makes the response *bigger than no dictionary at all*, by up to
45% here. A dictionary is a sized, trained, versioned artifact, not a switch you flip. Oversizing is cheap (16 KiB costs
the same latency as 1 KiB), so the safe default is to err upward and retrain as the payload shape drifts.

## Finding 2: the negotiation headers have a real, version-dependent cost

`Available-Dictionary` + `Dictionary-ID` + the `dcz` token in `Accept-Encoding` add up to 101 bytes on every request. On
HTTP/1.1 that's paid in full each time; HTTP/2 and HTTP/3 index repeated header values via HPACK/QPACK, so the same
negotiation should cost only a couple of bytes after the first request — in theory. Measured, not modeled: sending the
same warmed-up dcz-negotiated request 20 times over an HTTP/1.1-only connector versus an h2c-only one (same server,
same dictionary, live `Connection.getBytesIn`/`Out` deltas around the loop) comes out to 684 B/request on HTTP/1.1
versus 442 B/request on HTTP/2 — a 35% cut in total wire bytes, both directions. Smaller than "nearly free," because
framing overhead and the headers that aren't repeated don't vanish, but the direction holds.

Net wire bytes per request versus plain zstd, best dictionary per size (modeled from HPACK-indexing arithmetic — the
one size measured end-to-end above, the demo's 2,800 B default, confirms the direction):

| payload | response saving | net on HTTP/1.1   | net on HTTP/2+ |
|---------|-----------------|-------------------|----------------|
| 512 B   | 100 B           | ±0 (break-even)   | **−96 B**      |
| 2 KB    | 121 B           | −20 B             | **−117 B**     |
| 8 KB    | 70 B            | **+31 B (worse)** | −66 B          |
| 32 KB   | 79 B            | **+23 B (worse)** | −75 B          |

This is the number that decides whether `dcz` is worth adopting at all: on HTTP/1.1 it's a net loss outside a narrow
band around 2 KB; on HTTP/2+ it wins at every size tested.

The same story shows up end-to-end, not just in header bytes. `PerfTestDemo`'s four-tier sweep, HTTP/1.1 throughout,
plus `dcz` re-run over real HTTP/2 (`--http2`), at three payload sizes and fresh servers per run:

**512 B — the 4 KiB dictionary is eight times the payload**

| encoding | protocol | req/s | p50     | p99     | avg bytes/req |
|----------|----------|-------|---------|---------|----------------|
| identity | HTTP/1.1 | 13196 | 69.8 µs | 134.3 µs | 565.6 B       |
| gzip     | HTTP/1.1 | 15216 | 63.8 µs | 99.7 µs  | 217.9 B       |
| zstd     | HTTP/1.1 | 18587 | 52.3 µs | 78.1 µs  | 207.9 B       |
| dcz      | HTTP/1.1 | 19682 | 49.5 µs | 78.3 µs  | 107.8 B       |
| dcz      | HTTP/2   | 23399 | 42.2 µs | 63.0 µs  | 107.8 B       |

**2,800 B — the demo's default, same 4 KiB dictionary**

| encoding | protocol | req/s | p50     | p99      | avg bytes/req |
|----------|----------|-------|---------|----------|----------------|
| identity | HTTP/1.1 | 13243 | 69.0 µs | 133.3 µs | 2828.0 B      |
| gzip     | HTTP/1.1 | 13113 | 73.6 µs | 118.6 µs | 361.5 B       |
| zstd     | HTTP/1.1 | 17805 | 54.9 µs | 78.9 µs  | 377.0 B       |
| dcz      | HTTP/1.1 | 18470 | 52.2 µs | 82.1 µs  | 263.3 B       |
| dcz      | HTTP/2   | 22529 | 43.7 µs | 62.0 µs  | 263.3 B       |

**32,768 B — same 4 KiB dictionary, now an eighth of the payload**

| encoding | protocol | req/s | p50      | p99      | avg bytes/req |
|----------|----------|-------|----------|----------|----------------|
| identity | HTTP/1.1 | 10684 | 89.9 µs  | 170.0 µs | 32804.8 B     |
| gzip     | HTTP/1.1 | 5195  | 189.5 µs | 227.4 µs | 1530.3 B      |
| zstd     | HTTP/1.1 | 11653 | 83.8 µs  | 111.4 µs | 2060.8 B      |
| dcz      | HTTP/1.1 | 11449 | 85.4 µs  | 119.2 µs | 2997.1 B      |
| dcz      | HTTP/2   | 12794 | 76.9 µs  | 98.3 µs  | 2997.1 B      |

Three sizes, three different stories. At 512 B the dictionary is generously sized: `dcz` roughly halves the body
against plain `zstd` (107.8 B vs 207.9 B) and is the fastest tier on both protocols. At the 2,800 B default,
`dcz`/HTTP/2 is 22% faster in throughput and 16% lower at p50 than `dcz`/HTTP/1.1, and 27% faster than `zstd`/HTTP/1.1
— the best HTTP/1.1 has to offer. At 32 KB the same 4 KiB dictionary is now too small, exactly as Finding 1 warns, and
`dcz` ships *more* bytes than plain `zstd` (2997 B vs 2061 B, +45%, matching Finding 1's own `--dict 4` row at this
size). HTTP/2 still buys `dcz` a real edge over its own HTTP/1.1 run at that size (+12% req/s, −10% p50) — and `gzip`'s
CPU cost is even more visible here than at smaller sizes, falling to *half* identity's throughput (5195 vs 10684
req/s) — but neither protocol nor throughput fixes a sizing mistake. Protocol and dictionary size are separate knobs;
getting one right doesn't cover for the other.

HTTP/3 isn't measured here — the demo has no QUIC transport — but there's no structural reason to expect it to land
worse than HTTP/2 above. QPACK (RFC 9204) indexes repeated header values the same way HPACK does, over a transport
that also removes HTTP/2's TCP-level head-of-line blocking; if anything that argues for HTTP/3 matching or beating the
HTTP/2 numbers here, not falling behind them. Whether QUIC's own handshake and congestion-control overhead change the
latency picture at these payload sizes is a separate, unmeasured question.

## Finding 3: pick a compression level before reaching for a dictionary

Zstd's default level 3 is tuned for speed, and at larger payloads it can ship *more* bytes than the gzip it's meant to
replace — no dictionary fixes that. At 64 KB responses with `--dict 16`, against `gzip -6` at 2753 B / 2480 req/s:

| level       | zstd bytes | vs gzip -6 | req/s |
|-------------|------------|------------|-------|
| 1           | 3720 B     | +35%       | 7900  |
| 3 (default) | 3545 B     | +29%       | 8280  |
| 6           | 1506 B     | **−45%**   | 5250  |
| 9           | 1483 B     | −46%       | 4300  |
| 12          | 1483 B     | −46%       | 5090  |

Raising the level is a server-CPU-for-bytes trade with no cost to the client: zstd decompression speed is flat across
levels (slightly faster at higher ones), so the server pays once per response and every client decodes at the same rate.
Level and dictionary size interact rather than stack independently — the 8 KB / `--dict 4` cell is −10% against plain
zstd at level 3, but −25% at level 6 — so tune them together.

## Three requirements in the spec that are easy to skip

- **`Vary: accept-encoding, available-dictionary`** (§6.2) on every negotiated response. Without it, a shared cache can
  serve a dictionary-compressed body to a client holding a different dictionary — or none — which is undecodable, not
  just suboptimal.
- **A `Cache-Control` header on the dictionary itself** (§2.2.1). A stored dictionary only counts as a match while
  fresh; an uncacheable dictionary costs more to keep refetching than it ever saves.
- **Never advertise `dcz` without a matching dictionary in hand** (§6.1). A client with no dictionary can't decode a
  `dcz` response, so it must not offer the encoding. `Rfc9842ClientDemo` gates `dcz` on the `Use-As-Dictionary` `match`
  pattern for exactly this reason.

## Verdict

Use `dcz` when responses run roughly 0.5–16 KB, the connection is HTTP/2 or HTTP/3, and you're willing to size and
retrain the dictionary as the data drifts. Finding 2's numbers hold up end-to-end at that range: `dcz` is the fastest
tier at every size where the dictionary is sized right, and HTTP/2 adds another 12–27% in throughput on top of what
`dcz` already wins over plain `zstd` on HTTP/1.1.

Skip it if you're stuck on HTTP/1.1, the dictionary can't keep up with the payload — a 4 KiB dictionary against a
32 KB response ships *more* bytes than no dictionary at all, HTTP/2 or not — or nobody's going to own the dictionary
lifecycle. A stale or mis-sized dictionary is worse than none.

For a B2B integration specifically, that maps onto: negotiate and cache a shared dictionary once per partner API, keep
it on HTTP/2, and treat the dictionary as a versioned artifact with the same seriousness as the API contract itself.

If there's a one-line verdict, it's the least satisfying kind an engineer can give: it depends — on payload size,
dictionary freshness, and what's underneath the connection.

The full demo, including a JMH microbenchmark that isolates codec cost from the HTTP round trip, is
in [zstd-ffm](https://github.com/dfa1/zstd-ffm).
