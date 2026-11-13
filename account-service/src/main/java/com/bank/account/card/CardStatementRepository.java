package com.bank.account.card;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface CardStatementRepository extends JpaRepository<CardStatement, Long> {

    Page<CardStatement> findByCardId(Long cardId, Pageable pageable);

    Optional<CardStatement> findFirstByCardIdOrderByIdDesc(Long cardId);

    /** The statement made after this one, if there is one yet. */
    Optional<CardStatement> findFirstByCardIdAndIdGreaterThanOrderByIdAsc(Long cardId, Long id);
}
