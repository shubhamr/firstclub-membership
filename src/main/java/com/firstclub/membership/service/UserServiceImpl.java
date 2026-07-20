package com.firstclub.membership.service;

import com.firstclub.membership.dto.CreateUserRequest;
import com.firstclub.membership.dto.UserDto;
import com.firstclub.membership.exception.NotFoundException;
import com.firstclub.membership.model.AppUser;
import com.firstclub.membership.repository.AppUserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

  private final AppUserRepository repository;

  @Override
  @Transactional(readOnly = true)
  public List<UserDto> listUsers(int page, int size) {
    return repository.findByOrderByIdAsc(PageRequest.of(page, size)).stream()
        .map(UserDto::from)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public UserDto getUser(Long id) {
    return repository
        .findById(id)
        .map(UserDto::from)
        .orElseThrow(() -> new NotFoundException("User %d not found".formatted(id)));
  }

  @Override
  @Transactional
  public UserDto createUser(CreateUserRequest req) {
    AppUser saved = repository.save(new AppUser(req.name(), req.email(), req.cohort()));
    return UserDto.from(saved);
  }
}
