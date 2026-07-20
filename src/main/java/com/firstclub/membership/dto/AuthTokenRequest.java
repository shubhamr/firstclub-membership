package com.firstclub.membership.dto;

/**
 * Request a JWT from the dev token endpoint.
 *
 * <p>{@code userId} is the subject. {@code admin=true} requests the ADMIN role — which requires
 * {@code secret} to match the configured admin bootstrap secret, so admin escalation is never
 * anonymous. A non-admin token (the dev stand-in for a real login) needs no secret.
 *
 * <p>{@code admin} is a boxed {@link Boolean} so that omitting it means "no". Jackson will not map
 * a missing value onto a primitive, so a primitive here would reject the minimal payload {@code
 * {"userId":7001}} with a 400 rather than defaulting to the safer value.
 */
public record AuthTokenRequest(long userId, Boolean admin, String secret) {

  /** Absent means not an admin. */
  public boolean isAdmin() {
    return Boolean.TRUE.equals(admin);
  }
}
