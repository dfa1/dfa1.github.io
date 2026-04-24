# Make the Implicit Explicit

*10 June 2022*

*A `Data API` with per-request authorization — entitlements delegated to an `Entitlement API` owned by a separate team.
A series of forced decisions — versioned contracts, point-in-time consistency, decorator-based composition — each
driven by a different problem. This is a retrospective on a real system.*

> **Principle:** Software breaks at boundaries. Every implicit assumption at a boundary becomes an incident. The fix is always the same: make the boundary explicit — in the contract, in the consistency model, in the transport, in the code[^zen].

## The starting point

Before returning data, each request had to check whether the caller was entitled to see it. A dedicated entitlement
service held that information, exposed over a REST API. Changes deployed through integration, preprod, and production in sequence — so failures that slipped past contract tests surfaced before reaching production.

Two teams, one endpoint, one shared understanding:

```
┌──────────────────┐          HTTP               ┌─────────────────────┐
│    Data API      │ ──────────────────────────► │   Entitlement API   │
│                  │                             │                     │
└──────────────────┘                             └─────────────────────┘
                       ^^^^^^^^^^^^^^^^^^^^^^
                             boundary
```

## Versioned endpoints

That held until a field rename in the `Entitlement API` hit the staging environment — the deserializer started throwing errors on every call.

The `Entitlement API` needed to restructure the response format to support a new authorization model. Under the existing
setup, every consumer had to migrate simultaneously — the `Data API` shared the same DTO, so any field change required a
coordinated deployment.

The problem was also release cadence. The `Entitlement API` and the `Data API` evolved independently, but they couldn't be
*deployed* independently. A response shape change in the entitlement service meant coordinating with the `Data API` team —
and if either side needed to roll back, the other was dragged along. Every release became a negotiation.

