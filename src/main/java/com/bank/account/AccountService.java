package com.bank.account;

import com.bank.account.dto.AccountResponse;
import com.bank.account.dto.CreateAccountRequest;
import com.bank.account.dto.TransactionResponse;
import com.bank.common.ConflictException;
import com.bank.common.ResourceNotFoundException;
import com.bank.customer.Customer;
import com.bank.customer.CustomerRepository;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AccountService {

    private static final long ACCOUNT_NUMBER_BOUND = 1_000_000_000_000L; // 12 digits

    private final AccountRepository accountRepository;
    private final AccountTransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;
    private final SecureRandom random = new SecureRandom();

    public AccountResponse create(CreateAccountRequest request) {
        Customer customer = customerRepository.findById(request.customerId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Customer " + request.customerId() + " not found."));
        Account account = new Account(customer, generateAccountNumber(), request.type());
        return AccountResponse.from(accountRepository.saveAndFlush(account));
    }

    @Transactional(readOnly = true)
    public AccountResponse get(Long id) {
        return AccountResponse.from(findAccount(id));
    }

    @Transactional(readOnly = true)
    public Page<AccountResponse> list(Long customerId, Pageable pageable) {
        Page<Account> accounts = customerId == null
                ? accountRepository.findAll(pageable)
                : accountRepository.findByCustomerId(customerId, pageable);
        return accounts.map(AccountResponse::from);
    }

    public AccountResponse deposit(Long id, BigDecimal amount) {
        Account account = findAccount(id);
        account.deposit(amount);
        return saveWithLedgerEntry(account, TransactionType.DEPOSIT, amount);
    }

    public AccountResponse withdraw(Long id, BigDecimal amount) {
        Account account = findAccount(id);
        account.withdraw(amount);
        return saveWithLedgerEntry(account, TransactionType.WITHDRAWAL, amount);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> listTransactions(Long id, Pageable pageable) {
        findAccount(id); // an unknown account is a 404, not an empty history
        return transactionRepository.findByAccountId(id, pageable).map(TransactionResponse::from);
    }

    public void delete(Long id) {
        Account account = findAccount(id);
        if (!account.hasZeroBalance()) {
            throw new ConflictException("An account can only be deleted when its balance is zero.");
        }
        if (transactionRepository.existsByAccountId(id)) {
            throw new ConflictException("An account with transaction history cannot be deleted.");
        }
        accountRepository.delete(account);
    }

    /**
     * Flushes the balance first: if the optimistic lock or a constraint fails, no ledger entry is
     * written. Both writes share one database transaction, so they commit or roll back together.
     */
    private AccountResponse saveWithLedgerEntry(Account account, TransactionType type, BigDecimal amount) {
        Account saved = accountRepository.saveAndFlush(account);
        transactionRepository.save(new AccountTransaction(saved, type, amount));
        return AccountResponse.from(saved);
    }

    private Account findAccount(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + id + " not found."));
    }

    /** A collision is very unlikely. If one happens, the unique constraint rejects it with a 409. */
    private String generateAccountNumber() {
        return String.format(Locale.ROOT, "%012d", random.nextLong(ACCOUNT_NUMBER_BOUND));
    }
}
