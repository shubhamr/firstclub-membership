package com.firstclub.membership.service;

import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.model.MembershipTier;
import com.firstclub.membership.model.TierCriteria;
import com.firstclub.membership.model.UserActivity;
import com.firstclub.membership.repository.TierCriteriaRepository;
import com.firstclub.membership.repository.TierRepository;
import com.firstclub.membership.service.rules.TierRule;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Decides which tier a user qualifies for, given their activity. This is the extensibility seam:
 * the set of {@link TierRule} strategies is injected, and per-tier thresholds come from {@link
 * TierCriteria} rows. A tier qualifies when its criteria are unconditional (the base tier) or ANY
 * rule matches (OR semantics). Combination policy lives here and nowhere else.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TierAssignmentServiceImpl implements TierAssignmentService {

  private final List<TierRule> rules;
  private final TierRepository tierRepository;
  private final TierCriteriaRepository criteriaRepository;

  /**
   * Refuse to boot on a tier configuration that would silently promote the whole member base.
   *
   * <p>{@link #qualifies} treats a criteria row that no rule claims a threshold on as
   * unconditional. That is intended for the base tier, but the check cannot tell an
   * intentionally-open row from one an operator forgot to fill in. An all-null row seeded on Gold
   * or Platinum reads exactly like the base tier's, so every user qualifies and reconciliation
   * auto-upgrades everyone within a sweep.
   *
   * <p>The invariant this asserts: at most one active tier is unconditional, and if one is, it is
   * the lowest rank. Verifying it once at startup turns that misconfiguration from a silent mass
   * promotion into a refusal to start, which mirrors {@code SecurityDefaultsGuard}.
   */
  @PostConstruct
  void assertAtMostOneUnconditionalBaseTier() {
    List<MembershipTier> tiers = tierRepository.findByActiveTrueOrderByRankAsc();
    if (tiers.isEmpty()) {
      return; // an empty catalog is caught at request time by highestQualifyingTier
    }
    Map<Long, TierCriteria> criteriaByTier = loadCriteria(tiers);
    List<MembershipTier> unconditional =
        tiers.stream().filter(t -> isUnconditional(criteriaByTier.get(t.getId()))).toList();

    if (unconditional.size() > 1) {
      throw new IllegalStateException(
          "More than one active tier is unconditional (no rule claims a threshold): "
              + unconditional.stream().map(MembershipTier::getCode).toList()
              + ". Exactly one base tier may be open; the rest need criteria, or every user"
              + " qualifies for all of them.");
    }
    if (unconditional.size() == 1) {
      MembershipTier base = unconditional.getFirst();
      MembershipTier lowest = tiers.getFirst(); // ordered by rank ascending
      if (base.getRank() != lowest.getRank()) {
        throw new IllegalStateException(
            "The unconditional tier is %s (rank %d), but the lowest-rank active tier is %s (rank %d)."
                    .formatted(base.getCode(), base.getRank(), lowest.getCode(), lowest.getRank())
                + " An open tier above the base means everyone is auto-upgraded past the base into"
                + " it.");
      }
    }
  }

  private boolean isUnconditional(TierCriteria criteria) {
    // A missing row fails closed at request time, so it is not "open" here; only a present row that
    // no rule claims a threshold on is.
    return criteria != null && rules.stream().noneMatch(rule -> rule.isConfigured(criteria));
  }

  /**
   * The richest tier the user currently qualifies for; falls back to the lowest active (base) tier.
   */
  @Override
  public MembershipTier highestQualifyingTier(UserActivity activity) {
    List<MembershipTier> tiers = tierRepository.findByActiveTrueOrderByRankAsc();
    if (tiers.isEmpty()) {
      throw new NotFoundException("No active membership tiers are configured");
    }
    Map<Long, TierCriteria> criteriaByTier = loadCriteria(tiers);

    return tiers.stream()
        .sorted(Comparator.comparingInt(MembershipTier::getRank).reversed())
        .filter(t -> qualifies(activity, criteriaByTier.get(t.getId())))
        .findFirst()
        .orElse(tiers.getFirst()); // lowest rank = base tier
  }

  /** Whether a user may hold a specific tier (used to gate manual upgrades). */
  @Override
  public boolean isEligibleFor(MembershipTier tier, UserActivity activity) {
    TierCriteria criteria = criteriaRepository.findByTierId(tier.getId()).orElse(null);
    return qualifies(activity, criteria);
  }

  private boolean qualifies(UserActivity activity, TierCriteria criteria) {
    if (criteria == null) {
      // Fail CLOSED. A missing criteria row is a misconfiguration, not an open door: one forgotten
      // seed row would make a high tier qualify for everyone, and reevaluateTier would auto-upgrade
      // the whole member base into it. A tier is unconditional only via an explicit row no rule
      // claims a threshold on (see below), never via an absent one.
      log.debug(
          "User {} rejected: tier has no criteria row configured (failing closed)",
          activity.userId());
      return false;
    }
    if (isUnconditional(criteria)) {
      return true; // explicitly-open base tier (a real row no rule claims a threshold on)
    }
    var matched = rules.stream().filter(rule -> rule.qualifies(activity, criteria)).findFirst();
    // Which rule fired (or that none did) is the only way to answer "why am I not PLATINUM?"
    // without re-deriving the whole evaluation by hand. Guarded so the code list costs nothing
    // when debug is off — reconciliation evaluates every active user against every tier.
    if (log.isDebugEnabled()) {
      if (matched.isPresent()) {
        log.debug(
            "User {} qualifies for tier {} via rule {}",
            activity.userId(),
            criteria.getTierId(),
            matched.get().code());
      } else {
        log.debug(
            "User {} does not qualify for tier {}: no rule matched, of {}",
            activity.userId(),
            criteria.getTierId(),
            rules.stream().map(TierRule::code).toList());
      }
    }
    return matched.isPresent();
  }

  private Map<Long, TierCriteria> loadCriteria(List<MembershipTier> tiers) {
    List<Long> ids = tiers.stream().map(MembershipTier::getId).toList();
    // Single query for all criteria — no N+1 across the tier list.
    return criteriaRepository.findByTierIdIn(ids).stream()
        .collect(Collectors.toMap(TierCriteria::getTierId, Function.identity()));
  }
}
