package com.firstclub.membership.dto;

import com.firstclub.membership.model.AppUser;

/** API view of a user in the directory. */
public record UserDto(Long id, String name, String email, String cohort) {

  public static UserDto from(AppUser u) {
    return new UserDto(u.getId(), u.getName(), u.getEmail(), u.getCohort());
  }
}
