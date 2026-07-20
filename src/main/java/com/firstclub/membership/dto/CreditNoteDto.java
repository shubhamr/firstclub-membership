package com.firstclub.membership.dto;

import com.firstclub.membership.model.CreditNote;
import java.math.BigDecimal;
import java.time.Instant;

/** API view of a credit note. */
public record CreditNoteDto(
    Long id,
    Long subscriptionId,
    long userId,
    BigDecimal amount,
    String reason,
    Instant createdAt) {

  public static CreditNoteDto from(CreditNote c) {
    return new CreditNoteDto(
        c.getId(),
        c.getSubscriptionId(),
        c.getUserId(),
        c.getAmount(),
        c.getReason(),
        c.getCreatedAt());
  }
}
