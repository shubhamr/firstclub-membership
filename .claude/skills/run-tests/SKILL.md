---
name: run-tests
description: Run the test suite (JUnit + Testcontainers Postgres) reliably, including on Colima. Use when asked to test, verify, or check the build.
---

# Run the tests

The integration tests spin up a real PostgreSQL via Testcontainers, so **Docker must be running**.

## Standard run
```bash
mvn test          # unit + integration tests
mvn verify        # tests + Spotless format gate (what CI runs)
```

## On Colima (this machine)
Two environment specifics are required, else Testcontainers can't connect:
```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_RYUK_DISABLED=true
mvn test
```
- The Docker Engine API version is already pinned to `1.44` in the Surefire config (docker-java's legacy
  1.32 default is rejected by modern daemons — "client version too old"). Override with `-Dapi.version=…`.
- Ryuk (the container reaper) is disabled because Colima's virtiofs rejects its socket bind-mount, which
  otherwise fails every integration test at class-init. Tradeoff: an abnormally-killed run may leave stray
  containers — `docker ps` to check.

## Run one class / method
```bash
mvn test -Dtest=SubscriptionConcurrencyIntegrationTest
mvn test -Dtest='SubscriptionConcurrencyIntegrationTest#<methodName>'
mvn test -Dtest='TierAssignment*Test'          # globs work
```

## What the key tests prove
- `SubscriptionConcurrencyIntegrationTest` — one active subscription per user under concurrent
  subscribes; idempotent replay; upgrade applies exactly once.
- `OrderEventSpendConcurrencyIntegrationTest` — concurrent per-order events never lose spend (the
  monthly-spend recompute holds a per-user advisory lock).
- `JobCursorConcurrencyIntegrationTest` — the reconciliation cursor never regresses under concurrent
  advances (`@Version` + monotonic advance).
- `MembershipFlowIntegrationTest` — subscribe → activity-driven auto-upgrade → downgrade → cancel.
- `TierAssignmentIntegrationTest` / `TierAssignmentUnitTest` — the pluggable tier rule engine, including
  that a tier gated only by a rule is **not** treated as unconditional.

If you change concurrency-relevant code (locking, indexes, idempotency, the advisory lock, the cursor),
**run the matching concurrency test** and keep it green — those are the guardrails.
