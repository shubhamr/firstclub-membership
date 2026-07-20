package com.firstclub.membership.controller;

import com.firstclub.membership.dto.CreateUserRequest;
import com.firstclub.membership.dto.UserDto;
import com.firstclub.membership.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "User directory")
@RequiredArgsConstructor
public class UserController {

  private final UserService userService;

  @GetMapping
  @Operation(summary = "List users in the directory (paginated)")
  public List<UserDto> list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    return userService.listUsers(page, size);
  }

  @GetMapping("/{id}")
  @PreAuthorize("#id.toString() == authentication.name or hasRole('ADMIN')")
  @Operation(summary = "Get a single user by id")
  public UserDto get(@PathVariable Long id) {
    return userService.getUser(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Provision a new user in the directory")
  public UserDto create(@Valid @RequestBody CreateUserRequest req) {
    return userService.createUser(req);
  }
}
