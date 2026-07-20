package com.firstclub.membership.service.rules;

import com.firstclub.membership.model.TierCriteria;
import com.firstclub.membership.model.UserActivity;

/**
 * A single, self-contained progression rule. Adding a new way to qualify for a tier (e.g. referral
 * count, tenure, region) means adding one implementation of this interface as a Spring bean and a
 * column/params on {@link TierCriteria} — zero changes to the assignment flow.
 *
 * <p>Contract: return {@code true} only when this rule's threshold is <em>configured</em> on the
 * criteria AND the activity satisfies it. A rule whose threshold is absent must return {@code
 * false} (it simply does not apply), never {@code true}.
 */
public interface TierRule {

  boolean qualifies(UserActivity activity, TierCriteria criteria);

  /**
   * Whether this rule's threshold is set on the given criteria — i.e. whether the rule has an
   * opinion about this tier at all.
   *
   * <p>This is what makes a tier "unconditional" — a tier no rule claims is open to everyone.
   * Deriving that from the rules themselves, rather than a hardcoded list of criteria fields, keeps
   * adding a rule purely additive.
   *
   * <p>Get it wrong and it fails open: answering {@code false} while the threshold <em>is</em> set
   * makes a tier gated only by that criterion look unconfigured, so every user qualifies and the
   * member base is auto-upgraded into it. Abstract rather than defaulted for that reason — a
   * default would quietly reintroduce the same trap for the next rule someone writes.
   */
  boolean isConfigured(TierCriteria criteria);

  /**
   * Stable {@code UPPER_SNAKE} identifier, unique across rules. Logged by {@code
   * TierAssignmentService} to record which rule qualified a user, so it is an audit value rather
   * than a display string.
   */
  String code();
}
