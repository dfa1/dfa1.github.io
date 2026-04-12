# Distributed systems fail at the boundaries

*12 April 2026*

*A GraphQL API that needed per-field authorization. Each fix exposed the next boundary. Here's what we built, and what it cost us.*

## The starting point

The setup was straightforward: a GraphQL data API with per-field authorization. Every resolver, before returning data, had to check whether the caller was entitled to see that field. A dedicated entitlement service held that information, exposed over HTTP/REST.

No versioning. No formal contract. Two teams, one endpoint, and a shared understanding that worked until it didn't.

The first problem was release cadence. The entitlement API and the data API evolved independently, but they couldn't be *deployed* independently. A response shape change in the entitlement service meant coordinating with the data API team — and if either side needed to roll back, the other was dragged along. Every release became a negotiation. Every rollback a fire drill.

The boundary existed. It just wasn't owned.

```
┌──────────────────┐          HTTP/REST          ┌─────────────────────┐
│    Data API      │ ──────────────────────────► │   Entitlement API   │
│   (GraphQL)      │                             │   (no versioning)   │
└──────────────────┘                             └─────────────────────┘
```

## Point-in-time queries

Things got harder when we added point-in-time queries. Delivering a package to a client wasn't a single GraphQL call — it was hundreds, sometimes thousands of them, each checking entitlements independently. Under eventual consistency, the entitlement state could shift mid-delivery: a field authorized on request 1 might be denied by request 800.

The fix was to treat the entitlement snapshot as part of the request contract. The client sends a timestamp; the entitlement service returns the state *as of that moment*. One timestamp anchors the entire delivery to a consistent view.

We added ETag caching on top. If the entitlement snapshot hadn't changed since the last call, the service returned `304 Not Modified` and the data API used its cached copy. On high-volume deliveries this collapsed thousands of entitlement round-trips down to a handful.

The timestamp made consistency *explicit*. That's harder to implement than pretending eventual consistency is fine, but it's much simpler to reason about when something goes wrong.

```
┌──────────────────┐  GET /entitlements/{user}?asOf={T}  ┌─────────────────────┐
│    Data API      │ ───────────────────────────────────► │   Entitlement API   │
│   (GraphQL)      │ ◄─────────────────────────────────── │                     │
└──────────────────┘     200 + ETag  /  304 Not Modified  └─────────────────────┘
```

## Versioning the contract

HTTPS came next — not a design decision so much as a forced one, once the entitlement API started carrying PII. Transport integrity was non-negotiable.

With HTTPS in place, we added path-based versioning: `/v1/entitlements`, `/v2/entitlements`. Simple, visible, easy to route at the gateway level.

The discipline that mattered was keeping the DTOs fully isolated between versions. No shared types, no inheritance between `v1.EntitlementResponse` and `v2.EntitlementResponse`. At first this felt redundant — the fields were nearly identical. But it meant the data API team could migrate to v2 on their own schedule: test it in parallel, roll back to v1 without touching the entitlement service, and ship independently.

Isolated DTOs are the concrete implementation of an independent release cycle. The duplication is the point.

```
┌──────────────────┐   HTTPS GET /v1/entitlements/...   ┌─────────────────────┐
│    Data API      │ ─────────────────────────────────► │   Entitlement API   │
│   (GraphQL)      │                                    │    /v1     /v2       │
└──────────────────┘                                    └─────────────────────┘
```

## The API gateway

As more services needed entitlements, an API gateway appeared to handle routing, rate limiting, and — eventually — mutual TLS. mTLS was the right security choice. It was also a new boundary.

Every caller now needed a client certificate. Certificate rotation, expiry, and provisioning became their own operational surface. The gateway introduced failure modes distinct from the entitlement service itself: gateway timeouts, gateway retries, and a retry storm at the gateway level that looks nothing like a retry at the application level — it amplifies instead of absorbs.

We had to define separate timeout budgets for the gateway hop and the service hop, and implement retries explicitly with exponential backoff and jitter. The boundary didn't disappear — it multiplied.

```
┌──────────────────┐  mTLS  ┌─────────────────┐  HTTPS  ┌─────────────────────┐
│    Data API      │ ──────► │   API Gateway   │ ───────► │   Entitlement API   │
│   (GraphQL)      │         │  (rate limiting │          │    /v1     /v2       │
└──────────────────┘         │   routing)      │          └─────────────────────┘
                             └─────────────────┘
```

## What the layers reveal

Each simplification in this system eventually ran into a boundary it wasn't designed for. The unversioned REST endpoint was simple until synchronized rollbacks became the tax. Point-in-time snapshots added complexity, but turned an invisible consistency problem into an explicit contract parameter. Isolated DTOs looked like duplication until they decoupled two teams' deployment schedules. The gateway solved security and routing, then introduced its own failure surface.

None of these problems were obvious at the start. But each time you cross a boundary — between services, between teams, between release lifecycles — you're making an implicit contract. The cost of leaving it implicit shows up later, at 2am, during a rollback.

## Taming the boundary in code

The operational complexity outside the process is only half the story. Inside the data API, the entitlement service integration also needed to be isolated and composable.

The starting point was a plain interface — Fowler's [Gateway pattern](https://martinfowler.com/eaaCatalog/gateway.html):

