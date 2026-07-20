package com.firstclub.membership.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security.
 *
 * <ul>
 *   <li><b>Public:</b> the token endpoint, the read-only catalog, health/info, and API docs.
 *   <li><b>ADMIN only:</b> {@code /admin/**}, user provisioning/listing, subscription refunds, and
 *       anything else under {@code /actuator/**} (only health/info are exposed today; the rule
 *       keeps any endpoint enabled later ops-only by default rather than accidentally public).
 *   <li><b>Authenticated:</b> everything else. Per-user endpoints additionally enforce
 *       <em>self-or-admin</em> via {@code @PreAuthorize} on the controller (see
 *       {@code @EnableMethodSecurity}).
 * </ul>
 *
 * <p><b>Authentication is not authorization.</b> {@code anyRequest().authenticated()} proves only
 * that a caller holds a valid token, not whose data they may touch. Every per-user controller
 * therefore carries an explicit {@code @PreAuthorize}: {@code MembershipController}, {@code
 * ActivityController}, {@code CreditNoteController}, {@code UserController}, and all of {@code
 * SubscriptionController}. An endpoint under a user-scoped path without one is a bug.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  private static final String ADMIN = "ADMIN";

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                // The container's ERROR dispatch re-enters this chain carrying no authentication,
                // so anyRequest().authenticated() would answer 403-with-an-empty-body for every
                // malformed request body, masking the 400 the handler already produced and
                // breaking the problem+json contract. Authorization was already decided on the
                // original dispatch; permitting ERROR only lets that response be rendered.
                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/api/v1/auth/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/plans", "/api/v1/tiers")
                    .permitAll()
                    // Only health/info are exposed today. Everything else under /actuator is
                    // ADMIN by default, so enabling an endpoint later (metrics, env, heapdump)
                    // never makes it accidentally public: it exposes business telemetry, and that
                    // is ops-only.
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .hasRole(ADMIN)
                    .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole(ADMIN)
                    .requestMatchers(HttpMethod.POST, "/api/v1/users")
                    .hasRole(ADMIN)
                    .requestMatchers(HttpMethod.GET, "/api/v1/users")
                    .hasRole(ADMIN)
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
