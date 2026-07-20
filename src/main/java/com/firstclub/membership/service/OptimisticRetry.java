package com.firstclub.membership.service;

import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Retries a system-initiated operation that lost an optimistic-lock race. Each attempt must be a
 * proxied {@code @Transactional} call (a fresh transaction), so the caller passes a supplier that
 * invokes such a method on another bean — never a same-bean self-invocation.
 *
 * <p>Used for the auto tier re-evaluation triggered by activity ingestion: a rare conflict with a
 * concurrent manual tier change should transparently retry against reloaded state rather than 409 a
 * background recompute. (Spring Framework 7's built-in {@code @Retryable} could replace this; a
 * hand loop is used here to keep the retry boundary explicit and dependency-free.)
 */
@Component
@Slf4j
public class OptimisticRetry {

  public <T> T execute(Supplier<T> action, int maxAttempts) {
    int attempt = 0;
    while (true) {
      try {
        return action.get();
      } catch (OptimisticLockingFailureException ex) {
        if (++attempt >= maxAttempts) {
          log.warn("Optimistic retry exhausted after {} attempts", attempt);
          throw ex;
        }
        log.debug("Optimistic conflict, retry {}/{}", attempt, maxAttempts);
      }
    }
  }
}
