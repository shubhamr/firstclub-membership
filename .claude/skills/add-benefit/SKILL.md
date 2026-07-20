---
name: add-benefit
description: Add a new benefit or attach/tune a benefit on a tier (configurable perks). Use when asked to add a membership perk or change what a tier unlocks.
---

# Add or configure a benefit

Benefits are data: a `benefit` catalog row + `tier_benefit` mapping rows with JSONB `params`. Most changes
are runtime config via the admin API — no code, no redeploy. The admin routes require an ADMIN token.

## Runtime (no code) — preferred
```bash
B=http://localhost:8080/api/v1
# 0. Mint an ADMIN token (needs the bootstrap secret; default in .env.example is local-dev-admin-bootstrap)
ADMIN=$(curl -s -X POST $B/auth/token -H 'Content-Type: application/json' \
         -d '{"userId":0,"admin":true,"secret":"local-dev-admin-bootstrap"}' | jq -r .token)
# 1. Create a benefit in the catalog
curl -s -X POST $B/admin/benefits -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
     -d '{"code":"BIRTHDAY_GIFT","type":"EXCLUSIVE_DEALS","name":"Birthday Gift","description":"Annual gift"}'
# 2. Attach it to a tier with per-tier params (auto-evicts the catalog cache)
curl -s -X POST $B/admin/benefits/assignments -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
     -d '{"tierId":3,"benefitCode":"BIRTHDAY_GIFT","params":{"value":500}}'
# 3. Verify (public)
curl -s $B/tiers
```

## Seed it for fresh environments
Add the `insert into benefit ...` and `insert into tier_benefit ...` rows to a **new** Flyway migration
(the next free version — currently `V22__add_<benefit>.sql`) so demos start with it. Follow the pattern in
`V2__seed.sql`. Never edit `V2__seed.sql` itself: it has already been applied and Flyway checksums the
whole file.

## New benefit *category* (needs code)
Only if the perk is a genuinely new kind that downstream systems must interpret differently:
1. Add a value to `model/BenefitType.java` (current set: `FREE_DELIVERY`, `EXTRA_DISCOUNT`,
   `EXCLUSIVE_DEALS`, `EXCLUSIVE_COUPONS`, `PRIORITY_SUPPORT`).
2. If it carries behavior at checkout/delivery, teach the consuming code to read its `params` —
   `service/BenefitResolver` already returns `dto/ResolvedBenefit` with `type` + `params`.

## Notes
- Per-tier tuning (e.g. bump a tier's discount to 20%) is just an `assignments` call with new `params` —
  the upsert is race-safe and evicts the `tierBenefits` + `tiers` caches (in-memory `CacheManager`).
- Keep `params` shapes consistent per `type` (e.g. `EXTRA_DISCOUNT` →
  `{"discountPct":N, "categories":["FASHION", …]}`, `FREE_DELIVERY` → `{"minOrderValue":N}`).
- Widening an existing tier's `params` from SQL? Use `params || '{…}'::jsonb`, never `=` — assigning
  replaces the whole map and silently drops the keys already there.
