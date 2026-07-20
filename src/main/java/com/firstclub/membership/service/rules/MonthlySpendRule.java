package com.firstclub.membership.service.rules;

import com.firstclub.membership.model.TierCriteria;
import com.firstclub.membership.model.UserActivity;
import org.springframework.stereotype.Component;

/** Qualifies when the user's monthly spend meets the tier's configured minimum. */
@Component
public class MonthlySpendRule implements TierRule {

  @Override
  public boolean qualifies(UserActivity activity, TierCriteria criteria) {
    return isConfigured(criteria)
        && activity.monthlySpend().compareTo(criteria.getMinMonthlySpend()) >= 0;
  }

  @Override
  public boolean isConfigured(TierCriteria criteria) {
    return criteria.getMinMonthlySpend() != null;
  }

  @Override
  public String code() {
    return "MONTHLY_SPEND";
  }
}
