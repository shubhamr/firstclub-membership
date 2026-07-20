# Architecture: FirstClub Membership

Design of the membership backend across five areas: concurrency and data consistency, failure domains,
database and caching, domain extensibility, and a register of subscription-specific production risks. Each
item states the problem and the code that solves it; deferred work states the exact change that closes it.

Guiding principle: push invariants down to the layer that can guarantee them. Correctness that depends on
application code winning a race is not correctness; a database constraint is.

---

## Design at a glance

```
Controller (idempotency edge, RFC-7807 errors)
   → SubscriptionService  (tight @Transactional, @Version optimistic locking)
        → PlanService / TierService            (in-memory cached catalog)
        → TierAssignmentService + TierRule[]   (pluggable progression engine)
        → BenefitResolver                      (configurable per-tier perks)
        → PaymentPort   (adapter: circuit breaker + fallback)      ← hexagonal seam
        → NotificationPort (adapter: bounded @Async, fire-and-forget)
   Guards: uq_active_subscription_per_user (DB) · @Version (JPA) · idempotency_key (store)
```

Anything the business will reconfigure (tiers, benefits, progression criteria) is a row, not a code enum.
Services depend on ports; external systems are adapters.

---

## 0. Layering, structure & CQRS

### Layering: package-by-layer
The classic layers are their own packages:

```
controller/   REST endpoints (no business logic; idempotency edge)
service/      application logic + transactions   ├─ rules/  (tier strategy engine)
repository/   Spring Data repositories
model/        JPA entities, domain enums, value objects
dto/          API request/response records (entities never cross the wire)
gateway/      ports to external systems          ├─ adapter/  (circuit breaker / async impls)
config/       Spring @Configuration
exception/    domain exceptions + RFC-7807 handler
```

Boundaries are enforced: controllers delegate straight to services; services own the `@Transactional`
boundary; entities stay behind the service layer (DTOs cross the wire); external systems are reached only
through `gateway/` ports. The cost is the usual package-by-layer one — a feature change touches several
packages — which the ports/strategy seams keep localized.

### CQRS
Reads and writes are already distinct paths: query (`getMembership`, `listTiers`, `currentActivity`) versus
command (`subscribe`/`upgrade`/`cancel`, `ActivityIngestionService`, `BenefitAdminService`). Full CQRS
(separate models/datastores, event sourcing, async projections) is not used: at this scale it adds
eventual-consistency complexity with no payoff. The upgrade path, if scale demands it, is in §3/§4 (cache
read models, outbox projections).

### Design patterns

| Pattern | Where | Why |
|---|---|---|
| Strategy | `service/rules/TierRule` (progression); `service/pricing/PricingStrategy` (mode) | Behaviour varies and grows; new variant = new bean, no flow change |
| Factory | `service/pricing/PricingStrategyFactory` (resolves strategy by `PricingMode`) | Strategies self-register; rejects a duplicate or missing mode at startup rather than mispricing silently |
| Ports & Adapters (DIP) | `gateway/PaymentPort`, `gateway/NotificationPort` ← `gateway/adapter/*` | External systems swappable/testable; domain depends on abstractions |
| Observer | `event/MembershipLifecycleEvent` + `@TransactionalEventListener` | Side effects (notify/audit/webhooks) fire after commit, decoupled from the command |
| State | `model/SubscriptionStatus` transition table | Lifecycle rules in one place; illegal transitions rejected at the domain |
| Repository | `repository/*` (Spring Data) | Persistence behind an abstraction |
| Decorator | Resilience4j circuit breaker around the payment adapter | Cross-cutting resilience without touching call sites |
| Static Factory Method | `from()`/`of()` on DTOs + `UserActivity` | Intention-revealing construction |
| Guarded Command / Template | `IdempotencyService.runOnce(Supplier)` | At-most-once execution wraps any command uniformly |

Ports, strategies and repositories are interfaces because behaviour there varies. `PaymentPort` is the
clearest case: it is substituted for declining and throwing fakes in `TransactionBoundaryIntegrationTest`
and `RenewalDunningIntegrationTest`. Every application service is also exposed through an `Xxx` + `XxxImpl`
interface as a team convention; those have one implementation each today and the suite is integration-first
against real Postgres, so they buy consistency rather than substitutability.

---

