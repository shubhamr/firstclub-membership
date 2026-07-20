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
 * A user in the directory. Deliberately not linked by FK to subscriptions — membership references a
 * user_id loosely, so this table is a convenience registry, not the owner of identity.
 */
@Entity
@Table(name = "app_user")
@Getter
public class AppUser {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String email;

  @Column private String cohort;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  protected AppUser() {}

  public AppUser(String name, String email, String cohort) {
    this.name = name;
    this.email = email;
    this.cohort = cohort;
    this.createdAt = Instant.now();
  }

  public String getCohort() {
    return cohort;
  }
}
