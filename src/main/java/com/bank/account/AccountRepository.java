package com.bank.account;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Page<Account> findByCustomerId(Long customerId, Pageable pageable);

    boolean existsByIdAndCustomerId(Long id, Long customerId);
}
