package com.firstclub.membership;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests, backed by one shared PostgreSQL container.
 *
 * <p>The container is started in a static initializer and never stopped by the JUnit extension, so
 * a single instance serves every test class (the singleton-container pattern). Flyway migrates the
 * fresh schema on context startup.
 *
 * <p>Postgres rather than H2 is deliberate: the concurrency guarantees under test — the partial
 * unique index, {@code ON CONFLICT} upserts, optimistic locking — only hold against the same engine
 * as production.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired protected JdbcTemplate jdbcTemplate;

  /**
   * Seed a user in the directory so a chosen {@code user_id} satisfies the {@code app_user} foreign
   * keys added in V22. Idempotent — safe to call for the same id more than once, or across tests
   * that share the singleton container.
   */
  protected void seedUser(long id) {
    jdbcTemplate.update(
        "insert into app_user (id, name, email) values (?, ?, ?) on conflict (id) do nothing",
        id,
        "Test User " + id,
        "user-" + id + "@firstclub.test");
  }
}
