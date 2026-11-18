package com.bank.account.scheduled;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ScheduledPaymentRepository extends JpaRepository<ScheduledPayment, Long> {

    /** Locks the row, so a payment is sent or cancelled by one request or run at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ScheduledPayment p where p.id = :id")
    Optional<ScheduledPayment> findForUpdate(Long id);

    List<ScheduledPayment> findByCustomerIdOrderById(Long customerId);

    long countByCustomerIdAndStatus(Long customerId, ScheduledPayment.Status status);

    boolean existsByFromAccountIdAndStatus(Long fromAccountId, ScheduledPayment.Status status);

    /** Active payments due on or before this day. */
    @Query("""
            select p.id from ScheduledPayment p
            where p.status = com.bank.account.scheduled.ScheduledPayment.Status.ACTIVE and p.nextPaymentOn <= :day
            order by p.id""")
    List<Long> findIdsDueBy(LocalDate day);
}
