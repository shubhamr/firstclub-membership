package com.firstclub.membership.service;

import com.firstclub.membership.dto.TierDto;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.model.MembershipTier;
import com.firstclub.membership.repository.TierRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** Read path for tiers, including the benefits each unlocks. Cached (high-read catalog). */
@Service
@RequiredArgsConstructor
public class TierServiceImpl implements TierService {

  private final TierRepository tierRepository;
  private final BenefitResolver benefitResolver;

  @Cacheable("tiers")
  @Override
  public List<TierDto> listTiers() {
    return tierRepository.findByActiveTrueOrderByRankAsc().stream()
        .map(t -> TierDto.from(t, benefitResolver.benefitsForTier(t.getId())))
        .toList();
  }

  @Override
  public MembershipTier requireTier(Long tierId) {
    MembershipTier tier =
        tierRepository
            .findById(tierId)
            .orElseThrow(() -> new NotFoundException("Tier %d not found".formatted(tierId)));
    if (!tier.isActive()) {
      throw new NotFoundException("Tier %d is not active".formatted(tierId));
    }
    return tier;
  }
}
