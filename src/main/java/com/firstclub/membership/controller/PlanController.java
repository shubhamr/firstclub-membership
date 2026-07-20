package com.firstclub.membership.controller;

import com.firstclub.membership.dto.PlanDto;
import com.firstclub.membership.service.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
@Tag(name = "Plans", description = "Membership plan catalog")
@RequiredArgsConstructor
public class PlanController {

  private final PlanService planService;

  @GetMapping
  @Operation(summary = "List active membership plans")
  public List<PlanDto> list() {
    return planService.listActivePlans();
  }
}
