package com.bank.account.payee;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PayeeRepository extends JpaRepository<Payee, Long> {

    List<Payee> findByCustomerIdOrderByNameAsc(Long customerId);

    long countByCustomerId(Long customerId);

    boolean existsByCustomerIdAndNameIgnoreCase(Long customerId, String name);

    boolean existsByCustomerIdAndAccountNumber(Long customerId, String accountNumber);
}
