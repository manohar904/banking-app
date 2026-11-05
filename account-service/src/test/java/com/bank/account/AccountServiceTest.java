package com.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bank.common.ConflictException;
import com.bank.common.events.DomainEvents;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceTest {

    private static final Long ID = 1L;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountTransactionRepository transactionRepository;

    @Mock
    private RetiredCustomers retiredCustomers;

    @Mock
    private CustomerServiceClient customerService;

    @Mock
    private DomainEvents events;

    @InjectMocks
    private AccountService accountService;

    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account(42L, "000000000001", AccountType.CHECKING);
        when(accountRepository.findById(ID)).thenReturn(Optional.of(account));
        when(accountRepository.saveAndFlush(account)).thenReturn(account);
    }

    @Test
    void depositWritesLedgerEntryWithTheNewBalance() {
        account.deposit(new BigDecimal("5.00"));

        accountService.deposit(ID, new BigDecimal("10.00"));

        AccountTransaction entry = savedEntry();
        assertThat(entry.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(entry.getAmount()).isEqualByComparingTo("10.00");
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("15.00");
        assertThat(entry.getAccount()).isSameAs(account);
    }

    @Test
    void withdrawalWritesLedgerEntryWithTheNewBalance() {
        account.deposit(new BigDecimal("15.00"));

        accountService.withdraw(ID, new BigDecimal("4.00"));

        AccountTransaction entry = savedEntry();
        assertThat(entry.getType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(entry.getAmount()).isEqualByComparingTo("4.00");
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("11.00");
    }

    @Test
    void balanceIsFlushedBeforeTheLedgerEntryIsWritten() {
        accountService.deposit(ID, new BigDecimal("1.00"));

        InOrder order = inOrder(accountRepository, transactionRepository);
        order.verify(accountRepository).saveAndFlush(account);
        order.verify(transactionRepository).save(any(AccountTransaction.class));
    }

    @Test
    void rejectedWithdrawalWritesNoLedgerEntry() {
        assertThatThrownBy(() -> accountService.withdraw(ID, new BigDecimal("1.00")))
                .isInstanceOf(InsufficientFundsException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void accountWithHistoryCannotBeDeleted() {
        when(transactionRepository.existsByAccountId(ID)).thenReturn(true);

        assertThatThrownBy(() -> accountService.delete(ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("transaction history");

        verify(accountRepository, never()).delete(any());
    }

    @Test
    void accountWithoutHistoryCanBeDeleted() {
        when(transactionRepository.existsByAccountId(ID)).thenReturn(false);

        accountService.delete(ID);

        verify(accountRepository).delete(account);
    }

    private AccountTransaction savedEntry() {
        ArgumentCaptor<AccountTransaction> captor = ArgumentCaptor.forClass(AccountTransaction.class);
        verify(transactionRepository).save(captor.capture());
        return captor.getValue();
    }
}
