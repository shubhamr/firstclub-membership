package com.firstclub.membership.service;

import com.firstclub.membership.dto.BenefitAdminDtos.AssignBenefitRequest;
import com.firstclub.membership.dto.BenefitAdminDtos.BenefitDto;
import com.firstclub.membership.dto.BenefitAdminDtos.CreateBenefitRequest;
import com.firstclub.membership.exception.ConflictException;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.model.Benefit;
import com.firstclub.membership.repository.BenefitRepository;
import com.firstclub.membership.repository.TierBenefitRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Admin configuration surface for benefits.
 *
 * <p>Benefits and their per-tier params are edited at runtime; the read caches are evicted so the
 * change is visible without a redeploy or restart.
 */
@Service
@RequiredArgsConstructor
public class BenefitAdminServiceImpl implements BenefitAdminService {

  private final BenefitRepository benefitRepository;
  private final TierBenefitRepository tierBenefitRepository;
  private final ObjectMapper objectMapper;

  @PersistenceContext private EntityManager entityManager;

  @Transactional
  @Override
  public BenefitDto createBenefit(CreateBenefitRequest req) {
    benefitRepository
        .findByCode(req.code())
        .ifPresent(
            b -> {
              throw new ConflictException(
                  "Benefit with code %s already exists".formatted(req.code()));
            });
    Benefit saved =
        benefitRepository.save(new Benefit(req.code(), req.type(), req.name(), req.description()));
    return BenefitDto.from(saved);
  }

  /**
   * Assign (or re-tune) a benefit on a tier. Evicts the per-tier benefit cache and the tier catalog
   * cache so the new configuration is served immediately.
   */
  @Transactional
  @Caching(
      evict = {
        @CacheEvict(value = "tierBenefits", key = "#req.tierId()"),
        @CacheEvict(value = "tiers", allEntries = true)
      })
  @Override
  public void assignBenefitToTier(AssignBenefitRequest req) {
    Benefit benefit =
        benefitRepository
            .findByCode(req.benefitCode())
            .orElseThrow(
                () -> new NotFoundException("Benefit %s not found".formatted(req.benefitCode())));
    Map<String, Object> params = req.params() == null ? Map.of() : req.params();
    // Native upsert keeps the (tier_id, benefit_id) unique invariant race-safe under concurrent
    // edits.
    entityManager
        .createNativeQuery(
            """
                        insert into tier_benefit (tier_id, benefit_id, params, active)
                        values (:tierId, :benefitId, cast(:params as jsonb), true)
                        on conflict (tier_id, benefit_id)
                        do update set params = excluded.params, active = true, updated_at = now()
                        """)
        .setParameter("tierId", req.tierId())
        .setParameter("benefitId", benefit.getId())
        .setParameter("params", toJson(params))
        .executeUpdate();
  }

  /**
   * Remove a benefit from a tier by deactivating its mapping row, then evict the per-tier benefit
   * cache and the tier catalog cache so the perk disappears from reads immediately. Flips the same
   * {@code active} flag {@link #assignBenefitToTier}'s upsert sets, so a later re-assign restores
   * the mapping in place rather than leaving a duplicate. A native update keeps this symmetric with
   * the upsert and refreshes {@code updated_at}.
   */
  @Transactional
  @Caching(
      evict = {
        @CacheEvict(value = "tierBenefits", key = "#tierId"),
        @CacheEvict(value = "tiers", allEntries = true)
      })
  @Override
  public void unassignBenefitFromTier(Long tierId, String benefitCode) {
    Benefit benefit =
        benefitRepository
            .findByCode(benefitCode)
            .orElseThrow(
                () -> new NotFoundException("Benefit %s not found".formatted(benefitCode)));
    int changed =
        entityManager
            .createNativeQuery(
                """
                            update tier_benefit set active = false, updated_at = now()
                            where tier_id = :tierId and benefit_id = :benefitId and active = true
                            """)
            .setParameter("tierId", tierId)
            .setParameter("benefitId", benefit.getId())
            .executeUpdate();
    // 0 rows means the benefit exists but was not active on this tier; report it rather than
    // returning a misleading 204 for a no-op.
    if (changed == 0) {
      throw new NotFoundException(
          "Benefit %s is not assigned to tier %d".formatted(benefitCode, tierId));
    }
  }

  private String toJson(Map<String, Object> params) {
    try {
      return objectMapper.writeValueAsString(params);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("Invalid benefit params", e);
    }
  }
}