```java
interface EntitlementApi {
    Entitlements fetch(UserId user, Instant asOf);
}
```

Everything behind that interface is hidden from the GraphQL resolvers. They don't know whether the backing implementation is HTTP, cached, or in-memory. That isolation is what makes the rest possible.

From there, each concern became a Decorator layered on top:

```java
// Real HTTP call, used in production
class HttpEntitlementApi implements EntitlementApi {
    private final HttpClient http;
    private final URI baseUri;
    private final ObjectMapper mapper;

    HttpEntitlementApi(URI baseUri) {
        this.http = HttpClient.newHttpClient();
        this.baseUri = baseUri;
        this.mapper = new ObjectMapper();
    }

    @Override
    public Entitlements fetch(UserId user, Instant asOf) {
        URI uri = baseUri.resolve(
            "/v1/entitlements/" + user.value() + "?asOf=" + asOf.toEpochMilli());
        HttpRequest request = HttpRequest.newBuilder(uri)
            .GET()
            .header("Accept", "application/json")
            .build();
        try {
            HttpResponse<String> response =
                http.send(request, BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new EntitlementException("unexpected status: " + response.statusCode());
            }
            return mapper.readValue(response.body(), Entitlements.class);
        } catch (IOException | InterruptedException e) {
            throw new EntitlementException("fetch failed", e);
        }
    }
}

// Caffeine-backed cache — avoids redundant calls within a delivery
class CachingEntitlementApi implements EntitlementApi {
    CachingEntitlementApi(EntitlementApi delegate) { ... }
}

// Resilience4j (or Failsafe) retry policy with backoff
class FailsafeEntitlementApi implements EntitlementApi {
    FailsafeEntitlementApi(EntitlementApi delegate, RetryPolicy policy) { ... }
}

// Extra logging for specific environments or debugging
class LoggingEntitlementApi implements EntitlementApi {
    LoggingEntitlementApi(EntitlementApi delegate) { ... }
}

// Configurable in-memory stub for local development and system tests
class InMemoryEntitlementApi implements EntitlementApi { ... }
```

The production stack composes them, innermost to outermost:

```
  GraphQL resolver
         │
         ▼
 LoggingEntitlementApi      ← logs request / response
         │
         ▼
FailsafeEntitlementApi      ← retry with backoff on failure
         │
         ▼
 CachingEntitlementApi      ← Caffeine, keyed on user + asOf
         │  (cache miss)
         ▼
  HttpEntitlementApi        ← java.net.http → /v1/entitlements/...
         │
         ▼
   Entitlement API
```

```java
EntitlementApi api =
    new LoggingEntitlementApi(
        new FailsafeEntitlementApi(
            new CachingEntitlementApi(
                new HttpEntitlementApi(config)),
            retryPolicy));
```

In local development or system tests, `InMemoryEntitlementApi` replaces the whole stack. It can be programmed dynamically per test case — return this set of entitlements for this user, revoke them after a timestamp — without any HTTP involved. This is what makes testing the point-in-time behavior tractable: you can inject precise state changes without standing up the entitlement service.

The pattern works because the interface boundary is narrow and consistent. Each decorator does one thing. The composition is explicit and visible at the wiring point, not scattered across the codebase.

## Good boundaries simplify the system

When boundaries are explicit, predictable, and owned, the system becomes easier to reason about — not harder. The `EntitlementApi` interface is a boundary. The versioned REST endpoints are a boundary. The timestamp parameter is a boundary. Each one made the system *more* controllable, not more complex.

## Architecture is the art of shaping boundaries

The job is not to eliminate failure. It's to make failure survivable, observable, and boring. The retry decorator handles transient network errors. The cache absorbs repeated calls. The in-memory stub removes the external dependency in tests. The mTLS gateway enforces identity at the transport layer.

None of these are heroic. They're the result of treating each boundary as something to design, own, and evolve — rather than something to paper over.

## The boundary you can't wrap in a decorator

Every example above is a technical story. But the decisions that caused the most pain — no versioning, synchronized rollbacks, a gateway nobody owned end-to-end — were downstream of organizational ones. A team that designed their system in isolation. A release day that became a political constraint. A temporary integration that three teams inherited.

Software breaks at boundaries because that's where assumptions meet. A team building in isolation always makes their system work — they control the inputs. The interesting failures happen just outside: a downstream client changes a field in production, an API gateway upgrades and silently alters timeout behavior, a certificate expires on a Saturday because nobody tracked it. That's entropy. Not bugs, not negligence — just the natural drift between systems that don't share a feedback loop.

How do you design systems that survive this? I don't have a full answer yet, and I'm skeptical of people who claim they do. The technical tools help — explicit interfaces, versioned contracts, retry policies — but they address the symptoms. What seems to matter more is a mixture of clear expectations at each boundary (ownership, versioning guarantees, SLA commitments that someone is actually accountable for), communication that doesn't require scheduling a meeting to happen, and the willingness to push back on solutions that work for one team only. The temporary integration without versioning is always somebody's production dependency six months later.

The architectural patterns in this article are tools for making technical boundaries survivable. The harder problem is the socio-technical boundary — the gap between what one team assumes about another. That one doesn't have an interface you can define, and no decorator absorbs its failures.