## 1. Concurrency & Data Consistency

### 1.1 Double-subscribe under simultaneous requests
Two concurrent subscribes (double-click, retry after a gateway timeout): an app-level "already subscribed?"
check passes in *both* before either commits → two active memberships, double billing. A read-then-write
check across transactions is racy; only a DB constraint sees all writers.

Enforced by a partial unique index: `create unique index uq_active_subscription_per_user on
subscription(user_id) where status in ('PENDING','ACTIVE')`. Introduced over `status='ACTIVE'` in
`V1__schema.sql`, widened to `PENDING`+`ACTIVE` in `V11__reservation_unique_index.sql` once the charge
moved outside the transaction (§2.3) so the invariant holds *before* any money moves. At most one live row
per user. The pre-check in `SubscriptionService.subscribe` is a UX nicety; the index is the guarantee, and
the violation maps to a 409 in `ApiExceptionHandler`, never a 500. *Verified:*
`concurrentSubscribe_createsExactlyOneActiveSubscription` fires 8 simultaneous subscribes, asserts one wins.

### 1.2 Lost updates on tier change
A manual upgrade races an order-triggered re-evaluation: both read tier=SILVER, both write, last writer wins
and the other mutation vanishes with its audit/billing side effects. Pessimistic locks serialize but throttle
throughput and invite deadlocks; optimistic locking detects the conflict instead — the better trade when
conflicts are rare, and tier changes are rare.

`@Version long version` on `Subscription`; a losing writer gets `OptimisticLockException` → 409 ("reload and
retry"). No lost updates, no held locks. *Verified:* `concurrentUpgrade_toSameTier_appliesExactlyOnce`.

### 1.3 The idempotency gap
Networks retry (client timeout on `POST /subscriptions`, broker at-least-once redelivery); without dedupe each
retry is a fresh mutation — double charges, duplicate upgrades. Exactly-once comes from making the *operation*
idempotent, keyed by a client-supplied token.

Mutation endpoints accept an `Idempotency-Key` header; `IdempotencyService.runOnce` stores the response keyed
by `(key, endpoint)` (unique constraint) and replays it on retry instead of re-executing. Layered with §1.1
so a truly-concurrent duplicate that slips past the store is still stopped by the active-subscription index.
*Verified:* `idempotentSubscribe_replaysOriginalResult_withoutDoubleMutation`.

Not built — reserve-first: today the action runs, then the key is written, so two simultaneous first-time
requests can both execute (the DB index still prevents double state; one gets 409). To collapse concurrent
first-timers too, insert the idempotency row as `PENDING` before running (unique key ⇒ the second fast-fails
"in progress"), then update with the result. A short TTL plus a sweeper reaps abandoned PENDING keys.

### 1.4 Transaction boundaries & isolation
Wide transactions hold connections and locks; `SERIALIZABLE` everywhere trades throughput for safety most
operations don't need. Transactions are scoped to the single service method (`@Transactional` on
`SubscriptionService` mutations), default READ_COMMITTED — correctness comes from the unique index and
`@Version`, not the isolation level. OSIV is off (`spring.jpa.open-in-view: false`) so lazy loading can't
hold a connection across the view render.

### 1.5 Thread-pool saturation on async work
A notification storm (mass expiry, marketing blast) on Spring's default unbounded executor spawns threads
until the JVM OOMs. `AsyncConfig` defines a named `ThreadPoolTaskExecutor` (core 4 / max 8, queue 500) run
via `@Async("notificationExecutor")`, and sheds on saturation. `CallerRunsPolicy` is rejected: it hands work
back to the *request* thread exactly when the pool is saturated, turning a notification backlog into an API
slowdown — the opposite of why `AsyncNotificationAdapter` exists. Notifications are droppable (the adapter
swallows failures, and the subscription commits before any run), so the rejection handler drops the task and
increments `membership.notification.dropped` to make shedding visible. Work that must not be dropped belongs
in a transactional outbox, not a bigger queue.

### 1.6 Concurrent activity ingestion
Two order events for the same user arrive together; a naive read-modify-write on the stats row loses one, or
both INSERT and collide. `UserOrderStatsRepository.upsert` is a native `INSERT … ON CONFLICT (user_id) DO
UPDATE`, atomic and race-safe. The follow-on tier re-evaluation retries on optimistic conflict via
`OptimisticRetry` (each attempt a fresh transaction), so a background recompute never 409s a user.

