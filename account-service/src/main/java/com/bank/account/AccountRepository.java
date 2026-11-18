package com.bank.account;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Page<Account> findByCustomerId(Long customerId, Pageable pageable);

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByCustomerId(Long customerId);

    boolean existsByIdAndCustomerId(Long id, Long customerId);

    /** Savings accounts with interest due on or before this day, or not given a first date yet. */
    @Query("""
            select a.id from Account a
            where a.type = com.bank.account.AccountType.SAVINGS
              and (a.nextInterestOn is null or a.nextInterestOn <= :day)
            order by a.id""")
    List<Long> findSavingsIdsWithInterestDueBy(LocalDate day);
}
