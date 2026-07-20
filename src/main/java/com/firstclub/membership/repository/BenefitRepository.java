package com.firstclub.membership.repository;

import com.firstclub.membership.model.Benefit;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BenefitRepository extends JpaRepository<Benefit, Long> {

  Optional<Benefit> findByCode(String code);
}