### 1.7 Concurrency primitives: DB-level by design (no `synchronized`, no `SELECT … FOR UPDATE`)
Every concurrency invariant is enforced at the database — the only coordination point shared across
instances.

| Primitive | Where | Guarantees |
|---|---|---|
| DB unique constraint | `uq_active_subscription_per_user` (partial, `PENDING`+`ACTIVE`), `uq_idem_key_endpoint`, `uq_tier_benefit`, `uq_payment_reference` | The invariant is the DB's; no application lock discipline can beat it |
| Optimistic locking (`@Version`) | `Subscription` | Concurrent mutations can't both commit → loser gets 409 and retries; no held locks, no deadlocks |
| Atomic `INSERT … ON CONFLICT` | activity upsert, benefit assignment | Lock-free, race-safe read-modify-write in one statement |

- **Not `synchronized`/`ReentrantLock`:** they lock one JVM; this service scales horizontally, so an in-JVM
  lock gives zero protection against a concurrent request on another instance. The one in-JVM lock is in
  `StubPaymentAdapter` (a `synchronizedMap` LRU plus a `synchronized` check-and-record) — the stub standing
  in for gateway-side dedup, not a domain lock. A real gateway dedupes server-side on the idempotency key;
  the durable guarantee is `uq_payment_reference`, not the map.
- **Not `SELECT … FOR UPDATE`:** for every invariant here a constraint or atomic op is stronger and cheaper —
  a unique index can't be raced, and `ON CONFLICT`/atomic `UPDATE` needs no lock. Pessimistic locks add
  deadlock risk and throughput cost we don't need.
- **Where `FOR UPDATE` (or atomic decrement) would be right:** a finite resource counter, e.g. a capped tier
  ("GOLD limited to N seats"). Even there the atomic form wins: `UPDATE … SET claimed = claimed + 1 WHERE …
  AND claimed < cap` can't be oversold, holds no lock, and a mirrored release guarded on `claimed > 0` gives
  the claim back on a declined charge without breaching the cap. `SELECT … FOR UPDATE` then decrement is the
  fallback only when the check is too complex for a predicate; no invariant here needs it. Multi-instance job
  double-run is the other spot — the right primitive is a distributed lock (ShedLock), deferred to §5.

---

## 2. Failure Domains & Fault Tolerance

### 2.1 Downstream latency / outage cascade
A 5-second latency spike on the payment gateway blocks request threads; the pool fills; unrelated endpoints
(listing plans) time out. A circuit breaker converts "hang forever" into "fail fast" once a dependency is
clearly sick.

`PaymentPort` is a hexagonal port; `StubPaymentAdapter` is wrapped with a Resilience4j circuit breaker and
fallback (`paymentGateway` in `application.yml`: sliding window, failure-rate and slow-call thresholds,
open-state wait). A tripped breaker fails fast into a fallback that raises `PaymentFailedException` → 402,
never a hung thread. Exposing breaker state over actuator is config.

Not built: extend the same pattern to notification and any inventory/eligibility calls; add a bulkhead
(per-dependency threads) and an explicit `TimeLimiter` with real connect/read timeouts (the stub has no
socket; a real adapter must set both).

### 2.2 Non-critical side effects must not fail the core mutation
If a "welcome" notification throws, the subscription must not roll back — the user paid and is a member.
Notifications are fire-and-forget on the bounded async pool (`AsyncNotificationAdapter`); exceptions are
swallowed after the subscription has durably committed.

Not built — delivery guarantee via outbox: if notifications must be guaranteed, write an outbox row in the
subscription's transaction and have a relay publish it, for at-least-once delivery without coupling send to
commit.

### 2.2a Lost events & DLQ
- **Today:** domain events (`MembershipLifecycleEvent`) are in-process Spring `ApplicationEventPublisher`
  events, consumed by `MembershipNotificationListener` via `@TransactionalEventListener(AFTER_COMMIT)` on the
  same JVM. Delivery is best-effort, at-most-once: if the listener throws or the JVM dies between commit and
  listener, the event is lost — no retry, no DLQ. The `@Async` adapter also swallows its own errors.
