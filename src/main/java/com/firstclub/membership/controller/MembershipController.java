package com.firstclub.membership.controller;

import com.firstclub.membership.dto.MembershipView;
import com.firstclub.membership.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/{userId}/membership")
@Tag(name = "Membership", description = "Current membership and expiry")
@RequiredArgsConstructor
public class MembershipController {

  private final SubscriptionService subscriptionService;

  @GetMapping
  @PreAuthorize("#userId.toString() == authentication.name or hasRole('ADMIN')")
  @Operation(summary = "Get a user's current membership, tier, benefits and expiry")
  public MembershipView current(@PathVariable long userId) {
    return subscriptionService.getMembership(userId);
  }
}
