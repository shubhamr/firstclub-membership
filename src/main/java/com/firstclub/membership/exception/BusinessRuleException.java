package com.firstclub.membership.exception;

/**
 * Maps to HTTP 422. Used when a request is well-formed but violates a domain rule (e.g. tier not
 * eligible).
 */
public class BusinessRuleException extends RuntimeException {
  public BusinessRuleException(String message) {
    super(message);
  }
}
