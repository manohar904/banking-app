package com.bank.account.loan;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    /** Locks the row, so a loan is approved, rejected or repaid by one request at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Loan l where l.id = :id")
    Optional<Loan> findForUpdate(Long id);

    Page<Loan> findByCustomerIdAndStatusIn(Long customerId, Collection<LoanStatus> statuses, Pageable pageable);

    Page<Loan> findByStatusIn(Collection<LoanStatus> statuses, Pageable pageable);

    long countByCustomerIdAndStatus(Long customerId, LoanStatus status);

    boolean existsByAccountIdAndStatusIn(Long accountId, Collection<LoanStatus> statuses);

    /** Active loans with a payment due on or before this day. */
    @Query("""
            select l.id from Loan l
            where l.status = com.bank.account.loan.LoanStatus.ACTIVE and l.nextPaymentDue <= :day
            order by l.id""")
    List<Long> findIdsDueBy(LocalDate day);
}
