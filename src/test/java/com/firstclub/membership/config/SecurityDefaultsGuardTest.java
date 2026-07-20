package com.firstclub.membership.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins the startup guard for security configuration that is fine in dev and catastrophic anywhere
 * else: the app must refuse to boot with the dev token endpoint enabled outside dev or test, or
 * with a missing or under-length JWT secret.
 *
 * <p>The failure being prevented is silent. {@code /auth/token} mints a JWT for whatever {@code
 * userId} the caller names, with no credential, so the caller picks {@code authentication.name} —
 * the exact value every {@code @PreAuthorize} compares against. Left enabled it does not weaken
 * authorization, it nullifies it, and nothing in the running system looks wrong. The only safe time
 * to catch that is before the port opens.
 */
class SecurityDefaultsGuardTest {

  private static final String STRONG_SECRET = "a-sufficiently-long-hs256-signing-key-0123456789";

  private SecurityDefaultsGuard guard(String[] profiles, boolean devToken, String secret) {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles(profiles);
    SecurityDefaultsGuard guard = new SecurityDefaultsGuard(env);
    ReflectionTestUtils.setField(guard, "devTokenEnabled", devToken);
    ReflectionTestUtils.setField(guard, "jwtSecret", secret);
    return guard;
  }

  @Test
  void refusesToStartWithTheDevTokenEndpointEnabledOutsideDevOrTest() {
    assertThatThrownBy(() -> guard(new String[] {"prod"}, true, STRONG_SECRET).verify())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dev-token.enabled=true")
        .hasMessageContaining("any userId");
  }

  @Test
  void allowsTheDevTokenEndpointUnderTheDevProfile() {
    assertThatCode(() -> guard(new String[] {"dev"}, true, STRONG_SECRET).verify())
        .doesNotThrowAnyException();
  }

  @Test
  void allowsTheDevTokenEndpointUnderTheTestProfile() {
    assertThatCode(() -> guard(new String[] {"test"}, true, STRONG_SECRET).verify())
        .doesNotThrowAnyException();
  }

  @Test
  void refusesToStartWithoutAJwtSecret() {
    // JWT_SECRET has no default on purpose: a signing key committed to the repo lets anyone forge
    // an ADMIN token offline, with no endpoint involved. That failure survives switching the token
    // endpoint off, so it needs its own guard.
    assertThatThrownBy(() -> guard(new String[] {"prod"}, false, "").verify())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_SECRET");
  }

  @Test
  void refusesToStartWithAJwtSecretTooShortForHs256() {
    assertThatThrownBy(() -> guard(new String[] {"prod"}, false, "too-short").verify())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at least 32 bytes");
  }

  @Test
  void acceptsAProductionConfigurationWithNoDevTokenAndAStrongSecret() {
    assertThatCode(() -> guard(new String[] {"prod"}, false, STRONG_SECRET).verify())
        .doesNotThrowAnyException();
    assertThat(STRONG_SECRET.getBytes()).hasSizeGreaterThanOrEqualTo(32);
  }
}
