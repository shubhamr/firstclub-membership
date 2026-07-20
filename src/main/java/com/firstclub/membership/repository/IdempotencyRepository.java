package com.firstclub.membership.repository;

import com.firstclub.membership.model.IdempotencyRecord;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

  Optional<IdempotencyRecord> findByIdemKeyAndEndpoint(String idemKey, String endpoint);

  /**
   * Reap one bounded batch of expired keys.
   *
   * <p>A bulk {@code delete}, not a derived {@code deleteByCreatedAtBefore} — Spring Data
   * implements the derived form by SELECTing every match into the persistence context and issuing
   * one DELETE per row, putting a whole retention window of managed entities on the heap in one
   * transaction. The bulk form deletes set-at-a-time and touches no persistence context.
   *
   * <p>The {@code ctid} subquery supplies the limit, since JPQL has no {@code LIMIT} on DML.
   * Batching keeps each transaction short, so the purge never holds a long write lock or pins the
   * vacuum horizon.
   *
   * @return rows deleted; callers loop until this is less than {@code batchSize}.
   */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
            delete from idempotency_key
             where ctid in (
               select ctid from idempotency_key where created_at < :cutoff limit :batchSize)
            """,
      nativeQuery = true)
  int purgeBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