- **Acceptable?** For notifications, yes — a missed welcome email is low-stakes and a DLQ would be
  over-engineering. Not acceptable for any consumer driving money, cross-service entitlement propagation,
  partner webhooks, or reconciliation-critical analytics; those need at-least-once, retries and a DLQ.
- **Not built — Transactional Outbox** (not "just add a DLQ", which doesn't help if the event was never
  durably captured): (1) write the event to an `outbox` table in the state-change transaction; (2) a relay/
  poller (or Debezium CDC) publishes to a broker with retries; (3) after N retries, land it in a DLQ for
  replay; (4) consumers are idempotent (infra already exists). The durable `subscription_event` audit table
  is already a proto-outbox to relay from. Deferred until a cross-service consumer exists.

### 2.3 Payment I/O and the charge-then-persist boundary
Charging the gateway is remote I/O. Inside a DB transaction it pins a pooled JDBC connection for the whole
round-trip, so a slow gateway drains the pool (the cascade the circuit breaker exists to prevent,
reintroduced at the DB tier). And a transaction can't roll back an external charge: if the insert loses the
active-subscription race after a successful charge, the user is charged with no membership.

So payment is idempotent by reference (the gateway dedupes on our `reference`) and the paying operations run
**reserve → charge → activate**, never a charge inside a transaction. A short transaction inserts a `PENDING`
reservation and commits; the unique index (extended to `PENDING`+`ACTIVE` in `V11`) rejects a concurrent
second attempt before any money moves. The gateway is charged with no transaction open, and a second short
transaction flips `PENDING → ACTIVE`.

  - **Failure handling is the subtle part.** The reservation is committed before the charge, so any charge
    failure must abandon it — and the real adapter fails by *throwing* (the breaker fallback raises
    `PaymentFailedException`), not by returning `success=false`. The charge is wrapped to abandon
    (`PENDING → CANCELLED`) on both a declined result and a thrown exception, then rethrow. Miss this and,
    because the `V11` index covers `PENDING`, an orphaned reservation blocks the user forever — exactly under
    the gateway outage this design exists to survive.
  - **Backstop:** a hard crash between reserve and charge can still orphan a `PENDING` row;
    `sweepStalePendingReservations` (via `ExpirySweeper`) cancels reservations older than
    `membership.reservation-ttl-minutes`.
  - **Guard:** the paying methods assert no ambient transaction
    (`TransactionSynchronizationManager.isActualTransactionActive()`), so a caller adding `@Transactional`
    can't collapse the three phases into one via the `TransactionTemplate`'s REQUIRED propagation.
  - See `SubscriptionServiceImpl` and `TransactionBoundaryIntegrationTest` (declined, thrown, reaper cases).
    Not built: a real gateway `refund(...)` to compensate a failure after a successful charge (the stub can't
    refund).

---

## 3. Database & Caching Friction Points

### 3.1 The N+1 wall
Rendering tiers-with-benefits or a membership view issues one query per association — fine at 10 rows, a
DB-CPU fire at a million. Associations are `LAZY`; reads that need related rows use explicit fetch joins
(`SubscriptionRepository.findActiveByUserId` joins plan+tier; `TierBenefitRepository.findActiveByTierId`
joins benefit). `TierAssignmentService` loads all criteria in a single `findByTierIdIn`, not one lookup per
tier.

### 3.2 Unindexed lookups at scale
`V1__schema.sql` indexes the request-path accesses: `subscription(user_id)`, the partial unique on active, a
partial `subscription(expires_at) where status='ACTIVE'` for the expiry sweep, and
`subscription_event(user_id, created_at)` for audit reads. The scheduled jobs were the gap — they arrived
after V1 and their predicates were sequential-scanning. `V13` adds a partial index matching each job's
`WHERE` exactly (trial conversion, stale-reservation reap, keyset reconciliation), and `V12` adds
`idx_payment_unapplied` for the reconciliation query. Non-obvious dependency: the planner only uses a partial
index if it can prove the query predicate implies the index predicate, which needs `status` to reach SQL as a
literal. Hibernate renders JPQL enum constants inline, so it does — switching those queries to a
`SubscriptionStatus` *parameter* would silently restore four sequential scans with no test failure to catch
it.

