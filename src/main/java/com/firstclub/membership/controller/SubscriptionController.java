package com.firstclub.membership.controller;

import com.firstclub.membership.dto.SubscriptionDtos.ChangeTierRequest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscribeRequest;
import com.firstclub.membership.dto.SubscriptionDtos.SubscriptionDto;
import com.firstclub.membership.service.IdempotencyService;
import com.firstclub.membership.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Subscription mutations. Every endpoint accepts an optional {@code Idempotency-Key} header; a
 * stable key makes a retry safe, replaying the original result instead of re-mutating.
 *
 * <p><b>Authorization.</b> Every endpoint here moves money or revokes access, so each enforces
 * self-or-admin — {@code subscribe} against the {@code userId} in the body, path-addressed
 * mutations against the subscription's owner. Authentication alone isn't enough: without these
 * checks any token holder could subscribe on another member's behalf (charging them) or cancel any
 * subscription by id.
 *
 * <p>{@code isOwner} returns false both for "not yours" and for "does not exist", so an
 * unauthorised caller gets an identical 403 either way and cannot enumerate subscription ids.
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
@Tag(name = "Subscriptions", description = "Subscribe, upgrade, downgrade, cancel")
@RequiredArgsConstructor
public class SubscriptionController {

  private static final String IDEM_HEADER = "Idempotency-Key";

  private final SubscriptionService subscriptionService;
  private final IdempotencyService idempotencyService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("#req.userId().toString() == authentication.name or hasRole('ADMIN')")
  @Operation(summary = "Subscribe to a plan at a tier")
  public SubscriptionDto subscribe(
      @Parameter(description = "Optional idempotency key to make retries safe")
          @RequestHeader(value = IDEM_HEADER, required = false)
          String idemKey,
      @Valid @RequestBody SubscribeRequest req) {
    return idempotencyService.runOnce(
        idemKey,
        "POST /subscriptions",
        SubscriptionDto.class,
        () -> subscriptionService.subscribe(req));
  }

  @PostMapping("/{id}/upgrade")
  @PreAuthorize("@subscriptionServiceImpl.isOwner(#id, authentication.name) or hasRole('ADMIN')")
  @Operation(summary = "Upgrade the subscription to a higher tier (eligibility enforced)")
  public SubscriptionDto upgrade(
      @PathVariable Long id,
      @RequestHeader(value = IDEM_HEADER, required = false) String idemKey,
      @Valid @RequestBody ChangeTierRequest req) {
    return idempotencyService.runOnce(
        idemKey,
        "POST /subscriptions/%d/upgrade".formatted(id),
        SubscriptionDto.class,
        () -> subscriptionService.upgrade(id, req.targetTierId()));
  }

  @PostMapping("/{id}/downgrade")
  @PreAuthorize("@subscriptionServiceImpl.isOwner(#id, authentication.name) or hasRole('ADMIN')")
  @Operation(summary = "Downgrade the subscription to a lower tier")
  public SubscriptionDto downgrade(
      @PathVariable Long id,
      @RequestHeader(value = IDEM_HEADER, required = false) String idemKey,
      @Valid @RequestBody ChangeTierRequest req) {
    return idempotencyService.runOnce(
        idemKey,
        "POST /subscriptions/%d/downgrade".formatted(id),
        SubscriptionDto.class,
        () -> subscriptionService.downgrade(id, req.targetTierId()));
  }

  @PostMapping("/{id}/cancel")
  @PreAuthorize("@subscriptionServiceImpl.isOwner(#id, authentication.name) or hasRole('ADMIN')")
  @Operation(summary = "Cancel the subscription")
  public SubscriptionDto cancel(
      @PathVariable Long id, @RequestHeader(value = IDEM_HEADER, required = false) String idemKey) {
    return idempotencyService.runOnce(
        idemKey,
        "POST /subscriptions/%d/cancel".formatted(id),
        SubscriptionDto.class,
        () -> subscriptionService.cancel(id));
  }

  // Refund/chargeback revokes access and issues a credit note, so it is an ops action rather than
  // self-service: owner-or-admin would let any member refund themselves at will.
  @PostMapping("/{id}/refund")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Refund / chargeback the subscription (revokes access) — admin only")
  public SubscriptionDto refund(
      @PathVariable Long id, @RequestHeader(value = IDEM_HEADER, required = false) String idemKey) {
    return idempotencyService.runOnce(
        idemKey,
        "POST /subscriptions/%d/refund".formatted(id),
        SubscriptionDto.class,
        () -> subscriptionService.refund(id));
  }
}
