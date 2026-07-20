package com.firstclub.membership;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.firstclub.membership.repository.PlanRepository;
import com.firstclub.membership.repository.TierRepository;
import com.firstclub.membership.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the HTTP contract at the controller boundary: the subscribe endpoint maps domain outcomes
 * onto the right status codes and RFC-7807 problem bodies — 201 on success, 404 for an unknown
 * plan, 422 for a rejected business rule such as an unqualified tier.
 *
 * <p>Complements the service-level integration tests, which assert behaviour but not the wire
 * contract.
 */
class WebContractIntegrationTest extends AbstractIntegrationTest {

  @Autowired WebApplicationContext context;
  @Autowired JwtService jwt;
  @Autowired PlanRepository planRepository;
  @Autowired TierRepository tierRepository;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // Users whose subscribe must reach the service so the intended outcome is exercised: 201 for
    // 5001, and — because the registration check runs before plan/tier validation — 5002 (unknown
    // plan → 404) and 5003 (unqualified tier → 422). 5004 is the no-token case, rejected before any
    // service call, so it needs no row.
    seedUser(5001L);
    seedUser(5002L);
    seedUser(5003L);
  }

  private String adminBearer() {
    return "Bearer " + jwt.issue(0, true);
  }

  private Long planId() {
    return planRepository.findByActiveTrueOrderByPriceAsc().getFirst().getId();
  }

  private Long silverId() {
    return tierRepository.findByCode("SILVER").orElseThrow().getId();
  }

  private String body(long userId, Long planId, Long tierId) {
    return "{\"userId\": %d, \"planId\": %d, \"tierId\": %d}".formatted(userId, planId, tierId);
  }

  private Long platinumId() {
    return tierRepository.findByCode("PLATINUM").orElseThrow().getId();
  }

  @Test
  void subscribe_returns201() throws Exception {
    mvc.perform(
            post("/api/v1/subscriptions")
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(5001L, planId(), silverId())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void subscribe_unknownPlan_returns404() throws Exception {
    mvc.perform(
            post("/api/v1/subscriptions")
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(5002L, 999_999L, silverId())))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void subscribe_tierNotQualifiedFor_returns422() throws Exception {
    // A brand-new user has no order history, so PLATINUM's criteria cannot be met — a business
    // rule rejection (422), distinct from a missing resource (404) or a state conflict (409).
    mvc.perform(
            post("/api/v1/subscriptions")
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(5003L, planId(), platinumId())))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void subscribe_requiresAuthentication() throws Exception {
    mvc.perform(
            post("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(5004L, planId(), silverId())))
        .andExpect(status().is4xxClientError());
  }

  /**
   * A body Jackson cannot read must surface as 400 problem+json, not as the container's bare 403.
   *
   * <p>Regression guard. The ERROR dispatch re-enters the security filter chain carrying no
   * authentication, so {@code anyRequest().authenticated()} answered 403-with-an-empty-body for
   * every malformed body and masked the 400 the handler had already produced — silently breaking
   * the problem+json contract for the whole API. {@code SecurityConfig} permits the ERROR dispatch
   * for exactly this reason.
   */
  @Test
  void malformedBody_returns400ProblemJson_notBareForbidden() throws Exception {
    mvc.perform(
            post("/api/v1/admin/benefits")
                .header(HttpHeaders.AUTHORIZATION, adminBearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"X\",\"type\":\"NOT_A_BENEFIT_TYPE\",\"name\":\"X\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Malformed Request Body"));
  }

  /** Permitting the ERROR dispatch must not weaken authorization on the original dispatch. */
  @Test
  void malformedBody_withoutAToken_isStillRejected() throws Exception {
    mvc.perform(
            post("/api/v1/admin/benefits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"X\",\"type\":\"NOT_A_BENEFIT_TYPE\",\"name\":\"X\"}"))
        .andExpect(status().is4xxClientError())
        .andExpect(status().is(org.hamcrest.Matchers.not(400)));
  }
}