### 3.3 Cache stampede & stale data
Cache what is read-heavy and rarely-changing; never cache what must be consistent; make writes evict. Only the
catalog (plans, tiers, per-tier benefits) is cached (`@Cacheable`); subscription state never is. Admin edits
(`BenefitAdminService`) `@CacheEvict` immediately, so a write is never waiting on a TTL. The cache sits behind
Spring's `CacheManager`, so the store is an implementation choice — an in-memory `ConcurrentMap` here, a
shared Redis in production so instances don't each keep a copy — a dependency-plus-config change with no
service-code edit.

Not built — stampede protection: a short TTL plus jitter so keys don't expire in lockstep, and a per-key
cache-aside lock (or Redis `SETNX`) so one miss repopulates while others wait.

### 3.4 Expiry at scale
A nightly full-table scan to flip ended terms to EXPIRED doesn't scale and serves stale-active memberships
between runs. So expiry is computed lazily on read (`getMembership` never serves a stale-active membership),
and a scheduled `ExpirySweeper` only normalizes status in bounded, index-backed batches (`findExpiredIds` +
`markExpiredByIds`) via the partial `expires_at` index.

The tradeoff: `status` is a lagging projection, so a client reading the DB directly (BI, another service,
`WHERE status='ACTIVE'`) over-counts — it sees ACTIVE for a row already past its term until the next sweep.
`expires_at` is authoritative; effectively-active is `status='ACTIVE' AND expires_at + grace >= now()`, the
predicate the read path and the sweeper both apply. The divergence is bounded to the grace/dunning window
(serving a just-lapsed member there is intentional) plus the sweep interval.

Not built: guard the sweep with a shard lock (ShedLock) so only one node runs it.

---

## 4. Domain Extensibility & Architectural Debt

### 4.1 The rule-engine stress test
"Add progression by referral count." "Add a regional cohort." A hard-coded `if/else` ladder makes every new
rule a code change, a redeploy and a regression risk. Rules on a business cadence belong in data plus
strategies; new behavior should be additive (a bean + a config row), never a core-flow rewrite.

`TierRule` is a strategy interface; `OrderCountRule`, `MonthlySpendRule`, `CohortRule` are beans; thresholds
are `TierCriteria` rows. `TierAssignmentService` injects `List<TierRule>` and owns the combination policy (OR
semantics) in one place. Adding a rule is one new bean (`qualifies` + `isConfigured` + `code`) plus one
column, with zero change to the assignment seam, subscribe or upgrade — provided the rule reads activity the
system already collects. A rule needing a new input also touches `UserActivity`, `UserActivityServiceImpl` and
`ActivityDtos`, plus a data source; the `add-tier-rule` skill lists the full sequence.

`isConfigured` is the non-obvious half, because the obvious design fails *open*. "This tier has no criteria,
so everyone qualifies" was originally derived from a hardcoded check of the three known criteria fields. Add a
fourth criterion — exactly what `add-tier-rule` instructs — and a tier gated *only* by that new threshold
reads as unconfigured: every user qualifies, and `reevaluateTier` auto-upgrades the entire base into it.
Asking the rules themselves whether they claim a threshold makes the seam self-maintaining. *Verified:*
`TierAssignmentIntegrationTest`, and `TierAssignmentUnitTest` which regresses the fail-open directly.

### 4.2 Configurable benefits
Benefits are catalog rows; per-tier perks are `TierBenefit` rows with a JSONB `params` map (e.g.
`{"discountPct":15}`). Tuning or attaching a perk is an admin API call (`/api/v1/admin/benefits`), resolved by
`BenefitResolver` and cache-evicted on change, no redeploy. `BenefitType` names the families (`FREE_DELIVERY`,
`EXTRA_DISCOUNT`, `EXCLUSIVE_DEALS`, `EXCLUSIVE_COUPONS`, `PRIORITY_SUPPORT`). Higher ranks widen the same perk
through `params` rather than gaining rows: `EXTRA_DISCOUNT` gains categories with rank (V17), and
`EXCLUSIVE_COUPONS` is `{"couponsPerCycle":2}` on GOLD, `5` on PLATINUM — how many coupons a tier releases is
data, tunable through the same admin call.

