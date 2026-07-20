package com.firstclub.membership.service.rules;

import com.firstclub.membership.model.TierCriteria;
import com.firstclub.membership.model.UserActivity;
import org.springframework.stereotype.Component;

/** Qualifies when the user belongs to any cohort the tier grants access to. */
@Component
public class CohortRule implements TierRule {

  @Override
  public boolean qualifies(UserActivity activity, TierCriteria criteria) {
    return isConfigured(criteria)
        && criteria.getCohorts().stream().anyMatch(activity.cohorts()::contains);
  }

  @Override
  public boolean isConfigured(TierCriteria criteria) {
    return !criteria.getCohorts().isEmpty();
  }

  @Override
  public String code() {
    return "COHORT";
  }
}
