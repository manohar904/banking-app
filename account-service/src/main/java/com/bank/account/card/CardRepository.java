package com.bank.account.card;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CardRepository extends JpaRepository<Card, Long> {

    /** Locks the row, so purchases and payments on one card are applied one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Card c where c.id = :id")
    Optional<Card> findForUpdate(Long id);

    List<Card> findByCustomerIdOrderById(Long customerId);

    long countByCustomerIdAndStatusNot(Long customerId, CardStatus status);

    @Query("""
            select c.id from Card c
            where c.status <> com.bank.account.card.CardStatus.CLOSED and c.nextStatementOn <= :day
            order by c.id""")
    List<Long> findIdsWithStatementDueBy(LocalDate day);

    boolean existsByCustomerIdAndStatusNot(Long customerId, CardStatus status);
}
