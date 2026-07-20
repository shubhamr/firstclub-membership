package com.firstclub.membership.repository;

import com.firstclub.membership.model.Subscription;
import com.firstclub.membership.model.SubscriptionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

  /** Active subscription with plan+tier eagerly fetched — single query, no N+1 on the read path. */
  @Query(
      """
            select s from Subscription s
            join fetch s.plan
            join fetch s.tier
            where s.userId = :userId and s.status = com.firstclub.membership.model.SubscriptionStatus.ACTIVE
            """)
  Optional<Subscription> findActiveByUserId(@Param("userId") Long userId);

  @Query(
      """
            select s from Subscription s
            join fetch s.plan
            join fetch s.tier
            where s.id = :id
            """)
  Optional<Subscription> findByIdWithDetails(@Param("id") Long id);

  boolean existsByUserIdAndStatus(Long userId, SubscriptionStatus status);

  /**
   * The user's unresolved reservation, if any. Fetch-joined because the resume path reads plan and
   * tier to verify the retry matches the original request.
   *
   * <p>At most one can exist: {@code uq_active_subscription_per_user} covers PENDING.
   */
  @Query(
      """
            select s from Subscription s
            join fetch s.plan join fetch s.tier
            where s.userId = :userId and s.status = :status
            """)
  Optional<Subscription> findFirstByUserIdAndStatus(
      @Param("userId") Long userId, @Param("status") SubscriptionStatus status);

  long countByUserIdAndStatus(Long userId, SubscriptionStatus status);

  /**
   * Batch for the expiry sweep. Uses the partial index on (expires_at) where status='ACTIVE', so it
   * stays index-only even at millions of rows. Paginated to bound memory and lock footprint.
   */
  @Query(
      """
            select s.id from Subscription s
            where s.status = com.firstclub.membership.model.SubscriptionStatus.ACTIVE
              and s.expiresAt < :now
            order by s.expiresAt asc
            """)
  List<Long> findExpiredIds(@Param("now") Instant now, Pageable pageable);

  /**
   * Active subscribers after {@code lastId}, for the tier-reconciliation backfill.
   *
   * <p><b>Keyset</b>, not offset, pagination. Reconciliation does not shrink its own result set —
   * an active subscriber stays active after being re-evaluated — so repeatedly asking for page 0
   * would return the same first N subscribers forever and never reach the rest. Paging on {@code id
   * > :lastId} lets the caller drain the set, and is index-backed by {@code
   * idx_subscription_active_id}.
   */
  @Query(
      """
            select s.id as id, s.userId as userId from Subscription s
            where s.status = com.firstclub.membership.model.SubscriptionStatus.ACTIVE
              and s.id > :lastId
            order by s.id asc
            """)
  List<ActiveSubscriber> findActiveAfterId(@Param("lastId") long lastId, Pageable pageable);

  /** Projection for the reconciliation keyset scan: the cursor plus the user to re-evaluate. */
  interface ActiveSubscriber {
    Long getId();

    Long getUserId();
  }

  /** Trials whose window has elapsed and are due to convert to paid. */
  @Query(
      """
            select s.id from Subscription s
            where s.status = com.firstclub.membership.model.SubscriptionStatus.ACTIVE
              and s.trialEnd is not null
              and s.trialEnd <= :now
              and (s.nextRetryAt is null or s.nextRetryAt <= :now)
            order by s.trialEnd asc
            """)
  List<Long> findConvertibleTrialIds(@Param("now") Instant now, Pageable pageable);

  /** Auto-renew subscriptions due for a renewal charge (or a scheduled dunning retry). */
  @Query(
      """
            select s.id from Subscription s
            where s.status = com.firstclub.membership.model.SubscriptionStatus.ACTIVE
              and s.autoRenew = true
              and s.trialEnd is null
              and s.expiresAt <= :now
              and (s.nextRetryAt is null or s.nextRetryAt <= :now)
            order by s.expiresAt asc
            """)
  List<Long> findDueForRenewal(@Param("now") Instant now, Pageable pageable);

  /**
   * Bulk status normalization for the expiry sweep.
   *
   * <p>The {@code version} bump is deliberate: a bulk JPQL update bypasses JPA's optimistic locking
   * entirely, so without it an entity loaded concurrently could write back over the sweep with a
   * stale version and silently resurrect an EXPIRED subscription. Incrementing the column by hand
   * restores the guarantee {@code @Version} gives on entity updates. {@code flushAutomatically}
   * pairs with {@code clearAutomatically} so pending changes are written before the persistence
   * context is discarded.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
            update Subscription s
            set s.status = com.firstclub.membership.model.SubscriptionStatus.EXPIRED,
                s.version = s.version + 1
            where s.id in :ids
              and s.status = com.firstclub.membership.model.SubscriptionStatus.ACTIVE
            """)
  int markExpiredByIds(@Param("ids") List<Long> ids);

  /**
   * Extend a subscription's paid-through date only if it still reads exactly as expected.
   *
   * <p>Idempotency of the <em>state transition</em>, which gateway idempotency doesn't provide. If
   * two schedulers charge the same renewal, the gateway dedupes the money but both would still
   * extend {@code expiresAt} — one payment buying two billing periods. Putting the expected expiry
   * in the WHERE clause means the second writer updates 0 rows and stops. It holds even when a
   * distributed lock fails, which is why this is the primary guard and ShedLock only an
   * optimisation.
   *
   * @return 1 if this caller performed the extension, 0 if another already did.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
            update Subscription s
            set s.expiresAt = :newExpiry,
                s.renewalAttempts = 0,
                s.nextRetryAt = null,
                s.version = s.version + 1
            where s.id = :id
              and s.status = com.firstclub.membership.model.SubscriptionStatus.ACTIVE
              and s.expiresAt = :expectedExpiry
            """)
  int extendIfUnchanged(
      @Param("id") Long id,
      @Param("expectedExpiry") Instant expectedExpiry,
      @Param("newExpiry") Instant newExpiry);

  /**
   * Stale PENDING reservations that are safe to reap — rows whose subscribe crashed <em>before</em>
   * any money moved.
   *
   * <p>The {@code not exists} clause is the important part. CANCELLED is terminal, so cancelling a
   * reservation whose payment reached CHARGED or IN_DOUBT destroys the only thing that could still
   * be delivered: the member is left charged, with no subscription, and no state the application
   * can recover from. Those rows are deliberately left PENDING for {@link
   * com.firstclub.membership.service.PaymentReconciliationJob} to surface — a stuck reservation is
   * a far better outcome than a silently discarded payment.
   */
  @Query(
      """
            select s.id from Subscription s
            where s.status = com.firstclub.membership.model.SubscriptionStatus.PENDING
              and s.startAt < :cutoff
              and not exists (
                select 1 from Payment p
                where p.subscriptionId = s.id
                  and p.status in (
                    com.firstclub.membership.model.PaymentStatus.CHARGED,
                    com.firstclub.membership.model.PaymentStatus.IN_DOUBT))
            order by s.startAt asc
            """)
  List<Long> findStalePendingIds(@Param("cutoff") Instant cutoff, Pageable pageable);

  /** Cancel stale PENDING reservations, freeing the one-live-subscription unique index. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
            update Subscription s
            set s.status = com.firstclub.membership.model.SubscriptionStatus.CANCELLED,
                s.version = s.version + 1
            where s.id in :ids
              and s.status = com.firstclub.membership.model.SubscriptionStatus.PENDING
            """)
  int cancelStalePendingByIds(@Param("ids") List<Long> ids);
}
