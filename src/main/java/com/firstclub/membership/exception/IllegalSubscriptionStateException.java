package com.firstclub.membership.exception;

/**
 * Maps to HTTP 409. Raised by the subscription state machine when an operation is illegal in the
 * current lifecycle state (e.g. cancelling an already-cancelled subscription).
 */
public class IllegalSubscriptionStateException extends RuntimeException {
  public IllegalSubscriptionStateException(String message) {
    super(message);
  }
}
