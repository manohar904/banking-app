package com.bank.account.card;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface CardTransactionRepository extends JpaRepository<CardTransaction, Long> {

    Page<CardTransaction> findByCardId(Long cardId, Pageable pageable);
}
