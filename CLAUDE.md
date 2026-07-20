# CLAUDE.md — working agreement for this repo

Guidance for Claude Code (and humans) working in the FirstClub Membership backend. Keep it short; update
it when a convention changes.

## What this is
A tiered subscription-membership backend (Spring Boot 4.1 / Java 21 / Postgres). See
[README.md](README.md) to run it and [ARCHITECTURE.md](ARCHITECTURE.md) for the design rationale and the
production-hardening blueprint.

## Commands
```bash
docker compose up -d          # Postgres (override PG_HOST_PORT if 5432 is taken)
mvn spring-boot:run           # run the app (Flyway migrates + seeds on startup) → http://localhost:8080
mvn test                      # unit + Testcontainers integration tests (needs Docker)
mvn verify                    # tests + Spotless format gate
mvn spotless:apply            # auto-format to Google style before committing
docker compose --profile app up -d --build   # fully-containerised app + Postgres, no local JDK/Maven
./scripts/demo.sh             # narrated end-to-end walkthrough against a running instance
```
Swagger UI: `/swagger-ui/index.html` · Health: `/actuator/health` · OpenAPI: `/v3/api-docs`

## Architecture at a glance
- **Layered packages** (package-by-layer):
  `controller/ · service/ (+ rules/) · repository/ · model/ · dto/ · gateway/ (+ adapter/) · config/ · exception/`.
  Strict boundaries: controllers hold no logic; services own transactions; entities (`model/`) never leave
  the service layer — DTOs (`dto/`) cross the wire.
- **Ports & adapters:** external systems (`gateway/PaymentPort`, `gateway/NotificationPort`) are interfaces;
  adapters (`gateway/adapter/`) own timeouts / circuit breaker / async. Depend on the port, never a concrete client.
- **Extensibility seam:** tier progression is a strategy engine — `service/rules/TierRule` implementations +
  `model/TierCriteria` config rows. Adding a rule = one bean + one column *when it reads activity already
  collected*; a new activity input also touches `UserActivity`, `UserActivityServiceImpl` and `ActivityDtos`
  (see the `add-tier-rule` skill for the full sequence). `isConfigured` is what makes a tier "unconditional"
  derivable from the rules; hardcoding that check is a fail-open — see ARCHITECTURE.md §4.1.
- **CQRS:** read/write paths are separated in spirit (query methods vs command methods), not ceremonially —
  no separate read/write models or datastores. See ARCHITECTURE.md §0.
- **Patterns in use:** Strategy (tier rules, pricing), Factory (`PricingStrategyFactory`), Observer
  (`event/` + `@TransactionalEventListener`), State (`SubscriptionStatus` transition table), Ports/Adapters,
  Decorator (circuit breaker), Repository. Full table in ARCHITECTURE.md §0. Pricing mode is config
  (`membership.pricing.mode` / `PRICING_MODE`, default BASE).
- **Concurrency guards (do not weaken):** `uq_active_subscription_per_user` partial unique index,
  `@Version` optimistic locking on `Subscription` / `Payment` / `JobCursor`, `Idempotency-Key` store,
  bounded async pool, per-order event upserts (`user_order_event`, `ON CONFLICT DO NOTHING`) with the
  monthly-spend bucket recomputed under a per-user Postgres advisory lock (`MonthlySpendRecalculator`) so
  concurrent orders can't lose spend — see ARCHITECTURE.md §4.5. These enforce invariants at the DB layer —
  that's the point.

## Conventions
- Every application service is an interface (`Xxx`) + implementation (`XxxImpl`); inject the interface. A new
  service means adding both. Ports/strategies/repositories are interfaces too.
- DTOs are Java records, separate from entities; entities never leave the service layer.
- Errors → RFC-7807 `ProblemDetail` via `exception/ApiExceptionHandler` (409 conflict, 422 rule, 402 payment).
- **Lombok — allowed set only.** `@Getter` (entities), `@RequiredArgsConstructor` (Spring beans),
  `@Slf4j` (logging; SLF4J is the facade, Logback is Boot's implementation — never `@Log4j2`/`@Log`).
  **Banned: `@Data`, `@Setter`, `@EqualsAndHashCode`, `@ToString` on any `@Entity`.** Reasons, not taste:
  `@ToString` walks LAZY associations (the N+1 this repo forbids); `@EqualsAndHashCode` on a generated id
  breaks the entity contract the moment a transient instance is persisted; and the model package is
  **deliberately setter-free** — mutation goes through guarded methods (`Subscription.activate()`,
  `.cancel()`, `.reprice()`), so a generated `setStatus()` would walk straight past the state machine.
  `@Version` fields carry `@Getter(AccessLevel.NONE)`. Hand-written constructors that validate, derive
  fields, or take `@Value` params (e.g. `SubscriptionServiceImpl`) stay hand-written.
- Schema is owned by **Flyway** (`src/main/resources/db/migration`), Hibernate is `ddl-auto=validate`.
  New schema = a new `V__*.sql` migration, never an entity-driven DDL change.
- **Applied migrations are append-only — including their comments.** Never edit a `V__*.sql` that has
  already run anywhere; add a new migration instead. Flyway checksums the *whole file*, so even a
  comment-only edit invalidates every database that applied it, and the app refuses to start. These
  files read like documentation, which is exactly why sweeping "improve the comments" passes keep
  reaching for them — they must be excluded from any such pass. `FlywayConfig` repairs checksum drift
  automatically so this is survivable rather than fatal, but that is a safety net, not permission:
  it cannot tell a comment edit from changed SQL, and changed SQL means two databases at the same
  version with different schemas.
- Reads that touch associations use explicit fetch-joins / `@EntityGraph` — no lazy N+1.
- Only the read-heavy catalog is cached (in-memory `ConcurrentMap` via Spring's `CacheManager`,
  `spring.cache.type=simple` over `plans,tiers,tierBenefits`); subscription state is never cached. The
  seam is `CacheManager`, so a production deployment can back it with a distributed cache without
  service-code changes.
- Code style: google-java-format via Spotless. Run `mvn spotless:apply` before committing; `mvn verify`
  fails on drift. IDE style configs are in `config/`.
- **Doc comments are self-contained reference, not narrative.** A reader opening only that file should
  get the whole picture: open by stating what the thing *is* and define any term the rest depends on
  before leaning on it. Avoid argumentative framing that presumes a story the reader never saw ("the
  answer is deliberately X, not Y", "that's the point"), and don't allude to mechanisms in other files —
  name the concrete site or `{@link}` it. Keep the non-obvious rationale; just ground it first. This is
  voice, not brevity. Applies to `src/main/java` only — never the `V__*.sql` migrations (append-only, above).

## Gotchas (Spring Boot 4.1 specifics)
- **Jackson 3** (`tools.jackson.*`) is the autoconfigured mapper — inject that, not `com.fasterxml…`.
- Autoconfig is modularized: Flyway needs `spring-boot-flyway`.
- **Testcontainers on Colima:** export `DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"`; the
  Surefire config already pins `-Dapi.version=1.44` (docker-java's 1.32 default is rejected by modern daemons).
  Also export `TESTCONTAINERS_RYUK_DISABLED=true` — ryuk bind-mounts the Docker socket and Colima's virtiofs
  rejects it (`error while creating mount source path ... operation not supported`), which fails **every**
  integration test at class-init with a misleading `NoClassDefFoundError: AbstractIntegrationTest`. Tradeoff:
  ryuk is the container reaper, so an abnormally-killed run may leave stray containers (`docker ps` to check).

## Commit conventions
- Run `mvn spotless:apply` and `mvn verify` before committing.
