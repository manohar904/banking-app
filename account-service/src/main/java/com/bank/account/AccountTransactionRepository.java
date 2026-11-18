package com.bank.account;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Extends the bare Repository so the ledger has no update or delete methods. */
public interface AccountTransactionRepository extends Repository<AccountTransaction, Long> {

    AccountTransaction save(AccountTransaction transaction);

    Page<AccountTransaction> findByAccountId(Long accountId, Pageable pageable);

    boolean existsByAccountId(Long accountId);

    /** The entries from one moment, up to but not including another, oldest first. */
    List<AccountTransaction> findByAccountIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderById(
            Long accountId, Instant from, Instant until);

    /** The last entry before a moment: its balance is the balance at that moment. */
    Optional<AccountTransaction> findFirstByAccountIdAndCreatedAtLessThanOrderByIdDesc(Long accountId, Instant before);
}
