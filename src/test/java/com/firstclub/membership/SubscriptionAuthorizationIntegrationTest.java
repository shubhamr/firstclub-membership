package com.firstclub.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscriptionDto;
import com.firstclub.membership.model.SubscriptionStatus;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.TierRepository;
import com.firstclub.membership.security.JwtService;
import com.firstclub.membership.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins object-level authorization on every money-moving {@code SubscriptionController} endpoint: a
 * member may act only on their own subscription.
 *
 * <p>Without a {@code @PreAuthorize} on each of these, a self-service token endpoint lets any
 * authenticated caller subscribe on another member's behalf (charging them) or cancel any
 * subscription by id. Authentication is not authorization: a valid token proves who you are, never
 * what you may touch.
 */
class SubscriptionAuthorizationIntegrationTest extends AbstractIntegrationTest {

  // A distinct victim per test: subscriptions are committed (not rolled back between tests) and the
  // uq_active_subscription_per_user index allows only one live subscription per user, so sharing an
  // id would make these tests order-dependent.
  private static final long ATTACKER = 5100L;
  private static final long VICTIM_CANCEL = 5101L;
  private static final long VICTIM_ON_BEHALF = 5102L;
  private static final long VICTIM_UPGRADE = 5103L;
  private static final long VICTIM_DOWNGRADE = 5104L;
  private static final long VICTIM_REFUND = 5105L;
  private static final long VICTIM_SELF_CANCEL = 5106L;
  private static final long VICTIM_SELF_ATTEST = 5107L;
  private static final long VICTIM_SERVICE_ATTEST = 5108L;

