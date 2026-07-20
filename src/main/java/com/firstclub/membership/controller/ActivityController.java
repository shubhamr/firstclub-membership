package com.firstclub.membership.controller;

import com.firstclub.membership.dto.ActivityDtos.ActivityUpdateRequest;
import com.firstclub.membership.dto.MembershipView;
import com.firstclub.membership.service.ActivityIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order-activity ingestion, a service-to-service endpoint rather than a user-facing one.
 *
 * <p>It accepts one order event ({@code orderId}, {@code orderAmount}, {@code occurredAt}) plus the
 * lifetime {@code orderCount} and {@code cohorts}. Those are exactly the inputs the tier rules
 * evaluate, so whoever can write them chooses their own tier: a member allowed to post their own
 * orders could self-upgrade to the top tier while renewal keeps billing the locked-in {@code
 * pricePaid}. Order activity is asserted by the order service, never by the member it describes,
 * which is why it is admin only.
 */
@RestController
@RequestMapping("/api/v1/users/{userId}/activity")
@Tag(name = "Activity", description = "Feed user order activity; triggers tier re-evaluation")
@RequiredArgsConstructor
public class ActivityController {

  private final ActivityIngestionService ingestionService;

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Record user activity and re-evaluate membership tier (service-to-service)")
  public MembershipView record(
      @PathVariable long userId, @Valid @RequestBody ActivityUpdateRequest req) {
    return ingestionService.recordActivity(userId, req);
  }
}
