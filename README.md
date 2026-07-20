# FirstClub Membership Program

A tiered subscription-membership backend. It covers Monthly, Quarterly and Yearly plans, per-tier
benefits that are configurable at runtime, the subscription lifecycle (subscribe, upgrade, downgrade,
cancel, with expiry tracking), and automatic tier progression from Silver to Gold to Platinum driven
by a pluggable rule engine.

Java 21, Spring Boot 4.1, PostgreSQL 16 with Flyway, Testcontainers.

---

## Start here

Prerequisites: JDK 21, Maven, Docker.

```bash
cp .env.example .env      # required: JWT_SECRET has no default, so the app won't boot without it
docker compose up -d      # Postgres
mvn spring-boot:run       # Flyway migrates and seeds on startup
./scripts/demo.sh         # narrated walkthrough of every feature
```

`scripts/demo.sh` is the quickest way to see the system work. It mints its own tokens, reads plan and
tier ids back from the API, and uses a fresh user on each run, so you can run it as many times as you
like without resetting anything.

* Swagger UI: <http://localhost:8080/swagger-ui/index.html>
* Health: <http://localhost:8080/actuator/health>
* Postman collection: [`postman/`](postman/), 24 requests with tokens captured automatically

> If the app exits at startup with `Migrations have failed validation`, your Postgres volume is from
> an older revision of the schema. Reset it with `docker compose down -v && docker compose up -d`.
> A clean volume always migrates cleanly.

No local JDK or Maven? `docker compose --profile app up -d --build` builds and runs the whole stack
in containers. It reads the same `.env` file, so run `cp .env.example .env` first. Compose will stop
with a clear message if you forget.

---

## What's here, and how each requirement was handled

