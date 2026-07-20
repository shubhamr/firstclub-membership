package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.model.UserActivity;
import com.firstclub.membership.repository.UserMonthlySpendRepository;
import com.firstclub.membership.repository.UserOrderEventRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins "total order value in a month": spend qualifies a tier only in the month it was earned.
 *
 * <p>Both directions are asserted deliberately. The prior-month case alone would still pass if the
 * read path were broken to always report zero, so the current-month case is what proves the window
 * is a window rather than a floor.
 *
 * <p>No fixed clock is needed: the repository takes the month as a parameter, so a test can bank
 * spend against any bucket while the service resolves "now" through the real {@code Clock}.
 */
class MonthlySpendWindowIntegrationTest extends AbstractIntegrationTest {

  private static final BigDecimal PLATINUM_SPEND = new BigDecimal("30000");

  @Autowired UserMonthlySpendRepository monthlySpendRepository;
  @Autowired UserOrderEventRepository orderEventRepository;
  @Autowired UserActivityService userActivityService;
  @Autowired TierAssignmentService tierAssignmentService;

  @BeforeEach
  void seedUsers() {
    seedUser(7201L);
    seedUser(7202L);
    seedUser(7203L);
  }

  private String thisMonth() {
    return YearMonth.now(Clock.systemUTC()).toString();
  }

  private String lastMonth() {
    return YearMonth.now(Clock.systemUTC()).minusMonths(1).toString();
  }

  /**
   * Bank spend the way production does: an order event in that month, then recompute the bucket.
   */
  private void bankSpend(long userId, String yyyyMm, BigDecimal amount) {
    YearMonth month = YearMonth.parse(yyyyMm);
    Instant when = month.atDay(15).atStartOfDay(ZoneOffset.UTC).toInstant();
    orderEventRepository.record(userId * 100 + month.getMonthValue(), userId, amount, when);
    monthlySpendRepository.recomputeMonthFromEvents(userId, yyyyMm);
  }

  @Test
  void spendEarnedThisMonth_countsTowardTheTier() {
    long userId = 7201L;
    bankSpend(userId, thisMonth(), PLATINUM_SPEND);

    UserActivity activity = userActivityService.currentActivity(userId);

    assertThat(activity.monthlySpend()).isEqualByComparingTo(PLATINUM_SPEND);
    assertThat(tierAssignmentService.highestQualifyingTier(activity).getCode())
        .isEqualTo("PLATINUM");
  }

  @Test
  void spendEarnedLastMonth_doesNotCarryIntoThisMonth() {
    long userId = 7202L;
    bankSpend(userId, lastMonth(), PLATINUM_SPEND);

    UserActivity activity = userActivityService.currentActivity(userId);

    assertThat(activity.monthlySpend()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(tierAssignmentService.highestQualifyingTier(activity).getCode()).isEqualTo("SILVER");
  }

  @Test
  void spendIsBankedPerMonth_notAccumulatedAcrossMonths() {
    long userId = 7203L;
    bankSpend(userId, lastMonth(), PLATINUM_SPEND);
    bankSpend(userId, thisMonth(), new BigDecimal("100"));

    assertThat(userActivityService.currentActivity(userId).monthlySpend())
        .isEqualByComparingTo(new BigDecimal("100"));
  }
}
