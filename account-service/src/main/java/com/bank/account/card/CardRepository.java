package com.bank.account.card;

import jakarta.persistence.LockModeType;
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

    boolean existsByCustomerIdAndStatusNot(Long customerId, CardStatus status);
}
