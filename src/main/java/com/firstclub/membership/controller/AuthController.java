package com.firstclub.membership.controller;

import com.firstclub.membership.dto.AuthTokenRequest;
import com.firstclub.membership.security.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dev token issuance — a stand-in for a real identity provider, not a production auth model. In
 * production an external IdP authenticates the user and mints the JWT; this exists so the console
 * and tests can obtain one.
 *
 * <p>Two guards keep it from becoming an open admin oracle. The whole bean is gated on {@code
 * membership.auth.dev-token.enabled} — set it {@code false} anywhere a real IdP is in front and the
 * route disappears. And {@code admin=true} requires a {@code secret} matching {@code
 * membership.auth.admin-bootstrap-secret}, compared in constant time, so admin escalation is never
 * anonymous.
 *
 * <p>A non-admin token needs no secret; it only asserts a subject, which {@code @PreAuthorize} then
 * constrains to self-or-admin.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "membership.auth.dev-token.enabled", havingValue = "true")
@Tag(name = "Auth", description = "Dev JWT issuance (a real IdP replaces this in production)")
public class AuthController {

  private final JwtService jwt;
  private final String adminBootstrapSecret;

  public AuthController(
      JwtService jwt,
      @Value("${membership.auth.admin-bootstrap-secret:}") String adminBootstrapSecret) {
    this.jwt = jwt;
    this.adminBootstrapSecret = adminBootstrapSecret;
  }

  @PostMapping("/token")
  @Operation(summary = "Issue a JWT for a userId (admin=true requires the bootstrap secret)")
  public Map<String, String> token(@RequestBody AuthTokenRequest req) {
    if (req.isAdmin() && !bootstrapSecretMatches(req.secret())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "An admin token requires a valid bootstrap secret; admin escalation is not anonymous.");
    }
    return Map.of("token", jwt.issue(req.userId(), req.isAdmin()));
  }

  /** Constant-time comparison; an unconfigured (blank) secret can never be matched. */
  private boolean bootstrapSecretMatches(String provided) {
    if (adminBootstrapSecret == null || adminBootstrapSecret.isBlank() || provided == null) {
      return false;
    }
    return MessageDigest.isEqual(
        adminBootstrapSecret.getBytes(StandardCharsets.UTF_8),
        provided.getBytes(StandardCharsets.UTF_8));
  }
}
