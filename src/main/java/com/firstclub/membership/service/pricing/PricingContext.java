package com.firstclub.membership.service.pricing;

import com.firstclub.membership.model.MembershipPlan;
import com.firstclub.membership.model.MembershipTier;

/**
 * Inputs a pricing strategy may use. Deliberately minimal — region/time can be added without
 * touching callers.
 */
public record PricingContext(MembershipPlan plan, MembershipTier tier) {}
