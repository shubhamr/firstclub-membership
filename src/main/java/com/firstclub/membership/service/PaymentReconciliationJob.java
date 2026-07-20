package com.firstclub.membership.service;

import com.firstclub.membership.model.Payment;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finds charges that took money and never delivered — ledger rows still {@code CHARGED} well past
 * the point where the applying transaction should have committed.
 *
 * <p>This makes the reserve → charge → apply gap operable. Service-layer compensation handles the
 * failures it can see; this catches the ones nobody saw, where the process died between charge and
 * apply. Without it, "we charged a member and gave them nothing" is only discoverable by
 * reconciling against the gateway by hand.
 *
 * <p><b>It deliberately does not auto-remediate.</b> The right repair depends on why the apply
 * failed — a cancelled subscription needs a refund, a lost lock race needs a re-apply — and
 * silently picking the wrong one is worse than a precise alert carrying the exact references.
 * Refund automation would need {@code PaymentPort.refund}, which the port does not yet expose.
 */
@Component
@Slf4j
public class PaymentReconciliationJob {

  private static final int BATCH = 200;

  private final PaymentLedger paymentLedger;
  private final Clock clock;
  private final Duration settleWindow;
  private final AtomicInteger stuckCount = new AtomicInteger();
  private final AtomicInteger inDoubtCount = new AtomicInteger();

  public PaymentReconciliationJob(
      PaymentLedger paymentLedger,
      Clock clock,
      MeterRegistry metrics,
      @Value("${membership.payment.settle-window-minutes:15}") long settleWindowMinutes) {
    this.paymentLedger = paymentLedger;
    this.clock = clock;
    this.settleWindow = Duration.ofMinutes(settleWindowMinutes);
    // Alert on either being > 0. Both are denominated in real money: stuck_charged is money we
    // took and owe delivery on; in_doubt is money we may or may not have taken at all.
    metrics.gauge("membership.payment.stuck_charged", stuckCount);
    metrics.gauge("membership.payment.in_doubt", inDoubtCount);
  }

  @Scheduled(cron = "${membership.payment-reconciliation-cron}")
  public void reconcile() {
    Instant cutoff = clock.instant().minus(settleWindow);

    // The gauges count the WHOLE population, not the page: a gauge set from the batch saturates at
    // BATCH, so it would stop sizing the problem exactly when the problem became severe. The paged
    // fetch below is only for the detail log.
    stuckCount.set((int) paymentLedger.countStuckCharged(cutoff));
    inDoubtCount.set((int) paymentLedger.countInDoubt(cutoff));

    report(
        paymentLedger.findStuckCharged(cutoff, BATCH),
        "took money without applying",
        "delivery is owed to the member");
    report(
        paymentLedger.findInDoubt(cutoff, BATCH),
        "failed ambiguously",
        "ask the gateway whether it captured, using the reference as the idempotency key");
  }

  private void report(List<Payment> rows, String what, String remedy) {
    if (rows.isEmpty()) {
      return;
    }
    log.error(
        "PAYMENT RECONCILIATION: {} payment(s) {} — {}. References: {}",
        rows.size(),
        what,
        remedy,
        rows.stream().map(Payment::getReference).toList());
    for (Payment p : rows) {
      log.error(
          "  ref={} status={} user={} sub={} amount={} purpose={} at={} reason={}",
          p.getReference(),
          p.getStatus(),
          p.getUserId(),
          p.getSubscriptionId(),
          p.getAmount(),
          p.getPurpose(),
          p.getCreatedAt(),
          p.getFailureReason());
    }
    if (rows.size() == BATCH) {
      log.error("Batch was full ({}) — there are more beyond this page", BATCH);
    }
  }
}
