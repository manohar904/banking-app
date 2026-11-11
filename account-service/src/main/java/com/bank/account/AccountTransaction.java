package com.bank.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

/** One completed deposit, withdrawal or side of a transfer. Entries are never changed once written. */
@Entity
@Table(name = "account_transactions")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class AccountTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private TransactionType type;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    /** The same on both sides of a transfer. Empty for deposits and withdrawals. */
    @Column(updatable = false)
    private UUID transferId;

    /** True for a transfer the customer sent themselves, which counts towards their daily limit. */
    @Column(nullable = false, updatable = false)
    private boolean byCustomer;

    /** The other account in a transfer. Empty for deposits and withdrawals. */
    @Column(updatable = false, length = 12)
    private String counterpartyAccountNumber;

    /** The loan paid out or repaid. Empty for everything else. */
    @Column(updatable = false)
    private Long loanId;

    /** The credit card a payment went to. Empty for everything else. */
    @Column(updatable = false)
    private Long cardId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** One side of a transfer. As with the constructor below, call after the balance has changed. */
    public AccountTransaction(Account account, TransactionType type, BigDecimal amount, UUID transferId,
            String counterpartyAccountNumber, boolean byCustomer) {
        this(account, type, amount);
        this.transferId = transferId;
        this.counterpartyAccountNumber = counterpartyAccountNumber;
        this.byCustomer = byCustomer;
    }

    /** A loan paid out or repaid. As with the constructor below, call after the balance has changed. */
    public static AccountTransaction forLoan(Account account, TransactionType type, BigDecimal amount, Long loanId) {
        AccountTransaction entry = new AccountTransaction(account, type, amount);
        entry.loanId = loanId;
        return entry;
    }

    /** A credit card payment. As with the constructor below, call after the balance has changed. */
    public static AccountTransaction forCardPayment(Account account, BigDecimal amount, Long cardId) {
        AccountTransaction entry = new AccountTransaction(account, TransactionType.CARD_PAYMENT, amount);
        entry.cardId = cardId;
        return entry;
    }

    /** Call after the balance has changed, so that balanceAfter captures the new balance. */
    public AccountTransaction(Account account, TransactionType type, BigDecimal amount) {
        this.account = account;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = account.getBalance();
    }
}
