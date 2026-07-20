package com.firstclub.membership.gateway.adapter;

import com.firstclub.membership.exception.PaymentFailedException;
import com.firstclub.membership.gateway.PaymentPort;
import com.firstclub.membership.gateway.PaymentResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stub payment adapter standing in for a real gateway (Stripe/Razorpay/etc).
 *
 * <p>A {@code paymentGateway} circuit breaker guards the charge: once the failure or slow-call rate
 * crosses its threshold the breaker opens and calls fail fast into {@link #chargeFallback}, so a
 * gateway outage can't tie up request threads waiting on a dead dependency. The fallback returns a
 * domain {@link PaymentFailedException} (HTTP 402) — never a hung thread or a raw 500. Connect/read
 * timeouts, a bulkhead and a per-call {@code TimeLimiter} would need this to go async, and are not
 * implemented here.
 *
 * <p>Charges are idempotent: {@code reference} doubles as the gateway idempotency key, so retrying
 * a timed-out call replays the original result instead of charging twice. A real gateway dedupes
 * server-side on this key; the stub keeps an in-memory map to honour the same contract.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StubPaymentAdapter implements PaymentPort {

  /**
   * Cap on the tracked-charge LRU below. An unbounded map would be a memory leak: one entry per
   * charge, retained for the life of the process. A real gateway expires idempotency keys on a time
   * window (typically 24h); capping by size is the dependency-free equivalent.
   */
  private static final int MAX_TRACKED_CHARGES = 100_000;

  private final Map<String, PaymentResult> charged =
      Collections.synchronizedMap(
          new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PaymentResult> eldest) {
              return size() > MAX_TRACKED_CHARGES;
            }
          });

  private final MeterRegistry metrics;

  @Override
  @CircuitBreaker(name = "paymentGateway", fallbackMethod = "chargeFallback")
  public PaymentResult charge(long userId, BigDecimal amount, String reference) {
    // The check and the record must be atomic: with a get()-then-put(), two concurrent calls
    // carrying the same reference both miss the lookup and both charge — the double-charge this
    // contract exists to prevent. Holding the monitor across the synchronous in-memory call is
    // safe here; a real adapter would rely on the gateway's server-side dedupe, not a local lock.
    synchronized (charged) {
      PaymentResult existing = charged.get(reference);
      if (existing != null) {
        log.info("Idempotent charge replay for ref={} (no second charge)", reference);
        return existing;
      }
      // Simulated always-approve gateway. Deterministic (no RNG) so tests/demos are reproducible.
      log.info("Charging user={} amount={} ref={}", userId, amount, reference);
      PaymentResult result = PaymentResult.ok("txn-" + reference);
      charged.put(reference, result);
      return result;
    }
  }

  @SuppressWarnings("unused") // referenced by name from @CircuitBreaker(fallbackMethod)
  private PaymentResult chargeFallback(
      long userId, BigDecimal amount, String reference, Throwable t) {
    log.warn("Payment fallback for user={} ref={}: {}", userId, reference, t.toString());
    metrics.counter("membership.payment.failure").increment();
    throw new PaymentFailedException("Payment gateway unavailable; please retry shortly.");
  }
}
