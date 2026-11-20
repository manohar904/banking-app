package com.bank.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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

    /** The total of the account's entries of these types from a moment on, or null if there are none. */
    @Query("select sum(t.amount) from AccountTransaction t where t.account.id = :accountId and t.type in :types "
            + "and t.createdAt >= :from")
    BigDecimal sumOfTypesSince(@Param("accountId") Long accountId, @Param("types") Collection<TransactionType> types,
            @Param("from") Instant from);
}
