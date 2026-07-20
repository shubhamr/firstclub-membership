package com.firstclub.membership.exception;

/** Maps to HTTP 402. Raised when the payment port declines or its circuit is open. */
public class PaymentFailedException extends RuntimeException {
  public PaymentFailedException(String message) {
    super(message);
  }
}
