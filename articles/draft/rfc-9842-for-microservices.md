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

`gzip`'s Java default *is* level 6 — same bytes, same speed, confirmed by measuring both directly — so "gzip
default" further down is already its higher-effort setting, not its cheapest. Level-matched, the `zstd`-vs-`gzip`
speed gap shrinks from ~8× (default vs default) to ~4.5× at 512 KB (L3 vs L3)[^gzip-speed]; the rest is
architectural, not a tuning artifact — DEFLATE caps its window at 32 KB (RFC 1951), so past that size it can't see
matches further back, while `zstd`'s larger window keeps exploiting them, which is also why `gzip`'s ratio trails at
512 KB, not just its speed.

The dictionary's edge fades even faster: at level 6, it cuts 61% more than plain `zstd` on the small payload, 5%
more at 32 KB, under 1% more at 512 KB — a real payload's own repetition dwarfs a small fixed dictionary long before
the `gzip`/`zstd` gap closes. Benchmark your own workload before picking a level; this is one payload family, one
machine.

## The negotiation headers have a real cost in HTTP/1.1

`Available-Dictionary` + `Dictionary-ID` + the `dcz` token in `Accept-Encoding` add up to 101 bytes on every request.
HTTP/1.1 pays that in full each time; HPACK/QPACK should index it down to a couple of bytes after the first request
— in theory. Measured, not modeled: the same warmed-up request over HTTP/1.1 vs h2c comes out to 684 B/request vs
442 B/request — a real 35% cut, smaller than "nearly free" since framing overhead doesn't vanish.

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

