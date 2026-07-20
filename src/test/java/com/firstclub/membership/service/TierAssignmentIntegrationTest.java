package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.model.UserActivity;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins the rule engine against the seeded criteria: rules combine with OR, so any single qualifying
 * path is enough, and the highest qualifying tier wins.
 */
class TierAssignmentIntegrationTest extends AbstractIntegrationTest {

  @Autowired TierAssignmentService tierAssignmentService;

  @Test
  void noActivity_qualifiesForBaseTier() {
    assertThat(highest(UserActivity.empty(1))).isEqualTo("SILVER");
  }

  @Test
  void orderCountAlone_qualifiesForGold() {
    assertThat(highest(UserActivity.of(1, 6, BigDecimal.ZERO, List.of()))).isEqualTo("GOLD");
  }

  @Test
  void monthlySpendAlone_qualifiesForGold() {
    assertThat(highest(UserActivity.of(1, 0, new BigDecimal("6000"), List.of()))).isEqualTo("GOLD");
  }

  @Test
  void cohortAlone_qualifiesForPlatinum() {
    assertThat(highest(UserActivity.of(1, 0, BigDecimal.ZERO, List.of("VIP"))))
        .isEqualTo("PLATINUM");
  }

  @Test
  void highThresholds_selectHighestTier() {
    assertThat(highest(UserActivity.of(1, 20, new BigDecimal("25000"), List.of())))
        .isEqualTo("PLATINUM");
  }

  private String highest(UserActivity activity) {
    return tierAssignmentService.highestQualifyingTier(activity).getCode();
  }
}
