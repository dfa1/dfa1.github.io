# Distributed systems fail at the boundaries

*10 June 2021*

*A GraphQL API that needed per-field authorization. A series of forced decisions — versioned contracts, point-in-time
consistency, decorator-based composition — each triggered by a different failure. The hardest boundaries
turned out to be organizational, not technical. The patterns that emerged became the blueprint for every external
integration that followed.*

## The starting point

The setup was straightforward: a GraphQL data API with per-field authorization. Every resolver, before returning data,
had to check whether the caller was entitled to see that field. A dedicated entitlement service held that information,
exposed over HTTP/REST.

At this stage, we had two teams, one endpoint, and a shared understanding:

```
┌──────────────────┐          HTTP               ┌─────────────────────┐
│    Data API      │ ──────────────────────────► │   Entitlement API   │
│   (GraphQL)      │                             │                     │
└──────────────────┘                             └─────────────────────┘
                       ^^^^^^^^^^^^^^^^^^^^^^
                             boundary
```

## Versioned endpoints

That worked until the
entitlement team renamed a response field on a rainy Tuesday. The data API's deserializer was throwing errors; every downstream call was failing for the same reason.

The entitlement team needed to restructure the response format to support a new authorization model. Under the existing
setup, every consumer had to migrate simultaneously — the data API shared the same DTO, so any field change was a
coordinated deployment.

The problem was also release cadence. The entitlement API and the data API evolved independently, but they couldn't be
*deployed* independently. A response shape change in the entitlement service meant coordinating with the data API team —
and if either side needed to roll back, the other was dragged along. Every release became a negotiation.