### 4.3 Multi-city / multi-tenant / dynamic pricing
Not built, but the seams are shaped:
  - **Tenancy:** add a `tenant_id` discriminator and a Hibernate filter (or schema-per-tenant) resolved from
    request context; every unique/index gains `tenant_id` as its lead column. The ports/strategy design
    already localizes where tenant scoping is injected.
  - **Demand- or city-priced membership:** the seam is built. `PricingStrategy` is a strategy resolved by
    `PricingStrategyFactory` from config (`membership.pricing.mode`), with price resolution off the plan row
    and no change to the subscription flow. Deferred is the *inputs*: shipped `BASE` mode is list price × tier
    premium because that's all the data supports. Demand pricing needs demand and time on `PricingContext`;
    per-city pricing needs a `region` on the subscription. Either one is a new `PricingStrategy` bean and a
    new `PricingMode` value once the data exists — subscribe, upgrade and renewal stay untouched.
  - **Per-city inventory / eligibility:** another port + adapter behind the same circuit-breaker discipline as
    payment.

### 4.4 Two product decisions, made explicit
Auto never downgrades mid-cycle (`SubscriptionService.reevaluateTier` only upgrades): benefits earned within a
paid period aren't clawed back on a bad month. Downgrades are the user's explicit choice — encoded, since it
silently flips if left to emergent behavior.

Its counterpart, since monthly spend is windowed (§4.5): eligibility resets at the month boundary even though
tier does not. `isEligibleFor` also gates `reserve()` (subscribe) and `planUpgrade()` (manual upgrade), both
reading the current month's bucket. So a user who qualifies for Gold or Platinum *only* by spend cannot
subscribe or upgrade into it at 00:00 UTC on the 1st until fresh spend is ingested — existing members keep
their tier, prospective ones hit a cliff. Cohort- and order-count-qualified users are unaffected (neither is
windowed). The two rules only make sense read together: tier is kept for the paid period, eligibility is
re-earned each month.

### 4.5 "Total order value in a month" is a real window, derived from per-order events
Orders are the source of truth. `user_order_event(order_id, user_id, amount, occurred_at)` holds one row per
order; `user_monthly_spend(user_id, year_month, amount)` is a derived cache: the month's amount is
`SUM(amount)` over the events that fall in it (UTC), recomputed on each ingest by `MonthlySpendRecalculator`
under a per-user advisory lock so two concurrent orders can't let one recompute's stale `ON CONFLICT` sum
clobber the other. The read path is
a point lookup on the bucket, keeping the reconciliation fan-out cheap. A month with no events reads as zero —
so the tier engine reads a single number while storage keeps the grain it was computed from.

Storing per-order rather than a month total buys three things a collapsed total can't express (aggregation is
one-way):
  - `order_id` is the primary key, so a re-delivered order under at-least-once delivery is a no-op, not a
    double count.
  - A refund is the same order re-asserted at a lower amount; the bucket follows down.
  - The month an order counts toward comes from its `occurred_at`, not the server's clock at receipt, so an
    order near a boundary lands in the month it happened in, not misfiled by a caller in another timezone.

A rolling window (trailing 90 days) is then a different range over the same events
(`UserOrderEventRepository.sumForUserBetween`), which the monthly key structurally could not answer.

An earlier design overwrote a scalar `user_order_stats.monthly_spend` in place, so ₹20k spent once qualified
forever. V16 windowed it to a month; V20 moved the source to events with the bucket derived. The ingest API
takes the per-order shape and, for older callers, still accepts a caller-asserted month total.

Order count and cohorts stay lifetime-scoped on `user_order_stats` by design: the brief windows only order
*value* with "in a month". Ingest writes each table in its own transaction, not wrapped in one —
`ActivityIngestionService` must stay non-transactional so writes commit before tier re-evaluation is retried.
A crash between the two leaves spend stale for one ingest; it self-heals on the next, and since re-evaluation
only ever upgrades, the worst case is a briefly under-tiered user. *Verified:*
`OrderEventSpendIntegrationTest` (idempotency, refund, month boundary, rolling range) and
`MonthlySpendWindowIntegrationTest`.

Not a materialized view: Postgres `REFRESH` is a full rebuild, and ingest writes spend then immediately
re-evaluates the tier off it — a read-your-writes dependency an async refresh would break. The bucket is
maintained synchronously in the same write. Not built: a rolling-window `TierRule` (the range query exists, no
rule reads it), and retention/partitioning on `user_order_event`.

---

