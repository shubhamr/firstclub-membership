package com.firstclub.membership.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * Persisted result of a completed mutation, keyed by (idempotency key, endpoint). The unique
 * constraint on that pair is what collapses a client's retries into a single logical mutation.
 */
@Entity
@Table(name = "idempotency_key")
@Getter
public class IdempotencyRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "idem_key", nullable = false)
  private String idemKey;

  @Column(nullable = false)
  private String endpoint;

  @Column(name = "status_code", nullable = false)
  private int statusCode;

  @Column(name = "response_body", nullable = false)
  private String responseBody;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  protected IdempotencyRecord() {}

  public IdempotencyRecord(String idemKey, String endpoint, int statusCode, String responseBody) {
    this.idemKey = idemKey;
    this.endpoint = endpoint;
    this.statusCode = statusCode;
    this.responseBody = responseBody;
    this.createdAt = Instant.now();
  }
}
