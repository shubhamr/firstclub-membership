package com.firstclub.membership.repository;

import com.firstclub.membership.model.CreditNote;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditNoteRepository extends JpaRepository<CreditNote, Long> {

  List<CreditNote> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
