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
 * Converts free trials to paid once the trial window elapses: charges the locked price and extends
 * the subscription. Each conversion is a separate service call with its own transaction boundaries,
 * so one failure never aborts the batch. In a multi-instance deployment this needs a shard lock
 * (ShedLock).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TrialConversionJob {

  private static final int BATCH = 500;

  private final SubscriptionRepository subscriptionRepository;
  private final RenewalService renewalService;
  private final Clock clock;

  @Scheduled(cron = "${membership.trial-conversion-cron}")
  public void convert() {
    List<Long> ids =
        subscriptionRepository.findConvertibleTrialIds(clock.instant(), PageRequest.of(0, BATCH));
    int converted = 0;
    for (Long id : ids) {
      try {
        renewalService.convertTrial(id);
        converted++;
      } catch (RuntimeException e) {
        log.warn("Trial conversion skipped sub {}: {}", id, e.toString());
      }
    }
    if (!ids.isEmpty()) {
      log.info("Trial conversion processed {} subscription(s)", converted);
    }
  }
}
