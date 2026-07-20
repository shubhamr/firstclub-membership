package com.firstclub.membership.service.rules;

import com.firstclub.membership.model.TierCriteria;
import com.firstclub.membership.model.UserActivity;
import org.springframework.stereotype.Component;

/** Qualifies when the user's order count meets the tier's configured minimum. */
@Component
public class OrderCountRule implements TierRule {

  @Override
  public boolean qualifies(UserActivity activity, TierCriteria criteria) {
    return isConfigured(criteria) && activity.orderCount() >= criteria.getMinOrders();
  }

  @Override
  public boolean isConfigured(TierCriteria criteria) {
    return criteria.getMinOrders() != null;
  }

  @Override
  public String code() {
    return "ORDER_COUNT";
  }
}
