package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.firstclub.membership.model.MembershipTier;
import com.firstclub.membership.model.TierCriteria;
import com.firstclub.membership.model.UserActivity;
import com.firstclub.membership.repository.TierCriteriaRepository;
import com.firstclub.membership.repository.TierRepository;
import com.firstclub.membership.service.rules.CohortRule;
import com.firstclub.membership.service.rules.MonthlySpendRule;
import com.firstclub.membership.service.rules.OrderCountRule;
import com.firstclub.membership.service.rules.TierRule;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the combination policy in {@link TierAssignmentServiceImpl}, the one place that decides what
 * "unconditional" means.
 *
 * <p>Plain JUnit rather than an integration test: the service takes its collaborators on the
 * constructor, so the rule engine runs without Postgres or a Spring context.
 */
class TierAssignmentUnitTest {

  private static final long TIER_ID = 2L;

  private final TierRepository tierRepository = mock(TierRepository.class);
  private final TierCriteriaRepository criteriaRepository = mock(TierCriteriaRepository.class);

  /** Criteria with none of the three shipped thresholds set. */
  private TierCriteria bareCriteria() {
    TierCriteria criteria = mock(TierCriteria.class);
    when(criteria.getMinOrders()).thenReturn(null);
    when(criteria.getMinMonthlySpend()).thenReturn(null);
    when(criteria.getCohorts()).thenReturn(List.of());
    return criteria;
  }

  private MembershipTier tierWithCriteria(TierCriteria criteria) {
    MembershipTier tier = mock(MembershipTier.class);
    when(tier.getId()).thenReturn(TIER_ID);
    when(criteriaRepository.findByTierId(TIER_ID)).thenReturn(Optional.ofNullable(criteria));
    return tier;
  }

  private TierAssignmentServiceImpl serviceWith(List<TierRule> rules) {
    return new TierAssignmentServiceImpl(rules, tierRepository, criteriaRepository);
  }

  private List<TierRule> shippedRules() {
    return List.of(new OrderCountRule(), new MonthlySpendRule(), new CohortRule());
  }

  /**
   * Adding a rule must not fail open. If "unconditional" were derived from a fixed list of criteria
   * fields, a tier gated only by a newer criterion would read as "nothing configured": every user
   * would qualify and {@code reevaluateTier} would auto-upgrade the whole member base into it.
   * Deriving it from the rules themselves is what keeps adding a rule purely additive.
   */
  @Test
  void tierGatedOnlyByANewerRule_isNotTreatedAsUnconditional() {
    TierRule newerRule = mock(TierRule.class);
    when(newerRule.isConfigured(any())).thenReturn(true); // the fourth threshold is set on the tier
    when(newerRule.qualifies(any(), any())).thenReturn(false); // and the user does not meet it

    TierCriteria criteria = bareCriteria(); // none of the three shipped thresholds set
    MembershipTier tier = tierWithCriteria(criteria);

    List<TierRule> rules =
        List.of(new OrderCountRule(), new MonthlySpendRule(), new CohortRule(), newerRule);

    assertThat(serviceWith(rules).isEligibleFor(tier, UserActivity.empty(1L))).isFalse();
  }

  /** The other half of the contract: a tier no rule claims is still open to everyone. */
  @Test
  void tierNoRuleClaimsAThresholdOn_staysOpenAsTheBaseTier() {
    MembershipTier tier = tierWithCriteria(bareCriteria());

    assertThat(serviceWith(shippedRules()).isEligibleFor(tier, UserActivity.empty(1L))).isTrue();
  }

  /** A missing criteria row is a misconfiguration, not an open door. */
  @Test
  void missingCriteriaRow_failsClosed() {
    MembershipTier tier = tierWithCriteria(null);

    assertThat(serviceWith(shippedRules()).isEligibleFor(tier, UserActivity.empty(1L))).isFalse();
  }
}
