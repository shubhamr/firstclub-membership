package com.firstclub.membership.service;

import com.firstclub.membership.dto.ActivityDtos.ActivityUpdateRequest;
import com.firstclub.membership.dto.MembershipView;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.repository.AppUserRepository;
import com.firstclub.membership.repository.UserOrderEventRepository;
import com.firstclub.membership.repository.UserOrderStatsRepository;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Write side of user activity. Records the order (and lifetime snapshots), rebuilds the affected
 * month's spend bucket from the events, then asks the subscription service to re-evaluate the tier,
 * retrying on an optimistic conflict so a concurrent manual tier change doesn't drop the
 * auto-upgrade.
 *
 * <p>Intentionally not {@code @Transactional}: each write commits before the retried, separately
 * transactional re-evaluation runs (see {@link OptimisticRetry}).
 */
@Service
@RequiredArgsConstructor
public class ActivityIngestionServiceImpl implements ActivityIngestionService {

  private static final int MAX_REEVAL_ATTEMPTS = 3;

  private final UserOrderStatsRepository statsRepository;
  private final MonthlySpendRecalculator monthlySpendRecalculator;
  private final UserOrderEventRepository orderEventRepository;
  private final SubscriptionService subscriptionService;
  private final OptimisticRetry optimisticRetry;
  private final ObjectMapper objectMapper;
  private final AppUserRepository appUserRepository;

  @Override
  public MembershipView recordActivity(long userId, ActivityUpdateRequest req) {
    if (!appUserRepository.existsById(userId)) {
      throw new NotFoundException("User %d is not registered".formatted(userId));
    }
    List<String> cohorts = req.cohorts() == null ? List.of() : req.cohorts();
    // Each write is its own transaction, not wrapped in one: this method stays non-transactional so
    // they commit before the retried re-evaluation below. A crash between them leaves spend stale
    // for one ingest, self-heals on the next, and reevaluateTier only upgrades, so the worst case
    // is a briefly under-tiered user.
    statsRepository.upsert(userId, req.orderCount(), toJson(cohorts));

    // Store the order, then derive the month's bucket from the events. The month comes from
    // occurredAt (the caller's clock), never from the server's clock at receipt, so an order placed
    // near a month boundary lands in the month it actually happened in.
    orderEventRepository.record(req.orderId(), userId, req.orderAmount(), req.occurredAt());
    String month = YearMonth.from(req.occurredAt().atZone(ZoneOffset.UTC)).toString();
    monthlySpendRecalculator.recompute(userId, month);

    // Activity changed, so the user may now qualify for a higher tier. Retry on concurrent
    // conflict.
    return optimisticRetry.execute(
        () -> subscriptionService.reevaluateTier(userId), MAX_REEVAL_ATTEMPTS);
  }

  private String toJson(List<String> cohorts) {
    try {
      return objectMapper.writeValueAsString(cohorts);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("Invalid cohorts payload", e);
    }
  }
}
