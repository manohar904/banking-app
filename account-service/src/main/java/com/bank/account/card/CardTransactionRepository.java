package com.bank.account.card;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

interface CardTransactionRepository extends JpaRepository<CardTransaction, Long> {

    Page<CardTransaction> findByCardId(Long cardId, Pageable pageable);

    /** Everything after one moment, up to and including another, oldest first. */
    @Query("""
            select t from CardTransaction t where t.cardId = :cardId
              and t.createdAt > :after and t.createdAt <= :until order by t.id""")
    List<CardTransaction> findBetween(Long cardId, Instant after, Instant until);

    /** Payments made after one moment, up to and including another. */
    @Query("""
            select coalesce(sum(t.amount), 0) from CardTransaction t
            where t.cardId = :cardId and t.type = com.bank.account.card.CardTransaction.Type.PAYMENT
              and t.createdAt > :after and t.createdAt <= :until""")
    BigDecimal paidBetween(Long cardId, Instant after, Instant until);
}
