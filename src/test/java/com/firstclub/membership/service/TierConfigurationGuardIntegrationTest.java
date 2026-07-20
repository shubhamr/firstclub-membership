package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstclub.membership.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the startup guard for tier configuration.
 *
 * <p>The failure it prevents is silent. {@link TierAssignmentServiceImpl} treats a criteria row
 * that no rule claims a threshold on as unconditional, which is correct for the base tier. But an
 * all-null row seeded on Gold or Platinum is byte-identical to the base tier's, so the check cannot
 * tell an intentionally-open tier from one an operator forgot to fill in. Left unguarded, every
 * user qualifies for that tier and reconciliation auto-upgrades the whole member base into it,
 * logging each promotion as legitimate. The only safe place to catch that is before the port opens.
 *
 * <p>Each mutation runs in a rolled-back transaction, so the shared container's seed is untouched.
 */
@Transactional
class TierConfigurationGuardIntegrationTest extends AbstractIntegrationTest {

  @Autowired TierAssignmentServiceImpl service;
  @Autowired JdbcTemplate jdbc;

  private long tierId(String code) {
    return jdbc.queryForObject("select id from membership_tier where code = ?", Long.class, code);
  }

  private void makeUnconditional(String code) {
    jdbc.update(
        "update tier_criteria set min_orders = null, min_monthly_spend = null, cohorts = '[]'::jsonb"
            + " where tier_id = ?",
        tierId(code));
  }

  @Test
  void theSeededConfigurationBoots() {
    assertThatCode(service::assertAtMostOneUnconditionalBaseTier).doesNotThrowAnyException();
  }

  @Test
  void twoUnconditionalTiers_refuseToStart() {
    makeUnconditional("GOLD"); // SILVER is already open; now two tiers are

    assertThatThrownBy(service::assertAtMostOneUnconditionalBaseTier)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("More than one")
        .hasMessageContaining("GOLD");
  }

  @Test
  void anOpenTierAboveTheBase_refusesToStart() {
    // Close the base so exactly one tier is open, but make it a non-lowest one.
    jdbc.update("update tier_criteria set min_orders = 1 where tier_id = ?", tierId("SILVER"));
    makeUnconditional("GOLD");

    assertThatThrownBy(service::assertAtMostOneUnconditionalBaseTier)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GOLD")
        .hasMessageContaining("lowest-rank");
  }
}
