package com.firstclub.membership.repository;

import com.firstclub.membership.model.AppUser;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

  List<AppUser> findByOrderByIdAsc(Pageable pageable);
}
