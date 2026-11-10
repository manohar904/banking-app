package com.bank.account;

import com.bank.account.dto.AccountResponse;
import com.bank.account.dto.TransferAllowanceResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfers a customer sends themselves. Each customer may send up to a daily limit, counted over
 * all their accounts, from midnight in the configured time zone. Staff transfers have no limit and
 * do not count towards it.
 */
@Service
@Transactional
public class CustomerTransfers {

    private final AccountService accountService;
    /** The same per-customer lock that opening an account takes; sharing it is harmless. */
    private final RetiredCustomers customerLock;
    private final JdbcTemplate jdbc;
    private final BigDecimal dailyLimit;
    private final Clock clock;

    CustomerTransfers(AccountService accountService, RetiredCustomers customerLock, JdbcTemplate jdbc,
            @Value("${bank.transfers.customer-daily-limit}") BigDecimal dailyLimit,
            @Value("${bank.transfers.time-zone}") ZoneId timeZone) {
        this.accountService = accountService;
        this.customerLock = customerLock;
        this.jdbc = jdbc;
        this.dailyLimit = dailyLimit;
        this.clock = Clock.system(timeZone);
    }

    /**
     * The customer's lock is held until the transfer commits, so two transfers sent at the same
     * moment are counted one after the other and cannot both slip under the limit. The caller has
     * already checked that the account is the customer's.
     */
    public AccountResponse transfer(Long customerId, Long fromAccountId, String toAccountNumber, BigDecimal amount) {
        customerLock.lock(customerId);
        BigDecimal remaining = remainingToday(customerId);
        if (amount.compareTo(remaining) > 0) {
            throw new TransferLimitExceededException(remaining.signum() == 0
                    ? "You have reached today's transfer limit of " + dailyLimit.toPlainString()
                            + ". You can send more tomorrow."
                    : "This is more than you can send today. Your daily limit is " + dailyLimit.toPlainString()
                            + ", and you can still send " + remaining.toPlainString() + " today.");
        }
        return accountService.transfer(fromAccountId, toAccountNumber, amount, true);
    }

    @Transactional(readOnly = true)
    public TransferAllowanceResponse allowance(Long accountId) {
        Long customerId = accountService.get(accountId).customerId();
        BigDecimal sent = sentToday(customerId);
        return new TransferAllowanceResponse(dailyLimit, sent, dailyLimit.subtract(sent).max(BigDecimal.ZERO));
    }

    private BigDecimal remainingToday(Long customerId) {
        return dailyLimit.subtract(sentToday(customerId)).max(BigDecimal.ZERO);
    }

    private BigDecimal sentToday(Long customerId) {
        Instant startOfDay = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        return jdbc.queryForObject("""
                select coalesce(sum(t.amount), 0) from account_transactions t
                join accounts a on a.id = t.account_id
                where a.customer_id = ? and t.type = 'TRANSFER_OUT' and t.by_customer and t.created_at >= ?
                """, BigDecimal.class, customerId, java.sql.Timestamp.from(startOfDay));
    }
}
