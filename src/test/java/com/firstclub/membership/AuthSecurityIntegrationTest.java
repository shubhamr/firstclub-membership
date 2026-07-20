package com.firstclub.membership;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.firstclub.membership.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the token-issuance endpoint and the JWT validation edges.
 *
 * <ul>
 *   <li>admin escalation via {@code /auth/token} is <b>not anonymous</b> — it requires the
 *       bootstrap secret;
 *   <li>a tampered or expired bearer is rejected (stays unauthenticated), never trusted.
 * </ul>
 */
class AuthSecurityIntegrationTest extends AbstractIntegrationTest {

  @Autowired WebApplicationContext context;
  @Autowired JwtService jwt;

  @Value("${security.jwt.secret}")
  String jwtSecret;

  @Value("${membership.auth.admin-bootstrap-secret}")
  String adminSecret;

  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tokenPost(
      String body) {
    return post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON).content(body);
  }

  @Test
  void adminToken_withoutSecret_isForbidden() throws Exception {
    mvc.perform(tokenPost("{\"userId\":0,\"admin\":true}")).andExpect(status().isForbidden());
  }

  @Test
  void adminToken_withWrongSecret_isForbidden() throws Exception {
    mvc.perform(tokenPost("{\"userId\":0,\"admin\":true,\"secret\":\"nope\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminToken_withCorrectSecret_isIssued() throws Exception {
    mvc.perform(tokenPost("{\"userId\":0,\"admin\":true,\"secret\":\"" + adminSecret + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty());
  }

  @Test
  void selfToken_needsNoSecret() throws Exception {
    mvc.perform(tokenPost("{\"userId\":7001,\"admin\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty());
  }

  @Test
  void tokenSignedWithTheWrongKey_isRejected() throws Exception {
    // Forge a well-formed token for a real user, signed with a key we are not trusted to hold.
    //
    // Signing with a foreign key rather than mutating a valid signature is what makes this
    // deterministic: the final base64url character of a 256-bit HMAC encodes only part of a byte,
    // so character substitutions often decode to the identical signature and verify fine. It is
    // also the threat that matters — an attacker holds their own key, not a near-miss of ours.
    SecretKey attackerKey =
        Keys.hmacShaKeyFor(
            "an-entirely-different-signing-key-0123456789".getBytes(StandardCharsets.UTF_8));
    Instant now = Instant.now();
    String forged =
        Jwts.builder()
            .subject("7001")
            .claim("role", "ADMIN") // a self-granted role must not survive signature verification
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(1, ChronoUnit.HOURS)))
            .signWith(attackerKey)
            .compact();

    mvc.perform(
            get("/api/v1/users/7001/membership")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void expiredToken_isRejected() throws Exception {
    SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    Instant now = Instant.now();
    String expired =
        Jwts.builder()
            .subject("7001")
            .claim("role", "USER")
            .issuedAt(Date.from(now.minus(2, ChronoUnit.HOURS)))
            .expiration(Date.from(now.minus(1, ChronoUnit.HOURS)))
            .signWith(key)
            .compact();
    mvc.perform(
            get("/api/v1/users/7001/membership")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
        .andExpect(status().is4xxClientError());
  }
}
