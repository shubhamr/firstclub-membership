package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.gateway.PaymentPort;
import com.firstclub.membership.gateway.PaymentResult;
import com.firstclub.membership.model.SubscriptionStatus;
import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.repository.TierRepository;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Pins renewal and dunning, the highest-risk unattended path: a declined renewal schedules a retry
 * and keeps the member active through the grace window, only the configured number of consecutive
 * failures revokes the subscription, and a later success resets the dunning counter.
 *
 * <p>The gateway is toggleable so the failure schedule runs end to end rather than being simulated
 * by writing attempt counts directly.
 */
class RenewalDunningIntegrationTest extends AbstractIntegrationTest {

  static final AtomicBoolean DECLINE = new AtomicBoolean(false);

  @TestConfiguration
  static class ControllableGatewayConfig {
    @Bean
    @Primary
    PaymentPort controllableGateway() {
      return (userId, amount, reference) ->
          DECLINE.get()
              ? new PaymentResult(false, null, "declined")
              : PaymentResult.ok("txn-" + reference);
    }
  }

  @Autowired SubscriptionService subscriptionService;
  @Autowired RenewalService renewalService;
  @Autowired SubscriptionRepository subscriptionRepository;
  @Autowired PlanRepository planRepository;
  @Autowired TierRepository tierRepository;

  @BeforeEach
  void resetGateway() {
    DECLINE.set(false);
    seedUser(6101L);
    seedUser(6102L);
  }

  private Long planId() {
    return planRepository.findByActiveTrueOrderByPriceAsc().getFirst().getId();
  }

  private Long silverId() {
    return tierRepository.findByCode("SILVER").orElseThrow().getId();
  }

  @Test
  void declinedRenewal_dunsThenRevokesAfterMaxAttempts() {
    long userId = 6101L;
    var dto = subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));
    Long id = dto.id();

    DECLINE.set(true);

    // A first failure duns rather than revokes: still ACTIVE, retry scheduled, counter bumped.
    renewalService.renew(id);
    var afterOne = subscriptionRepository.findByIdWithDetails(id).orElseThrow();
    assertThat(afterOne.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(afterOne.getRenewalAttempts()).isEqualTo(1);

    // Default max-dunning-attempts is 4, so the fourth consecutive failure revokes.
    renewalService.renew(id); // 2
    renewalService.renew(id); // 3
    renewalService.renew(id); // 4 → exhausted
    var afterFour = subscriptionRepository.findByIdWithDetails(id).orElseThrow();
    assertThat(afterFour.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
  }

  @Test
  void successfulRenewal_afterAFailure_extendsExpiry_andResetsDunning() {
    long userId = 6102L;
    var dto = subscriptionService.subscribe(new SubscribeRequest(userId, planId(), silverId()));

    DECLINE.set(true);
    renewalService.renew(dto.id()); // one failure → attempts = 1
    assertThat(
            subscriptionRepository.findByIdWithDetails(dto.id()).orElseThrow().getRenewalAttempts())
        .isEqualTo(1);

    DECLINE.set(false);
    var before = subscriptionService.getMembership(userId).expiresAt();
    renewalService.renew(dto.id()); // success → extend + reset

    var after = subscriptionRepository.findByIdWithDetails(dto.id()).orElseThrow();
    assertThat(after.getRenewalAttempts()).isZero();
    assertThat(after.getExpiresAt()).isAfter(before);
  }
}
