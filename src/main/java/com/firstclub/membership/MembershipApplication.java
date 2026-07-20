package com.firstclub.membership;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FirstClub Membership Program.
 *
 * <p>A tiered subscription-membership backend. See {@code ARCHITECTURE.md} for the design rationale
 * behind the concurrency, fault-tolerance and extensibility decisions.
 */
@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
public class MembershipApplication {

  public static void main(String[] args) {
    SpringApplication.run(MembershipApplication.class, args);
  }
}
