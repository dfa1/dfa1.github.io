# RFC 9842 for Microservices: It Depends

*14 September 2026*

*[RFC 9842](https://www.rfc-editor.org/rfc/rfc9842.html) — Compression Dictionary Transport, published September 2025 —
defines a way for HTTP clients and servers to negotiate a shared compression dictionary, then compress responses against
it (`dcb` for Brotli, `dcz` for Zstandard). It was written with browsers in mind: the two motivating use cases in the
spec are shipping a delta-compressed JS bundle against the previous version, and sharing a dictionary of common
HTML/template boilerplate across pages on a site. `Sec-Fetch-Site` checks, CORS-aware mitigations, cookie-like tracking
protections — the security section reads like it was written for a browser vendor, because it was. That side is
already well covered elsewhere —
[MDN](https://developer.mozilla.org/en-US/docs/Glossary/Compression_dictionary_transport),
[caniuse](https://caniuse.com/wf-compression-dictionary-transport) — so the rest of this piece skips it.*

*None of that is browser-specific in the way it first looks, though. The negotiation headers themselves —
`Use-As-Dictionary`, `Available-Dictionary`, `Dictionary-ID` — are plain HTTP, usable by any client that speaks
`Accept-Encoding`/`Content-Encoding`.*

## Problem

Modern microservices exchange a lot of JSON, and every byte of it has a price tag: cloud providers meter cross-AZ and
egress traffic per GB, so a chattier API is a bigger line item before it's anything else. Latency is the second cost —
a slow link turns a big payload into a delay problem on top of a billing one.

Compression is the standard lever for both — gzip or zstd, cold, on every response. A shared dictionary is the same lever with more leverage: the parts of
the payload that repeat across responses — schema, boilerplate, whatever doesn't change request to request — get
factored out once instead of re-compressed from scratch every time.

The question I actually wanted answered wasn't "is this applicable?" but "is it worth it?" zstd-ffm already treats
this as a first-class citizen — a framework-agnostic model
layer ([#91](https://github.com/dfa1/zstd-ffm/issues/91) for the `dcz` codec,
[#92](https://github.com/dfa1/zstd-ffm/issues/92) for the header parsing/building) that follows
[sans-io](https://sans-io.readthedocs.io)'s split: the library provides only the protocol, and the I/O is supplied by
whatever framework the caller is already using.

### Coupled scenario
If it's two services you control end-to-end — an internal service mesh, a client SDK you also ship — you already know
at deploy time which dictionary applies to which endpoint. There's nothing to discover, so hardcode
`Available-Dictionary`/`Dictionary-ID` on the request and skip parsing `Use-As-Dictionary` on responses entirely. That's
the RFC's "Common Content" use case, applied to a B2B API instead of a website.

```
  COUPLED — same team owns both ends

  ┌────────────────┐                                            ┌────────────────────────┐
  │   service A    │────────── ships dict v3 in build ─────────>│       service B        │
  │  (your team)   │                                            │    (your team, too)    │
  └────────────────┘                                            └────────────────────────┘

  no negotiation — B hardcodes Available-Dictionary/Dictionary-ID, skips parsing
  Use-As-Dictionary entirely. deploy-time knowledge covers it.
```

This setup is simple, but it comes with a deployment cost: the two services must ship in lockstep.

### Decoupled scenario

The negotiation is useful when two ends are decoupled: a public API with
third-party integrators writing their own clients — SDKs, curl, whatever — that you can't push config to, or two
microservices owned by different teams. `Use-As-Dictionary` on the response is how they discover "there's a
dictionary, here's its ID, it applies to `/orders/*`" without an out-of-band contract, and start sending it back on
later requests for smaller responses. It also buys dictionary rotation for free: bump the dictionary server-side, and
clients pick up the new ID and freshness off `Cache-Control` on their own, no coordinated redeploy.

```
  DECOUPLED — B2B API, third-party integrators, or cross-team services

  ┌────────────────┐                                            ┌────────────────────────┐
  │    your API    │──────────── Use-As-Dictionary ────────────>│      their client      │
  │  owns dict v3  │<─────────── Available-Dictionary ──────────│ SDK / curl / whatever  │
  └────────────────┘                                            └────────────────────────┘

  you control this — they don't. no out-of-band contract, no coordinated deploy.
```

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

`Dictionary-ID` is the optional half of that pair. The server set `id="orders-v3"` in `Use-As-Dictionary` above, so
per §2.3 the client MUST echo it back — it's a cheap lookup key for the server, not part of decoding. Leave `id` off
the `Use-As-Dictionary` response and there's nothing to echo: the client sends `Available-Dictionary` alone, and the
server resolves the dictionary from the hash by itself.

With a match, the server switches encodings and compresses against the shared dictionary instead of from scratch:

```
HTTP/1.1 200 OK
Content-Encoding: dcz
Vary: accept-encoding, available-dictionary
```

That's the whole negotiation — one extra GET to fetch the dictionary, then `Available-Dictionary` (plus
`Dictionary-ID`, if the server bothered to set one) on every request after that. `NaiveClientDemo` never sends
`Available-Dictionary`, so it never sees anything but the `zstd`/`gzip` rungs; `Rfc9842ClientDemo` is what runs the
exchange above.

## A small demo + benchmark

The [demo](https://github.com/dfa1/zstd-ffm/tree/main/rfc9842/src/test/java/io/github/dfa1/zstd/rfc9842/demo) runs on
embedded Jetty (`jetty-server` + `jetty-http2-server`, test-scoped) rather than the JDK's own `com.sun.net.httpserver`,
which only speaks HTTP/1.1 — one server exposing HTTP/1.1 *and* HTTP/2[^http3] (h2c, no TLS needed:
`java.net.http.HttpClient` does the RFC 7540 §3.2 cleartext upgrade) on the same port. That's what turns the HTTP/2
numbers below into measurements instead of HPACK arithmetic.

- **`ServerDemo`** negotiates the same four-rung ladder as before, best first: `dcz` if the client offers a matching
  dictionary, plain `zstd` if accepted, `gzip` if accepted, otherwise an uncompressed body — over either protocol. Its
  dictionary is trained (`ZstdDictionary.train`) on 300 synthetic NDJSON analytics events built from a seeded `Random`
  for reproducibility — the same shape of data it actually serves, since training on the wrong shape has the same
  effect as an undersized dictionary: it stops matching what's actually sent.
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

## Pick a compression level before reaching for a dictionary

Zstd's default level 3 is tuned for speed, not ratio — on a small payload it can lose to `gzip` outright, dictionary
or not, and the dictionary's own edge shrinks as the payload grows past it. Three sizes of the same order-shaped
JSON — one `/orders/{id}` response, then two `/orders?page=N` pages — compressed every way, the *same* 2 KiB
dictionary reused at all three sizes, not retrained per size[^repro]:

**Small — one order, 579 B**

| algorithm | level       | bytes | vs identity |
|-----------|-------------|-------|--------------|
| identity  | —           | 579 B | baseline     |
| gzip      | default     | 380 B | −34%         |
| zstd      | 1           | 392 B | −32%         |
| zstd      | 3 (default) | 393 B | −32%         |
| zstd      | 6           | 389 B | −33%         |
| zstd      | 9           | 389 B | −33%         |
| zstd      | 12          | 388 B | −33%         |
| dcz       | 1           | 99 B  | −83%         |
| dcz       | 3 (default) | 104 B | −82%         |
| dcz       | 6           | 94 B  | −84%         |
| dcz       | 9           | 97 B  | −83%         |
| dcz       | 12          | 94 B  | −84%         |

**32 KB — a page of ~40 orders, 33,161 B**

| algorithm | level       | bytes    | vs identity |
|-----------|-------------|----------|--------------|
| identity  | —           | 33,161 B | baseline     |
| gzip      | default     | 4,906 B  | −85%         |
| zstd      | 1           | 4,209 B  | −87%         |
| zstd      | 3 (default) | 4,524 B  | −86%         |
| zstd      | 6           | 4,340 B  | −87%         |
| zstd      | 9           | 3,931 B  | −88%         |
| zstd      | 12          | 3,933 B  | −88%         |
| dcz       | 1           | 3,739 B  | −89%         |
| dcz       | 3 (default) | 4,066 B  | −88%         |
| dcz       | 6           | 3,897 B  | −88%         |
| dcz       | 9           | 3,480 B  | −90%         |
| dcz       | 12          | 3,473 B  | −90%         |

**512 KB — a page of ~640 orders, 524,787 B**

| algorithm | level       | bytes     | vs identity |
|-----------|-------------|-----------|--------------|
| identity  | —           | 524,787 B | baseline     |
| gzip      | default     | 67,897 B  | −87%         |
| zstd      | 1           | 60,175 B  | −89%         |
| zstd      | 3 (default) | 67,668 B  | −87%         |
| zstd      | 6           | 62,604 B  | −88%         |
| zstd      | 9           | 54,401 B  | −90%         |
| zstd      | 12          | 53,417 B  | −90%         |
| dcz       | 1           | 59,637 B  | −89%         |
| dcz       | 3 (default) | 67,409 B  | −87%         |
| dcz       | 6           | 62,057 B  | −88%         |
| dcz       | 9           | 53,860 B  | −90%         |
| dcz       | 12          | 52,870 B  | −90%         |

The trade-off doesn't show up against identity — every row past 32 KB clusters in the same 85–90% band no matter the
algorithm. It shows up comparing `dcz` to plain `zstd` at the *same* level: at level 12, the same 2 KiB dictionary
cuts 76% more than plain zstd on the single order (94 B vs 388 B), 12% more on the 32 KB page (3,473 B vs 3,933 B),
and about 1% more on the 512 KB page (52,870 B vs 53,417 B). A fixed-size dictionary's contribution doesn't scale
with the payload — past some point the payload carries enough internal repetition of its own that zstd finds most of
it unaided, and the dictionary lifecycle stops paying for itself. Turn any of these percentages into your own
request volume and your own cloud's $/GB to see whether it's worth it for you; this is one payload family, one
dictionary, one machine — benchmark your own workload before picking a level.

## The negotiation headers have a real cost in HTTP/1.1

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

The same story shows up end-to-end, not just in header bytes.
[`PerfTestDemo`](https://github.com/dfa1/zstd-ffm/blob/main/rfc9842/src/test/java/io/github/dfa1/zstd/rfc9842/demo/PerfTestDemo.java)'s
four-tier sweep, HTTP/1.1 throughout, plus `dcz` re-run over real HTTP/2 (`--http2`), at the same three sizes and the
same 2 KiB dictionary as [above](#pick-a-compression-level-before-reaching-for-a-dictionary), fresh servers per run:

| payload   | encoding | protocol | req/s | p50      | p99      | avg bytes/req |
|-----------|----------|----------|-------|----------|----------|---------------|
| 579 B     | identity | HTTP/1.1 | 12237 | 75.5 µs  | 159.7 µs | 707.0 B       |
| 579 B     | gzip     | HTTP/1.1 | 15050 | 64.9 µs  | 96.3 µs  | 228.4 B       |
| 579 B     | zstd     | HTTP/1.1 | 17953 | 53.2 µs  | 92.2 µs  | 223.8 B       |
| 579 B     | dcz      | HTTP/1.1 | 18580 | 52.0 µs  | 96.5 µs  | 123.5 B       |
| 579 B     | dcz      | HTTP/2   | 23745 | 41.8 µs  | 55.9 µs  | 123.0 B       |
| 32,768 B  | identity | HTTP/1.1 | 10353 | 91.0 µs  | 183.9 µs | 32,804.8 B    |
| 32,768 B  | gzip     | HTTP/1.1 | 5226  | 188.6 µs | 227.5 µs | 1,530.3 B     |
| 32,768 B  | zstd     | HTTP/1.1 | 11447 | 84.3 µs  | 135.6 µs | 2,060.8 B     |
| 32,768 B  | dcz      | HTTP/1.1 | 11619 | 84.1 µs  | 127.6 µs | 2,859.1 B     |
| 32,768 B  | dcz      | HTTP/2   | 13300 | 74.3 µs  | 90.7 µs  | 2,859.9 B     |
| 524,288 B | identity | HTTP/1.1 | 2417  | 371.0 µs | 701.7 µs | 524,311.2 B   |
| 524,288 B | gzip     | HTTP/1.1 | 396   | 2516.5 µs| 2847.8 µs| 19,485.3 B    |
| 524,288 B | zstd     | HTTP/1.1 | 2339  | 424.3 µs | 468.4 µs | 9,110.0 B     |
| 524,288 B | dcz      | HTTP/1.1 | 2268  | 438.9 µs | 489.5 µs | 9,041.4 B     |
| 524,288 B | dcz      | HTTP/2   | 2334  | 428.0 µs | 484.8 µs | 9,041.2 B     |

At 579 B the 2 KiB dictionary is generously sized (over 3× the payload): `dcz` nearly halves the body against plain
`zstd` (123.5 B vs 223.8 B, −45%) and is the fastest tier on both protocols; HTTP/2 adds another 28% throughput and
cuts p50 by 20% on top of that. At 32 KB the same dictionary is now an eighth of the payload — too small — and the
[picking-a-level](#pick-a-compression-level-before-reaching-for-a-dictionary) pattern repeats end-to-end: `dcz` ships
*more* bytes than plain `zstd` (2,859 B vs 2,061 B, +39%), even though HTTP/2 still buys it a real edge over its own
HTTP/1.1 run (+14% req/s, −12% p50). At 512 KB the dictionary's contribution has nearly vanished — `dcz` and `zstd`
land within 1% of each other in bytes (9,041 B vs 9,110 B), matching the ~1% gap measured directly against the
same-shape JSON above — and HTTP/2's own edge shrinks too (+3% req/s over HTTP/1.1, down from +28% at 579 B), because
compression CPU time now dominates the request instead of connection or header overhead. The one dramatic mover at
this size is `gzip`, whose throughput collapses to roughly a sixth of every other tier's (396 req/s vs ~2,300–2,400)
— the same CPU-for-bytes trade-off from picking a level, just far more extreme at 512 KB than at a few kilobytes.

## Requirements in the spec that are easy to skip

- **`Vary: accept-encoding, available-dictionary`** (§6.2) on every negotiated response. Without it, a shared cache can
  serve a dictionary-compressed body to a client holding a different dictionary — or none — which is undecodable, not
  just suboptimal.
- **A `Cache-Control` header on the dictionary itself** (§2.2.1). A stored dictionary only counts as a match while
  fresh; an uncacheable dictionary costs more to keep refetching than it ever saves.
- **Never advertise `dcz` without a matching dictionary in hand** (§6.1). A client with no dictionary can't decode a
  `dcz` response, so it must not offer the encoding. `Rfc9842ClientDemo` only offers `dcz` when the
  `Use-As-Dictionary` `match` pattern applies, for exactly this reason.

## Conclusion

> **It depends.** The least satisfying answer an engineer can give — on payload size, dictionary freshness, and what's
> underneath the connection.

My rule of thumb:

Use `dcz` when responses run roughly 0.5–16 KB, the connection is HTTP/2 or HTTP/3, and you're willing to size and
retrain the dictionary as the data drifts. The
[negotiation-cost numbers](#the-negotiation-headers-have-a-real-cost-in-http11) hold up end-to-end at that
range: `dcz` is the fastest tier at every size where the dictionary is sized right, and HTTP/2 adds another 12–27% in
throughput on top of what
`dcz` already wins over plain `zstd` on HTTP/1.1.

Skip `dcz` if you're stuck on HTTP/1.1, the dictionary can't keep up with the payload — a 4 KiB dictionary against a
32 KB response ships *more* bytes than no dictionary at all, HTTP/2 or not — or nobody's going to own the dictionary
lifecycle. A stale or mis-sized dictionary is worse than none.

The full demo, including a JMH microbenchmark that isolates codec cost from the HTTP round trip, is
in [zstd-ffm](https://github.com/dfa1/zstd-ffm).

---

[^http3]: HTTP/3 isn't measured here — the demo has no QUIC transport — but there's no structural reason to expect it
    to land worse than HTTP/2. QPACK (RFC 9204) indexes repeated header values the same way HPACK does, over a
    transport that also removes HTTP/2's TCP-level head-of-line blocking; if anything that argues for HTTP/3 matching
    or beating the [HTTP/2 numbers](#the-negotiation-headers-have-a-real-cost-in-http11), not falling behind
    them. Whether QUIC's own handshake and congestion-control overhead change the latency picture at these payload
    sizes is a separate, unmeasured question.

[^repro]: Reproducible: `ZstdDictionary.train` on 300 single-order JSON samples (`java.util.Random`, seed `0x5EED`)
    into a 2 KiB dictionary, via [zstd-ffm](https://github.com/dfa1/zstd-ffm)'s `zstd` module —
    `Zstd.compress`/`ZstdCompressContext.compress` at levels 1/3/6/9/12 for the plain-`zstd` and `dcz` rows,
    `java.util.zip.GZIPOutputStream` at its default level for `gzip`. The 32 KB and 512 KB rows are
    `[order, order, ...]` JSON arrays of the same randomly-varied order shape (seed `2`, disjoint from the training
    seed) built up to each target byte count. Not checked into the repo — a small standalone class against the
    published library, same training/payload-generation shape as
    [`DczTestServer`](https://github.com/dfa1/zstd-ffm/blob/main/rfc9842/src/test/java/io/github/dfa1/zstd/rfc9842/demo/DczTestServer.java).
