package com.firstclub.membership.service;

import com.firstclub.membership.model.IdempotencyRecord;
import com.firstclub.membership.repository.IdempotencyRepository;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Executes a mutation at most once per (idempotency key, endpoint), returning the original response
 * on replay — so a client retrying a timed-out POST, or a broker redelivering an event, does not
 * double-mutate.
 *
 * <p>Intentionally NOT {@code @Transactional}: {@code action} runs in its own transaction (a
 * proxied service call) and commits before the idempotency record is written, so a duplicate-key
 * collision here can never poison the business transaction.
 *
 * <p>This layer collapses sequential retries. Truly-concurrent duplicates that slip past it are
 * still caught at the data layer by the {@code uq_active_subscription_per_user} unique index, and
 * surfaced as a clean 409.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

  private final IdempotencyRepository repository;
  private final ObjectMapper objectMapper;

  @Override
  public <T> T runOnce(String key, String endpoint, Class<T> type, Supplier<T> action) {
    if (key == null || key.isBlank()) {
      // Idempotency key is optional; without one we cannot dedupe, so just execute.
      return action.get();
    }

    var existing = repository.findByIdemKeyAndEndpoint(key, endpoint);
    if (existing.isPresent()) {
      return deserialize(existing.get().getResponseBody(), type);
    }

    T result = action.get();

    try {
      repository.saveAndFlush(new IdempotencyRecord(key, endpoint, 200, serialize(result)));
    } catch (DataIntegrityViolationException duplicate) {
      // A concurrent request with the same key committed first — return its stored response.
      return repository
          .findByIdemKeyAndEndpoint(key, endpoint)
          .map(r -> deserialize(r.getResponseBody(), type))
          .orElseThrow(() -> duplicate);
    }
    return result;
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException e) {
      throw new IllegalStateException("Failed to serialize idempotent response", e);
    }
  }

  private <T> T deserialize(String body, Class<T> type) {
    try {
      return objectMapper.readValue(body, type);
    } catch (JacksonException e) {
      throw new IllegalStateException("Failed to deserialize idempotent response", e);
    }
  }
}
