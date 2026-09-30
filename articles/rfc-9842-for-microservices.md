# RFC 9842 for Microservices: It Depends

*14 September 2026*

*[RFC 9842](https://www.rfc-editor.org/rfc/rfc9842.html) — Compression Dictionary Transport, published September 2025 —
lets HTTP clients and servers negotiate a shared compression dictionary, then compress responses against it (`dcb` for
Brotli, `dcz` for Zstandard). It was written with browsers in mind: the spec's two motivating use cases
([§1.1](https://www.rfc-editor.org/rfc/rfc9842.html#section-1.1)) are a delta-compressed JS bundle against the
previous version, and a dictionary of common HTML/template boilerplate shared across pages.*

*The negotiation itself isn't browser-specific: `Use-As-Dictionary`, `Available-Dictionary` and `Dictionary-ID` are
plain HTTP, usable by any client that speaks `Accept-Encoding`/`Content-Encoding`. So I tried it where most of my
JSON actually flows: between microservices.*

## Problem

Modern microservices exchange a lot of JSON, and on a constrained link every byte of it costs time and money.
Latency is usually the bigger reason to act, though; the bill is the bonus.

Switching encoding — protobuf or Avro over gRPC — is the other way to attack this, and a more fundamental one: it
shrinks the payload at the source instead of compressing the waste afterward. It's also a migration. New IDL, new
client contracts, every consumer updated, and for a public API that means every integrator you don't control.
Compression is a filter and a header. A shared dictionary goes one step further: what repeats across responses —
schema, boilerplate — gets factored out once instead of recompressed from scratch every time.

### Who owns the client

|           | coupled                                               | decoupled                                               |
|-----------|-------------------------------------------------------|---------------------------------------------------------|
| example   | internal mesh, a client SDK you also ship             | public API, third-party integrators, cross-team services |
| discovery | none — hardcode `Available-Dictionary`/`Dictionary-ID` | `Link` → dictionary → `Use-As-Dictionary`               |
| rotation  | both ends redeploy in lockstep                        | new `id` + `Cache-Control` freshness, no redeploy       |
| RFC fit   | [§1.1.2 "Common Content"](https://www.rfc-editor.org/rfc/rfc9842.html#section-1.1.2), minus the negotiation | the full negotiation below |

```
  COUPLED — dictionary baked into both builds

  service B (client)           service A (server)
  │                                             │
  │ GET /orders/1                               │
  │ Accept-Encoding: zstd, dcz                  │
  │ Available-Dictionary: :<v3 hash>:           │
  │────────────────────────────────────────────>│
  │                                             │
  │ 200 OK                                      │
  │ Content-Encoding: dcz                       │
  │<────────────────────────────────────────────│
  │                                             │
  rotating the dictionary = redeploying both
```

```
  DECOUPLED — dictionary discovered at runtime

  their client                           your API
  │                                             │
  │ GET /orders/1                               │
  │ Accept-Encoding: gzip, zstd                 │
  │────────────────────────────────────────────>│
  │                                             │
  │ 200 OK, Content-Encoding: zstd              │
  │ Link: </dict/orders-v3>                     │
  │<────────────────────────────────────────────│
  │                                             │
  │ GET /dict/orders-v3                         │
  │────────────────────────────────────────────>│
  │                                             │
  │ 200 OK                                      │
  │ Use-As-Dictionary: match="/orders/*"        │
  │ Cache-Control: max-age=2592000              │
  │<────────────────────────────────────────────│
  │                                             │
  │ GET /orders/2                               │
  │ Accept-Encoding: gzip, zstd, dcz            │
  │ Available-Dictionary: :<v3 hash>:           │
  │────────────────────────────────────────────>│
  │                                             │
  │ 200 OK, Content-Encoding: dcz               │
  │<────────────────────────────────────────────│
  │                                             │
  rotating the dictionary = serving a new one; clients refetch it when it goes stale
```

The coupled column skips the negotiation, not the encoding. The rest of this article is about the decoupled one.

## Negotiation

A client with no dictionary yet just asks for what it always asks for:

```
GET /orders/12345 HTTP/1.1
Accept-Encoding: gzip, zstd
```

The server compresses normally and links to a dictionary the client can pick up for next time
([§3](https://www.rfc-editor.org/rfc/rfc9842.html#section-3)):

```
HTTP/1.1 200 OK
Content-Encoding: zstd
Link: </dict/orders-v3>; rel="compression-dictionary"
Vary: accept-encoding, available-dictionary
```

`Link` doesn't depend on `Accept-Encoding`: a client with no dictionary can't advertise `dcz`
([§6.1](https://www.rfc-editor.org/rfc/rfc9842.html#section-6.1)), so the server advertises to everyone and clients
that don't know the relation ignore it.

`Use-As-Dictionary` goes on the *dictionary's* own response, not on the data response. It declares which requests the
dictionary applies to and what ID to echo back
([§2.1](https://www.rfc-editor.org/rfc/rfc9842.html#section-2.1)); fetching it is a plain GET, cached like any
other resource:

```
GET /dict/orders-v3 HTTP/1.1

HTTP/1.1 200 OK
Cache-Control: max-age=2592000
Use-As-Dictionary: match="/orders/*", id="orders-v3"
```

On the data response instead, it would make *this body* the dictionary — the delta-compression use case
([§1.1.1](https://www.rfc-editor.org/rfc/rfc9842.html#section-1.1.1)), not a shared dictionary.

From then on the client offers the stored dictionary on every request matching the pattern:

```
GET /orders/67890 HTTP/1.1
Accept-Encoding: gzip, zstd, dcz
Available-Dictionary: :pZGm1Av0IEBKARczz7exkNYsZb8LzaMrV7J32a2fFG4=:
Dictionary-ID: "orders-v3"
```

`Available-Dictionary`'s value isn't the ID — it's the base64-encoded SHA-256 hash of the dictionary bytes the
client is holding, wrapped in colons because it's a structured-field byte sequence
([RFC 9651 §3.3.5](https://www.rfc-editor.org/rfc/rfc9651.html#section-3.3.5)). That's what lets the server confirm
the client has the exact dictionary it's about to compress against, not just one with a matching name. On a hash
mismatch the server falls back to plain `zstd` or `gzip` rather than send something the client can't decode.

`Dictionary-ID` is optional. The server set `id="orders-v3"` above, so the client MUST echo it
([§2.3](https://www.rfc-editor.org/rfc/rfc9842.html#section-2.3)) — a cheap lookup key, not part of decoding. Without
an `id`, the server resolves the dictionary from the hash alone.

With a match, the server switches encodings and compresses against the shared dictionary instead of from scratch:

```
HTTP/1.1 200 OK
Content-Encoding: dcz
Vary: accept-encoding, available-dictionary
```

### Requirements in the spec that are easy to skip

- **`Vary: accept-encoding, available-dictionary`** on every cacheable negotiated response
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

The question I actually wanted answered wasn't "is this applicable?" but "is it worth it?".
[zstd-ffm](https://github.com/dfa1/zstd-ffm) treats this as a first-class citizen as of [v0.14](https://github.com/dfa1/zstd-ffm/blob/main/CHANGELOG.md#014---2026-09-18): the
`io.github.dfa1.zstd:zstd-rfc9842` module on Maven Central ships the `dcz` codec
([#91](https://github.com/dfa1/zstd-ffm/issues/91)) and the header parsing/building
([#92](https://github.com/dfa1/zstd-ffm/issues/92)) as a framework-agnostic model layer, following
[sans-io](https://sans-io.readthedocs.io)'s split: the library provides only the protocol, the I/O comes from whatever
framework the caller is already using.

The [demo](https://github.com/dfa1/zstd-ffm/tree/main/rfc9842/src/test/java/io/github/dfa1/zstd/rfc9842/demo) runs on
embedded Jetty (`jetty-server` + `jetty-http2-server`, test-scoped) instead of the JDK's HTTP/1.1-only
`com.sun.net.httpserver`: one port serves HTTP/1.1 *and* HTTP/2[^http3] as h2c, via the
[RFC 7540 §3.2](https://www.rfc-editor.org/rfc/rfc7540.html#section-3.2) cleartext upgrade that
`java.net.http.HttpClient` still performs (RFC 9113 has since deprecated it). That's what makes the HTTP/2 header
bytes below a measurement.

| class               | role                                                                                   |
|---------------------|----------------------------------------------------------------------------------------|
| `ServerDemo`        | four-rung ladder, best first: `dcz` → `zstd` → `gzip` → identity, over either protocol |
| `NaiveClientDemo`   | `Accept-Encoding: gzip` and nothing else — what nearly every HTTP client does today     |
| `Rfc9842ClientDemo` | fetches the dictionary from a known URL (one server, so no `Link` discovery), then offers it on matching requests; `--http2` picks the connector |
| `PerfTestDemo`      | all four rungs: throughput, latency percentiles, bytes; `--http2` re-runs over h2c     |

`ServerDemo`'s dictionary is trained (`ZstdDictionary.train`) on 300 synthetic NDJSON analytics events from a seeded
`Random` — the same shape it serves, since a dictionary trained on the wrong shape stops matching what's sent.

Everything below is measured on one laptop (Apple M5, 10 cores, JDK 25), server and client as separate `exec:java`
processes — not in-process — with no JVM tuning beyond the `--enable-native-access` flag FFM requires. Not a rigorous
benchmark, but real numbers instead of intuition; iteration counts vary by section.

## Pick a compression level before reaching for a dictionary

Zstd's default level 3 is tuned for speed, not ratio — on a small payload plain `zstd` can lose to `gzip` outright.
An order object with realistic varying fields — name, email, street address, tracking number — the same 2 KiB
dictionary at all three sizes, levels matched across algorithms[^repro]:

| algorithm | level | 620 B         | 32 KB (33,284 B) | 512 KB (524,377 B) |
|-----------|-------|---------------|------------------|--------------------|
| gzip      | 3     | 428 B (−31%)  | 8,788 B (−74%)   | 127,902 B (−76%)   |
| gzip      | 6     | 426 B (−31%)  | 8,087 B (−76%)   | 116,315 B (−78%)   |
| zstd      | 3     | 436 B (−30%)  | 7,907 B (−76%)   | 109,398 B (−79%)   |
| zstd      | 6     | 433 B (−30%)  | 7,702 B (−77%)   | 102,902 B (−80%)   |
| dcz       | 3     | 166 B (−73%)  | 7,500 B (−77%)   | 109,335 B (−79%)   |
| dcz       | 6     | 168 B (−73%)  | 7,292 B (−78%)   | 102,381 B (−80%)   |

`gzip`'s Java default *is* level 6 — same bytes, same speed, measured both ways — so every `gzip` number here and
below is already its higher-effort setting. Level-matched, the `zstd`-vs-`gzip` speed gap shrinks from ~8×
(default vs default) to ~4.5× at 512 KB (L3 vs L3)[^gzip-speed].

What's left is architectural, not a tuning artifact: DEFLATE caps its window at 32 KiB
([RFC 1951 §2](https://www.rfc-editor.org/rfc/rfc1951.html#section-2)), so past that size it can't see matches further
back while `zstd`'s larger window still can — which is why `gzip` trails on ratio at 512 KB, not just on speed. The
dictionary's edge fades faster still: at level 6 its body is 61% smaller than plain `zstd`'s on the small payload,
5% smaller at 32 KB, under 1% at 512 KB: past a few KB, the payload's own repetition does the dictionary's job.

## The negotiation headers have a real cost in HTTP/1.1

`Available-Dictionary` + `Dictionary-ID` + the `dcz` token in `Accept-Encoding` add up to about 100 bytes on every request.
HTTP/1.1 pays that in full each time; HPACK/QPACK should index it down to a couple of bytes after the first request —
in theory. Measured: the same warmed-up request for the demo's default 2,800 B payload is 684 B over HTTP/1.1 vs 442 B
over h2c — a 35% cut of the whole request, since framing overhead doesn't vanish.

Net wire bytes per request versus plain zstd, best dictionary per size — modeled from HPACK-indexing arithmetic, with
the 2,800 B measurement confirming the direction:

| payload | response saving | net on HTTP/1.1   | net on HTTP/2+ |
|---------|-----------------|-------------------|----------------|
| 512 B   | 100 B           | ±0 (break-even)   | **−96 B**      |
| 2 KB    | 121 B           | −20 B             | **−117 B**     |
| 8 KB    | 70 B            | **+31 B (worse)** | −66 B          |
| 32 KB   | 79 B            | **+22 B (worse)** | −75 B          |

On HTTP/1.1, `dcz` is a net loss outside a narrow band around 2 KB; on HTTP/2+ it wins at every size modeled.

## Loopback hides the case for compression

Next is end-to-end throughput — but not over loopback. Its effectively infinite bandwidth only shows when compression
pays *in CPU terms*, not on a link where bytes cost transfer time, which is this article's premise. A bandwidth-capped
proxy in front of the same server fixes that, with no code changes and no second machine[^network-sim]:

```
  NETWORK SIMULATION — a real bandwidth ceiling instead of loopback's effectively infinite one

  ┌─────────────────┐                        ┌─────────────────┐                        ┌─────────────────┐
  │  ProxyPerfTest  │────── GET :19842 ─────>│    toxiproxy    │──────── :9842 ────────>│    ServerDemo   │
  │   (the client)  │<────── bw-capped ──────│ bandwidth toxic │<────── real body ──────│   (the server)  │
  └─────────────────┘                        └─────────────────┘                        └─────────────────┘
```

Neither bandwidth is a datacenter fabric: 20 Mbps is a mobile client or a thin WAN hop, 1 Gbps is roughly the
floor for anything inside one. A dictionary earns the most exactly where you don't own the pipe — the
[decoupled](#who-owns-the-client) case, not the coupled one. Same server, same client logic, same 2 KiB
dictionary, two payload sizes — `ServerDemo`'s NDJSON analytics events, not the order objects from the level
tables, so byte counts aren't comparable across sections:

| payload | encoding | avg bytes/req | 20 Mbps req/s | 20 Mbps p50 | 1 Gbps req/s | 1 Gbps p50 |
|---------|----------|---------------|---------------|-------------|--------------|------------|
| 579 B   | identity | 707 B         | 1,248         | 0.79 ms     | 2,411        | 0.38 ms    |
| 579 B   | gzip     | 227 B         | 1,777         | 0.57 ms     | 3,003        | 0.31 ms    |
| 579 B   | zstd     | 223 B         | 1,885         | 0.52 ms     | 3,539        | 0.27 ms    |
| 579 B   | dcz      | 123 B         | 1,969         | 0.46 ms     | 3,587        | 0.26 ms    |
| 512 KB  | identity | 524,311 B     | 4.7           | 212.5 ms    | 208.5        | 4.78 ms    |
| 512 KB  | gzip     | 19.5 KB       | 77.8          | 13.0 ms     | 334.8        | 2.98 ms    |
| 512 KB  | zstd     | 9.1 KB        | 190.3         | 5.24 ms     | 1,347.4      | 0.73 ms    |
| 512 KB  | dcz      | 9.1–9.2 KB    | 181.3         | 5.47 ms     | 1,382.1      | 0.71 ms    |

At 579 B the ranking holds at both bandwidths (`dcz` > `zstd` > `gzip` > `identity`), with wider margins as the pipe
narrows. At 512 KB and 1 Gbps, bandwidth stops being the bottleneck: `gzip`'s lead over `identity` collapses from
16.6× to 1.6×, while `zstd`/`dcz` stay ~6.5× ahead on codec cost alone — 4× `gzip`'s throughput. `dcz` adds nothing
over plain `zstd` at 512 KB, the same undersized-dictionary story as
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

`dcz` is Zstandard compressed against a shared dictionary instead of from scratch. Whether that's worth the
dictionary lifecycle depends less on the encoding than on the situation:

| situation | verdict |
|-----------|---------|
| browser, static assets | **OK** — [the spec's own two use cases](https://www.rfc-editor.org/rfc/rfc9842.html#section-1.1) |
| public JSON API, small repetitive responses | **OK** — [the case that pays](#loopback-hides-the-case-for-compression) |
| latency-sensitive constrained link | **OK** — [the narrower the pipe, the wider the margin](#loopback-hides-the-case-for-compression) |
| stuck on HTTP/1.1 | **MEASURE** — [the narrow band around 2 KB only](#the-negotiation-headers-have-a-real-cost-in-http11) |
| highly variable JSON | **MEASURE** — [benchmark before training](#pick-a-compression-level-before-reaching-for-a-dictionary) |
| two services, one team | **SKIP** — [hardcode it instead](#who-owns-the-client) |
| large responses already on plain `zstd` | **SKIP** — [under 1% left to win](#pick-a-compression-level-before-reaching-for-a-dictionary) |
| gRPC or protobuf already in place | **SKIP** — [a different problem](#problem) |

On an OK or MEASURE row, in this order:

1. **Level** — pick it before the dictionary, then size and train the dictionary on the shape you serve
   ([why](#pick-a-compression-level-before-reaching-for-a-dictionary)).
2. **Transport** — HTTP/2+ indexes the per-request headers away; HTTP/1.1 pays them in full
   ([why](#the-negotiation-headers-have-a-real-cost-in-http11)).
3. **Owner** — someone retrains the dictionary as the data drifts. A stale dictionary is worse than none.

---

[^http3]: HTTP/3 isn't measured — the demo has no QUIC transport. [QPACK](https://www.rfc-editor.org/rfc/rfc9204.html)
    indexes repeated headers like HPACK does, so the
    [header-byte result](#the-negotiation-headers-have-a-real-cost-in-http11) should carry over; QUIC's own latency
    profile at these sizes is unmeasured.

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
    (still on loopback physically) and throttles the downstream/response direction only with a `bandwidth` toxic.
    Setup script and client (`network-sim.sh`, `ProxyPerfTest.java`) linked in full under
    [Reproduction scripts](#reproduction-scripts).
