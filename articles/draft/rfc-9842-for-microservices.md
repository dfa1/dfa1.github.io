# RFC 9842 for Microservices: It Depends

*14 September 2026*

*[RFC 9842](https://www.rfc-editor.org/rfc/rfc9842.html) — Compression Dictionary Transport, published September 2025 —
lets HTTP clients and servers negotiate a shared compression dictionary, then compress responses against it (`dcb` for
Brotli, `dcz` for Zstandard). It was written with browsers in mind: the spec's two motivating use cases
([§1.1](https://www.rfc-editor.org/rfc/rfc9842.html#section-1.1)) are a delta-compressed JS bundle against the
previous version, and a dictionary of common HTML/template boilerplate shared across pages.*

*The negotiation itself isn't browser-specific: `Use-As-Dictionary`, `Available-Dictionary` and `Dictionary-ID` are
plain HTTP, usable by any client that speaks `Accept-Encoding`/`Content-Encoding`.*

## Problem

Modern microservices exchange a lot of JSON, and on a constrained link every byte of it costs time. Bytes cost money
too: moving data in the cloud is rarely free, whether that's cross-AZ traffic
([$0.01/GB each direction on AWS](https://aws.amazon.com/ec2/pricing/on-demand/#Data_Transfer_within_the_same_AWS_Region)),
egress to the internet, or a NAT gateway in the path — the exact rate depends on the provider and the topology, but
there's always a rate. Latency is usually the bigger reason to act, though; the bill is the bonus.

Switching encoding — protobuf or Avro over gRPC — is the other way to attack this, and a more fundamental one: it
shrinks the payload at the source instead of compressing the waste afterwards. It's also a migration. New IDL, new
client contracts, every consumer updated, and for a public API that means every integrator you don't control. Turning
on compression is a filter and a header.

Compression is the cheap lever for both costs — gzip or zstd, cold, on every response. A shared dictionary is the
same lever with more leverage: the parts of the payload that repeat across responses — schema, boilerplate, whatever
doesn't change request to request — get factored out once instead of re-compressed from scratch every time.

### Coupled scenario

If it's two services you control end-to-end — an internal service mesh, a client SDK you also ship — you already know
at deploy time which dictionary applies to which endpoint. There's nothing to discover, so hardcode
`Available-Dictionary`/`Dictionary-ID` on the request and skip parsing `Use-As-Dictionary` on responses entirely.
That's the RFC's ["Common Content"](https://www.rfc-editor.org/rfc/rfc9842.html#section-1.1.2) use case, applied to a
B2B API instead of a website.

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

The negotiation is useful when two ends are decoupled: a public API with third-party integrators writing their own
clients — SDKs, curl, whatever — that you can't push config to, or two microservices owned by different teams.
`Use-As-Dictionary` on the response is how they discover "there's a dictionary, here's its ID, it applies to
`/orders/*`" without an out-of-band contract, and start sending it back on later requests for smaller responses. It
also buys dictionary rotation for free: bump the dictionary server-side, and clients pick up the new ID and freshness
off `Cache-Control` on their own, no coordinated redeploy.

```
  DECOUPLED — B2B API, third-party integrators, or cross-team services

  ┌────────────────┐                                            ┌────────────────────────┐
  │    your API    │──────────── Use-As-Dictionary ────────────>│      their client      │
  │  owns dict v3  │<─────────── Available-Dictionary ──────────│ SDK / curl / whatever  │
  └────────────────┘                                            └────────────────────────┘

  you control this — they don't. no out-of-band contract, no coordinated deploy.
```

## Negotiation

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

`Available-Dictionary`'s value isn't the ID — it's the base64url-encoded SHA-256 hash of the dictionary bytes the
client is holding, wrapped in colons because it's a structured-field byte sequence
([RFC 8941 §3.3.5](https://www.rfc-editor.org/rfc/rfc8941.html#section-3.3.5)). That's what lets the server confirm
the client has the exact dictionary it's about to compress against, not just a dictionary with a matching name. Get a
hash mismatch and the server falls back to a lower rung of the ladder rather than send something the client can't
decode.

`Dictionary-ID` is the optional half of that pair. The server set `id="orders-v3"` in `Use-As-Dictionary` above, so
per [§2.3](https://www.rfc-editor.org/rfc/rfc9842.html#section-2.3) the client MUST echo it back — it's a cheap lookup
key for the server, not part of decoding. Leave `id` off the `Use-As-Dictionary` response and there's nothing to echo:
the client sends `Available-Dictionary` alone, and the server resolves the dictionary from the hash by itself.

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

### Requirements in the spec that are easy to skip

- **`Vary: accept-encoding, available-dictionary`** on every negotiated response
  ([§6.2](https://www.rfc-editor.org/rfc/rfc9842.html#section-6.2)). Without it, a shared cache can serve a
  dictionary-compressed body to a client holding a different dictionary — or none — which is undecodable, not just
  suboptimal.
- **A `Cache-Control` header on the dictionary itself**
  ([§2.2.1](https://www.rfc-editor.org/rfc/rfc9842.html#section-2.2.1)). A stored dictionary only counts as a match
  while fresh; an uncacheable dictionary costs more to keep refetching than it ever saves.
- **Never advertise `dcz` without a matching dictionary in hand**
  ([§6.1](https://www.rfc-editor.org/rfc/rfc9842.html#section-6.1)). A client with no dictionary can't decode a `dcz`
  response, so it must not offer the encoding. `Rfc9842ClientDemo` only offers `dcz` when the `Use-As-Dictionary`
  `match` pattern applies, for exactly this reason.

## Run the demo

The question I actually wanted answered wasn't "is this applicable?" but "is it worth it?" zstd-ffm treats this as a
first-class citizen as of [v0.14](https://github.com/dfa1/zstd-ffm/blob/main/CHANGELOG.md#014---2026-09-18): the
`io.github.dfa1.zstd:zstd-rfc9842` module on Maven Central ships the `dcz` codec
([#91](https://github.com/dfa1/zstd-ffm/issues/91)) and the header parsing/building
([#92](https://github.com/dfa1/zstd-ffm/issues/92)) as a framework-agnostic model layer, following
[sans-io](https://sans-io.readthedocs.io)'s split: the library provides only the protocol, the I/O comes from whatever
framework the caller is already using.

The [demo](https://github.com/dfa1/zstd-ffm/tree/main/rfc9842/src/test/java/io/github/dfa1/zstd/rfc9842/demo) runs on
embedded Jetty (`jetty-server` + `jetty-http2-server`, test-scoped) rather than the JDK's own
`com.sun.net.httpserver`, which only speaks HTTP/1.1 — one server exposing HTTP/1.1 *and* HTTP/2[^http3] (h2c, no TLS
needed: `java.net.http.HttpClient` does the [RFC 7540 §3.2](https://www.rfc-editor.org/rfc/rfc7540.html#section-3.2)
cleartext upgrade) on the same port. That's what turns the HTTP/2 numbers below into measurements instead of HPACK
arithmetic.

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
processes — not in-process — with no JVM tuning beyond the `--enable-native-access` flag FFM requires. Not a rigorous
benchmark, but real numbers instead of intuition; iteration counts vary by section and are noted where they matter.

## Pick a compression level before reaching for a dictionary

Zstd's default level 3 is tuned for speed, not ratio — on a small payload it can lose to `gzip` outright, dictionary
or not, and the dictionary's own edge shrinks as the payload grows past it. An order object with realistic varying
fields — name, email, street address, tracking number — same 2 KiB dictionary reused at all three sizes, levels
matched across algorithms instead of comparing each one's default[^repro]:

**Small — 620 B**

| algorithm | level   | bytes | vs identity |
|-----------|---------|-------|--------------|
| identity  | —       | 620 B | baseline     |
| gzip      | default | 426 B | −31%         |
| gzip      | 3       | 428 B | −31%         |
| gzip      | 6       | 426 B | −31%         |
| zstd      | 3       | 436 B | −30%         |
| zstd      | 6       | 433 B | −30%         |
| dcz       | 3       | 166 B | −73%         |
| dcz       | 6       | 168 B | −73%         |

**32 KB — 33,284 B**

| algorithm | level   | bytes    | vs identity |
|-----------|---------|----------|--------------|
| identity  | —       | 33,284 B | baseline     |
| gzip      | default | 8,087 B  | −76%         |
| gzip      | 3       | 8,788 B  | −74%         |
| gzip      | 6       | 8,087 B  | −76%         |
| zstd      | 3       | 7,907 B  | −76%         |
| zstd      | 6       | 7,702 B  | −77%         |
| dcz       | 3       | 7,500 B  | −77%         |
| dcz       | 6       | 7,292 B  | −78%         |

**512 KB — 524,377 B**

| algorithm | level   | bytes     | vs identity |
|-----------|---------|-----------|--------------|
| identity  | —       | 524,377 B | baseline     |
| gzip      | default | 116,315 B | −78%         |
| gzip      | 3       | 127,902 B | −76%         |
| gzip      | 6       | 116,315 B | −78%         |
| zstd      | 3       | 109,398 B | −79%         |
| zstd      | 6       | 102,902 B | −80%         |
| dcz       | 3       | 109,335 B | −79%         |
| dcz       | 6       | 102,381 B | −80%         |

`gzip`'s Java default *is* level 6 — same bytes, same speed, measured both ways — so "gzip default" further down is
already its higher-effort setting, not its cheapest. Level-matched, the `zstd`-vs-`gzip` speed gap shrinks from ~8×
(default vs default) to ~4.5× at 512 KB (L3 vs L3)[^gzip-speed].

What's left is architectural, not a tuning artifact: DEFLATE caps its window at 32 KB
([RFC 1951 §2](https://www.rfc-editor.org/rfc/rfc1951.html#section-2)), so past that size it can't see matches further
back while `zstd`'s larger window still can — which is why `gzip` trails on ratio at 512 KB, not just on speed. The
dictionary's edge fades faster still: at level 6 it cuts 61% more than plain `zstd` on the small payload, 5% more at 32 KB, under
1% more at 512 KB. A real payload's own repetition dwarfs a small fixed dictionary long before the `gzip`/`zstd` gap
closes.

## The negotiation headers have a real cost in HTTP/1.1

`Available-Dictionary` + `Dictionary-ID` + the `dcz` token in `Accept-Encoding` add up to 101 bytes on every request.
HTTP/1.1 pays that in full each time; HPACK/QPACK should index it down to a couple of bytes after the first request —
in theory. Measured, not modeled: the same warmed-up request over HTTP/1.1 vs h2c comes out to 684 B/request vs 442
B/request — a real 35% cut, smaller than "nearly free" since framing overhead doesn't vanish.

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

## Loopback hides the case for compression

The obvious next step is measuring throughput end-to-end, not just header bytes — but not over loopback. Loopback has
(near) infinite bandwidth, so a client and server on the same machine tell you when compression pays off *in CPU terms
alone*; they say nothing about when it pays off on a link where bytes also cost transfer time, which is the entire
premise of this article. A bandwidth-capped proxy in front of the same server — no code changes, no second machine —
fixes that[^network-sim]:

```
  NETWORK SIMULATION — a real bandwidth ceiling instead of loopback's effectively infinite one

  ┌─────────────────┐                        ┌─────────────────┐                        ┌─────────────────┐
  │  ProxyPerfTest  │────── GET :19842 ─────>│    toxiproxy    │──────── :9842 ────────>│    ServerDemo   │
  │   (the client)  │<────── bw-capped ──────│ bandwidth toxic │<────── real body ──────│   (the server)  │
  └─────────────────┘                        └─────────────────┘                        └─────────────────┘
```

Both bandwidths sit deliberately outside the datacenter: 20 Mbps is a mobile client or a thin WAN hop, 1 Gbps is
roughly the floor for anything inside one. A dictionary earns the most exactly where you don't own the pipe — the
[decoupled scenario](#decoupled-scenario), not the coupled one. Same server, same client logic, same 2 KiB
dictionary, two payload sizes:

| payload | encoding | bandwidth | req/s   | p50      | avg bytes/req |
|---------|----------|-----------|---------|----------|----------------|
| 579 B   | identity | 20 Mbps   | 1,248   | 0.79 ms  | 707.0 B        |
| 579 B   | gzip     | 20 Mbps   | 1,777   | 0.57 ms  | 227.1 B        |
| 579 B   | zstd     | 20 Mbps   | 1,885   | 0.52 ms  | 223.3 B        |
| 579 B   | dcz      | 20 Mbps   | 1,969   | 0.46 ms  | 122.7 B        |
| 579 B   | identity | 1 Gbps    | 2,411   | 0.38 ms  | 707.0 B        |
| 579 B   | gzip     | 1 Gbps    | 3,003   | 0.31 ms  | 227.3 B        |
| 579 B   | zstd     | 1 Gbps    | 3,539   | 0.27 ms  | 223.5 B        |
| 579 B   | dcz      | 1 Gbps    | 3,587   | 0.26 ms  | 122.9 B        |
| 512 KB  | identity | 20 Mbps   | 4.7     | 212.5 ms | 524,311.2 B    |
| 512 KB  | gzip     | 20 Mbps   | 77.8    | 13.0 ms  | 19,487.4 B     |
| 512 KB  | zstd     | 20 Mbps   | 190.3   | 5.24 ms  | 9,125.3 B      |
| 512 KB  | dcz      | 20 Mbps   | 181.3   | 5.47 ms  | 9,187.3 B      |
| 512 KB  | identity | 1 Gbps    | 208.5   | 4.78 ms  | 524,311.2 B    |
| 512 KB  | gzip     | 1 Gbps    | 334.8   | 2.98 ms  | 19,486.1 B     |
| 512 KB  | zstd     | 1 Gbps    | 1,347.4 | 0.73 ms  | 9,110.2 B      |
| 512 KB  | dcz      | 1 Gbps    | 1,382.1 | 0.71 ms  | 9,065.1 B      |

At 579 B the ranking holds at both bandwidths (`dcz` > `zstd` > `gzip` > `identity`), with wider margins as the pipe
narrows. At 512 KB the picture flips: `gzip`'s margin over `identity` collapses from 16.6× at 20 Mbps to 1.6× at
1 Gbps as bandwidth stops being the bottleneck, while `zstd`/`dcz` hold ~6.5× ahead on codec cost alone (4×
`gzip`'s throughput at 1 Gbps). `dcz` itself adds nothing over plain `zstd` at 512 KB — 181.3 vs 190.3 req/s at
20 Mbps, 1,382.1 vs 1,347.4 at 1 Gbps — the same undersized-dictionary story as
[picking a level](#pick-a-compression-level-before-reaching-for-a-dictionary).

## Reproduction scripts

The full demo, including a JMH microbenchmark that isolates codec cost from the HTTP round trip, is in
[zstd-ffm](https://github.com/dfa1/zstd-ffm).

Four files on [GitHub Gist](https://gist.github.com/dfa1/0c0eaef6384eaa59a0eae8721423acda) — not part of zstd-ffm,
just built against its published `zstd`/`rfc9842` modules plus [DataFaker](https://www.datafaker.net) and
[toxiproxy](https://github.com/Shopify/toxiproxy). `SizeCompareFaker.java` produces the byte-size table;
`GzipLevelSpeedFaker.java` produces the timing figures; `network-sim.sh` sets up the bandwidth-capped proxy and
`ProxyPerfTest.java` is the client that drives it.

## Conclusion

Use `dcz` when responses run roughly 0.5–16 KB, the dictionary is sized to the payload, and you're willing to retrain
it as the data drifts[^bill] — the numbers hold whether the link is generous or constrained, and the smaller the pipe, the
bigger the win. HTTP/2 helps independently of all that — a real 35% cut in negotiation-header bytes over HTTP/1.1,
not just HPACK theory — but that's a header-bytes result specifically, not a blanket throughput guarantee.

Skip `dcz` if you're stuck on HTTP/1.1 outside the narrow band where the header bytes pay for themselves, the
dictionary can't keep up with the payload — a 4 KiB dictionary against a 32 KB response ships *more* bytes than no
dictionary at all — or nobody's going to own the dictionary lifecycle. A stale or mis-sized dictionary is worse than
none.

---

[^http3]: HTTP/3 isn't measured here — the demo has no QUIC transport — but there's no structural reason to expect it
    to land worse than HTTP/2. QPACK (RFC 9204) indexes repeated header values the same way HPACK does, over a
    transport that also removes HTTP/2's TCP-level head-of-line blocking; if anything that argues for HTTP/3 matching
    or beating the [HTTP/2 numbers](#the-negotiation-headers-have-a-real-cost-in-http11), not falling behind
    them. Whether QUIC's own handshake and congestion-control overhead change the latency picture at these payload
    sizes is a separate, unmeasured question.

[^repro]: This table's order objects use [DataFaker](https://www.datafaker.net) for the varying fields (name, email,
    address, tracking number), seeded for reproducibility (`java.util.Random`, seed `2` for the measured payloads,
    `0x5EED` for 300 training samples) — linked in full under
    [Reproduction scripts](#reproduction-scripts).

[^gzip-speed]: Timed separately from the byte sizes above, same payload generator, same machine: 200 warmup
    iterations discarded, then 1,500 measured iterations per algorithm/level, wall-clock around the compress call
    and around a full compress-then-decompress round trip. Not part of the live HTTP measurements further down —
    isolated in-process timing, no server, no network, no Jetty — linked in full under
    [Reproduction scripts](#reproduction-scripts).

[^network-sim]: [toxiproxy](https://github.com/Shopify/toxiproxy) sits between the client and the real `ServerDemo`
    (still on loopback physically) and throttles the downstream/response direction only with a `bandwidth` toxic —
    the request side is headers-only and doesn't need throttling to see the effect. Same `ServerDemo`, same 2 KiB
    dictionary, same decode-cost-included methodology as the byte-size table above, just over HTTP/1.1 only and far
    fewer iterations per cell (20–200, scaled down from the byte-size table's hundreds — network conditions
    dominate here, not JIT warmup noise, so fewer samples are already stable). Setup script and client
    (`network-sim.sh`, `ProxyPerfTest.java`) linked in full under [Reproduction scripts](#reproduction-scripts).

[^bill]: If the cloud bill is the motivation, size it first: at 579 B, `dcz` saves ~100 B per response over plain
    `zstd` (122.7 B vs 223.3 B [above](#loopback-hides-the-case-for-compression)) — about $50/month at 10,000 req/s
    sustained on AWS's cross-AZ rate, real money at scale but easily eaten by an engineer-hour of retraining below
    it. `identity` to `zstd` saves roughly five times as many bytes, for free. Adopt `dcz` for latency on a link you
    don't own; the bill alone rarely justifies it.