At 579 B `dcz` nearly halves the body against plain `zstd` and is the fastest tier on both protocols, HTTP/2 adding
another 28% throughput. At 32 KB the same
[undersized-dictionary](#pick-a-compression-level-before-reaching-for-a-dictionary) pattern repeats end-to-end —
`dcz` ships more bytes than plain `zstd` (+39%) even though HTTP/2 still boosts its throughput (+14%). At 512 KB
`dcz` and `zstd` land within 1% of each other, HTTP/2's own edge shrinks to +3% (down from +28%), and `gzip` is the
outlier: throughput collapses to a sixth of every other tier's (396 req/s vs ~2,300–2,400) — compression CPU cost
now dominates the request.

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

## Reproduction scripts

The two standalone classes behind the "Pick a compression level" numbers — not part of zstd-ffm, just built against
its published `zstd` module plus [DataFaker](https://www.datafaker.net).

[`SizeCompareFaker.java`](https://github.com/dfa1/dfa1.github.io/blob/master/articles/draft/rfc-9842-for-microservices/SizeCompareFaker.java)
— the byte-size table:

```java
import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressionLevel;
import io.github.dfa1.zstd.ZstdDictionary;
import net.datafaker.Faker;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/// Compresses an order-shaped JSON payload — one order, a 32 KB page of
/// orders, a 512 KB page of orders — with identity, gzip (default/3/6), and
/// zstd/dcz (levels 3/6, a shared 2 KiB dictionary trained on 300 samples).
/// The order JSON's varying fields (name, email, address, tracking number,
/// sku, item name) come from DataFaker, so the payload has realistic entropy
/// rather than cycling through a handful of fixed values. Reproduces the
/// byte-size table in "Pick a compression level before reaching for a
/// dictionary", in the RFC 9842 for Microservices article.
///
/// Run: javac against zstd-ffm's `zstd` module and datafaker on the
/// classpath, then `java --enable-native-access=ALL-UNNAMED`.
public final class SizeCompareFaker {

    private static final long TRAINING_SEED = 0x5EED;
    private static final long MEASURE_SEED = 2;

    public static void main(String[] args) throws Exception {
        List<byte[]> trainingSamples = new ArrayList<>();
        Faker trainingFaker = new Faker(new Random(TRAINING_SEED));
        for (int i = 0; i < 300; i++) {
            trainingSamples.add(order(trainingFaker).getBytes(StandardCharsets.UTF_8));
        }
        ZstdDictionary dict = ZstdDictionary.train(trainingSamples, ZstdByteSize.ofKiB(2));

        report(dict, "small", singleOrder());
        report(dict, "32 KB", ordersPage(32 * 1024L));
        report(dict, "512 KB", ordersPage(512 * 1024L));
        System.out.println();
        System.out.printf("dictionary size: %d B%n", dict.toByteArray().length);
    }

    private static void report(ZstdDictionary dict, String label, byte[] payload) throws Exception {
        System.out.printf("== %s (%d B) ==%n", label, payload.length);
        System.out.printf("identity %d%n", payload.length);
        System.out.printf("gzip default %d%n", gzip(payload, -1).length);
        System.out.printf("gzip L3 %d%n", gzip(payload, 3).length);
        System.out.printf("gzip L6 %d%n", gzip(payload, 6).length);
        for (int level : new int[]{3, 6}) {
            ZstdCompressionLevel lvl = new ZstdCompressionLevel(level);
            byte[] zstdNoDict = Zstd.compress(payload, lvl);
            try (ZstdCompressContext ctx = new ZstdCompressContext().level(lvl)) {
                byte[] zstdDict = ctx.compress(payload, dict);
                System.out.printf("zstd L%d %d%n", level, zstdNoDict.length);
                System.out.printf("dcz L%d %d%n", level, zstdDict.length);
            }
        }
    }

    private static byte[] singleOrder() {
        return order(new Faker(new Random(MEASURE_SEED))).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] ordersPage(long targetBytes) {
        Faker faker = new Faker(new Random(MEASURE_SEED));
        StringBuilder page = new StringBuilder("[");
        boolean first = true;
        while (page.length() < targetBytes) {
            if (!first) {
                page.append(",");
            }
            page.append(order(faker));
            first = false;
        }
        page.append("]");
        return page.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String order(Faker faker) {
        String status = faker.options().option("shipped", "processing", "delivered", "cancelled", "returned");
        String carrier = faker.options().option("DHL", "UPS", "FedEx", "Swiss Post");
        int qty1 = 1 + faker.random().nextInt(5);
        int unitPrice1 = 500 + faker.random().nextInt(5000);
        int totalCents = 500 + faker.random().nextInt(20000);
        return """
                {"id":"ord_%s","status":"%s","createdAt":"%s","updatedAt":"%s",\
                "customer":{"id":"cus_%s","email":"%s","name":"%s"},\
                "items":[{"sku":"SKU-%04d","name":"%s","qty":%d,"unitPriceCents":%d}],\
                "shipping":{"carrier":"%s","trackingId":"%s","address":{"line1":"%s","city":"%s",\
                "postalCode":"%s","country":"%s"}},"currency":"CHF","totalCents":%d,\
                "links":{"self":"https://api.example.com/orders/ord_%s","customer":"https://api.example.com/customers/cus_%s"}}\
                """.formatted(
                faker.internet().uuid().substring(0, 8), status,
                faker.timeAndDate().past(30, java.util.concurrent.TimeUnit.DAYS).toString(),
                faker.timeAndDate().past(1, java.util.concurrent.TimeUnit.DAYS).toString(),
                faker.internet().uuid().substring(0, 8), faker.internet().emailAddress(), faker.name().fullName(),
                faker.random().nextInt(9999), faker.commerce().productName(), qty1, unitPrice1,
                carrier, faker.number().digits(18),
                faker.address().streetAddress(), faker.address().city(),
                faker.address().zipCode(), faker.address().countryCode(),
                totalCents,
                faker.internet().uuid().substring(0, 8), faker.internet().uuid().substring(0, 8));
    }

    private static byte[] gzip(byte[] data, int level) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out, Math.min(65536, Math.max(512, data.length))) {
            {
                if (level >= 0) {
                    def.setLevel(level);
                }
            }
        }) {
            gz.write(data);
        }
        return out.toByteArray();
    }
}
```

[`GzipLevelSpeedFaker.java`](https://github.com/dfa1/dfa1.github.io/blob/master/articles/draft/rfc-9842-for-microservices/GzipLevelSpeedFaker.java)
— the timing figures:

```java
import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdCompressionLevel;
import net.datafaker.Faker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/// Isolated in-process timing (no server, no network) for gzip
/// default/3/6 vs zstd 3/6, compressing and decompressing the same
/// DataFaker order-page payload SizeCompareFaker measures for size. Reproduces
/// the speed figures in "Pick a compression level before reaching for a
/// dictionary", in the RFC 9842 for Microservices article.
///
/// Run: javac against zstd-ffm's `zstd` module and datafaker on the
/// classpath, then `java --enable-native-access=ALL-UNNAMED`.
public final class GzipLevelSpeedFaker {

    public static void main(String[] args) throws Exception {
        for (int size : new int[]{32 * 1024, 512 * 1024}) {
            byte[] payload = ordersPage(size);
            time("gzip default", size, () -> gzip(payload, -1), () -> gunzip(gzip(payload, -1)));
            time("gzip L3", size, () -> gzip(payload, 3), () -> gunzip(gzip(payload, 3)));
            time("gzip L6", size, () -> gzip(payload, 6), () -> gunzip(gzip(payload, 6)));
            time("zstd L3", size, () -> Zstd.compress(payload, new ZstdCompressionLevel(3)),
                    () -> Zstd.decompress(Zstd.compress(payload, new ZstdCompressionLevel(3))));
            time("zstd L6", size, () -> Zstd.compress(payload, new ZstdCompressionLevel(6)),
                    () -> Zstd.decompress(Zstd.compress(payload, new ZstdCompressionLevel(6))));
        }
    }

    interface Thrower<T> { T get() throws Exception; }

    private static void time(String label, int size, Thrower<byte[]> compress, Thrower<byte[]> roundTrip) throws Exception {
        for (int i = 0; i < 200; i++) { compress.get(); roundTrip.get(); }
        int n = 1500;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) { compress.get(); }
        long compressNanos = System.nanoTime() - start;
        start = System.nanoTime();
        for (int i = 0; i < n; i++) { roundTrip.get(); }
        long roundTripNanos = System.nanoTime() - start;
        System.out.printf("%-13s %7d B  compress %8.1f us/op   compress+decompress %8.1f us/op%n",
                label, size, compressNanos / 1000.0 / n, roundTripNanos / 1000.0 / n);
    }

    private static byte[] ordersPage(long targetBytes) {
        Faker faker = new Faker(new Random(2));
        StringBuilder page = new StringBuilder("[");
        boolean first = true;
        while (page.length() < targetBytes) {
            if (!first) page.append(",");
            page.append(order(faker));
            first = false;
        }
        page.append("]");
        return page.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String order(Faker faker) {
        String status = faker.options().option("shipped", "processing", "delivered", "cancelled", "returned");
        String carrier = faker.options().option("DHL", "UPS", "FedEx", "Swiss Post");
        int qty1 = 1 + faker.random().nextInt(5);
        int unitPrice1 = 500 + faker.random().nextInt(5000);
        int totalCents = 500 + faker.random().nextInt(20000);
        return """
                {"id":"ord_%s","status":"%s","createdAt":"%s","updatedAt":"%s",\
                "customer":{"id":"cus_%s","email":"%s","name":"%s"},\
                "items":[{"sku":"SKU-%04d","name":"%s","qty":%d,"unitPriceCents":%d}],\
                "shipping":{"carrier":"%s","trackingId":"%s","address":{"line1":"%s","city":"%s",\
                "postalCode":"%s","country":"%s"}},"currency":"CHF","totalCents":%d,\
                "links":{"self":"https://api.example.com/orders/ord_%s","customer":"https://api.example.com/customers/cus_%s"}}\
                """.formatted(
                faker.internet().uuid().substring(0, 8), status,
                faker.timeAndDate().past(30, java.util.concurrent.TimeUnit.DAYS).toString(),
                faker.timeAndDate().past(1, java.util.concurrent.TimeUnit.DAYS).toString(),
                faker.internet().uuid().substring(0, 8), faker.internet().emailAddress(), faker.name().fullName(),
                faker.random().nextInt(9999), faker.commerce().productName(), qty1, unitPrice1,
                carrier, faker.number().digits(18),
                faker.address().streetAddress(), faker.address().city(),
                faker.address().zipCode(), faker.address().countryCode(),
                totalCents,
                faker.internet().uuid().substring(0, 8), faker.internet().uuid().substring(0, 8));
    }

    private static byte[] gzip(byte[] data, int level) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out, Math.min(65536, Math.max(512, data.length))) {
            { if (level >= 0) def.setLevel(level); }
        }) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return gz.readAllBytes();
        }
    }
}
```

---

[^http3]: HTTP/3 isn't measured here — the demo has no QUIC transport — but there's no structural reason to expect it
    to land worse than HTTP/2. QPACK (RFC 9204) indexes repeated header values the same way HPACK does, over a
    transport that also removes HTTP/2's TCP-level head-of-line blocking; if anything that argues for HTTP/3 matching
    or beating the [HTTP/2 numbers](#the-negotiation-headers-have-a-real-cost-in-http11), not falling behind
    them. Whether QUIC's own handshake and congestion-control overhead change the latency picture at these payload
    sizes is a separate, unmeasured question.

[^repro]: This table's order objects use [DataFaker](https://www.datafaker.net) for the varying fields (name, email,
    address, tracking number), seeded for reproducibility (`java.util.Random`, seed `2` for the measured payloads,
    `0x5EED` for 300 training samples) — shown in full under
    [Reproduction scripts](#reproduction-scripts).

[^gzip-speed]: Timed separately from the byte sizes above, same payload generator, same machine: 200 warmup
    iterations discarded, then 1,500 measured iterations per algorithm/level, wall-clock around the compress call
    and around a full compress-then-decompress round trip. Not part of the live HTTP measurements further down —
    isolated in-process timing, no server, no network, no Jetty — shown in full under
    [Reproduction scripts](#reproduction-scripts).
