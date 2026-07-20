package com.firstclub.membership.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the {@code Authorization: Bearer <jwt>} header and populates the security context with
 * the userId (principal name) and a ROLE_USER / ROLE_ADMIN authority. Invalid/absent tokens leave
 * the request unauthenticated; the authorization rules then decide 401/403.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtService jwt;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      try {
        Claims claims = jwt.parse(header.substring(BEARER_PREFIX.length()));
        String role = claims.get("role", String.class);
        // A token with no role claim is malformed; leave the request unauthenticated rather than
        // granting a meaningless "ROLE_null" authority.
        if (role != null) {
          var auth =
              new UsernamePasswordAuthenticationToken(
                  claims.getSubject(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
          SecurityContextHolder.getContext().setAuthentication(auth);
        }
      } catch (JwtException | IllegalArgumentException e) {
        SecurityContextHolder.clearContext(); // invalid token → stays unauthenticated
      }
    }
    chain.doFilter(request, response);
  }
}