  @Autowired WebApplicationContext context;
  @Autowired JwtService jwt;
  @Autowired SubscriptionService subscriptionService;
  @Autowired SubscriptionRepository subscriptionRepository;
  @Autowired PlanRepository planRepository;
  @Autowired TierRepository tierRepository;

  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // Only the victims a positive path actually subscribes or ingests activity for. The attacker
    // (5100) and VICTIM_ON_BEHALF (5102) are never persisted — their requests are rejected at the
    // authorization boundary before any write — so seeding them is neither needed nor correct.
    seedUser(VICTIM_CANCEL);
    seedUser(VICTIM_UPGRADE);
    seedUser(VICTIM_DOWNGRADE);
    seedUser(VICTIM_REFUND);
    seedUser(VICTIM_SELF_CANCEL);
    seedUser(VICTIM_SELF_ATTEST);
    seedUser(VICTIM_SERVICE_ATTEST);
  }

  private String bearer(long userId, boolean admin) {
    return "Bearer " + jwt.issue(userId, admin);
  }

  private Long planId() {
    return planRepository.findByActiveTrueOrderByPriceAsc().getFirst().getId();
  }

  private Long silverId() {
    return tierRepository.findByCode("SILVER").orElseThrow().getId();
  }

  private SubscriptionDto subscribeVictim(long victimId) {
    return subscriptionService.subscribe(new SubscribeRequest(victimId, planId(), silverId()));
  }

  @Test
  void cancellingAnotherUsersSubscription_isRejected_andLeavesItActive() throws Exception {
    SubscriptionDto victimSub = subscribeVictim(VICTIM_CANCEL);

    mvc.perform(
            post("/api/v1/subscriptions/{id}/cancel", victimSub.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(ATTACKER, false)))
        .andExpect(status().isForbidden());

    // Rejection is not enough: the subscription must be untouched.
    assertThat(subscriptionRepository.findById(victimSub.id()).orElseThrow().getStatus())
        .isEqualTo(SubscriptionStatus.ACTIVE);
  }

  @Test
  void subscribingOnBehalfOfAnotherUser_isRejected() throws Exception {
    String body =
        """
        {"userId":%d,"planId":%d,"tierId":%d}
        """
            .formatted(VICTIM_ON_BEHALF, planId(), silverId());

    mvc.perform(
            post("/api/v1/subscriptions")
                .header(HttpHeaders.AUTHORIZATION, bearer(ATTACKER, false))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());

    // No subscription — and therefore no charge — was created for the victim.
    assertThat(
            subscriptionRepository.existsByUserIdAndStatus(
                VICTIM_ON_BEHALF, SubscriptionStatus.ACTIVE))
        .isFalse();
  }

  @Test
  void upgradingAnotherUsersSubscription_isRejected() throws Exception {
    SubscriptionDto victimSub = subscribeVictim(VICTIM_UPGRADE);
    Long goldId = tierRepository.findByCode("GOLD").orElseThrow().getId();

    mvc.perform(
            post("/api/v1/subscriptions/{id}/upgrade", victimSub.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(ATTACKER, false))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetTierId\":%d}".formatted(goldId)))
        .andExpect(status().isForbidden());
  }

  @Test
  void downgradingAnotherUsersSubscription_isRejected() throws Exception {
    SubscriptionDto victimSub = subscribeVictim(VICTIM_DOWNGRADE);

    mvc.perform(
            post("/api/v1/subscriptions/{id}/downgrade", victimSub.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(ATTACKER, false))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetTierId\":%d}".formatted(silverId())))
        .andExpect(status().isForbidden());
  }

  @Test
  void refund_isAdminOnly() throws Exception {
    SubscriptionDto victimSub = subscribeVictim(VICTIM_REFUND);

    // Even the owner cannot self-refund: a refund revokes access and issues a credit note, so it is
    // an ops action rather than self-service.
    mvc.perform(
            post("/api/v1/subscriptions/{id}/refund", victimSub.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(VICTIM_REFUND, false)))
        .andExpect(status().isForbidden());

    mvc.perform(
            post("/api/v1/subscriptions/{id}/refund", victimSub.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(0, true)))
        .andExpect(status().isOk());
  }

  @Test
  void ownerCanCancelTheirOwnSubscription() throws Exception {
    SubscriptionDto victimSub = subscribeVictim(VICTIM_SELF_CANCEL);

    mvc.perform(
            post("/api/v1/subscriptions/{id}/cancel", victimSub.id())
                .header(HttpHeaders.AUTHORIZATION, bearer(VICTIM_SELF_CANCEL, false)))
        .andExpect(status().isOk());

    assertThat(subscriptionRepository.findById(victimSub.id()).orElseThrow().getStatus())
        .isEqualTo(SubscriptionStatus.CANCELLED);
  }

  @Test
  void memberCannotAttestTheirOwnOrderActivity_soTiersCannotBeSelfGranted() throws Exception {
    // orderCount / monthlySpend / cohorts ARE the tier criteria, so "you may write your own
    // activity" means "you may choose your own tier": POST 9999 orders and reevaluateTier
    // auto-upgrades you to the top tier, with no proration charged and renewals still billing the
    // locked-in lower price. Self-or-admin is not sufficient here — this is service-to-service
    // data, and only the order service may assert it.
    subscribeVictim(VICTIM_SELF_ATTEST);

    mvc.perform(
            post("/api/v1/users/{userId}/activity", VICTIM_SELF_ATTEST)
                .header(HttpHeaders.AUTHORIZATION, bearer(VICTIM_SELF_ATTEST, false))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"orderCount\":9999,\"orderId\":990003,\"orderAmount\":999999,\"occurredAt\":\"2026-01-15T10:00:00Z\",\"cohorts\":[\"VIP\"]}"))
        .andExpect(status().isForbidden());

    // Still on the tier they actually paid for.
    assertThat(
            subscriptionRepository
                .findActiveByUserId(VICTIM_SELF_ATTEST)
                .orElseThrow()
                .getTier()
                .getCode())
        .isEqualTo("SILVER");
  }

  @Test
  void adminCanIngestActivityOnBehalfOfTheOrderService() throws Exception {
    // Admin-only must not mean unusable: the endpoint still has to work for the order service, or
    // tier progression stops entirely.
    mvc.perform(
            post("/api/v1/users/{userId}/activity", VICTIM_SERVICE_ATTEST)
                .header(HttpHeaders.AUTHORIZATION, bearer(9999L, true))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"orderCount\":3,\"orderId\":990004,\"orderAmount\":100,\"occurredAt\":\"2026-01-15T10:00:00Z\",\"cohorts\":[]}"))
        .andExpect(status().isOk());
  }

  @Test
  void unknownSubscriptionId_isForbiddenNotNotFound_soIdsCannotBeEnumerated() throws Exception {
    // isOwner() returns false for both "not yours" and "does not exist", so an unauthorised caller
    // gets an identical response either way and learns nothing about which ids are real.
    mvc.perform(
            post("/api/v1/subscriptions/{id}/cancel", 99_999_999L)
                .header(HttpHeaders.AUTHORIZATION, bearer(ATTACKER, false)))
        .andExpect(status().isForbidden());
  }
}
