package com.firstclub.membership.service;

import com.firstclub.membership.dto.ResolvedBenefit;
import java.util.List;

/** Resolves the effective benefits for a tier (cached). */
public interface BenefitResolver {

  List<ResolvedBenefit> benefitsForTier(Long tierId);
}
