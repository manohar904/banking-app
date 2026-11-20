package com.bank.account.spending;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpendingAlertRepository extends JpaRepository<SpendingAlert, Long> {
}
