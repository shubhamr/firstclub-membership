package com.firstclub.membership.service;

/**
 * The time-driven half of the subscription lifecycle: converting a free trial to paid, renewing a
 * paid period, and dunning a renewal that failed.
 *
 * <p>Separate from {@link SubscriptionService} because nothing here is request-driven — every entry
 * point is called by a scheduled job, never by a controller. Trial conversion and renewal share a
 * bean rather than splitting further because they share the dunning state: a failed conversion
 * backs off on the renewal retry schedule, which is what keeps the conversion job's queue draining.
 */
public interface RenewalService {

  /** Convert a trial subscription to paid (charge + extend); no-op if not a due trial. */
  void convertTrial(Long subscriptionId);

  /** Renew a subscription: charge + extend on success; dunning retry / revoke on failure. */
  void renew(Long subscriptionId);
}