The solution was to add path-based versioning to the `Entitlement API`: `/v1/entitlements`, `/v2/entitlements`. Simple, visible and explicit. An [OpenAPI](https://www.openapis.org/) spec was published per version — a
machine-readable contract that makes the boundary explicit to any new consumer — alongside contract testing with [Pact](https://pact.io).
The discipline that mattered was keeping the DTOs, both in the server and in the client, fully isolated between versions. No shared types, no inheritance
between `v1.Entitlements` and `v2.Entitlements`. At first this felt redundant — the fields were nearly
identical. But it meant the `Data API` team could migrate to v2 on their own schedule: test it in parallel, roll back to
v1 without touching the entitlement service, and ship independently.

Isolated DTOs are what an independent release cycle looks like in practice, in the presence
of breaking changes. The duplication is the solution.

```
┌──────────────────┐   GET /v1/entitlements/...         ┌─────────────────────┐
│    Data API      │ ─────────────────────────────────► │   Entitlement API   │
│  (production)    │                                    │                     │
└──────────────────┘                                    └─────────────────────┘


┌──────────────────┐   GET /v2/entitlements/...         ┌─────────────────────┐
│    Data API      │ ─────────────────────────────────► │   Entitlement API   │
│    (staging)     │                                    │                     │
└──────────────────┘                                    └─────────────────────┘

```

[Postel's law](https://en.wikipedia.org/wiki/Robustness_principle) — *be conservative in what you send, be liberal in
what you accept* — offers partial protection here: configuring the deserializer to ignore unknown fields means additive
changes (new fields) are invisible to existing consumers and don't require coordination. But a rename
is still a breaking change, and silently mapping a missing field to null makes it worse — the system keeps running, just
wrong. Ignoring unknown fields is necessary but not sufficient; it doesn't replace a contract.

The versioned endpoint made the migration path explicit — the boundary stopped being a shared assumption and became a contract. Later, once the migration to v2 was complete, v1 was removed without any coordinated deployment.

## Point-in-time queries

In load testing, a delivery using the `Data API` completed with fields missing from the later records. No errors in the logs — the entitlement service responded correctly
every time. The problem was that calls in the latter half of a thousand-request delivery landed after an entitlement
update was applied. The result was a delivery that reflected two different authorization states.

The root cause was the delivery model. A package delivery wasn't a single `Data API` call — it was hundreds, sometimes
thousands of them, each checking entitlements independently. Under eventual consistency, the entitlement state could
shift mid-delivery: a field authorized on request 1 might be denied by request 800.

The solution was to treat the entitlement snapshot as part of the request contract. The client sent a timestamp; the
entitlement service returned the state *as of that moment*. One timestamp anchored the entire delivery to a consistent
view and it was actually useful to reproduce bugs or explain certain data issues to customers.

After some discussions between the two teams, the `Entitlement API` added `ETag` [^etag] support on top. Each entitlement change produces a new immutable snapshot; the ETag is the content hash of that snapshot. The `Data API` sends `If-None-Match` on subsequent calls — if the snapshot selected by `asOf` hasn't changed, the server returns `304 Not Modified` and the `Data API` uses its cached copy, skipping deserialization entirely:


```
┌──────────────────┐  GET /v1/entitlements/{user}?asOf=T  ┌─────────────────────┐
│    Data API      │ ───────────────────────────────────► │   Entitlement API   │
│                  │ ◄─────────────────────────────────── │                     │
└──────────────────┘     200 + ETag  /  304 Not Modified  └─────────────────────┘
```

That's harder to implement than pretending eventual consistency is fine, but
it's much simpler to reason about when something goes wrong[^asOf].

The timestamp parameter made the consistency boundary explicit — instead of assuming eventual consistency was acceptable, the delivery contract acknowledged the problem and gave both sides a way to work around it.

## Zero-trust at the transport layer

Internal services often rely on implicit network trust: if a caller is inside the perimeter, it is assumed safe. Zero-trust rejects that assumption — every caller must authenticate, regardless of where the call originates.

The entitlement service held authorization state for every user in the system. Treating it as implicitly trusted because it lives on an internal network was an issue waiting to be discovered by an audit. As more services needed entitlements, an API gateway was introduced to enforce identity and policy at the transport layer: routing, rate limiting, and — once each consumer had a client certificate — mutual TLS.

mTLS is the concrete implementation of zero-trust at the service boundary: the server authenticates the client, the client authenticates the server, and neither trusts the network between them. But the gateway introduced a boundary in its own right.

Every caller now needed a client certificate. Certificate rotation, expiry, and provisioning became their own
operational surface. The API Gateway introduced failure modes distinct from the `Entitlement API` itself and it
was operated by yet another team.

Those failure modes required explicit retry logic with exponential backoff and jitter. The boundary didn't disappear — it transformed into a more complex one.

```
┌──────────────────┐  mTLS   ┌─────────────────┐  HTTPS   ┌─────────────────────┐
│    Data API      │ ──────► │   API Gateway   │ ───────► │   Entitlement API   │
│                  │         │  (rate limiting │          │    /v1     /v2      │
└──────────────────┘         │   routing)      │          └─────────────────────┘
                             └─────────────────┘
```

Every boundary crossing — between services, between teams, between release lifecycles — carries an implicit
contract.

## Taming the boundary in the client code

The operational complexity outside the process was only half the story. Inside the `Data API`, the `Entitlement API`
integration also needed to be isolated and composable.

The starting point was a plain interface — [Fowler's Gateway pattern](https://martinfowler.com/eaaCatalog/gateway.html):

```java
/**
 * Fetches the entitlements for a user as of a given point in time.
 *
 * @see <a href="https://martinfowler.com/eaaCatalog/gateway.html">Gateway (EAA)</a>
 */
interface EntitlementApi {
    Entitlements fetch(UserId user, Instant asOf);
}
```

Everything behind that interface was hidden from the callers. They didn't know whether the backing
implementation was the real one or a stub. That isolation was what made the rest possible:
```java
// Real HTTP call to /v2/entitlements/{user}?asOf={T}
class HttpEntitlementApi implements EntitlementApi {
    HttpEntitlementApi(URI baseUri) { /* ... */ }
}
// Configurable in-memory stub for local development and system tests
class InMemoryEntitlementApi implements EntitlementApi {
    InMemoryEntitlementApi(ConcurrentHashMap<UserId, Entitlements> state) { /* ... */ }
}

```


In local development or system tests, `InMemoryEntitlementApi` replaced the whole stack. It could be programmed
dynamically per test case — return this set of entitlements for this user, revoke them after a timestamp — without any
HTTP involved. This was what made testing the point-in-time behavior tractable: precise state changes could be injected
without standing up the entitlement service.

From there, each non-functional concern became a [Decorator](https://en.wikipedia.org/wiki/Decorator_pattern) layered on top:

```java
// cache keyed on (user, timestamp) — avoids redundant calls within a delivery
// the retention policy is to keep the cache as long as it is used:
// if the key (userId/timestamp) is not used for 10 minutes, it is dropped
// (see https://github.com/ben-manes/caffeine)
class CachingEntitlementApi implements EntitlementApi {
    CachingEntitlementApi(EntitlementApi delegate) { /* ... */ }
}

// Failsafe retry policy with backoff (see https://failsafe.dev)
class FailsafeEntitlementApi implements EntitlementApi {
    FailsafeEntitlementApi(EntitlementApi delegate, RetryPolicy policy) { /* ... */ }
}

// Extra logging for specific environments
class LoggingEntitlementApi implements EntitlementApi {
    LoggingEntitlementApi(EntitlementApi delegate) { /* ... */ }
}

// other decorators can be added anytime: this is an extension point of the system
```

The production stack composed them, innermost to outermost:

```
   Data API caller
         │
         ▼
 LoggingEntitlementApi      ← logs request / response counters
         │
         ▼
FailsafeEntitlementApi      ← retry with backoff on failure
         │
         ▼
 CachingEntitlementApi      ← cache keyed on userId + asOf
         │  (cache miss)
         ▼
  HttpEntitlementApi        ← GET /v2/entitlements/...
         │
         ▼
   Entitlement API
```

Production-like config:
```java
EntitlementApi api =
    new LoggingEntitlementApi(
        new FailsafeEntitlementApi(
            new CachingEntitlementApi(
                new HttpEntitlementApi(baseUri)),
            retryPolicy));
```

The pattern works because the interface boundary is narrow and stable. Each decorator does one thing. The
composition is explicit and visible at the wiring point, not scattered across the codebase.

After this, two libraries quickly became the de-facto standards:
- [failsafe](https://failsafe.dev) for retries/bulkhead
- [caffeine](https://github.com/ben-manes/caffeine) for any local cache

The interface made the integration point explicit — every cross-boundary call, regardless of what sat behind it, had a single visible seam.

## The full picture

The entitlement integration didn't stay unique for long. Once the interface-plus-decorators pattern proved itself, it
became the standard approach for every external dependency as the `Data API` grew.
Every new integration got the same treatment: a narrow "Gateway" interface, an HTTP implementation,
a caching layer, a retry wrapper, and an in-memory fake for testing.

```
                          ┌──────────────────────────────────────── ... ─┐
                          │                   Data API                   │
                          │                                              │
                          │  EntitlementApi  ProductApi  SearchApi  ...  │
                          │                                              │
                          └──────────┬──────────┬──────────┬─────── ...  ┘
                                     │          │          │
                         ┌───────────┘          │          └──────────────┐
                         │                      │                         │
                         ▼                      ▼                         ▼
               ┌──────────────────┐   ┌──────────────────┐   ┌──────────────────────┐
               │   API Gateway    │   │  Product         │   │    Elasticsearch     │
               │   (mTLS, rate    │   │  Service         │   │    (search)          │
               │    limiting)     │   └──────────────────┘   └──────────────────────┘
               └────────┬─────────┘
                        │
                        ▼
             ┌─────────────────────┐
             │   Entitlement API   │
             │         /v2         │
             └─────────────────────┘
```

## What good boundary design looks like

The first version was trivial to write: one HTTP call, no versioning, no decorators. But it's the hardest to
operate — a response shape change means a coordinated rollback across two teams, and a consistency bug mid-delivery is
nearly invisible until it surfaces as wrong data.

The current version is the opposite. More moving parts in code: versioned endpoints, isolated DTOs, a decorator stack, a
timestamp parameter, mTLS, retries, etc. But operationally it's predictable: each team ships independently, failures are isolated,
consistency problems surface as explicit errors rather than silent data drift. *The complexity moves from the runtime —
where it is invisible — into the code, where it can be read, tested, and reasoned about.*

That trade-off is worth being deliberate about. The job is not to eliminate failure — it's to make failure *transient*,
*observable*, and *auditable*. The retry decorator `FailsafeEntitlementApi` handles transient network errors.
The cache absorbs repeated calls. The in-memory stub removes the external dependency in tests. The mTLS gateway enforces identity at the transport layer.

None of these are heroic. They're the result of treating each boundary as something to design, own, and evolve — rather
than something to patch over.

```
Before                                   After
─────────────────────────────────────    ────────────────────────────────────────────
Data API ──HTTP──► Entitlement API       Data API ──mTLS──► API Gateway
                                                  ──HTTPS──► Entitlement API /v2
shared DTO                               isolated DTOs per version (OpenAPI + Pact)
coupled deploys                          independent deploys
perimeter trust                          mutual TLS, explicit identity
eventual consistency                     ?asOf= timestamp, ETag / 304
flat HTTP call                           decorator stack (cache, retry, logging)
```

- **The decorator stack scaled because someone named it early.** It wasn't invented as a system-wide pattern — it
  emerged from one problem. What made it durable was treating it as the standard rather than a one-off, so when the
  product-data integration came, and then the calculation service, and then Elasticsearch, nobody reinvented it. That kind
  of generalization required someone to notice the pattern and name it explicitly — before the second integration, not
  after the fifth.

- **Nobody owned the seam, and that was the root cause.** The early pain — coordinated rollbacks, no
  versioning — came from two teams each owning their own service but nobody owning the seam between them. Versioned
  endpoints and isolated DTOs only became possible once both teams accepted responsibility for the contract itself.

- **Isolated DTOs are what independent deployment actually looks like.** They felt like over-engineering at first. In
  practice, they were what made it possible for two teams to ship on different schedules. The duplication is the point.

## When to apply this

The signal is usually a deployment that required more coordination than it should have, or a bug that only appeared under
load because an assumption about consistency was never written down.

The pattern applies wherever two teams share a boundary without anyone owning the seam between them. The cost of leaving it implicit scales with how often either side changes and how many consumers sit downstream. A single team, a single consumer, infrequent change: the informal contract probably survives. Two teams, multiple consumers, frequent change: it won't.

A useful test: can either team deploy today without coordinating with the other? If not, the boundary is implicit.

## Outcome

The outcome is that two teams shipped on independent schedules, with no coordinated rollbacks.

The point-in-time contract eliminated an entire class of consistency problems: deliveries spanning thousands of API
calls completed with a single coherent authorization state.

On the `Data API` side, the `CachingEntitlementApi` decorator cut entitlement calls by 99.5% (5K out of every 1M forwarded to the `Entitlement API`) — almost every call was a
cache hit. This matched exactly how the `Data API` was used: when a downstream application started a delivery, it used
the same `userId`/`asOf` repeatedly until all data was delivered. When the delivery ended, it stopped. The 0.5% misses represented only the first call per delivery, when the cache was cold. The cache entry expires 10 minutes after last use in each `Data API` node. On those misses, the `ETag`/`304` path further reduced overhead — since user entitlements changed rarely, most cache-cold calls still returned `304 Not Modified`.

These gains weren't optimizations — they were the side-effects of making boundaries explicit.

The strategy that ran through all of this was the same: make the implicit explicit and
[writing down the why](https://dfa1.github.io/articles/write-down-the-why) to share the problem/solution pair with
other teams.

Software breaks at boundaries because that's where assumptions accumulate faster than feedback.

---

[^etag]: The `asOf` timestamp selects the snapshot that was current at a given moment. See [ETag](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/ETag).

[^asOf]: snapshots are kept for slightly more than 24h before expiring and freeing the underlying storage. Within a delivery, the `CachingEntitlementApi` decorator handles repetition at the in-process level — same `userId`/`asOf` key hits the local cache without any HTTP call. The `ETag`/`304` path kicks in only on cache misses, avoiding unnecessary deserialization when the snapshot hasn't changed between deliveries.

[^zen]: the title is a tribute to "Explicit is better than implicit." — [The Zen of Python](https://peps.python.org/pep-0020/).

