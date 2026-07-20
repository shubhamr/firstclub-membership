package com.firstclub.membership.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies HS256 JWTs. The subject is the userId; a {@code role} claim carries
 * USER/ADMIN. In production an external identity service would mint these; this is the membership
 * service's local validator plus a dev token endpoint (see {@code AuthController}).
 */
@Service
public class JwtService {

  private final SecretKey key;
  private final Duration ttl;
  private final Clock clock;

  public JwtService(
      @Value("${security.jwt.secret}") String secret,
      @Value("${security.jwt.ttl-hours:12}") long ttlHours,
      Clock clock) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.ttl = Duration.ofHours(ttlHours);
    this.clock = clock;
  }

  public String issue(long userId, boolean admin) {
    var now = clock.instant();
    return Jwts.builder()
        .subject(String.valueOf(userId))
        .claim("role", admin ? "ADMIN" : "USER")
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(ttl)))
        .signWith(key)
        .compact();
  }

  public Claims parse(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }
}
