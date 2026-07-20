package com.firstclub.membership.dto;

import com.firstclub.membership.model.Subscription;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;

public final class SubscriptionDtos {

  private SubscriptionDtos() {}

  public record SubscribeRequest(
      @NotNull @Positive Long userId, @NotNull Long planId, @NotNull Long tierId) {}

  public record ChangeTierRequest(@NotNull Long targetTierId) {}

  /** Compact result of a mutation. Serializable/deserializable for the idempotency store. */
  public record SubscriptionDto(
      Long id,
      long userId,
      String planCadence,
      String tierCode,
      String status,
      BigDecimal pricePaid,
      Instant trialEnd,
      Instant startAt,
      Instant expiresAt) {

    public static SubscriptionDto from(Subscription s) {
      return new SubscriptionDto(
          s.getId(),
          s.getUserId(),
          s.getPlan().getCadence().name(),
          s.getTier().getCode(),
          s.getStatus().name(),
          s.getPricePaid(),
          s.getTrialEnd(),
          s.getStartAt(),
          s.getExpiresAt());
    }
  }
}
