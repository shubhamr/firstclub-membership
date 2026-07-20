package com.firstclub.membership.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup on security configuration that is safe in dev and catastrophic anywhere else.
 *
 * <p>{@code /auth/token} mints a JWT for whatever {@code userId} the body names, with no
 * credential. That lets the caller choose {@code authentication.name} — the value every
 * {@code @PreAuthorize} here compares against. Enabled in a real environment it doesn't weaken
 * authorization, it nullifies it: every self-or-admin check passes for every user. So the default
 * is off, and the unsafe combination refuses to boot rather than being one forgotten override away.
 *
 * <p>It runs on {@code @PostConstruct}, not as a filter or health indicator, because the only
 * useful moment to refuse is before the port opens — a warning in a log is how this class of
 * misconfiguration reaches production.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SecurityDefaultsGuard {

  private static final int MIN_HS256_SECRET_BYTES = 32;

  private final Environment environment;

  @Value("${membership.auth.dev-token.enabled:false}")
  private boolean devTokenEnabled;

  /**
   * Explicit acknowledgement, for local runs that cannot set a profile.
   *
   * <p>{@code spring.profiles.active} in {@code .env} has no effect: profiles are resolved before
   * {@code spring.config.import} processes that file, so a profile is not something the .env-only
   * workflow can declare. This second switch is deliberate rather than a loophole — it defaults to
   * off, and its name states plainly what it does.
   */
  @Value("${membership.auth.dev-token.allow-outside-dev:false}")
  private boolean allowOutsideDev;

  @Value("${security.jwt.secret:}")
  private String jwtSecret;

  @PostConstruct
  void verify() {
    boolean devOrTest = environment.matchesProfiles("dev", "test") || allowOutsideDev;

    if (devTokenEnabled && !devOrTest) {
      throw new IllegalStateException(
          """
          membership.auth.dev-token.enabled=true outside the dev/test profiles.

          POST /api/v1/auth/token issues a token for any userId with no credential, so a caller can \
          assume any identity and satisfy every @PreAuthorize in the application.

          Either set AUTH_DEV_TOKEN_ENABLED=false, or — if this really is a local demo — declare it \
          by running under the dev profile, or setting \
          membership.auth.dev-token.allow-outside-dev=true (AUTH_DEV_TOKEN_ALLOW_OUTSIDE_DEV=true), \
          which .env.example does. Note that spring.profiles.active cannot be set from .env: \
          profiles are resolved before that file is imported.""");
    }

    if (jwtSecret.getBytes().length < MIN_HS256_SECRET_BYTES) {
      throw new IllegalStateException(
          "security.jwt.secret must be at least %d bytes for HS256 (got %d). Set JWT_SECRET."
              .formatted(MIN_HS256_SECRET_BYTES, jwtSecret.getBytes().length));
    }

    if (devTokenEnabled) {
      log.warn(
          "Dev token endpoint is ENABLED: /api/v1/auth/token will mint a token for any userId "
              + "without a credential. Acceptable under the {} profile; never in production.",
          String.join(",", environment.getActiveProfiles()));
    }
  }
}
