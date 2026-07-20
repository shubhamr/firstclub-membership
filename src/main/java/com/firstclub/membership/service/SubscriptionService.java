package com.firstclub.membership.service;

import com.firstclub.membership.dto.CreditNoteDto;
import com.firstclub.membership.dto.MembershipView;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscriptionDto;
import java.util.List;

/** Subscription lifecycle: subscribe, upgrade, downgrade, cancel, view, and tier re-evaluation. */
public interface SubscriptionService {

  SubscriptionDto subscribe(SubscribeRequest req);

  SubscriptionDto upgrade(Long subscriptionId, Long targetTierId);

  SubscriptionDto downgrade(Long subscriptionId, Long targetTierId);

  SubscriptionDto cancel(Long subscriptionId);

  /** Refund / chargeback: revoke access (credits unused time). */
  SubscriptionDto refund(Long subscriptionId);

  /** Credit notes issued to a user (paginated). */
  List<CreditNoteDto> creditNotes(long userId, int page, int size);

  /**
   * Whether the given security principal owns the subscription. Used by {@code @PreAuthorize} to
   * enforce self-or-admin on the path-addressed subscription mutations.
   */
  boolean isOwner(Long subscriptionId, String principalName);

  MembershipView getMembership(long userId);

  /** Recompute the user's tier from activity and auto-upgrade if newly eligible. */
  MembershipView reevaluateTier(long userId);

  /** Bulk-normalize expired subscriptions; returns the count updated. */
  int sweepExpired(int batchSize);

  /**
   * Reap stale {@code PENDING} reservations — rows left behind if a subscribe crashed between
   * reserving and charging/activating. Without this backstop such a row would sit in the
   * PENDING+ACTIVE unique index forever and block the user. Returns the count cancelled.
   */
  int sweepStalePendingReservations(int batchSize);
}
