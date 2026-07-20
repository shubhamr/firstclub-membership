package com.firstclub.membership.exception;

import java.net.URI;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates domain and infrastructure exceptions into RFC-7807 {@link ProblemDetail} responses.
 *
 * <p>The status choices carry meaning for the client: 409 for state the caller can resolve by
 * reloading and retrying, 422 for a well-formed request that a domain rule rejects, 402 for a
 * declined charge. Concurrency conflicts — optimistic-lock loss and the active-subscription unique
 * index — surface as 409 rather than a 500, so a retry against fresh state is the documented
 * recovery.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  /**
   * A body Jackson cannot read — malformed JSON, or a value outside a closed enum such as {@code
   * BenefitType}. A 400 naming what was rejected is far more useful than the container's default,
   * and keeps every error on the problem+json contract.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail onUnreadableBody(HttpMessageNotReadableException ex) {
    // Jackson's message carries the useful part first, then parser internals (source location,
    // reference chain). Keep the first sentence; the rest is noise to an API client.
    String detail = ex.getMostSpecificCause().getMessage();
    int noise = detail.indexOf(" at [Source:");
    return problem(
        HttpStatus.BAD_REQUEST,
        "Malformed Request Body",
        noise > 0 ? detail.substring(0, noise) : detail);
  }

  @ExceptionHandler(NotFoundException.class)
  public ProblemDetail onNotFound(NotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
  }

  @ExceptionHandler(ConflictException.class)
  public ProblemDetail onConflict(ConflictException ex) {
    return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
  }

  @ExceptionHandler(BusinessRuleException.class)
  public ProblemDetail onBusinessRule(BusinessRuleException ex) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Business Rule Violation", ex.getMessage());
  }

  @ExceptionHandler(PaymentFailedException.class)
  public ProblemDetail onPayment(PaymentFailedException ex) {
    return problem(HttpStatus.PAYMENT_REQUIRED, "Payment Failed", ex.getMessage());
  }

  /** Illegal lifecycle transition (state machine rejected the operation). */
  @ExceptionHandler(IllegalSubscriptionStateException.class)
  public ProblemDetail onIllegalState(IllegalSubscriptionStateException ex) {
    return problem(HttpStatus.CONFLICT, "Illegal Subscription State", ex.getMessage());
  }

  /** Lost optimistic-lock race — the row moved under us. Safe to retry against reloaded state. */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ProblemDetail onOptimisticLock(OptimisticLockingFailureException ex) {
    return problem(
        HttpStatus.CONFLICT,
        "Concurrent Modification",
        "The subscription was modified concurrently. Reload and retry.");
  }

  /**
   * Unique-constraint hit (e.g. a second ACTIVE subscription for the same user) — a concurrency
   * conflict.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ProblemDetail onDataIntegrity(DataIntegrityViolationException ex) {
    return problem(
        HttpStatus.CONFLICT,
        "Conflict",
        "The operation conflicts with existing state (possibly a concurrent duplicate).");
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail onValidation(MethodArgumentNotValidException ex) {
    String detail =
        ex.getBindingResult().getFieldErrors().stream()
            .map(e -> "%s %s".formatted(e.getField(), e.getDefaultMessage()))
            .collect(Collectors.joining("; "));
    return problem(HttpStatus.BAD_REQUEST, "Validation Failed", detail);
  }

  private ProblemDetail problem(HttpStatus status, String title, String detail) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setTitle(title);
    pd.setType(
        URI.create("https://firstclub.com/problems/" + title.toLowerCase().replace(' ', '-')));
    return pd;
  }
}
