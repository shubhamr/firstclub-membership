package com.firstclub.membership.service;

import java.util.function.Supplier;

/**
 * Executes a mutation at most once per (idempotency key, endpoint), replaying the result on retry.
 */
public interface IdempotencyService {

  <T> T runOnce(String key, String endpoint, Class<T> type, Supplier<T> action);
}
