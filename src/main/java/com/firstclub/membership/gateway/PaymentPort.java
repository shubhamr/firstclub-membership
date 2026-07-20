package com.firstclub.membership.gateway;

import java.math.BigDecimal;

/**
 * Port to an external payment gateway. Domain code depends on this interface, never on a concrete
 * client; the adapter owns timeouts, the circuit breaker and the fallback. Swapping gateways, or
 * stubbing one in tests, is a matter of providing a different implementation.
 */
public interface PaymentPort {

  /**
   * Charge the user for a subscription. Implementations must be resilient to downstream
   * latency/outage and translate a hard failure into a {@link
   * com.firstclub.membership.exception.PaymentFailedException} rather than letting the caller
   * thread hang.
   */
  PaymentResult charge(long userId, BigDecimal amount, String reference);
}
