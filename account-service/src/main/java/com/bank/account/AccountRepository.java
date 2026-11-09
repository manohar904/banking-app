package com.bank.account;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Page<Account> findByCustomerId(Long customerId, Pageable pageable);

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByCustomerId(Long customerId);

    boolean existsByIdAndCustomerId(Long id, Long customerId);
}