## 5. Production risks & mitigations: the subscription-specific register

Subscription systems fail around money, entitlement and time; building the seams early keeps each fix cheap.
Implemented items cite the code anchor; deferred items give the exact next step.

### Money
| Risk (what *will* happen) | Status | How it's handled / the next step |
|---|---|---|
| Price change retroactively hits existing subscribers | Implemented | `subscription.price_paid` locks the price at purchase (V5); plan price changes only affect new subs |
| Retry of a timed-out charge → double-charge | Implemented | charge is idempotent on `reference` (the gateway idempotency key); `StubPaymentAdapter` dedupes, a real gateway dedupes server-side |
| Refund / chargeback must revoke access | Implemented | `POST /subscriptions/{id}/refund` revokes + audits (`REFUNDED`) |
| Mid-cycle upgrade with no proration | Implemented | tier price multipliers + `PricingService.proratedUpgradeCharge` (delta × remaining ÷ period), charged idempotently; auto-upgrades stay free |
| Early cancel/refund with no credit for unused time | Implemented | `credit_note` (V7) + `PricingService.proratedRefundCredit`, the mirror of upgrade proration; `GET /users/{id}/credit-notes` |
| Free trials (trial → convert to paid) | Implemented | `plan.trial_days` (V8) + `subscription.trial_end`; subscribe skips the charge, `TrialConversionJob` charges + extends at trial end |
| Failed renewal ≠ cancellation (dunning) | Implemented | `RenewalJob` charges at expiry; on failure `RenewalService.renew` retries on a schedule (1/3/5/7d) within the grace window, then revokes after `max-dunning-attempts` |
| Invoices + taxes as durable records | Deferred | membership is flat/tiered; generate invoice/credit-note line items + tax when finance needs formal records |
| Usage metering, minimum commitments, add-ons, wallet | Out of scope | usage-billing concerns; membership is flat/tiered |
| Refund-after-qualifying tier (order big → get tier → refund) | Deferred | recompute tier on refund; treat order value as provisional until settled |
| `charge → persist` saga gap (charged, sub not created) | Implemented | reserve `PENDING` → charge (no tx open) → activate; declined charge abandons the reservation. Compensating refund after a post-charge failure stays deferred (stub can't refund) |
| Remote I/O (payment) inside a DB transaction pins a pooled connection | Implemented | the charge runs between two short transactions, never inside one, so a slow gateway can't drain the pool (`SubscriptionServiceImpl`) |
| A read endpoint (`GET /membership`) that writes | Implemented | lazy expiry returns the inactive view without persisting; the scheduled sweep owns the `EXPIRED` transition. `getMembership` is `@Transactional(readOnly=true)` |

### Entitlement
| Risk | Status | How it's handled / the next step |
|---|---|---|
| A qualifying user is stuck on a lower tier because an activity event was lost/delayed | Implemented | `TierReconciliationJob` recomputes tiers for active users on a schedule. Progress is a persisted cursor (`job_cursor`, V19) with a `@Version` optimistic lock (V21) so a second instance's advance conflicts rather than regressing it. Paging fixed the within-run re-scan; the durable cursor fixes across-run coverage. `JobProgressIntegrationTest`, `JobCursorConcurrencyIntegrationTest` |
| Directory cohort (VIP/PREMIER) not counted for tier eligibility | Implemented | `UserActivityService` unions the directory cohort with activity cohorts |
| Changing a tier's benefits under active members | Deferred | today cache-evict applies immediately (fine for additions); for reductions, version entitlements per subscription or apply at renewal |
| "Total order value in a month" is under-specified (calendar vs rolling, timezone, reset) | Implemented | pinned to the calendar month in UTC: `user_monthly_spend(user_id, year_month)`, keyed `YYYY-MM` off the injected `Clock` (V16, §4.5). A month with no bucket reads as zero; tier is never clawed back, but re-qualifying resets at the boundary |
| Downstream service serves benefits to a cancelled user (stale entitlement cache) | Deferred | entitlement revocation is a distributed cache-invalidation problem (Redis pub/sub / short TTL), same as a flag kill-switch |

### Time & lifecycle
| Risk | Status | How it's handled / the next step |
|---|---|---|
| Failed renewal instantly revokes a paying member | Implemented | configurable grace period (`membership.grace-period-days`); benefits continue through the window while renewal/dunning runs |
| Never serve a stale-active membership | Implemented | lazy expiry on read + batched sweep, both grace-aware |
| Renewal thundering herd (a signup cohort all renews the same day → gateway throttling) | Deferred | jitter/spread renewal times; never batch-charge a cohort at one instant |
| Cancel-then-resubscribe / reactivation semantics | Deferred | define new-period vs resume; the active-unique index handles the race, not the policy |

### Operational
| Risk | Status | How it's handled / the next step |
|---|---|---|
| `idempotency_key` table grows forever | Implemented | `IdempotencyCleanupJob` reaps past a retention window |
| Lost events: in-process Spring events are best-effort (at-most-once), no DLQ | Implemented (best-effort) / outbox deferred | fine for notifications; for cross-service/critical consumers use Transactional Outbox → broker → DLQ with idempotent consumers (§2.2a) |
| Scheduled jobs double-run across instances | Deferred (reconciliation cursor `@Version`-guarded) | shard lock (ShedLock) on sweep / reconciliation / cleanup; the money-critical renewal is already CAS-safe (`extendIfUnchanged`) |
| Unbounded list endpoint returns the whole table as it grows | Implemented | `/users` and `/users/{id}/credit-notes` take `page`/`size` (default 0/50) → `PageRequest` on the query; never a full-table scan into memory |
| App can't run without a local JDK/Maven toolchain | Implemented | multi-stage `Dockerfile` (build on Temurin 21+Maven, run on JRE) + `docker compose --profile app up` runs the whole stack containerised |
| No membership-specific observability | Implemented | `MembershipMetricsListener` counts committed lifecycle events + a payment-failure counter (`membership.lifecycle` tagged by type); exposing them to a scraping registry is config; alert on CANCELLED/REFUNDED/RENEWAL_FAILED spikes, not just CPU |
| Unauthenticated API: anyone can cancel/refund/admin | Implemented (authz) | Stateless JWT authz always enforced: public catalog, ADMIN-only `/admin/**` + provisioning, self-or-admin per-user (`@PreAuthorize`), validated by `JwtAuthFilter`. Token issuance is a dev stand-in, not production auth (next row) |
| Token endpoint could mint an ADMIN token for anyone | Implemented (hardened) / real IdP deferred | `/auth/token` gated by `membership.auth.dev-token.enabled`, off by default (the `@ConditionalOnProperty` bean and route don't exist); `admin=true` needs a constant-time-checked bootstrap secret, so admin escalation is never anonymous. Dev bootstrap; production uses an external IdP. `AuthSecurityIntegrationTest` |
| Secrets with working defaults committed to the repo | Implemented | `JWT_SECRET` and the admin bootstrap secret have no default — a committed signing key is worst-case (anyone with the repo forges an ADMIN token offline, making every `@PreAuthorize` decorative). Startup fails without a ≥32-byte secret; a blank bootstrap secret can never match. `SecurityDefaultsGuard` refuses to boot on the unsafe combination (`SecurityDefaultsGuardTest`) |
| Self-attested activity is self-granted entitlement | Implemented | `POST /users/{id}/activity` is ADMIN-only, not self-or-admin: `orderCount`/`monthlySpend`/`cohorts` *are* the tier criteria, so self-posting would be tier escalation. In production, a service-to-service call from order fulfilment |
| Fail-open tier authorization: a missing criteria row = everyone qualifies | Implemented | `TierAssignmentService.qualifies` fails closed on an absent criteria row (a misconfiguration denies, never grants); the base tier is open only via an explicit unconditional row. Otherwise one forgotten seed row auto-upgrades the whole base into a top tier |
| Payment resilience overclaimed (TimeLimiter/bulkhead) | Implemented (breaker) / TimeLimiter deferred | only the circuit breaker is wired onto the synchronous charge (a slow call trips it); a per-call `TimeLimiter` needs an async adapter, not wired by choice (§2.1) |
| GDPR "delete me" must reach every table | Deferred | cascade delete/anonymize across subscription, events, stats, directory |

The method, not the list: make the decisions explicit (price-lock, grace window, window semantics, proration)
instead of leaving them to accident, and leave seams — pricing strategy, payment port, domain events,
config-driven entitlements — so deferred work is cheap to add rather than a rewrite.

