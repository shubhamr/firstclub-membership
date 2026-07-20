package com.firstclub.membership.controller;

import com.firstclub.membership.dto.BenefitAdminDtos.AssignBenefitRequest;
import com.firstclub.membership.dto.BenefitAdminDtos.BenefitDto;
import com.firstclub.membership.dto.BenefitAdminDtos.CreateBenefitRequest;
import com.firstclub.membership.service.BenefitAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/benefits")
@Tag(name = "Admin: Benefits", description = "Runtime configuration of benefits and per-tier perks")
@RequiredArgsConstructor
public class BenefitAdminController {

  private final BenefitAdminService service;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a benefit in the catalog")
  public BenefitDto create(@Valid @RequestBody CreateBenefitRequest req) {
    return service.createBenefit(req);
  }

  @PostMapping("/assignments")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Assign or re-tune a benefit on a tier (evicts caches)")
  public void assign(@Valid @RequestBody AssignBenefitRequest req) {
    service.assignBenefitToTier(req);
  }

  @DeleteMapping("/assignments/{tierId}/{benefitCode}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Remove a benefit from a tier (evicts caches)")
  public void unassign(@PathVariable Long tierId, @PathVariable String benefitCode) {
    service.unassignBenefitFromTier(tierId, benefitCode);
  }
}
