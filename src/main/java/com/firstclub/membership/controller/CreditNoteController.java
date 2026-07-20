package com.firstclub.membership.controller;

import com.firstclub.membership.dto.CreditNoteDto;
import com.firstclub.membership.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/{userId}/credit-notes")
@Tag(name = "Credit notes", description = "Credits issued for unused time on cancel/refund")
@RequiredArgsConstructor
public class CreditNoteController {

  private final SubscriptionService subscriptionService;

  @GetMapping
  @PreAuthorize("#userId.toString() == authentication.name or hasRole('ADMIN')")
  @Operation(summary = "List a user's credit notes (paginated)")
  public List<CreditNoteDto> list(
      @PathVariable long userId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return subscriptionService.creditNotes(userId, page, size);
  }
}
