package com.bank.account;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Extends the bare Repository so the ledger has no update or delete methods. */
public interface AccountTransactionRepository extends Repository<AccountTransaction, Long> {

    AccountTransaction save(AccountTransaction transaction);

    Page<AccountTransaction> findByAccountId(Long accountId, Pageable pageable);

    boolean existsByAccountId(Long accountId);
}
