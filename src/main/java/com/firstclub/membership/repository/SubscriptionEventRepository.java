package com.firstclub.membership.repository;

import com.firstclub.membership.model.SubscriptionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionEventRepository extends JpaRepository<SubscriptionEvent, Long> {

  /**
   * Number of lifecycle events recorded for a subscription — a cheap monotonic sequence used to
   * scope an upgrade's payment reference to a single attempt. Backed by {@code
   * idx_sub_event_subscription}.
   */
  long countBySubscriptionId(Long subscriptionId);
}
