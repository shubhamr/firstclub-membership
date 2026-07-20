---
name: run-app
description: Start the FirstClub Membership app locally (Postgres + Spring Boot) and hit the API. Use when asked to run, start, demo, or manually test the service.
---

# Run the membership app

## Steps
1. Start infrastructure (Postgres only — the catalog cache is in-memory, no Redis to run):
   ```bash
   docker compose up -d
   ```
   If host port 5432 is already in use, override and remember the port:
   ```bash
   PG_HOST_PORT=5433 docker compose up -d
   ```
2. Wait for Postgres to report `healthy`:
   ```bash
   docker compose ps
   ```
3. Run the app (Flyway migrates + seeds on startup):
   ```bash
   # default port:
   mvn spring-boot:run
   # if you overrode the DB port in step 1:
   DB_URL=jdbc:postgresql://localhost:5433/membership mvn spring-boot:run
   ```
   The app reads `.env` if present (`spring.config.import=optional:file:.env`), so
   `cp .env.example .env` once and the run needs no exported vars.
4. App is up when the log prints `Started MembershipApplication`. Then:
   - Swagger UI: http://localhost:8080/swagger-ui.html
   - Health: http://localhost:8080/actuator/health

## No local JDK/Maven — fully containerised
```bash
cp .env.example .env               # the app container reads .env for JWT_SECRET etc.
docker compose --profile app up -d --build
```
This builds the image and runs app + Postgres together. The default (no profile) still starts only
Postgres, for running the app from an IDE / `mvn spring-boot:run`.

## Smoke test
The catalog is public; everything else needs a bearer token, so the quickest end-to-end check is the
demo script (subscribe → idempotent replay → activity-driven auto-upgrade → configure a perk → cancel):
```bash
./scripts/demo.sh                  # against http://localhost:8080; safe to re-run
```
Public catalog, no token:
```bash
B=http://localhost:8080/api/v1
curl -s $B/plans; curl -s $B/tiers
```
Authenticated calls first mint a token:
```bash
SELF=$(curl -s -X POST $B/auth/token -H 'Content-Type: application/json' \
        -d '{"userId":7001}' | jq -r .token)
curl -s -X POST $B/subscriptions -H "Authorization: Bearer $SELF" \
     -H 'Content-Type: application/json' -H 'Idempotency-Key: K1' \
     -d '{"userId":7001,"planId":1,"tierId":1}'
```
Activity is fed as per-order events (ADMIN-only — order value/cohorts ARE the tier criteria); monthly
spend is derived from the events, not posted directly. See the `add-tier-rule` skill and `scripts/demo.sh`
step 6 for the exact shape.

## Teardown
```bash
docker compose down          # keep the volume
docker compose down -v        # drop the Postgres volume (fresh seed next time)
```

## Notes
- On Colima, if the app or tooling can't reach Docker: `export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"`.
- Never commit real secrets; `.env` is gitignored (`.env.example` is the template).
