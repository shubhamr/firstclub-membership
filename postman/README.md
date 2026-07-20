# Postman collection

A complete Postman collection for the FirstClub Membership REST API. It covers all 17 controller
endpoints plus the exposed actuator endpoints, with token capture wired up so the folders run
top-to-bottom without copying ids by hand.

| File | What it is |
|---|---|
| `FirstClub-Membership.postman_collection.json` | The collection (v2.1 schema), 25 requests in 8 folders |
| `FirstClub-Local.postman_environment.json` | Environment pointing at `http://localhost:8080` |

### How you get an API token: you don't, the collection does it

Run `Auth > Mint self token` and `Auth > Mint admin token` first. Their test scripts write
`selfToken` and `adminToken` into collection variables, and every later request references
`{{selfToken}}` or `{{adminToken}}` in its `Authorization` header. You never copy a JWT by hand.

The only thing that must line up is the admin secret. The environment ships
`adminBootstrapSecret = local-dev-admin-bootstrap`, which matches `.env.example`. If you changed
`ADMIN_BOOTSTRAP_SECRET` in your `.env`, change it here too or admin minting returns 403.

Prefer the terminal? [`scripts/demo.sh`](../scripts/demo.sh) does the same walkthrough with curl.

### Saved examples

24 of the 25 requests ship a saved example response. Open a request and pick it from the dropdown
next to the URL to see the real body and status without running anything. Every example was captured
from a live run against a clean database rather than hand-written, so the shapes are exactly what the
service returns: `201` on subscribe, the same id on an idempotent replay, `200` with the widened
benefit set after a tier auto-upgrade, and `404` / `409` / `422` / `400` problem+json for the error
contract.

The one without an example is `Activity / Refund an order`, a variant of the order request above.

## Import

In Postman: Import → Files, select both JSON files, then pick FirstClub Local in the environment
dropdown (top right). Or from the CLI with Newman:

```bash
npm install -g newman
newman run postman/FirstClub-Membership.postman_collection.json \
  -e postman/FirstClub-Local.postman_environment.json
```

## Start the app first

The collection talks to a running service. From the repo root:

```bash
cp .env.example .env     # REQUIRED, see below
docker compose up -d     # Postgres
mvn spring-boot:run      # Flyway migrates + seeds on startup
```

Two secrets have no defaults and matter here:

- `JWT_SECRET` is the HS256 signing key (>= 32 bytes). The app refuses to start without it, on
  purpose: a signing key committed to the repo would let anyone forge an ADMIN token offline, which
  makes every authorization control decorative.
- `ADMIN_BOOTSTRAP_SECRET` is required to mint an ADMIN token. Blank or unset means admin minting is
  impossible, and `Auth > Mint admin token` returns 403. Every ADMIN-only request in the collection
  (Users, Activity, Admin, Refund) then fails with 403 too.

The collection variable `adminBootstrapSecret` must equal the app's `ADMIN_BOOTSTRAP_SECRET`. Both
default to `local-dev-admin-bootstrap`, the value in `.env.example`. Change one and change the other.

`.env.example` also sets `AUTH_DEV_TOKEN_ENABLED=true` and `AUTH_DEV_TOKEN_ALLOW_OUTSIDE_DEV=true`.
Both default to off. With them off the `POST /api/v1/auth/token` bean and its route do not exist at
all (`@ConditionalOnProperty`), so the whole collection is unauthenticated and returns 401. That is
the correct production posture, where a real IdP replaces the dev token endpoint.

If you changed `SERVER_PORT`, update the `baseUrl` environment variable to match.

If the app exits at startup with `Migrations have failed validation`, the Postgres volume predates
the current schema. Reset it with `docker compose down -v && docker compose up -d`. A clean volume
always migrates cleanly.

## Recommended run order

Run the folders in the order they appear. Each step feeds the next through collection variables, so
nothing needs to be pasted by hand.

1. Auth: `Mint self token` and `Mint admin token` capture `selfToken` and `adminToken`.
   Everything after this depends on them.
2. Catalog: `List plans` captures `planId` (Monthly); `List tiers` captures `tierId` (SILVER,
   rank 1), `goldTierId` (rank 2) and `platinumTierId` (rank 3). Both are public and send no token.
3. Users: directory listing and provisioning (ADMIN), plus a self-or-admin single-user read.
4. Subscriptions: `Subscribe` captures `subscriptionId`, then replay/membership/upgrade/
   downgrade/cancel/refund/credit-notes.
5. Activity: post a per-order event as ADMIN and watch the tier re-evaluate. `Refund an order`
   re-sends the same order at a lower amount and the spend drops.
6. Admin: create a benefit and re-tune a per-tier perk at runtime.
7. Actuator: health and info are the only exposed endpoints, both public.
8. Error contract: the negative paths.

### Two ordering caveats

- `Error contract > 422 — upgrade to an unqualified tier` must run before
  `Activity > Record order activity`. That order (25000, `VIP` cohort, this month) qualifies the
  user for PLATINUM, after which the upgrade succeeds with 200 instead of 422.
  Likewise `Subscriptions > Upgrade tier` expects activity to have been fed first.
- Cancel and refund are mutually exclusive. `CANCELLED` is a terminal state, so whichever you run
  second returns 409 Illegal Subscription State. Subscribe again to exercise the other one.

`Error contract > 409 — double-subscribe` needs the user to still hold an ACTIVE subscription, so
run it before cancelling.

## What the collection demonstrates

- Authorization is per-endpoint, not blanket. Public endpoints send no `Authorization` header;
  ADMIN-only endpoints send `adminToken`; self-or-admin endpoints send `selfToken`. Swapping a token
  is the fastest way to see the 403s that matter.
- Idempotency. Subscription mutations carry an `Idempotency-Key`. Upgrade, downgrade, cancel and
  refund use a fresh `{{$guid}}` per send. `Subscribe` uses a guid generated once into
  `subscribeIdemKey`, so `Subscribe — replay the same Idempotency-Key` can resend the identical key
  and show the original result being replayed rather than a second subscription being created.
  Contrast that with the 409 double-subscribe, which sends a fresh key and the same body.
- The RFC-7807 error contract. Every request in the Error contract folder returns
  `application/problem+json`, and each has a test asserting the status and the content type: 404 not
  found, 422 domain-rule violation, 409 conflict. 402 covers a declined payment and 400 a
  bean-validation failure.

## Keeping it in sync

The collection was written against the controllers, not against documentation. Every path, method,
body field and authorization rule was read from
`src/main/java/com/firstclub/membership/controller/`, `dto/` and `security/SecurityConfig.java`.
If you add or change an endpoint, update this collection in the same change. The live OpenAPI
document at `/v3/api-docs` (Swagger UI at `/swagger-ui.html`) is the cross-check.
