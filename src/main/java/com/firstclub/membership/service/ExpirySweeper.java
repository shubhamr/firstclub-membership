package com.firstclub.membership.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically normalizes the status of subscriptions whose term has elapsed. This is housekeeping,
 * not correctness: {@link SubscriptionService#getMembership} already expires lazily on read, so a
 * user is never served a stale-active membership even between sweeps.
 *
 * <p>In a multi-instance deployment this should run under a shard lock (ShedLock) so only one node
 * sweeps.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ExpirySweeper {

  private static final int BATCH_SIZE = 500;

  private final SubscriptionService subscriptionService;

  @Scheduled(cron = "${membership.expiry-sweep-cron}")
  public void sweep() {
    int expired = subscriptionService.sweepExpired(BATCH_SIZE);
    if (expired > 0) {
      log.info("Expiry sweep normalized {} subscription(s)", expired);
    }
    // Backstop for reserve → charge → activate: reap PENDING reservations orphaned by a hard crash
    // between reserving and charging, which would otherwise block the user via the unique index.
    int reaped = subscriptionService.sweepStalePendingReservations(BATCH_SIZE);
    if (reaped > 0) {
      log.info("Reaped {} stale PENDING reservation(s)", reaped);
    }
  }
}
