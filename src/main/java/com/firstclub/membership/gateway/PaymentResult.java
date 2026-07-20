package com.firstclub.membership.gateway;

/** Outcome of a charge attempt against the payment port. */
public record PaymentResult(boolean success, String transactionRef, String message) {

  public static PaymentResult ok(String ref) {
    return new PaymentResult(true, ref, "approved");
  }
}