The solution was to add path-based versioning to the EntitlementApi: `/v1/entitlements`, `/v2/entitlements`. Simple, visible, easy to
route at the gateway level. We also started publishing an [OpenAPI](https://www.openapis.org/) spec per version — a
machine-readable contract that made the boundary explicit to any new consumer — and adopted contract testing with [Pact](https://pact.io).
The discipline that mattered was keeping the DTOs fully isolated between versions. No shared types, no inheritance
between `v1.EntitlementResponse` and `v2.EntitlementResponse`. At first this felt redundant — the fields were nearly
identical. But it meant the data API team could migrate to v2 on their own schedule: test it in parallel, roll back to
v1 without touching the entitlement service, and ship independently.

Isolated DTOs are the concrete implementation of an independent release cycle in the presence
of breaking changes. The duplication is the solution.

```
┌──────────────────┐   HTTPS GET /v1/entitlements/...   ┌─────────────────────┐
│    Data API      │ ─────────────────────────────────► │   Entitlement API   │
│   (GraphQL)      │                                    │                     │
└──────────────────┘                                    └─────────────────────┘


and on the staging environment

┌──────────────────┐   HTTPS GET /v2/entitlements/...   ┌─────────────────────┐
│    Data API      │ ─────────────────────────────────► │   Entitlement API   │
│   (GraphQL)      │                                    │                     │
└──────────────────┘                                    └─────────────────────┘

```

Once the migration to v2 was complete, v1 was removed without any coordinate deployment.

[Postel's law](https://en.wikipedia.org/wiki/Robustness_principle) — *be conservative in what you send, be liberal in
what you accept* — offers partial protection here: configuring the deserializer to ignore unknown fields means additive
changes (new fields) are invisible to existing consumers and don't require coordination. But a rename
is still a breaking change, and silently mapping a missing field to null made it worse — the system kept running, just
wrong. Ignoring unknown fields is necessary but not sufficient; it doesn't replace a contract.

## Point-in-time queries

A delivery arrived with half its fields missing. No errors in the logs — the entitlement service had responded correctly
every time. The problem was that two calls in the middle of a thousand-request delivery had landed after an entitlement
update was applied. The client received a package that reflected two different authorization states.

The root cause was the delivery model. A package delivery wasn't a single `Data API` call — it was hundreds, sometimes
thousands of them, each checking entitlements independently. Under eventual consistency, the entitlement state could
shift mid-delivery: a field authorized on request 1 might be denied by request 800.

The fix was to treat the entitlement snapshot as part of the request contract. The client sends a timestamp; the
entitlement service returns the state *as of that moment*. One timestamp anchors the entire delivery to a consistent
view.

The `Entitlement Api`'s team added `ETag` [^etag] caching on top to make this solution scalable. If the entitlement snapshot hadn't changed since the last call, the service returned
`304 Not Modified` and the data API used its cached copy. On high-volume deliveries this collapsed hundred of thousands
of round-trips down to a handful.

The timestamp made consistency *explicit*. That's harder to implement than pretending eventual consistency is fine, but
it's much simpler to reason about when something goes wrong [^asOf].

```
┌──────────────────┐  GET /entitlements/{user}?asOf={T}   ┌─────────────────────┐
│    Data API      │ ───────────────────────────────────► │   Entitlement API   │
│   (GraphQL)      │ ◄─────────────────────────────────── │                     │
└──────────────────┘     200 + ETag  /  304 Not Modified  └─────────────────────┘
```

## Transport security and API-GW

HTTPS came next — not a design decision so much as a forced one.
A third team integrated with the entitlement API directly — and called production from their staging environment for two
weeks before anyone noticed. No enforced authentication at the transport layer, no rate limiting, nothing to prevent an
accidental consumer from adding load. That was the event that made a gateway non-negotiable.

As more services needed entitlements, an API gateway appeared to handle routing, rate limiting, and — eventually —
mutual TLS. mTLS was the right security choice, but it introduced a boundary in its own right.

Every caller now needed a client certificate. Certificate rotation, expiry, and provisioning became their own
operational surface. The gateway introduced failure modes distinct from the entitlement service itself: gateway
timeouts, gateway retries, and a retry storm at the gateway level that looks nothing like a retry at the application
level — it amplifies instead of absorbs.

We had to define separate timeout budgets for the gateway hop and the service hop, and implement retries explicitly with
exponential backoff and jitter. The boundary didn't disappear — it transformed into a
more complex one.

```
┌──────────────────┐  mTLS   ┌─────────────────┐  HTTPS   ┌─────────────────────┐
│    Data API      │ ──────► │   API Gateway   │ ───────► │   Entitlement API   │
│   (GraphQL)      │         │  (rate limiting │          │    /v1     /v2      │
└──────────────────┘         │   routing)      │          └─────────────────────┘
                             └─────────────────┘
```

Each time you cross a boundary — between services, between teams, between release lifecycles — you're making an implicit
contract. The cost of leaving it implicit shows up later on another incident.

## Taming the boundary in the client code

The operational complexity outside the process is only half the story. Inside the data API, the entitlement service
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

Everything behind that interface is hidden from the GraphQL resolvers. They don't know whether the backing
implementation is HTTP, cached, or in-memory. That isolation is what makes the rest possible. The Javadoc is also where
the *why* lives — a direct link back to the pattern that motivated the design, for whoever reads this six months later.

```java
// Real HTTP call to /v1/entitlements/{user}?asOf={T}
class HttpEntitlementApi implements EntitlementApi {
	HttpEntitlementApi(URI baseUri) { /* ... */ }
}
// Configurable in-memory stub for local development and system tests
class InMemoryEntitlementApi implements EntitlementApi {
    InMemoryEntitlementApi(ConcurrentHashMap<UserId, Entitlements> state) { /* ... */ }
}
```

From there, each concern became a [Decorator](https://en.wikipedia.org/wiki/Decorator_pattern) layered on top:

```java
// cache keyed on (user, timestamp) — avoids redundant calls within a delivery
// the retention policy is to keep the cache as long as it is used:
// if the key (userId/timestamp) is not used for 5 minutes, it is dropped
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

// DistributedTracing adds the X-RequestId header
// other decorators can be added anytime: this is an extension point of the system

```

The production stack composes them, innermost to outermost:

```
  GraphQL resolver
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
  HttpEntitlementApi        ← GET /v1/entitlements/...
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

In local development or system tests, `InMemoryEntitlementApi` replaces the whole stack. It can be programmed
dynamically per test case — return this set of entitlements for this user, revoke them after a timestamp — without any
HTTP involved. This is what makes testing the point-in-time behavior tractable: you can inject precise state changes
without standing up the entitlement service.

The pattern works because the interface boundary is narrow and consistent. Each decorator does one thing. The
composition is explicit and visible at the wiring point, not scattered across the codebase.

## The full picture

The entitlement integration didn't stay unique for long. Once the interface-plus-decorators pattern proved itself, it
became the standard approach for every external dependency the data API grew. Product-data lookups, on-the-fly field
calculations, Elasticsearch-backed search — each one got the same treatment: a narrow interface, an HTTP implementation,
a caching layer, a retry wrapper, and an in-memory stub for testing.

```
                          ┌──────────────────────────────────────── ... ─┐
                          │              Data API (GraphQL)              │
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
           ┌────────────┴─────────────┐
           │                          │
           ▼                          ▼
┌─────────────────────┐    ┌─────────────────────┐
│   Entitlement API   │    │    Entitlement API  │
│         /v1         │    │        /v2          │
└─────────────────────┘    └─────────────────────┘
```

Every integration follows the same composition stack: logging → retry → cache → HTTP. The in-memory fake replaces the
entire stack in tests. The wiring is visible at one place, not scattered across the codebase. What looked like a
response to a specific problem with the entitlement service turned out to be a general answer to the question of how to
integrate with anything external (see [Gateway](https://martinfowler.com/articles/gateway-pattern.html)).

## Entropy between teams

**The technical boundary is the easy one.** The decisions that caused the most pain — no versioning, synchronized releases and rollbacks — were downstream of organizational ones: a team that designed their system in isolation, where every release required multiple meetings just to coordinate. Inter-team dependencies like that can sometimes be managed, but it's far better to remove them.

Software breaks at boundaries because that's where assumptions accumulate. A team building in isolation always makes their
system work — they control the inputs. The interesting failures happen just outside that box: a downstream client changes a field
in production, an API gateway upgrades and silently alters timeout behavior, a certificate expires on a Saturday because
nobody tracked it. **That's entropy** — not bugs, not negligence, just the natural drift between systems that don't
share a feedback loop.

How do you design systems that survive this? I don't have a full answer, and I'm skeptical of people who claim they
do. The technical tools help — explicit interfaces, versioned contracts, [contract testing](https://pact.io), retry
policies — but *they address the symptoms*. What seems to matter more is a mixture of **clear expectations at each
boundary** (ownership, versioning guarantees, SLA commitments that someone is actually accountable for),
**communication that doesn't require scheduling a meeting to happen**, and the willingness to push back on solutions
that work for one team only. Teams need to see beyond their own service boundary — to understand the context they
operate in and accept changes that make the *overall* system better, even when those changes add friction locally.
That's not an engineering problem; it's a sociotechnical one.

## Architecture is the art of shaping boundaries

The first version was operationally simple: one HTTP call, no versioning, no decorators. And yet, it was also the hardest to
operate — a response shape change meant a coordinated rollback across two teams, and a consistency bug mid-delivery was
nearly invisible until it surfaced as wrong data in production.

The current version is the opposite. More moving parts in code: versioned endpoints, isolated DTOs, a decorator stack, a
timestamp parameter, mTLS, retries, etc. But operationally it's predictable: each team ships independently, failures are isolated,
consistency problems surface as explicit errors rather than silent data drift. *The complexity moved from the runtime —
where it was invisible — into the code, where it can be read, tested, and reasoned about.*

That trade-off is worth being deliberate about. The job is not to eliminate failure — it's to make failure *transient*,
*observable*, and *auditable*. The retry decorator `FailsafeEntitlementApi` handles transient network errors.
The cache absorbs repeated calls. The in-memory stub removes the external dependency in tests. The mTLS gateway enforces identity at the transport layer.

None of these are heroic. They're the result of treating each boundary as something to design, own, and evolve — rather
than something to patch over.

**Local solutions that generalize are worth naming.** The decorator stack wasn't invented as a company-wide pattern — it
emerged from one problem. What made it durable was treating it as the standard rather than a one-off, so when the
product-data integration came, and then the calculation service, and then Elasticsearch, nobody reinvented it. That kind
of generalization requires someone to notice the pattern and name it explicitly — before the second integration, not
after the fifth.

**The contract and the why need to be shared.** The early pain — synchronized rollbacks, no
versioning — came from two teams each owning their own service but nobody owning the seam between them. Versioned
endpoints and isolated DTOs only became possible once someone accepted responsibility for the contract itself.

**Isolated DTOs are what independent deployment actually looks like.** They felt like over-engineering at first. In
practice, they were what made it possible for two teams to ship on different schedules. The duplication is the point.

## Outcome

The 2 teams shipped on independent schedules, with no coordinated rollbacks.

The point-in-time contract eliminated an entire class of consistency incidents: deliveries spanning thousands of API
calls now complete with a single coherent authorization state. The `ETag` cache on the `Entitlement API`
is quite effective (as user entitlements changes rarely).

On the `Data API` side, the `CachingEntitlementApi` decorator cut entitlement calls by 99.5% — almost every call is a
cache hit. It is exactly how the Data API is designed: when some downstream application starts a delivery, it uses
the same `userId`/`asOf` repeatedly until all data is delivered. When it is done, it stops. So the 0.5% misses represent
only the first call when the cache is empty. The cache stays alive for 10 minutes more, then it expires in each
`Data API` node.  What is also interesting is the number of calls it is saving: every 1M calls, only 5K are actually
forwarded to the `Entitlement API`.

Distributed systems fail at the boundaries because that’s where assumptions accumulate
faster than feedback. Align your teams on this early on and remember to [write down the why](https://dfa1.github.io/articles/write-down-the-why).

---

[^etag]: every change in the entitlements produces a new snapshot.  that is used to build the `ETag` in the `Entitlement API`.
The `Data API` requests with `if-none-match` as described [here](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/ETag).

[^asOf]: every snapshot is kept for a short period of time (slightly more than 24h). After that it expires and frees
the underlying storage, and it is referenced by id as the `ETag`.
