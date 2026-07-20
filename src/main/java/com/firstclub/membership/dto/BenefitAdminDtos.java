package com.firstclub.membership.dto;

import com.firstclub.membership.model.Benefit;
import com.firstclub.membership.model.BenefitType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** Request/response payloads for the admin benefit-configuration API. */
public final class BenefitAdminDtos {

  private BenefitAdminDtos() {}

  public record CreateBenefitRequest(
      @NotBlank String code,
      @NotNull BenefitType type,
      @NotBlank String name,
      String description) {}

  public record AssignBenefitRequest(
      @NotNull Long tierId, @NotBlank String benefitCode, Map<String, Object> params) {}

  public record BenefitDto(
      Long id, String code, String type, String name, String description, boolean active) {
    public static BenefitDto from(Benefit b) {
      return new BenefitDto(
          b.getId(),
          b.getCode(),
          b.getType().name(),
          b.getName(),
          b.getDescription(),
          b.isActive());
    }
  }
}
