package com.firstclub.membership.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstclub.membership.AbstractIntegrationTest;
import com.firstclub.membership.dto.CreateUserRequest;
import com.firstclub.membership.dto.UserDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pins the user directory: the seeded listing, creation with a generated id, single fetch, and the
 * directory cohort feeding tier eligibility.
 */
class UserIntegrationTest extends AbstractIntegrationTest {

  @Autowired UserService userService;
  @Autowired UserActivityService userActivityService;
  @Autowired TierAssignmentService tierAssignmentService;

  @Test
  void listUsers_returnsSeededDirectory() {
    List<UserDto> users = userService.listUsers(0, 50);
    assertThat(users).hasSizeGreaterThanOrEqualTo(4);
    assertThat(users).anyMatch(u -> u.id() == 7001L && u.name().equals("Aarav Shah"));
    assertThat(users).anyMatch(u -> "VIP".equals(u.cohort()));
  }

  @Test
  void createUser_generatesIdAboveSeeds_andIsFetchable() {
    UserDto created =
        userService.createUser(
            new CreateUserRequest("Test Newbie", "newbie@firstclub.test", "GOLD"));
    assertThat(created.id()).isGreaterThanOrEqualTo(8000L);

    UserDto fetched = userService.getUser(created.id());
    assertThat(fetched.email()).isEqualTo("newbie@firstclub.test");
    assertThat(fetched.cohort()).isEqualTo("GOLD");
  }

  @Test
  void directoryCohort_feedsTierEligibility() {
    // User 7003 is VIP in the directory with no order activity, which isolates the cohort as the
    // only qualifying input.
    var activity = userActivityService.currentActivity(7003L);
    assertThat(activity.cohorts()).contains("VIP");
    // The rule engine ORs its rules, so the cohort alone reaches PLATINUM.
    assertThat(tierAssignmentService.highestQualifyingTier(activity).getCode())
        .isEqualTo("PLATINUM");
  }
}
