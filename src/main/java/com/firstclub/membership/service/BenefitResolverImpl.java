package com.firstclub.membership.service;

import com.firstclub.membership.dto.ResolvedBenefit;
import com.firstclub.membership.repository.TierBenefitRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Resolves the effective benefits for a tier. Cached per tier because it is read on every
 * membership view / checkout and changes only when an admin edits the configuration (cache is then
 * evicted).
 */
@Service
@RequiredArgsConstructor
public class BenefitResolverImpl implements BenefitResolver {

  private final TierBenefitRepository tierBenefitRepository;

  @Cacheable(value = "tierBenefits", key = "#tierId")
  @Override
  public List<ResolvedBenefit> benefitsForTier(Long tierId) {
    return tierBenefitRepository.findActiveByTierId(tierId).stream()
        .map(ResolvedBenefit::from)
        .toList();
  }
}
