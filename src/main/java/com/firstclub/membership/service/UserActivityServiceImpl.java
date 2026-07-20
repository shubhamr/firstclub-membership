package com.firstclub.membership.service;

import com.firstclub.membership.model.AppUser;
import com.firstclub.membership.model.UserActivity;
import com.firstclub.membership.model.UserMonthlySpend;
import com.firstclub.membership.model.UserOrderStats;
import com.firstclub.membership.repository.AppUserRepository;
import com.firstclub.membership.repository.UserMonthlySpendRepository;
import com.firstclub.membership.repository.UserOrderStatsRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of user activity for the tier engine. Composes the evaluated activity from three
 * sources: lifetime order count and cohorts (from {@link UserOrderStats}), this month's spend (from
 * {@link UserMonthlySpend}), and the user's directory cohort (from {@link AppUser}).
 *
 * <p>Cohorts are unioned across activity and the identity directory, so "user belongs to cohort X"
 * counts toward eligibility whichever side it came from.
 */
@Service
@RequiredArgsConstructor
public class UserActivityServiceImpl implements UserActivityService {

  private final UserOrderStatsRepository repository;
  private final UserMonthlySpendRepository monthlySpendRepository;
  private final AppUserRepository userRepository;
  private final Clock clock;

  /** One read-only transaction covers all three reads rather than three autocommit round-trips. */
  @Transactional(readOnly = true)
  @Override
  public UserActivity currentActivity(long userId) {
    var stats = repository.findByUserId(userId);

    Set<String> cohorts = new HashSet<>(stats.map(UserOrderStats::getCohorts).orElse(List.of()));
    userRepository
        .findById(userId)
        .map(AppUser::getCohort)
        .filter(cohort -> !cohort.isBlank())
        .ifPresent(cohorts::add);

    // Only the CURRENT month counts. A missing bucket is zero spend — that is what stops last
    // month's total from continuing to qualify a spend-gated tier.
    BigDecimal monthlySpend =
        monthlySpendRepository
            .findByUserIdAndYearMonth(userId, YearMonth.now(clock).toString())
            .map(UserMonthlySpend::getAmount)
            .orElse(BigDecimal.ZERO);

    return new UserActivity(
        userId,
        stats.map(UserOrderStats::getOrderCount).orElse(0),
        monthlySpend,
        Set.copyOf(cohorts));
  }
}