| The brief asks for | How it works | Where |
|---|---|---|
| Monthly / Quarterly / Yearly plans, each priced | Plans are rows rather than enum constants. Cadence, price and duration are all data, so adding a plan is an INSERT | `membership_plan`, `GET /api/v1/plans` |
| Benefits: free delivery, extra discount, exclusive deals, priority support, exclusive coupons | A benefit catalog plus per-tier assignments. Per-tier tuning lives in a JSONB `params` column | `benefit`, `tier_benefit`, `GET /api/v1/tiers` |
| "Each tier unlocks additional perks, should be configurable" | Perks are data, not code. A new perk, including a custom coupon drop, can be created and attached to a tier with arbitrary params at runtime. No deploy, no restart; caches are evicted so it is live on the next read. See [Configuring perks](#configuring-perks) | `POST /api/v1/admin/benefits` and `/assignments` |
| Get plans and tiers for the user to choose | Read-only, public, cached in memory | `PlanController`, `TierController` |
| Subscribe to a plan (plan + tier) | Reserve, charge, then activate across three transactions, so a gateway call never holds a database connection. An `Idempotency-Key` makes retries at-most-once | `POST /api/v1/subscriptions` |
| Upgrade, downgrade, cancel | A guarded state machine on the entity. Upgrades are eligibility-checked and prorated; downgrades are always the member's choice | `POST /api/v1/subscriptions/{id}/…` |
| Track current membership and expiry | One read returns tier, resolved benefits, status and `expiresAt`. Expiry is computed lazily on read and only persisted by the scheduled sweep, so reads never write | `GET /api/v1/users/{userId}/membership` |
| Tier progression by orders, monthly order value, or cohort | A strategy engine: one `TierRule` bean per criterion, thresholds stored in `tier_criteria` rows, OR semantics, highest qualifying tier wins | `service/rules/` |

### The three tier criteria

| Criterion | Rule | Reads |
|---|---|---|
| Number of orders more than X | `OrderCountRule` | `tier_criteria.min_orders` |
| Total order value in a month | `MonthlySpendRule` | `tier_criteria.min_monthly_spend` |
| Belonging to a certain cohort | `CohortRule` | `tier_criteria.cohorts` (JSONB) |

As seeded, Gold needs 5 orders or ₹5,000 a month or the `PREMIER` cohort. Platinum needs 15 orders or
₹20,000 or `VIP`.

Three notes on the design:

* Adding a rule is additive. It takes one `@Component implements TierRule` and one column. Neither
  `TierAssignmentServiceImpl` nor the subscribe flow needs to change.
* "Total order value in a month" is windowed for real, and derived from per-order events. Orders
  land in `user_order_event`, and `user_monthly_spend` is a cache recomputed as a SUM over the month.
  That keeps the read a point lookup while the events stay correctable: a re-delivered order does not
  double-count (keyed on `order_id`), a refund lowers its month, and an order counts toward the month
  it happened in, not the month it was received. See [ARCHITECTURE.md §4.5](ARCHITECTURE.md).
* The engine fails closed, and refuses to boot on the misconfiguration that would defeat that. A
  missing `tier_criteria` row denies the tier rather than granting it. A tier counts as unconditional
  only when an explicit row exists that no rule claims a threshold on, derived by `TierRule.isConfigured`
  rather than hardcoding `SILVER`. Because an all-null row on a high tier reads exactly like the base
  tier's, a startup guard rejects any config where more than one tier is unconditional, or the open
  one is not the lowest rank, turning a silent mass-promotion into a failed start.

<a id="configuring-perks"></a>
### Configuring perks at runtime

Configurability is the load-bearing half of that requirement, so it is better shown than claimed.
Here is a custom coupon perk being added to Gold while the app is running:

```bash
# 1. Define the perk
curl -X POST localhost:8080/api/v1/admin/benefits -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' \
  -d '{"code":"FESTIVE_COUPON_DROP","type":"EXCLUSIVE_COUPONS","name":"Festive coupon drop"}'

# 2. Attach it to Gold with whatever params the perk needs
curl -X POST localhost:8080/api/v1/admin/benefits/assignments -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' \
  -d '{"tierId":2,"benefitCode":"FESTIVE_COUPON_DROP",
       "params":{"couponsPerCycle":3,"campaign":"DIWALI"}}'

# 3. It is live on the next read, because the assignment evicts the caches
curl -s localhost:8080/api/v1/tiers
```

`params` is JSONB and free-form, so each perk carries whatever configuration it needs: a coupon count
and a campaign here, `{"discountPct":15}` for a discount, `{"minOrderValue":0}` for delivery. Step 8
of `scripts/demo.sh` runs exactly this, and two tests in `BenefitConfigurationIntegrationTest` cover
it.

Where the boundary sits, and why. Perk instances and their tuning are data. Perk families
(`BenefitType`: `FREE_DELIVERY`, `EXTRA_DISCOUNT`, `EXCLUSIVE_DEALS`, `EXCLUSIVE_COUPONS`,
`PRIORITY_SUPPORT`) are a closed enum, so inventing a genuinely new kind of perk is a code change.
That is on purpose. The type is the contract that tells checkout, delivery and support how to read
`params`. If the type were a free-form string, an admin could create `{"type":"FREE_RETURNS"}` that
no downstream system knows how to honour: configurable in the database and inert in production.
An unknown type is rejected with a 400 that names the accepted values.

---

## How it's put together

Layering is explicit: `controller → service → repository → model`, with `gateway/` for external
systems and `dto/` for the wire format. Controllers hold no logic, and entities never leave the
service layer.

External systems sit behind ports. `PaymentPort` and `NotificationPort` are interfaces; the adapters
own the circuit breaker and the async dispatch. Swapping in a real gateway means writing one class.

There are two services, split by what drives them. `SubscriptionService` handles request-driven work
(subscribe, upgrade, downgrade, cancel, refund, reads). `RenewalService` handles time-driven work
(trial conversion, renewal, dunning) and is only ever called by scheduled jobs.

Entities own their transitions. `Subscription` has no public setters at all. Every mutation goes
through a guarded method such as `activate()`, `cancel()` or `reprice()`, and each of those runs a
state-machine check. Associations are LAZY, there are no cascades, and `open-in-view` is off.

N+1 reads are handled where they arise. Every association a read actually touches is pulled with an
explicit `join fetch` (`SubscriptionRepository.findByIdWithDetails`, `findActiveByUserId`) or a
single batched query (`TierAssignmentServiceImpl.loadCriteria` uses one `findByTierIdIn` for all
tiers), so no read walks a lazy association in a loop. The one place a read fans out per tier,
`TierServiceImpl.listTiers` resolving benefits, is bounded by the tier count and sits behind the
catalog cache, so it does not run per request. Partial-index queries inline their status enum as a
literal so the index stays usable rather than degrading to a scan.

Concurrency is enforced by the database rather than by application checks. This is the part most
worth reading the code for:

| Guard | What it prevents |
|---|---|
| `uq_active_subscription_per_user`, a partial unique index over `PENDING` and `ACTIVE` | Two concurrent subscribes both reserving and both charging |
| `@Version` optimistic locking on `Subscription` and `Payment` | Lost updates. The loser gets a 409 and retries |
| An explicit `version = version + 1` inside JPQL bulk updates | A bulk update bypassing the optimistic lock and resurrecting an expired subscription |
| `extendIfUnchanged`, a compare-and-set on `expiresAt` | Two renewal runners buying two billing periods with one payment |
| The `Idempotency-Key` store, unique per (key, endpoint) | A retried HTTP call mutating twice |
| A payment ledger with a three-way outcome (`CHARGE_NOW`, `ALREADY_CHARGED`, `ALREADY_DONE`) | A charge that took money but never delivered becoming unrecoverable |
| `assertChargeableOutsideTransaction()` | A future `@Transactional` collapsing reserve, charge and activate into one transaction |

There is no `synchronized`, no `ReentrantLock` and no `SELECT … FOR UPDATE` anywhere. A JVM lock only
guards one process, so it gives no protection at all once a second instance is running, and the
service is meant to scale horizontally. A unique index cannot be raced no matter how many instances
exist. Pessimistic row locks would work, but they add deadlock risk and hold locks across the
transaction for invariants that a constraint or an atomic statement already covers more cheaply.
`ARCHITECTURE.md` §1 goes through the reasoning case by case, including where a lock genuinely would
be the right tool.

All of this is exercised by concurrency tests against a real PostgreSQL, not mocks.

---

## Getting a token

Every mutation is authorized, so a call without an `Authorization` header is rejected rather than
executed. Token issuance here stands in for a real identity provider; the authorization itself is
always enforced.

```bash
# Self token. No secret needed, and an absent "admin" means "no".
curl -s -X POST http://localhost:8080/api/v1/auth/token \
     -H 'Content-Type: application/json' -d '{"userId":7001}'

# Admin token. Requires the bootstrap secret, so admin escalation is never anonymous.
curl -s -X POST http://localhost:8080/api/v1/auth/token \
     -H 'Content-Type: application/json' \
     -d '{"userId":0,"admin":true,"secret":"local-dev-admin-bootstrap"}'
```

Send it as `Authorization: Bearer <token>`. The secret is `ADMIN_BOOTSTRAP_SECRET` from your `.env`,
and it has no default, so leaving it blank makes minting an admin token impossible.

Both `scripts/demo.sh` and the Postman collection handle this for you. Postman captures the tokens
into collection variables, so you never copy a JWT by hand.

The token endpoint is off by default. `.env.example` enables it for local runs, and
`SecurityDefaultsGuard` refuses to boot if it is switched on outside dev without an explicit
acknowledgement. For a real deployment, leave `AUTH_DEV_TOKEN_ENABLED` unset so the bean and its
route disappear, then front the app with an external IdP.

Who can call what: the catalog and Swagger are public, along with the liveness slice of actuator
(`/actuator/health`, `/actuator/info`). Nothing else under `/actuator` is exposed, and the rest of
`/actuator/**` is ADMIN by default so enabling an endpoint later never makes it accidentally public.
`/admin/**` and user provisioning require ADMIN.
Activity ingestion is ADMIN-only, since the order data it carries (count, value, cohorts) is what
the tier criteria evaluate, so letting users post their own would be free tier escalation. The
remaining per-user endpoints are self-or-admin.

---

## API surface

| Method and path | Auth | Purpose |
|---|---|---|
| `POST /api/v1/auth/token` | public | Mint a dev JWT |
| `GET  /api/v1/plans` | public | List plans (cached) |
| `GET  /api/v1/tiers` | public | List tiers and the benefits each unlocks (cached) |
| `POST /api/v1/subscriptions` | self | Subscribe to a plan at a tier. Accepts `Idempotency-Key` |
| `POST /api/v1/subscriptions/{id}/upgrade` | owner | Upgrade tier, eligibility enforced and prorated |
| `POST /api/v1/subscriptions/{id}/downgrade` | owner | Downgrade tier |
| `POST /api/v1/subscriptions/{id}/cancel` | owner | Cancel, crediting unused time |
| `POST /api/v1/subscriptions/{id}/refund` | ADMIN | Refund or chargeback |
| `GET  /api/v1/users/{userId}/membership` | self | Current membership, tier, benefits, expiry |
| `GET  /api/v1/users/{userId}/credit-notes` | self | Credit notes, paginated |
| `POST /api/v1/users/{userId}/activity` | ADMIN | Feed one order event (`orderId`, `orderAmount`, `occurredAt`), triggering tier re-evaluation |
| `GET  /api/v1/users`, `POST /api/v1/users` | ADMIN | User directory and provisioning |
| `POST /api/v1/admin/benefits`, `/assignments` | ADMIN | Configure benefits and per-tier perks at runtime |

Errors come back as RFC-7807 `application/problem+json`: 409 for a conflict, 422 for a business rule,
402 for a payment failure, 404 for not found, 400 for a malformed body.

---

## Tests

```bash
mvn test        # unit and integration (Testcontainers needs Docker running)
mvn verify      # the above plus the Spotless format gate
```

106 tests. Testcontainers starts a real PostgreSQL, so the concurrency guarantees run against the same
engine as production instead of an in-memory substitute. Some highlights:

* `SubscriptionConcurrencyIntegrationTest`: 8 concurrent subscribes produce exactly one active
  subscription, and 6 concurrent upgrades apply exactly once.
* `OrderEventSpendConcurrencyIntegrationTest` / `JobCursorConcurrencyIntegrationTest`: concurrent
  orders for one user never lose spend, and concurrent cursor advances never regress the cursor.
* `TransactionBoundaryIntegrationTest`: the gateway is charged outside any transaction, an ambiguous
  failure keeps its reservation so a retry can resolve it, and reads never write.
* `PaymentLedgerIntegrationTest`: charged-not-applied recovery, propagation boundaries, refunds.
* `TierAssignmentIntegrationTest`: the rule engine, covering OR semantics, highest-tier selection and
  the fail-closed path.
* `BenefitConfigurationIntegrationTest`: creating and attaching a perk at runtime, and re-tuning one
  in place.
* `SubscriptionAuthorizationIntegrationTest`: the full self, owner and admin matrix.

On Colima, export `DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"` and
`TESTCONTAINERS_RYUK_DISABLED=true`. Surefire already pins `-Dapi.version=1.44`, because docker-java's
1.32 default is rejected by modern daemons.

---

## Where to look next

| Document | What it covers |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Design rationale: concurrency, failure domains, extensibility, and a register of production risks marked implemented, deferred or out of scope |
| [docs/DATABASE.md](docs/DATABASE.md) | Schema, ER diagram, constraints and indexes |
| [postman/README.md](postman/README.md) | Import instructions and run order for the collection |
| [CLAUDE.md](CLAUDE.md) + [`.claude/skills/`](.claude/skills) | How this was built: the repo's working agreement and the task runbooks an agent — or a new engineer — can follow |

### Built with an agent, checked in

This repo was written with Claude Code, and the tooling that guided it is committed rather than hidden:

* **[CLAUDE.md](CLAUDE.md)** — the working agreement: layer boundaries, the Lombok allow-list and the
  reasoning behind it, the append-only-migrations rule, and the Spring Boot 4.1 gotchas worth knowing.
* **[`.claude/skills/`](.claude/skills)** — runbooks for recurring tasks: `run-app`, `run-tests`, and —
  doubling as executable extensibility docs — `add-benefit` and `add-tier-rule`.

They are conventions and documentation; no application code depends on them.

### Scope, and what was left out

The omissions are considered rather than overlooked.

* Distributed scheduler locking (ShedLock). The scheduled jobs are safe on a single instance, and two
  replicas would double-fire them. Each job's javadoc says so. The renewal compare-and-set means the
  money-critical job stays correct even under a double run; a lock would only make it cheaper.
* A real payment gateway. `PaymentPort` is a stub behind a circuit breaker. The ledger models what a
  real integration would need, and the adapter does not pretend to be one.
* Rate limiting, multi-currency, tax, downgrade-on-inactivity, coupon issuance. Each of these is a
  separate domain rather than part of membership.

Four things go beyond the brief on purpose: the payment ledger, idempotency with optimistic locking,
trials with renewal and dunning, and JWT authorization. Each one demonstrates a judgment the brief
implies without asking for it. None of them is required reading.

## Code style

google-java-format, enforced by Spotless. Run `mvn spotless:apply` to format; `mvn verify` fails on
drift. IDE style configs for IntelliJ and Eclipse are in [`config/`](config/).
