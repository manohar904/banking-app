package com.bank.account.loan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface LoanRepaymentRepository extends JpaRepository<LoanRepayment, Long> {

    List<LoanRepayment> findByLoanIdOrderById(Long loanId);
}
