package com.firstclub.membership.repository;

import com.firstclub.membership.model.Payment;
import com.firstclub.membership.model.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

  Optional<Payment> findByReference(String reference);

  List<Payment> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

  long countByStatus(PaymentStatus status);

  /**
   * Money taken but never applied. Backed by {@code idx_payment_unapplied}, a partial index over
   * {@code status = 'CHARGED'} only — so this stays cheap no matter how large the ledger grows.
   * This is the reconciliation query, and the thing worth alerting on.
   */
  @Query(
      """
            select p from Payment p
            where p.status = com.firstclub.membership.model.PaymentStatus.CHARGED
              and p.createdAt < :cutoff
            order by p.createdAt asc
            """)
  List<Payment> findStuckCharged(@Param("cutoff") Instant cutoff, Pageable pageable);

  /**
   * Total money actually collected and applied for a subscription.
   *
   * <p>The only correct basis for a refund credit. {@code subscription.price_paid} holds the
   * <b>list</b> price locked for renewal grandfathering, not what the member actually paid —
   * crediting against it would let an unconverted trial walk away with a full-price credit note.
   *
   * <p>Returns empty when nothing was ever applied (trials); callers must read that as zero, not as
   * "unknown".
   */
  @Query(
      """
            select sum(p.amount) from Payment p
            where p.subscriptionId = :subscriptionId
              and p.status = com.firstclub.membership.model.PaymentStatus.APPLIED
            """)
  Optional<BigDecimal> sumAppliedAmount(@Param("subscriptionId") Long subscriptionId);

  /**
   * Ambiguous gateway outcomes awaiting resolution. Separate from {@link #findStuckCharged} because
   * they need a different remedy: a stuck CHARGED needs delivery, an IN_DOUBT needs someone to ask
   * the gateway what actually happened.
   */
  @Query(
      """
            select p from Payment p
            where p.status = com.firstclub.membership.model.PaymentStatus.IN_DOUBT
              and p.createdAt < :cutoff
            order by p.createdAt asc
            """)
  List<Payment> findInDoubt(@Param("cutoff") Instant cutoff, Pageable pageable);

  /** The reference of a subscription's applied payment, for reversal on refund. */
  @Query(
      """
            select p.reference from Payment p
            where p.subscriptionId = :subscriptionId
              and p.status = com.firstclub.membership.model.PaymentStatus.APPLIED
            order by p.createdAt desc
            """)
  List<String> findAppliedReference(
      @Param("subscriptionId") Long subscriptionId, Pageable pageable);

  /** Unpaged counts for the reconciliation gauges — backed by the two partial indexes. */
  long countByStatusAndCreatedAtBefore(PaymentStatus status, Instant cutoff);
}
