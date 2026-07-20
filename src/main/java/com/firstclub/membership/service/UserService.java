package com.firstclub.membership.service;

import com.firstclub.membership.dto.CreateUserRequest;
import com.firstclub.membership.dto.UserDto;
import java.util.List;

/** Read/provision access to the user directory. */
public interface UserService {

  List<UserDto> listUsers(int page, int size);

  UserDto getUser(Long id);

  UserDto createUser(CreateUserRequest req);
}
