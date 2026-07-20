package com.firstclub.membership.controller;

import com.firstclub.membership.dto.TierDto;
import com.firstclub.membership.service.TierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tiers")
@Tag(name = "Tiers", description = "Membership tiers and the benefits they unlock")
@RequiredArgsConstructor
public class TierController {

  private final TierService tierService;

  @GetMapping
  @Operation(summary = "List active tiers with their resolved benefits")
  public List<TierDto> list() {
    return tierService.listTiers();
  }
}
