package com.firstclub.membership;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.firstclub.membership.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the JWT access rules at the web layer: the catalog is public, membership reads are
 * self-or-admin, and the user directory is admin-only.
 *
 * <p>MockMvc runs over the real security filter chain, so these exercise the configured rules
 * rather than a stubbed authorization decision.
 */
class SecurityIntegrationTest extends AbstractIntegrationTest {

  @Autowired WebApplicationContext context;
  @Autowired JwtService jwt;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private String bearer(long userId, boolean admin) {
    return "Bearer " + jwt.issue(userId, admin);
  }

  @Test
  void catalog_isPublic() throws Exception {
    mvc.perform(get("/api/v1/plans")).andExpect(status().isOk());
  }

  @Test
  void membership_requiresAuthentication() throws Exception {
    mvc.perform(get("/api/v1/users/7001/membership")).andExpect(status().is4xxClientError());
  }

  @Test
  void membership_selfOrAdminOnly() throws Exception {
    mvc.perform(
            get("/api/v1/users/7001/membership")
                .header(HttpHeaders.AUTHORIZATION, bearer(7001, false)))
        .andExpect(status().isOk()); // self
    mvc.perform(
            get("/api/v1/users/7001/membership")
                .header(HttpHeaders.AUTHORIZATION, bearer(7002, false)))
        .andExpect(status().isForbidden()); // someone else
    mvc.perform(
            get("/api/v1/users/7001/membership").header(HttpHeaders.AUTHORIZATION, bearer(0, true)))
        .andExpect(status().isOk()); // admin
  }

  @Test
  void userDirectory_requiresAdmin() throws Exception {
    mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearer(7001, false)))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearer(0, true)))
        .andExpect(status().isOk());
  }
}
