---
name: add-tier-rule
description: Add a new tier-progression rule (e.g. referral count, tenure, region) to the membership rule engine. Use when asked to add or change how users qualify for a tier.
---

# Add a tier-progression rule

The tier engine is a strategy pattern: `service/rules/TierRule` implementations evaluate a `UserActivity`
against per-tier `TierCriteria`. Existing rules: `OrderCountRule`, `MonthlySpendRule`, `CohortRule`.
Adding a qualifying path is **additive** — a new bean + (optionally) a new criteria column. Never edit the
subscribe/upgrade flow.

## Steps
1. **(If the rule needs a new threshold)** add a nullable column to `tier_criteria` in a **new** Flyway
   migration — the next free version (currently `V22__add_<x>_criteria.sql`) — and a matching field +
   getter on `model/TierCriteria.java`. Nullable = "this rule doesn't gate this tier". Never edit an
   existing migration: they are already applied, and Flyway checksums the whole file.

2. **(If the rule reads new activity)** add the field to `model/UserActivity.java` (a record) and populate
   it in `service/UserActivityServiceImpl.currentActivity()`, which composes activity from the order
   stats, monthly-spend window and directory sources.

3. **Create the rule** in `service/rules/`, implementing `TierRule`'s three methods:
   ```java
   @Component
   public class ReferralCountRule implements TierRule {
     @Override public boolean isConfigured(TierCriteria c) {
       return c.getMinReferrals() != null;
     }
     @Override public boolean qualifies(UserActivity a, TierCriteria c) {
       return isConfigured(c) && a.referrals() >= c.getMinReferrals();
     }
     @Override public String code() { return "REFERRAL_COUNT"; }
   }
   ```
   Contract: `qualifies` returns `true` only when the threshold is **configured** AND met.

   `isConfigured` is not optional bookkeeping — it is how `TierAssignmentService` decides a tier is
   *unconditional* (open to everyone). Report it wrongly and a tier gated only by your new threshold looks
   unconfigured, so **every user qualifies** and the whole base is auto-upgraded into it. That is why the
   method is abstract rather than defaulted: the compiler makes you answer.

   `code()` must be a stable `UPPER_SNAKE` string, unique across rules — `TierAssignmentService` logs it
   at DEBUG to record which rule qualified a user (and lists all codes when none matched). It is the
   audit trail for "why is this user on this tier?", so don't reword an existing one casually.

4. **Nothing else to wire.** `TierAssignmentServiceImpl` injects `List<TierRule>` and already combines
   them (OR semantics), and picks up the new `code()` in its evaluation logging for free. Spring picks up
   the new `@Component` automatically.

5. **Seed / configure** the threshold for the relevant tiers — in a **new** migration or via admin data.
   Not in `V2__seed.sql`: it has already been applied and editing it breaks the Flyway checksum.

6. **Test it** — add an integration case to `TierAssignmentIntegrationTest` asserting the new qualifying
   path selects the expected tier, and a unit case to `TierAssignmentUnitTest` covering `isConfigured`
   (a tier gated only by your rule must **not** be treated as unconditional). Then
   `mvn test -Dtest='TierAssignment*Test'`.

## Changing combination policy
OR-across-rules lives in exactly one place: `TierAssignmentServiceImpl.qualifies(...)`. Change it there
(e.g. to require ALL configured rules) — not in the rules themselves.
