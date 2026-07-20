package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscriptionDto;
import com.firstclub.membership.model.MembershipTier;
import com.firstclub.membership.model.Subscription;
import com.firstclub.membership.model.SubscriptionStatus;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.TierRepository;
import com.firstclub.membership.repository.UserMonthlySpendRepository;
import com.firstclub.membership.repository.UserOrderEventRepository;
import com.firstclub.membership.repository.UserOrderStatsRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins the three concurrency guarantees against a real Postgres: the active-subscription unique
 * index, idempotent retries, and optimistic-lock protection against lost updates.
 */
class SubscriptionConcurrencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired SubscriptionService subscriptionService;
  @Autowired IdempotencyService idempotencyService;
  @Autowired SubscriptionRepository subscriptionRepository;
  @Autowired UserOrderStatsRepository statsRepository;
  @Autowired UserMonthlySpendRepository monthlySpendRepository;
  @Autowired UserOrderEventRepository orderEventRepository;
  @Autowired UserActivityService userActivityService;
  @Autowired PlanRepository planRepository;
  @Autowired TierRepository tierRepository;

  @BeforeEach
  void seedUsers() {
    seedUser(1001L);
    seedUser(1002L);
    seedUser(1003L);
  }

  private Long planId() {
    return planRepository.findByActiveTrueOrderByPriceAsc().getFirst().getId();
  }

  private Long tierId(String code) {
    return tierRepository.findByCode(code).orElseThrow().getId();
  }

  @Test
  void concurrentSubscribe_createsExactlyOneActiveSubscription() throws Exception {
    long userId = 1001L;
    SubscribeRequest req = new SubscribeRequest(userId, planId(), tierId("SILVER"));

    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Callable<Boolean>> tasks = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      tasks.add(
          () -> {
            try {
              subscriptionService.subscribe(req);
              return true;
            } catch (RuntimeException expectedConflict) {
              return false; // ConflictException / DataIntegrityViolation / OptimisticLock
            }
          });
    }
    List<Future<Boolean>> results = pool.invokeAll(tasks);
    pool.shutdown();

    long successes =
        results.stream()
            .filter(
                f -> {
                  try {
                    return f.get();
                  } catch (Exception e) {
                    return false;
                  }
                })
            .count();

    // At most one subscribe may win; the DB partial unique index enforces it regardless of races.
    assertThat(successes).isEqualTo(1);
    assertThat(subscriptionRepository.countByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
        .isEqualTo(1);
  }

  @Test
  void idempotentSubscribe_replaysOriginalResult_withoutDoubleMutation() {
    long userId = 1002L;
    SubscribeRequest req = new SubscribeRequest(userId, planId(), tierId("SILVER"));
    String key = "idem-1002";

    SubscriptionDto first =
        idempotencyService.runOnce(
            key,
            "POST /subscriptions",
            SubscriptionDto.class,
            () -> subscriptionService.subscribe(req));
    SubscriptionDto replay =
        idempotencyService.runOnce(
            key,
            "POST /subscriptions",
            SubscriptionDto.class,
            () -> subscriptionService.subscribe(req));

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(subscriptionRepository.countByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE))
        .isEqualTo(1);
  }

  @Test
  void concurrentUpgrade_toSameTier_appliesExactlyOnce() throws Exception {
    long userId = 1003L;
    SubscriptionDto sub =
        subscriptionService.subscribe(new SubscribeRequest(userId, planId(), tierId("SILVER")));

    // Stats are written directly so the user qualifies for PLATINUM without the auto-upgrade path
    // firing, which would consume the transition this test is racing.
    statsRepository.upsert(userId, 30, "[\"VIP\"]");
    orderEventRepository.record(
        userId * 100 + 1,
        userId,
        new BigDecimal("30000"),
        YearMonth.now(ZoneOffset.UTC).atDay(15).atStartOfDay(ZoneOffset.UTC).toInstant());
    monthlySpendRepository.recomputeMonthFromEvents(
        userId, YearMonth.now(Clock.systemUTC()).toString());
    // The VIP cohort alone also qualifies for PLATINUM, so a dropped spend write would leave the
    // test green while covering nothing. Assert the spend landed.
    assertThat(userActivityService.currentActivity(userId).monthlySpend())
        .isEqualByComparingTo(new BigDecimal("30000"));

    MembershipTier platinum = tierRepository.findByCode("PLATINUM").orElseThrow();

    int threads = 6;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicInteger successes = new AtomicInteger();
    List<Callable<Void>> tasks = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      tasks.add(
          () -> {
            try {
              subscriptionService.upgrade(sub.id(), platinum.getId());
              successes.incrementAndGet();
            } catch (RuntimeException expected) {
              // optimistic-lock loss OR "already at that tier" — both are correct rejections
            }
            return null;
          });
    }
    pool.invokeAll(tasks);
    pool.shutdown();

    // The SILVER -> PLATINUM transition can happen at most once; no lost updates, no double apply.
    assertThat(successes.get()).isEqualTo(1);
    Subscription reloaded = subscriptionRepository.findByIdWithDetails(sub.id()).orElseThrow();
    assertThat(reloaded.getTier().getCode()).isEqualTo("PLATINUM");
  }
}
