package com.firstclub.membership.service;

import com.firstclub.membership.repository.SubscriptionRepository;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Charges auto-renew subscriptions whose paid-through date has elapsed (or a dunning retry is due)
 * and extends them; on failure the service schedules the next retry and eventually revokes. Each
 * renewal is a separate service call with its own transaction boundaries, so one failure never
 * aborts the batch. In a multi-instance deployment this needs a shard lock (ShedLock).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RenewalJob {

  private static final int BATCH = 500;

  private final SubscriptionRepository subscriptionRepository;
  private final RenewalService renewalService;
  private final Clock clock;

  @Scheduled(cron = "${membership.renewal-cron}")
  public void renew() {
    List<Long> ids =
        subscriptionRepository.findDueForRenewal(clock.instant(), PageRequest.of(0, BATCH));
    int processed = 0;
    for (Long id : ids) {
      try {
        renewalService.renew(id);
        processed++;
      } catch (RuntimeException e) {
        log.warn("Renewal skipped sub {}: {}", id, e.toString());
      }
    }
    if (!ids.isEmpty()) {
      log.info("Renewal processed {} subscription(s)", processed);
    }
  }
}
