package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.dto.BenefitAdminDtos.AssignBenefitRequest;
import com.firstclub.membership.dto.BenefitAdminDtos.CreateBenefitRequest;
import com.firstclub.membership.dto.ResolvedBenefit;
import com.firstclub.membership.dto.TierDto;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.model.BenefitType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins configurable perks as a caller sees them: each tier's benefit set and the effective params
 * checkout acts on.
 */
class BenefitConfigurationIntegrationTest extends AbstractIntegrationTest {

  @Autowired TierService tierService;
  @Autowired BenefitAdminService benefitAdminService;

  private ResolvedBenefit benefit(String tierCode, String benefitCode) {
    TierDto tier =
        tierService.listTiers().stream()
            .filter(t -> t.code().equals(tierCode))
            .findFirst()
            .orElseThrow();
    return tier.benefits().stream()
        .filter(b -> b.code().equals(benefitCode))
        .findFirst()
        .orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private List<String> categories(ResolvedBenefit benefit) {
    return (List<String>) benefit.params().get("categories");
  }

  /** The discount applies to selected categories, not to everything. */
  @Test
  void extraDiscount_isScopedToCategories() {
    assertThat(categories(benefit("SILVER", "EXTRA_DISCOUNT"))).containsExactly("FASHION");
    assertThat(categories(benefit("GOLD", "EXTRA_DISCOUNT")))
        .containsExactlyInAnyOrder("FASHION", "ELECTRONICS");
    assertThat(categories(benefit("PLATINUM", "EXTRA_DISCOUNT")))
        .containsExactlyInAnyOrder("FASHION", "ELECTRONICS", "HOME", "GROCERY");
  }

  /**
   * Category params must widen the existing params rather than replace them: an assignment drops
   * the percentage and leaves the perk inert with no error anywhere.
   */
  @Test
  void addingCategories_didNotDropTheDiscountPercentage() {
    assertThat(benefit("SILVER", "EXTRA_DISCOUNT").params()).containsEntry("discountPct", 5);
    assertThat(benefit("GOLD", "EXTRA_DISCOUNT").params()).containsEntry("discountPct", 10);
    assertThat(benefit("PLATINUM", "EXTRA_DISCOUNT").params()).containsEntry("discountPct", 15);
  }

  /** Each tier unlocks a strict superset of the tier below it. */
  @Test
  void higherTiersUnlockMorePerks() {
    var silver = benefitCodes("SILVER");
    var gold = benefitCodes("GOLD");
    var platinum = benefitCodes("PLATINUM");

    assertThat(gold).containsAll(silver).contains("EXCLUSIVE_DEALS");
    assertThat(platinum).containsAll(gold).contains("PRIORITY_SUPPORT");
  }

  private List<String> benefitCodes(String tierCode) {
    return tierService.listTiers().stream()
        .filter(t -> t.code().equals(tierCode))
        .findFirst()
        .orElseThrow()
        .benefits()
        .stream()
        .map(ResolvedBenefit::code)
        .toList();
  }

  private Long tierId(String code) {
    return tierService.listTiers().stream()
        .filter(t -> t.code().equals(code))
        .findFirst()
        .orElseThrow()
        .id();
  }

  /**
   * The requirement is "each tier unlocks additional perks — <em>should be configurable</em>", so
   * the configurability itself is the thing under test: a perk that does not exist at boot is
   * created and attached to a tier at runtime, and a subsequent read reflects it.
   *
   * <p>Reading back through {@link TierService} matters: {@code listTiers} is {@code @Cacheable},
   * so without the assignment's {@code @CacheEvict} the new perk would stay invisible until the TTL
   * lapsed or the app restarted — exactly what "no deploy" must not mean. Tests run the simple same
   * in-memory manager as production; the eviction contract under test is the cache abstraction,
   * independent of the backing store.
   */
  @Test
  void aNewPerkCanBeCreatedAndAttachedToATierAtRuntime() {
    benefitAdminService.createBenefit(
        new CreateBenefitRequest(
            "FESTIVE_COUPON_DROP",
            BenefitType.EXCLUSIVE_COUPONS,
            "Festive coupon drop",
            "Seasonal coupons, configured live"));

    assertThat(tierService.listTiers().stream().filter(t -> t.code().equals("GOLD")).findFirst())
        .hasValueSatisfying(
            t ->
                assertThat(t.benefits())
                    .as("not attached to any tier yet, so it must not appear")
                    .noneMatch(b -> b.code().equals("FESTIVE_COUPON_DROP")));

    benefitAdminService.assignBenefitToTier(
        new AssignBenefitRequest(
            tierId("GOLD"),
            "FESTIVE_COUPON_DROP",
            Map.of("couponsPerCycle", 3, "campaign", "DIWALI")));

    ResolvedBenefit attached = benefit("GOLD", "FESTIVE_COUPON_DROP");
    assertThat(attached.params())
        .as("arbitrary per-tier params travel through untouched")
        .containsEntry("couponsPerCycle", 3)
        .containsEntry("campaign", "DIWALI");
    assertThat(benefit("GOLD", "EXTRA_DISCOUNT").params())
        .as("attaching a perk must not disturb the tier's existing ones")
        .containsEntry("discountPct", 10);
    assertThat(tierService.listTiers().stream().filter(t -> t.code().equals("SILVER")).findFirst())
        .hasValueSatisfying(
            t ->
                assertThat(t.benefits())
                    .as("and must not leak onto a tier it was not assigned to")
                    .noneMatch(b -> b.code().equals("FESTIVE_COUPON_DROP")));
  }

  /**
   * Unassign is the reverse of assign: the perk stops appearing on the tier, and because it is a
   * soft delete a later re-assign brings it back through the same mapping. Reads go through {@link
   * TierService} so the test also pins the cache eviction, as the assign test does. A fresh,
   * uniquely-coded perk is used so this commits without disturbing the seeded tier composition
   * other tests assert on.
   */
  @Test
  void aPerkCanBeUnassignedFromATierAndReassignedAtRuntime() {
    benefitAdminService.createBenefit(
        new CreateBenefitRequest(
            "UNASSIGN_ROUNDTRIP", BenefitType.EXCLUSIVE_COUPONS, "Round-trip perk", null));
    benefitAdminService.assignBenefitToTier(
        new AssignBenefitRequest(tierId("GOLD"), "UNASSIGN_ROUNDTRIP", Map.of()));
    assertThat(benefitCodes("GOLD")).contains("UNASSIGN_ROUNDTRIP");

    benefitAdminService.unassignBenefitFromTier(tierId("GOLD"), "UNASSIGN_ROUNDTRIP");
    assertThat(benefitCodes("GOLD"))
        .as("the unassigned perk disappears from the tier")
        .doesNotContain("UNASSIGN_ROUNDTRIP");

    benefitAdminService.assignBenefitToTier(
        new AssignBenefitRequest(tierId("GOLD"), "UNASSIGN_ROUNDTRIP", Map.of()));
    assertThat(benefitCodes("GOLD"))
        .as("re-assigning reactivates the same mapping")
        .contains("UNASSIGN_ROUNDTRIP");
  }

  /**
   * Unassigning a perk the tier does not currently have is a NotFound (surfaced as 404), not a
   * silent no-op — PRIORITY_SUPPORT is seeded on PLATINUM only, never on SILVER.
   */
  @Test
  void unassigningAPerkTheTierDoesNotHave_isRejected() {
    assertThatThrownBy(
            () -> benefitAdminService.unassignBenefitFromTier(tierId("SILVER"), "PRIORITY_SUPPORT"))
        .isInstanceOf(NotFoundException.class);
  }

  /** Re-tuning an already-assigned perk updates it in place rather than duplicating the row. */
  @Test
  void reassigningAPerkRetunesItInPlace() {
    benefitAdminService.assignBenefitToTier(
        new AssignBenefitRequest(
            tierId("PLATINUM"), "EXCLUSIVE_COUPONS", Map.of("couponsPerCycle", 9)));

    List<ResolvedBenefit> coupons =
        tierService.listTiers().stream()
            .filter(t -> t.code().equals("PLATINUM"))
            .findFirst()
            .orElseThrow()
            .benefits()
            .stream()
            .filter(b -> b.code().equals("EXCLUSIVE_COUPONS"))
            .toList();

    assertThat(coupons).as("re-assignment is an upsert, not an insert").hasSize(1);
    assertThat(coupons.getFirst().params()).containsEntry("couponsPerCycle", 9);
  }
}
