package com.firstclub.membership.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request to provision a user in the directory. */
public record CreateUserRequest(
    @NotBlank String name, @NotBlank @Email String email, String cohort) {}
